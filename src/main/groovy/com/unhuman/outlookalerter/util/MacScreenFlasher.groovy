package com.unhuman.outlookalerter.util

import groovy.transform.CompileStatic

import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.*
import com.unhuman.outlookalerter.core.ConfigManager
import com.unhuman.outlookalerter.model.CalendarEvent
import com.unhuman.outlookalerter.util.LogManager
import com.unhuman.outlookalerter.util.LogCategory
import java.util.List
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Mac-specific implementation of ScreenFlasher
 * Enhanced with system state validation to prevent hanging during standby/wake scenarios
 */
@CompileStatic
class MacScreenFlasher implements ScreenFlasher {

    // Instance variables for flash settings
    private int flashDurationMs

    // Overlay windows that should always appear on top of flash windows.
    // The flash elevation timer will re-elevate these after each of its own
    // toFront() calls so the overlay stays visible without a separate timer fight.
    private static final List<JFrame> overlayWindows = new CopyOnWriteArrayList<JFrame>()

    /** Register windows that must remain above flash windows. */
    static void registerOverlayWindows(List<JFrame> windows) {
        overlayWindows.addAll(windows)
    }

    /** Remove all registered overlay windows. */
    static void clearOverlayWindows() {
        overlayWindows.clear()
    }

    // Callback fired on the EDT once flash windows are visible.
    // Used by the banner to show itself at exactly the right moment.
    private static final java.util.concurrent.atomic.AtomicReference<Runnable> onFlashReady =
        new java.util.concurrent.atomic.AtomicReference<Runnable>(null)

    /** Register a callback to run on the EDT once flash windows are visible. */
    static void setOnFlashReady(Runnable callback) {
        onFlashReady.set(callback)
    }

    // Track active flash frames for cleanup - using CopyOnWriteArrayList for thread safety
    private final List<JFrame> activeFlashFrames = new CopyOnWriteArrayList<JFrame>()

    // Track active timers for cleanup - using CopyOnWriteArrayList for thread safety
    private final List<Timer> activeTimers = new CopyOnWriteArrayList<Timer>()

    // Track countdown timers separately (these are managed independently)
    private final Map<JFrame, Timer> countdownTimers = Collections.synchronizedMap(new HashMap<JFrame, Timer>())

    // Track labels for countdown updates
    private final Map<JFrame, JLabel> countdownLabels = Collections.synchronizedMap(new HashMap<JFrame, JLabel>())

    // Flag to prevent multiple simultaneous cleanups
    private final AtomicBoolean cleanupInProgress = new AtomicBoolean(false)

    // Guard against duplicate shutdown hooks
    private static volatile boolean shutdownHookRegistered = false

    // System state tracking to prevent alerts during problematic conditions
    private final AtomicLong lastSystemWakeTime = new AtomicLong(System.currentTimeMillis())
    private final AtomicBoolean systemStateValidationInProgress = new AtomicBoolean(false)

    // Sleep/wake monitor for macOS
    private final MacSleepWakeMonitor sleepWakeMonitor = MacSleepWakeMonitor.getInstance()

