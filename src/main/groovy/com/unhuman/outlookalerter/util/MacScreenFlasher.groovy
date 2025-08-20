package com.unhuman.outlookalerter.util

import groovy.transform.CompileStatic
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.*
import com.unhuman.outlookalerter.core.ConfigManager
import com.sun.jna.*
import com.unhuman.outlookalerter.util.MacWindowHelper
import com.unhuman.outlookalerter.util.ScreenFlasher
import com.unhuman.outlookalerter.model.CalendarEvent
import java.util.List
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch

/**
 * Mac-specific implementation of ScreenFlasher
 * Simplified and more robust version to prevent stuck windows
 */
@CompileStatic
class MacScreenFlasher implements ScreenFlasher {

    // Instance variables for flash settings
    private int flashDurationMs

    // Track active flash frames for cleanup
    private final List<JFrame> activeFlashFrames = Collections.synchronizedList(new ArrayList<JFrame>())
    
    // Track active timers for cleanup
    private final List<javax.swing.Timer> activeTimers = Collections.synchronizedList(new ArrayList<javax.swing.Timer>())

    // Track countdown timers separately (these are managed independently)
    private final Map<JFrame, Timer> countdownTimers = Collections.synchronizedMap(new HashMap<JFrame, Timer>())

    // Track labels for countdown updates
    private final Map<JFrame, JLabel> countdownLabels = Collections.synchronizedMap(new HashMap<JFrame, JLabel>())

    // Flag to prevent multiple simultaneous cleanups
    private final AtomicBoolean cleanupInProgress = new AtomicBoolean(false)

    /**
     * Constructor
     */
    MacScreenFlasher() {
        // Read flash duration from configuration
        try {
            ConfigManager configManager = ConfigManager.getInstance()
            if (configManager != null) {
                flashDurationMs = configManager.getFlashDurationSeconds() * 1000
                System.out.println("Mac screen flasher initialized with duration: " + 
                                   configManager.getFlashDurationSeconds() + " seconds")
            } else {
                flashDurationMs = 5000 // 5 seconds default
                System.out.println("Mac screen flasher using default duration: 5 seconds")
            }
        } catch (Exception e) {
            flashDurationMs = 5000 // 5 seconds default
            System.err.println("Error initializing flash duration: " + e.getMessage())
        }
        
        // Register shutdown hook to ensure cleanup
        Runtime.runtime.addShutdownHook(new Thread({ forceCleanup() } as Runnable))
    }
    
    /**
     * Robust cleanup method that prevents race conditions and ensures all resources are freed
     */
    private void forceCleanup() {
        // Use atomic flag to prevent concurrent cleanup attempts
        if (!cleanupInProgress.compareAndSet(false, true)) {
            System.out.println("MacScreenFlasher: Cleanup already in progress, skipping")
            return
        }

        try {
            System.out.println("MacScreenFlasher: Starting cleanup of " + activeFlashFrames.size() + " frames and " + activeTimers.size() + " timers")

            // Stop all active timers first
            synchronized(activeTimers) {
                for (javax.swing.Timer timer : new ArrayList<javax.swing.Timer>(activeTimers)) {
                    try {
                        if (timer != null && timer.isRunning()) {
                            timer.stop()
                        }
                    } catch (Exception e) {
                        System.err.println("Error stopping timer: " + e.getMessage())
                    }
                }
                activeTimers.clear()
            }

            // Stop all countdown timers
            synchronized(countdownTimers) {
                for (Timer timer : new ArrayList<Timer>(countdownTimers.values())) {
                    try {
                        if (timer != null && timer.isRunning()) {
                            timer.stop()
                        }
                    } catch (Exception e) {
                        System.err.println("Error stopping countdown timer: " + e.getMessage())
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

            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        synchronized(activeFlashFrames) {
                            List<JFrame> framesToDispose = new ArrayList<>(activeFlashFrames)

                            for (JFrame frame : framesToDispose) {
                                try {
                                    if (frame != null) {
                                        System.out.println("Disposing flash frame: " + frame)
                                        frame.setVisible(false)
                                        frame.dispose()
                                    }
                                } catch (Exception e) {
                                    System.err.println("Error disposing frame: " + e.getMessage())
                                }
                            }

                            activeFlashFrames.clear()
                        }
                    } finally {
                        cleanupLatch.countDown()
                    }
                }
            })

            // Wait for cleanup to complete (with timeout)
            try {
                cleanupLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (InterruptedException e) {
                System.err.println("Cleanup wait interrupted")
            }

            System.out.println("MacScreenFlasher: Cleanup completed")

        } catch (Exception e) {
            System.err.println("Error during cleanup: " + e.getMessage())
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

        System.out.println("MacScreenFlasher: Flash requested for " + events.size() + " event(s), duration: " + flashDurationMs/1000 + " seconds")

        // Clean up any existing windows first
        forceCleanup()

        // Create and show flash windows
        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()
        System.out.println("Creating flash windows for " + screens.length + " screens")
        
        List<JFrame> newFrames = []
        for (GraphicsDevice screen : screens) {
            JFrame frame = createFlashWindowForScreen(screen, events)
            if (frame != null) {
                newFrames.add(frame)
            }
        }
        
        // Add to tracking
        synchronized(activeFlashFrames) {
            activeFlashFrames.addAll(newFrames)
        }
        
        // Set up cleanup timer
        setupCleanupTimer()
    }

    /**
     * Sets up a single, reliable cleanup timer with backup
     */
    private void setupCleanupTimer() {
        long startTime = System.currentTimeMillis()

        // Primary cleanup timer
        Timer primaryTimer = new Timer(flashDurationMs, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("Primary cleanup timer fired after " +
                    (System.currentTimeMillis() - startTime) / 1000.0 + " seconds")
                forceCleanup()

                Timer source = (Timer)e.getSource()
                synchronized(activeTimers) {
                    activeTimers.remove(source)
                }
            }
        })
        primaryTimer.setRepeats(false)

        // Backup cleanup timer (runs 1 second later as failsafe)
        Timer backupTimer = new Timer(flashDurationMs + 1000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("Backup cleanup timer fired - checking for stuck windows")

                synchronized(activeFlashFrames) {
                    if (!activeFlashFrames.isEmpty()) {
                        System.out.println("Found " + activeFlashFrames.size() + " stuck windows, forcing cleanup")
                        forceCleanup()
                    }
                }
                
                Timer source = (Timer)e.getSource()
                synchronized(activeTimers) {
                    activeTimers.remove(source)
                }
            }
        })
        backupTimer.setRepeats(false)

        // Track timers and start them
        synchronized(activeTimers) {
            activeTimers.add(primaryTimer)
            activeTimers.add(backupTimer)
        }

        primaryTimer.start()
        backupTimer.start()

        System.out.println("Cleanup timers started - primary: " + flashDurationMs + "ms, backup: " + (flashDurationMs + 1000) + "ms")
    }
    
    private JFrame createFlashWindowForScreen(GraphicsDevice screen, List<CalendarEvent> events) {
        try {
            // Create frame with proper macOS settings
            JFrame frame = new JFrame("⚠️ Meeting Alert ⚠️", screen.getDefaultConfiguration())

            // Set critical properties before showing
            frame.setUndecorated(true)
            frame.setType(JFrame.Type.UTILITY)
            frame.setAlwaysOnTop(true)
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
            
            // Get configuration
            Color alertColor = getAlertColor()
            Color textColor = getAlertTextColorWithOpacity()
            double opacity = getAlertOpacity()
            
            // Set opacity safely
            try {
                float actualOpacity = (float)Math.min(Math.max(opacity, 0.7f), 0.95f)
                frame.setOpacity(actualOpacity)
            } catch (Throwable t) {
                System.err.println("Could not set opacity: " + t.getMessage())
            }
            
            // Set up frame bounds and background
            Rectangle bounds = screen.getDefaultConfiguration().getBounds()
            frame.setBounds(bounds)
            frame.setBackground(alertColor)

            // Create content panel
            JPanel panel = new JPanel(new GridBagLayout())
            panel.setBackground(alertColor)
            panel.setOpaque(true)
            frame.setContentPane(panel)

            // Create label with alert text
            String textColorHex = String.format("#%02x%02x%02x",
                textColor.getRed(), textColor.getGreen(), textColor.getBlue())
            
            StringBuilder labelContent = new StringBuilder("<html><center>" +
                "<h1 style='color: " + textColorHex + "; font-size: 64px; margin-bottom: 30px'>⚠️ MEETING ALERT ⚠️</h1>" +
                "<h2 style='color: " + textColorHex + "; font-size: 48px; margin-bottom: 20px'>")

            // Add event subjects
            for (int i = 0; i < events.size(); i++) {
                CalendarEvent event = events.get(i)
                labelContent.append(event.subject)

                // Add separator for multiple events
                if (i < events.size() - 1) {
                    labelContent.append("<br>")
                }
            }

            labelContent.append("</h2>" +
                "<p style='color: " + textColorHex + "; font-size: 36px; margin-bottom: 40px'>Starting in " +
                (events.get(0).getMinutesToStart() + 1) + " minute(s)</p>" +
                "<p style='color: " + textColorHex + "; font-size: 24px'>This alert will close in " +
                (flashDurationMs / 1000) + " seconds</p>" +
                "</center></html>")

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

            // Show frame
            frame.setVisible(true)
            frame.toFront()
            
            // Start countdown timer for this frame
            startCountdownTimer(frame, label)

            // Apply macOS window settings
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        long windowHandle = Native.getWindowID(frame)
                        MacWindowHelper.setWindowLevel(windowHandle, 1000) // CGScreenSaverWindowLevel
                        frame.toFront()
                    } catch (Exception e) {
                        System.err.println("Could not set macOS window level: " + e.getMessage())
                    }
                }
            })

            return frame
        } catch (Exception e) {
            System.err.println("Error creating flash window: " + e.getMessage())
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
            System.err.println("Error getting alert color: " + e.getMessage())
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
            System.err.println("Error getting alert text color: " + e.getMessage())
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
            System.err.println("Error getting alert opacity: " + e.getMessage())
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

        System.out.println("Starting countdown timer for frame: " + frame + " with " + totalDurationSeconds + " seconds")

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
                                    // Get the current label content
                                    String labelText = label.getText()

                                    // Update only the countdown part using regex
                                    String newLabelText = labelText.replaceFirst(
                                        "This alert will close in \\d+ seconds",
                                        "This alert will close in " + Math.max(0, secondsRemaining[0]) + " seconds"
                                    )

                                    // Set the updated text
                                    label.setText(newLabelText)
                                    label.repaint()
                                } catch (Exception ex) {
                                    System.err.println("Error updating countdown: " + ex.getMessage())
                                }
                            }
                        })
                    } catch (Exception ex) {
                        System.err.println("Error scheduling countdown update: " + ex.getMessage())
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

                    System.out.println("Countdown timer stopped for frame: " + frame)
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

        System.out.println("Countdown timer started for frame: " + frame)
    }
}