    // --- EDT Watchdog ---
    final AtomicBoolean edtWatchdogStarted = new AtomicBoolean(false)
    private volatile boolean edtWatchdogRunning = true
    final AtomicLong lastEdtResponseTime = new AtomicLong(System.currentTimeMillis())
    void startEDTWatchdog() {
        if (edtWatchdogStarted.compareAndSet(false, true)) {
            Thread watchdog = new Thread({
                while (edtWatchdogRunning) {
                    final AtomicBoolean responded = new AtomicBoolean(false)
                    final long checkStartTime = System.currentTimeMillis()

                    try {
                        SwingUtilities.invokeLater({
                            responded.set(true)
                            lastEdtResponseTime.set(System.currentTimeMillis())
                        })
                        Thread.sleep(5000) // Check every 5 seconds

                        long timeSinceResponse = System.currentTimeMillis() - lastEdtResponseTime.get()

                        if (!responded.get() || timeSinceResponse > 10000) {
                            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "[EDT WATCHDOG] CRITICAL: EDT unresponsive for " +
                                (timeSinceResponse / 1000) + " seconds - attempting recovery")

                            // Try emergency cleanup of flash windows
                            try {
                                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "[EDT WATCHDOG] Attempting emergency cleanup")
                                forceCleanup()
                            } catch (Exception cleanupEx) {
                                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "[EDT WATCHDOG] Emergency cleanup failed: " + cleanupEx.getMessage())
                            }
                        }
                    } catch (Exception e) {
                        LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "[EDT WATCHDOG] Exception: " + e.getMessage())
                    }
                }
            } as Runnable)
            watchdog.setDaemon(true)
            watchdog.setName("EDT-Watchdog")
            watchdog.start()
        }
    }

    /**
     * Constructor
     */
    MacScreenFlasher() {
        // Read flash duration from configuration
        try {
            ConfigManager configManager = ConfigManager.getInstance()
            if (configManager != null) {
                flashDurationMs = configManager.getFlashDurationSeconds() * 1000
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Mac screen flasher initialized with duration: " + 
                                   configManager.getFlashDurationSeconds() + " seconds")
            } else {
                flashDurationMs = 5000 // 5 seconds default
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Mac screen flasher using default duration: 5 seconds")
            }
        } catch (Exception e) {
            flashDurationMs = 5000 // 5 seconds default
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error initializing flash duration: " + e.getMessage())
        }
        
        // Register shutdown hook to ensure cleanup (only once via static flag)
        if (!shutdownHookRegistered) {
            shutdownHookRegistered = true
            Runtime.getRuntime().addShutdownHook(new Thread({
                forceCleanup()
                edtWatchdogRunning = false
                sleepWakeMonitor.stopMonitoring()
            } as Runnable))
        }

        startEDTWatchdog()

        // Start sleep/wake monitoring
        sleepWakeMonitor.startMonitoring()

        // Register wake listener to update our wake time and cleanup stuck windows
        sleepWakeMonitor.addWakeListener({
            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "[MacScreenFlasher] Wake event detected, updating wake time")
            updateLastWakeTime()

            // Force cleanup of any stuck windows from before sleep
            SwingUtilities.invokeLater({
                if (!activeFlashFrames.isEmpty()) {
                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "[MacScreenFlasher] Cleaning up " + activeFlashFrames.size() +
                        " windows that may have been stuck during sleep")
                    forceCleanup()
                }
            } as Runnable)
        } as Runnable)
    }

    /**
     * Robust cleanup method that prevents race conditions and ensures all resources are freed
     */
    @Override
    void forceCleanup() {
        // Use atomic flag to prevent concurrent cleanup attempts
        if (!cleanupInProgress.compareAndSet(false, true)) {
            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING,
                "MacScreenFlasher: Cleanup already in progress, skipping")
            return
        }

        try {
            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING,
                "MacScreenFlasher: Starting cleanup of " + activeFlashFrames.size() + " frames and " + activeTimers.size() + " timers")

            // Stop all active timers first (CopyOnWriteArrayList is already thread-safe)
            for (Timer timer : new ArrayList<Timer>(activeTimers)) {
                try {
                    if (timer != null && timer.isRunning()) {
                        timer.stop()
                    }
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error stopping timer: " + e.getMessage())
                }
            }
            activeTimers.clear()

            // Stop all countdown timers
            synchronized(countdownTimers) {
                for (Timer timer : new ArrayList<Timer>(countdownTimers.values())) {
                    try {
                        if (timer != null && timer.isRunning()) {
                            timer.stop()
                        }
                    } catch (Exception e) {
                        LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error stopping countdown timer: " + e.getMessage())
                    }
                }
                countdownTimers.clear()
            }

            // Clear countdown labels
            synchronized(countdownLabels) {
                countdownLabels.clear()
            }

            // Dispose all frames on EDT to avoid threading issues
            final CountDownLatch cleanupLatch = new CountDownLatch(1)

            Runnable cleanupTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        List<JFrame> framesToDispose = new ArrayList<>(activeFlashFrames)
                        for (JFrame frame : framesToDispose) {
                            try {
                                if (frame != null) {
                                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Disposing flash frame: " + frame)
                                    frame.setVisible(false)
                                    frame.dispose()
                                }
                            } catch (Exception e) {
                                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error disposing frame: " + e.getMessage())
                            }
                        }
                        activeFlashFrames.clear()
                    } finally {
                        cleanupLatch.countDown()
                    }
                }
            }

            if (SwingUtilities.isEventDispatchThread()) {
                // Already on EDT, just run cleanupTask
                cleanupTask.run()
            } else {
                SwingUtilities.invokeLater(cleanupTask)
                // Wait for cleanup to complete (with timeout)
                try {
                    cleanupLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                } catch (InterruptedException e) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Cleanup wait interrupted")
                }
            }

            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Cleanup completed")

        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error during cleanup: " + e.getMessage())
            e.printStackTrace()
        } finally {
            cleanupInProgress.set(false)
        }
    }

    @Override
    void flash(CalendarEvent event) {
        flashMultiple([event])
    }

    @Override
    void flashMultiple(List<CalendarEvent> events) {
        if (!events) return

        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Flash requested for " + events.size() + " event(s), duration: " + flashDurationMs/1000 + " seconds")

        // Validate system state before proceeding
        if (!validateSystemState()) {
            LogManager.getInstance().warn(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: System state validation failed, skipping alert")
            return
        }

        // Validate display environment
        if (!validateDisplayEnvironment()) {
            LogManager.getInstance().warn(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Display environment validation failed, skipping alert")
            return
        }

        // Validate events have content to display
        if (!validateEventContent(events)) {
            LogManager.getInstance().warn(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Event content validation failed, skipping alert")
            return
        }

        // Clean up any existing windows first
        forceCleanup()

        // Create and show flash windows ON THE EDT for Swing thread safety
        // Swing components must be created and manipulated on the Event Dispatch Thread
        // to avoid intermittent rendering failures (invisible/unpainted windows)
        final List<JFrame> newFrames = Collections.synchronizedList(new ArrayList<JFrame>())
        try {
            Runnable createWindows = {
                GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Creating flash windows for " + screens.length + " screens")

                for (GraphicsDevice screen : screens) {
                    try {
                        JFrame frame = createFlashWindowForScreen(screen, events)
                        if (frame != null) {
                            newFrames.add(frame)
                        } else {
                            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING,
                                "FlashWindow: createFlashWindowForScreen returned null for '" + screen.getIDstring() + "'")
                        }
                    } catch (Exception screenEx) {
                        // Per-screen isolation: one monitor failure must not prevent others
                        LogManager.getInstance().error(LogCategory.ALERT_PROCESSING,
                            "MacScreenFlasher: Failed to create flash for screen '" + screen.getIDstring() + "': " + screenEx.getMessage())
                    }
                }

                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING,
                    "FlashWindow: created " + newFrames.size() + "/" + screens.length + " flash windows")
            } as Runnable

            if (SwingUtilities.isEventDispatchThread()) {
                createWindows.run()
            } else {
                SwingUtilities.invokeAndWait(createWindows)
            }
        } catch (java.lang.reflect.InvocationTargetException ite) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING,
                "MacScreenFlasher: EDT invocation error during window creation: " + ite.getCause()?.getMessage())
        } catch (InterruptedException ie) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING,
                "MacScreenFlasher: Interrupted while creating flash windows on EDT: " + ie.getMessage())
            Thread.currentThread().interrupt()
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING,
                "MacScreenFlasher: Unexpected error during flash window creation: " + e.getMessage())
        }

        // Verify at least one frame was created successfully
        if (newFrames.isEmpty()) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: No flash windows were created successfully")
            return
        }

        // Add to tracking (CopyOnWriteArrayList is already thread-safe)
        activeFlashFrames.addAll(newFrames)

        // Fire the onFlashReady callback so the banner can show itself now
        // that flash windows are visible. Run on EDT to ensure Swing safety.
        // AtomicReference.getAndSet(null) is an atomic read-and-clear (one-shot).
        Runnable readyCallback = onFlashReady.getAndSet(null)
        if (readyCallback != null) {
            SwingUtilities.invokeLater(readyCallback)
        }

        // Request user attention on macOS to help bring windows above full-screen apps
        try {
            if (java.awt.Taskbar.isTaskbarSupported()) {
                java.awt.Taskbar taskbar = java.awt.Taskbar.getTaskbar()
                if (taskbar.isSupported(java.awt.Taskbar.Feature.USER_ATTENTION)) {
                    taskbar.requestUserAttention(true, true)  // enabled=true, critical=true
                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Requested critical user attention via Taskbar")
                }
            }
        } catch (Exception e) {
            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Could not request user attention: " + e.getMessage())
        }

        // Set up cleanup timer
        setupCleanupTimer()
    }

    /**
     * Sets up a single, reliable cleanup timer with backup and final failsafe
     */
    private void setupCleanupTimer() {
        long startTime = System.currentTimeMillis()

        // Primary cleanup timer
        Timer primaryTimer = new Timer(flashDurationMs, new ActionListener() {
            void actionPerformed(ActionEvent e) {
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING,
                    "FlashCleanup: Primary timer fired after " +
                    (System.currentTimeMillis() - startTime) / 1000.0 + " seconds")
                forceCleanup()

                Timer source = (Timer)e.getSource()
                activeTimers.remove(source)
            }
        })
        primaryTimer.setRepeats(false)

        // Backup cleanup timer (runs 1 second later as failsafe)
        Timer backupTimer = new Timer(flashDurationMs + 1000, new ActionListener() {
            void actionPerformed(ActionEvent e) {
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Backup cleanup timer fired - checking for stuck windows")

                if (!activeFlashFrames.isEmpty()) {
                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Found " + activeFlashFrames.size() + " stuck windows, forcing cleanup")
                    forceCleanup()
                }
                
                Timer source = (Timer)e.getSource()
                activeTimers.remove(source)
            }
        })
        backupTimer.setRepeats(false)

        // Final failsafe timer (runs 2 seconds after backup to guarantee cleanup)
        Timer finalFailsafeTimer = new Timer(flashDurationMs + 2000, new ActionListener() {
            void actionPerformed(ActionEvent e) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!activeFlashFrames.isEmpty()) {
                                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "FINAL FAILSAFE: Forcing cleanup of " + activeFlashFrames.size() + " remaining flash frames")
                                forceCleanup()
                            } else {
                                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "FINAL FAILSAFE: No frames remaining, cleanup successful")
                            }
                        } catch (Exception ex) {
                            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "FINAL FAILSAFE: Cleanup failed: " + ex.getMessage())
                        }
                    }
                })

                Timer source = (Timer)e.getSource()
                activeTimers.remove(source)
            }
        })
        finalFailsafeTimer.setRepeats(false)

        // Track timers and start them (CopyOnWriteArrayList is already thread-safe)
        activeTimers.add(primaryTimer)
        activeTimers.add(backupTimer)
        activeTimers.add(finalFailsafeTimer)

        primaryTimer.start()
        backupTimer.start()
        finalFailsafeTimer.start()

        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Cleanup timers started - primary: " + flashDurationMs + "ms, backup: " + (flashDurationMs + 1000) + "ms, failsafe: " + (flashDurationMs + 2000) + "ms")
    }
    
    private JFrame createFlashWindowForScreen(GraphicsDevice screen, List<CalendarEvent> events) {
        try {
            // Create frame with proper macOS settings
            JFrame frame = new JFrame("⚠️ Meeting Alert ⚠️", screen.getDefaultConfiguration())

            // Set critical properties before showing
            frame.setUndecorated(true)
            // Use POPUP type for higher window level on macOS - this helps appear over full-screen apps
            frame.setType(JFrame.Type.POPUP)
            frame.setAlwaysOnTop(true)
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
            
            // Additional settings to help with full-screen app visibility
            frame.setFocusableWindowState(true)  // Allow focus to help bring to front
            frame.setAutoRequestFocus(true)      // Request focus to trigger window activation

            // Get configuration
            Color alertColor = getAlertColor()
            Color textColor = getAlertTextColorWithOpacity()
            double opacity = getAlertOpacity()
            
            // Set opacity safely
            try {
                float actualOpacity = (float)Math.min(Math.max(opacity, 0.7f), 0.95f)
                frame.setOpacity(actualOpacity)
            } catch (Throwable t) {
                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Could not set opacity: " + t.getMessage())
            }
            
            // Set up frame bounds and background
            Rectangle bounds = screen.getDefaultConfiguration().getBounds()
            frame.setBounds(bounds)
            frame.setBackground(alertColor)

            // Create content panel (no border — the separate banner windows provide the frame)
            JPanel panel = new JPanel(new GridBagLayout())
            panel.setBackground(alertColor)
            panel.setOpaque(true)
            frame.setContentPane(panel)

            // Create label with alert text
            String textColorHex = String.format("#%02x%02x%02x",
                textColor.getRed(), textColor.getGreen(), textColor.getBlue())

            // Build banner message for the top bar
            String bannerMsg = (events.size() == 1)
                ? "Upcoming meeting: " + HtmlUtil.escapeHtml(events.get(0).getSubject())
                : events.size() + " upcoming meetings starting soon"

            StringBuilder labelContent = new StringBuilder("<html><div style='text-align: center; width: 100%;'>" +
                "<div style='background-color: #DC0000; color: white; font-size: 18px; font-weight: bold; " +
                "padding: 10px 20px; margin-bottom: 20px; width: 100%;'>" + bannerMsg + "</div>" +
                "<h1 style='color: " + textColorHex + "; font-size: 48px; margin-bottom: 30px; white-space: nowrap;'>⚠️ MEETING ALERT ⚠️</h1>" +
                "<div style='color: " + textColorHex + "; font-size: 36px; margin-bottom: 10px;'>")

            // Add event subjects
            for (int i = 0; i < events.size(); i++) {
                CalendarEvent event = events.get(i)
                labelContent.append("<div style='margin-bottom: 10px;'>")
                    .append(HtmlUtil.escapeHtml(event.subject))

                // Add location if it exists - show domain for URLs, full text for physical locations
                if (event.location) {
                    String displayLocations = getMeetingLocations(event.location)
                    if (displayLocations) {
                        labelContent.append("<br/><span style='font-size: 20px; font-weight: normal; color: ")
                            .append(textColorHex)
                            .append(";'>📍 ")
                            .append(HtmlUtil.escapeHtml(displayLocations))
                            .append("</span>")
                    }
                }

                labelContent.append("</div>")

                // Add separator for multiple events
                if (i < events.size() - 1) {
                    labelContent.append("<br/>")
                }
            }

            labelContent.append("</div>" +
                "<p style='color: " + textColorHex + "; font-size: 28px; margin-bottom: 40px;'>Starting in " +
                (events.get(0).getMinutesToStart() + 1) + " minute(s)</p>" +
                "<p style='color: " + textColorHex + "; font-size: 16px;'>This alert will close in " +
                flashDurationMs.intdiv(1000) + " seconds</p>" +
                "</div></html>")

            JLabel label = new JLabel(labelContent.toString(), SwingConstants.CENTER)
            label.setForeground(textColor)
            label.setBackground(alertColor)
            label.setOpaque(true)
            
            GridBagConstraints gbc = new GridBagConstraints()
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.weightx = 1.0
            gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.BOTH
            panel.add(label, gbc)

            // Store the label for countdown updates
            countdownLabels.put(frame, label)

            // Show the frame and force it to front immediately
            // Note: pack() was intentionally removed — it resizes the frame to preferred size
            // which macOS window manager may constrain, then setBounds must override again.
            // Since we set explicit full-screen bounds, pack() is unnecessary and harmful.
            frame.setVisible(true)
            frame.toFront()
            frame.setAlwaysOnTop(true)  // Ensure it stays on top

            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING,
                "FlashWindow: created on screen '" + screen.getIDstring() +
                "' bounds=" + bounds + " visible=" + frame.isVisible() +
                " opacity=" + frame.getOpacity())

            // Use standard Swing approach to ensure window is on top
            // Note: Native window handle access is blocked by Java module system on macOS 15.7.1
            // Standard Swing methods work reliably without needing native handles
            //
            // The elevation timer runs for the FULL flash duration (not just the first second)
            // so that if another app steals focus at any point, the flash is re-elevated.
            Timer elevationTimer = new Timer(100, null)
            final int[] attemptCount = [0]
            final int maxAttempts = Math.max(5, (int)(flashDurationMs / 1000))

            elevationTimer.addActionListener(new ActionListener() {
                @Override
                void actionPerformed(ActionEvent e) {
                    attemptCount[0]++

                    try {
                        // Stop if the frame has already been disposed
                        if (!frame.isDisplayable()) {
                            ((Timer)e.getSource()).stop()
                            return
                        }

                        boolean hasOverlays = !overlayWindows.isEmpty()

                        // First tick: full-screen breakthrough — toggle alwaysOnTop and toFront().
                        // Subsequent ticks: only toFront() the flash when NO overlay windows
                        // are registered. When overlays exist, the flash already has
                        // alwaysOnTop and is visible; calling toFront() again would
                        // momentarily push it above the banner causing flicker.
                        if (attemptCount[0] == 1) {
                            frame.setAlwaysOnTop(false)
                            frame.setAlwaysOnTop(true)
                            frame.toFront()
                        } else if (!hasOverlays) {
                            frame.toFront()
                        }

                        // Re-elevate any registered overlay windows (e.g. banner frame)
                        // so they stay on top of the flash.
                        for (JFrame overlay : overlayWindows) {
                            if (overlay.isDisplayable()) {
                                overlay.toFront()
                            }
                        }

                        if (attemptCount[0] >= maxAttempts) {
                            ((Timer)e.getSource()).stop()
                        }
                    } catch (Exception ex) {
                        LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error during window elevation: " + ex.getMessage())
                        ((Timer)e.getSource()).stop()
                    }
                }
            })

            elevationTimer.setInitialDelay(100)
            elevationTimer.setDelay(1000)
            elevationTimer.setRepeats(true)
            activeTimers.add(elevationTimer)
            elevationTimer.start()

            // Start countdown timer for this frame
            startCountdownTimer(frame, label)


            return frame
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error creating flash window: " + e.getMessage())
            e.printStackTrace()
            return null
        }
    }

    // Helper methods for getting configuration values
    private Color getAlertColor() {
        try {
            ConfigManager config = ConfigManager.getInstance()
            if (config != null) {
                String hexColor = config.getFlashColor()
                return hexColor ? Color.decode(hexColor) : Color.RED
            }
            return Color.RED
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error getting alert color: " + e.getMessage())
            return Color.RED
        }
    }

    private Color getAlertTextColorWithOpacity() {
        try {
            ConfigManager config = ConfigManager.getInstance()
            if (config != null) {
                String hexColor = config.getFlashTextColor()
                return hexColor ? Color.decode(hexColor) : Color.WHITE
            }
            return Color.WHITE
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error getting alert text color: " + e.getMessage())
            return Color.WHITE
        }
    }

    private double getAlertOpacity() {
        try {
            ConfigManager config = ConfigManager.getInstance()
            if (config != null) {
                return config.getFlashOpacity()
            }
            return 0.9
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error getting alert opacity: " + e.getMessage())
            return 0.9
        }
    }

    /**
     * Creates and starts a countdown timer for a flash frame
     * This timer updates the countdown text every second
     * @param frame The flash frame to create a countdown for
     * @param label The label containing the countdown text
     */
    private void startCountdownTimer(JFrame frame, JLabel label) {
        // Calculate initial seconds remaining
        int totalDurationSeconds = (int)(flashDurationMs / 1000)
        final int[] secondsRemaining = [totalDurationSeconds]

        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Starting countdown timer for frame: " + frame + " with " + totalDurationSeconds + " seconds")

        // Create a timer that fires every second to update the countdown
        Timer countdownTimer = new Timer(1000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Decrement seconds
                secondsRemaining[0]--

                // Update the label text if the frame is still valid
                if (secondsRemaining[0] >= 0 && frame.isDisplayable()) {
                    try {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    String labelText = label.getText()
                                    String newLabelText = labelText.replaceFirst(
                                        "This alert will close in \\d+ seconds",
                                        "This alert will close in " + Math.max(0, secondsRemaining[0]) + " seconds"
                                    )
                                    label.setText(newLabelText)
                                    label.repaint()
                                } catch (Exception ex) {
                                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error updating countdown: " + ex.getMessage())
                                }
                            }
                        })
                    } catch (Exception ex) {
                        LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error scheduling countdown update: " + ex.getMessage())
                    }
                }

                // Stop when reaching zero or if frame is no longer valid
                if (secondsRemaining[0] <= 0 || !frame.isDisplayable()) {
                    Timer source = (Timer)e.getSource()
                    source.stop()

                    // Remove from tracking
                    synchronized(countdownTimers) {
                        countdownTimers.remove(frame)
                    }

                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Countdown timer stopped for frame: " + frame)
                }
            }
        })

        // Start the timer
        countdownTimer.setRepeats(true)
        countdownTimer.start()

        // Store the timer for cleanup
        synchronized(countdownTimers) {
            countdownTimers.put(frame, countdownTimer)
        }

        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Countdown timer started for frame: " + frame)
    }

    /**
     * Processes a meeting location to return a displayable version.
     * Handles multiple locations separated by semicolons.
     * For URLs, extracts just the domain name (e.g., "zoom" from "https://zoom.us/j/123456").
     * For physical locations, returns the location as-is.
     * @param location The raw location string from the calendar event (may contain multiple locations separated by semicolons)
     * @return A processed location string suitable for display with all locations semicolon-separated, or null if no displayable locations
     */
    private String getMeetingLocations(String location) {
        if (!location) return null

        String trimmedLocation = location.trim()
        if (!trimmedLocation) return null

        // Split by semicolons to handle multiple locations
        String[] locations = trimmedLocation.split(";")
        List<String> processedLocations = new ArrayList<>()

        for (String singleLocation : locations) {
            String processedLocation = processSingleLocation(singleLocation.trim())
            if (processedLocation) {
                processedLocations.add(processedLocation)
            }
        }

        // Return semicolon-separated string of processed locations
        if (processedLocations.isEmpty()) {
            return null
        }

        return processedLocations.join("; ")
    }

    /**
     * Processes a single location string
     * @param location A single location (not semicolon-separated)
     * @return Processed location string or null if not displayable
     */
    private String processSingleLocation(String location) {
        if (!location) return null

        // Check if this looks like a URL
        String lowerLocation = location.toLowerCase()

        // Handle URLs with protocols
        if (lowerLocation.matches("^(https?|ftp)://.*")) {
            return extractDomainFromUrl(location)
        }

        // Handle URLs without protocols but with common patterns
        if (lowerLocation.startsWith("www.") ||
            lowerLocation.contains(".com/") ||
            lowerLocation.contains(".net/") ||
            lowerLocation.contains(".org/") ||
            lowerLocation.contains("zoom.us") ||
            lowerLocation.contains("teams.microsoft.com") ||
            lowerLocation.contains("meet.google.com") ||
            lowerLocation.contains("webex.com") ||
            lowerLocation.contains("gotomeeting.com")) {
            return extractDomainFromUrl(location)
        }

        // If it doesn't look like a URL, treat it as a physical location
        return location
    }

    /**
     * Extracts the domain name from a URL, returning just the main part before the TLD.
     * Examples:
     *   "https://zoom.us/j/123456" -> "Zoom"
     *   "teams.microsoft.com/l/meetup" -> "Teams"
     *   "meet.google.com/abc-defg" -> "Google Meet"
     * @param url The URL to extract the domain from
     * @return The extracted domain name, capitalized appropriately
     */
    private String extractDomainFromUrl(String url) {
        try {
            // Remove protocol if present
            String cleanUrl = url.replaceFirst("^(https?|ftp)://", "")

            // Extract the domain part (everything before the first slash or question mark)
            String domain = cleanUrl.split("[/?#]")[0].toLowerCase()

            // Handle specific known domains with custom formatting
            if (domain.contains("zoom.us") || domain.equals("zoom.us")) {
                return "Zoom"
            } else if (domain.contains("teams.microsoft.com")) {
                return "Teams"
            } else if (domain.contains("meet.google.com")) {
                return "Google Meet"
            } else if (domain.contains("webex.com")) {
                return "WebEx"
            } else if (domain.contains("gotomeeting.com")) {
                return "GoToMeeting"
            } else if (domain.startsWith("www.")) {
                // Remove www. prefix
                domain = domain.substring(4)
            }

            // For other domains, extract the main part before the TLD
            String[] parts = domain.split("\\.")
            if (parts.length >= 2) {
                String mainDomain = parts[0]
                // Capitalize first letter
                return mainDomain.substring(0, 1).toUpperCase() + mainDomain.substring(1)
            }

            return domain.substring(0, 1).toUpperCase() + domain.substring(1)

        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error extracting domain from URL: " + url + " - " + e.getMessage())
            return "Online Meeting"
        }
    }

    /**
     * Validates the current system state before showing alerts
     * Ensures the system is not in sleep or display off state
     * @return True if the system is in a valid state for showing alerts, false otherwise
     */
    private boolean validateSystemState() {
        // Skip validation if already in progress
        if (systemStateValidationInProgress.get()) {
            return true
        }

        systemStateValidationInProgress.set(true)
        try {
            // Use sleep/wake monitor for accurate wake time
            long timeSinceLastWake = sleepWakeMonitor.getTimeSinceWake()

            // Log the wake time check
            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Checking system wake time: " + timeSinceLastWake + "ms since last wake")

            // If the system was just woken up very recently, give it time to stabilize
            // Wait 5 seconds after wake to ensure display is ready
            if (timeSinceLastWake < 5000) {
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "System recently woke up (" + timeSinceLastWake +
                    "ms ago), delaying alert for system stability")

                // Wait for system to stabilize
                long sleepTime = Math.max(0, 5000 - timeSinceLastWake)
                try {
                    Thread.sleep(sleepTime)
                } catch (InterruptedException e) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Sleep interrupted: " + e.getMessage())
                }
            }

            // System is considered valid for alerts
            return true
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error validating system state: " + e.getMessage())
            return false
        } finally {
            systemStateValidationInProgress.set(false)
        }
    }

    /**
     * Updates the last wake time stamp
     * Should be called on system wake events
     */
    void updateLastWakeTime() {
        lastSystemWakeTime.set(System.currentTimeMillis())
        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Last system wake time updated: " + lastSystemWakeTime.get())
    }

    /**
     * Validates that the display environment is ready for showing alerts
     * Checks for available displays, proper graphics environment, and EDT availability
     * @return True if display environment is valid, false otherwise
     */
    private boolean validateDisplayEnvironment() {
        try {
            // Check if running in headless mode
            if (GraphicsEnvironment.isHeadless()) {
                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Cannot show alerts in headless environment")
                return false
            }

            // Get graphics environment and validate it's available
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            if (ge == null) {
                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Graphics environment is null")
                return false
            }

            // Check for available screens
            GraphicsDevice[] screens = ge.getScreenDevices()
            if (screens == null || screens.length == 0) {
                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: No graphics devices available")
                return false
            }

            // Validate that at least one screen has a valid configuration
            boolean hasValidScreen = false
            for (GraphicsDevice screen : screens) {
                try {
                    GraphicsConfiguration config = screen.getDefaultConfiguration()
                    if (config != null) {
                        Rectangle bounds = config.getBounds()
                        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                            hasValidScreen = true
                            break
                        }
                    }
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error checking screen configuration: " + e.getMessage())
                }
            }

            if (!hasValidScreen) {
                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: No valid screen configurations found")
                return false
            }

            // NOTE: Previously tested EDT availability here with a 1-second latch timeout.
            // Removed because it causes false negatives: the banner and status label updates
            // are also enqueued on the EDT concurrently, so the EDT can legitimately be busy
            // when this check runs, causing the entire flash to be silently skipped.
            // The EDT watchdog provides adequate EDT health monitoring instead.

            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Display environment validation passed")
            return true

        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Error validating display environment: " + e.getMessage())
            return false
        }
    }

    /**
     * Validates that the events have proper content to display
     * Ensures events have subjects and are not corrupted
     * @param events List of events to validate
     * @return True if events are valid for display, false otherwise
     */
    private boolean validateEventContent(List<CalendarEvent> events) {
        if (events == null || events.isEmpty()) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: No events to validate")
            return false
        }

        try {
            // Filter out bad events instead of failing all events when one is invalid.
            // This prevents a single event with a missing subject from suppressing
            // alerts for all other valid meetings.
            Iterator<CalendarEvent> iterator = events.iterator()
            while (iterator.hasNext()) {
                CalendarEvent event = iterator.next()

                // Remove null events
                if (event == null) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Filtering out null event")
                    iterator.remove()
                    continue
                }

                // Remove events with empty/null subject
                String subject = event.getSubject()
                if (subject == null || subject.trim().isEmpty()) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Filtering out event with empty subject")
                    iterator.remove()
                    continue
                }

                // Remove corrupted events
                try {
                    event.getMinutesToStart()
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Filtering out corrupted event: " + e.getMessage())
                    iterator.remove()
                }
            }

            // Only fail if ALL events were filtered out
            if (events.isEmpty()) {
                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: All events were invalid, nothing to display")
                return false
            }

            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Event content validation passed for " + events.size() + " event(s)")
            return true

        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "MacScreenFlasher: Error validating event content: " + e.getMessage())
            return false
        }
    }
}
