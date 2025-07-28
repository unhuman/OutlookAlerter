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
import java.awt.image.BufferedImage
import javax.swing.*
import com.unhuman.outlookalerter.core.ConfigManager
import com.sun.jna.*
import com.unhuman.outlookalerter.util.MacWindowHelper
import com.unhuman.outlookalerter.util.ScreenFlasher
import com.unhuman.outlookalerter.model.CalendarEvent
import java.util.List
import java.util.concurrent.CountDownLatch

/**
 * Mac-specific implementation of ScreenFlasher
 * Uses Mac-specific APIs when available, falls back to cross-platform approach
 */
@CompileStatic
class MacScreenFlasher implements ScreenFlasher {
    // Default flash parameters
    private static final int DEFAULT_FLASH_COUNT = 3
    private static final int DEFAULT_FLASH_INTERVAL_MS = 400
    
    // Instance variables for flash settings
    private int flashDurationMs
    private int flashCount = DEFAULT_FLASH_COUNT
    private int flashIntervalMs = DEFAULT_FLASH_INTERVAL_MS
    
    // Track active flash frames for cleanup
    private final List<JFrame> activeFlashFrames = Collections.synchronizedList(new ArrayList<JFrame>())
    
    // Track countdown timers for each flash window
    private final Map<JFrame, Timer> countdownTimers = Collections.synchronizedMap(new HashMap<JFrame, Timer>())
    
    // Track labels for each frame for countdown updates
    private final Map<JFrame, JLabel> countdownLabels = Collections.synchronizedMap(new HashMap<JFrame, JLabel>())
    
    /**
     * Constructor
     */
    MacScreenFlasher() {
        // Read flash duration from configuration
        try {
            ConfigManager configManager = ConfigManager.getInstance()
            if (configManager != null) {
                // Convert seconds to milliseconds
                flashDurationMs = configManager.getFlashDurationSeconds() * 1000
                System.out.println("Mac screen flasher initialized with duration: " + 
                                   configManager.getFlashDurationSeconds() + " seconds")
            } else {
                // Default value if config manager not available
                flashDurationMs = 5000 // 5 seconds
                System.out.println("Mac screen flasher using default duration: 5 seconds (config not available)")
            }
        } catch (Exception e) {
            // Use default value on error
            flashDurationMs = 5000 // 5 seconds
            System.err.println("Error initializing flash duration: " + e.getMessage())
        }
        
        // Register shutdown hook to ensure cleanup
        Runtime.runtime.addShutdownHook(new Thread({ cleanup() } as Runnable))
    }
    
    /**
     * Cleanup method to dispose of any active flash frames
     */
    private void cleanup() {
        System.out.println("MacScreenFlasher: Cleaning up " + activeFlashFrames.size() + " flash frames")
        
        // Stop all countdown timers
        synchronized(countdownTimers) {
            countdownTimers.each { frame, timer ->
                try {
                    timer.stop()
                    System.out.println("Stopped countdown timer for frame: " + frame)
                } catch (Exception e) {
                    System.err.println("Error stopping countdown timer: " + e.getMessage())
                }
            }
            countdownTimers.clear()
        }
        
        synchronized(activeFlashFrames) {
            // Make a copy of the frames to avoid concurrent modification issues
            List<JFrame> framesToCleanup = new ArrayList<>(activeFlashFrames)
            
            // Dispose each frame
            framesToCleanup.each { frameToDispose ->
                try {
                    if (frameToDispose && frameToDispose.isDisplayable()) {
                        System.out.println("Disposing flash frame: " + frameToDispose)
                        frameToDispose.setVisible(false)
                        frameToDispose.dispose()
                    }
                } catch (Exception e) {
                    System.err.println("Error disposing flash frame: " + e.getMessage())
                }
            }
            
            // Clear the master list
            activeFlashFrames.clear()
            System.out.println("MacScreenFlasher: All flash frames cleaned up")
        }
    }

    @Override
    void flash(CalendarEvent event) {
        System.out.println("MacScreenFlasher: Flash requested for duration: " + flashDurationMs/1000 + " seconds")
        
        // First make absolutely sure we don't have any existing flash windows
        System.out.println("Performing cleanup of any existing flash windows before creating new ones")
        cleanup()
        cleanupAllFrames()
        
        // Create and show a flash window on each screen
        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()
        System.out.println("Creating flash windows for " + screens.length + " screens")
        
        // Print debug info about the screens
        for (int i = 0; i < screens.length; i++) {
            GraphicsDevice screen = screens[i]
            Rectangle bounds = screen.getDefaultConfiguration().getBounds()
            System.out.println("Screen " + i + ": " + bounds.width + "x" + bounds.height + 
                " at (" + bounds.x + "," + bounds.y + ")")
        }
        
        // Create flash frames
        List<JFrame> newFrames = []
        for (GraphicsDevice screen : screens) {
            JFrame frame = createFlashWindowForScreen(screen, event)
            if (frame != null) {
                newFrames.add(frame)
                System.out.println("Created flash window: " + frame)
            }
        }
        
        // Add new frames to tracking list
        synchronized(activeFlashFrames) {
            activeFlashFrames.addAll(newFrames)
            System.out.println("MacScreenFlasher: " + newFrames.size() + " flash windows created and showing")
        }
        
        // Print the exact timestamp when the flash started and when it should end
        long startTime = System.currentTimeMillis()
        long endTime = startTime + flashDurationMs
        System.out.println("FLASH TIMELINE: Started at " + new Date(startTime) + 
                          ", should end at " + new Date(endTime) + 
                          " (duration: " + flashDurationMs/1000.0 + " seconds)")
        
        // PRIMARY cleanup thread - sleeps for exactly the duration then cleans up
        Thread cleanupThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Sleep for exactly the requested duration
                    System.out.println("PRIMARY CLEANUP: Thread sleeping for " + flashDurationMs/1000.0 + " seconds")
                    Thread.sleep(flashDurationMs)
                    
                    // Log the actual time the thread woke up
                    long wakeupTime = System.currentTimeMillis()
                    System.out.println("PRIMARY CLEANUP: Thread woke up at " + new Date(wakeupTime) + 
                                      " (slept for " + (wakeupTime - startTime)/1000.0 + " seconds)")
                    
                    // Force cleanup on the EDT - using invokeLater to avoid thread issues
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            System.out.println("PRIMARY CLEANUP: Executing window disposal now")
                            cleanup();  // Call the main cleanup method
                            cleanupAllFrames();  // Also call the direct cleanup method
                            System.out.println("PRIMARY CLEANUP: Windows should now be closed")
                        }
                    });
                } catch (Exception e) {
                    System.err.println("Error in primary cleanup thread: " + e);
                    e.printStackTrace();
                    // Still try to clean up even if an error occurred
                    cleanupAllFrames();
                }
            }
        }, "Flash-Primary-Cleanup-Thread");
        
        // Make it a high-priority daemon thread
        cleanupThread.setDaemon(true);
        cleanupThread.setPriority(Thread.MAX_PRIORITY);
        cleanupThread.start();
        
        // FIRST BACKUP cleanup mechanism - fires 500ms after the primary
        final Timer firstBackupTimer = new Timer(flashDurationMs + 500, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("FIRST BACKUP CLEANUP: Timer fired at " + 
                                  new Date() + " (" + (System.currentTimeMillis() - startTime)/1000.0 + " seconds after start)");
                cleanupAllFrames();
                ((Timer)e.getSource()).stop();
            }
        });
        firstBackupTimer.setRepeats(false);
        firstBackupTimer.start();
        
        // SECOND BACKUP cleanup mechanism - guaranteed to execute if all else fails
        final Timer secondBackupTimer = new Timer(flashDurationMs + 1500, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("FINAL BACKUP CLEANUP: Last-resort timer fired at " + 
                                  new Date() + " (" + (System.currentTimeMillis() - startTime)/1000.0 + " seconds after start)");
                
                // Force direct cleanup with the most aggressive approach - MUST use invokeLater to avoid thread issues
                try {
                    // Always use invokeLater instead of invokeAndWait to avoid potential deadlocks
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            System.out.println("FINAL CLEANUP: Performing direct frame disposal and list clearing");
                            
                            // First directly dispose any tracked frames
                            synchronized(activeFlashFrames) {
                                for (JFrame windowFrame : new ArrayList<JFrame>(activeFlashFrames)) {
                                    try {
                                        if (windowFrame != null) {
                                            windowFrame.setVisible(false);
                                            windowFrame.dispose();
                                        }
                                    } catch (Exception ex) {
                                        // Just continue - we want to try all frames
                                    }
                                }
                                
                                // Clear the tracking list
                                activeFlashFrames.clear();
                            }
                            
                            System.out.println("FINAL CLEANUP: All flash windows should now be closed");
                        }
                    });
                } catch (Exception ex) {
                    System.err.println("Error in final backup cleanup: " + ex);
                }
                
                ((Timer)e.getSource()).stop();
            }
        });
        secondBackupTimer.setRepeats(false);
        secondBackupTimer.start();
    }
    
    /**
     * Cleanup method that uses the most direct approach to close windows
     */
    private void cleanupAllFrames() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                System.out.println("Performing forced window cleanup");
                synchronized(activeFlashFrames) {
                    // Copy to avoid concurrent modification
                    List<JFrame> framesToClose = new ArrayList<>(activeFlashFrames);
                    
                    // Close each frame
                    for (JFrame frame : framesToClose) {
                        try {
                            if (frame != null) {
                                System.out.println("Closing flash frame: " + frame);
                                frame.setVisible(false);
                                frame.dispose();
                            }
                        } catch (Exception e) {
                            System.err.println("Error closing frame: " + e.getMessage());
                        }
                    }
                    
                    // Clear all references
                    activeFlashFrames.clear();
                    System.out.println("All flash frames cleared from tracking list");
                }
            }
        });
    }
    
    /**
     * Creates and starts a countdown timer for a flash frame
     * @param frame The flash frame to create a countdown for
     * @param label The label containing the countdown text
     * @param startTimeMs The time when the flash started
     */
    private void startCountdownTimer(JFrame frame, JLabel label, long startTimeMs) {
        // Calculate initial seconds remaining
        int totalDurationSeconds = (int)(flashDurationMs / 1000);
        final int[] secondsRemaining = [totalDurationSeconds];
        
        // Create a timer that fires every second
        Timer countdownTimer = new Timer(1000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Decrement seconds
                secondsRemaining[0]--;
                
                // Update the label text
                if (secondsRemaining[0] >= 0) {
                    try {
                        // Get the current label content
                        String labelText = label.getText();
                        
                        // Update only the countdown part
                        String newLabelText = labelText.replaceFirst(
                            "This alert will close in \\d+ seconds", 
                            "This alert will close in " + secondsRemaining[0] + " seconds"
                        );
                        
                        // Set the updated text
                        label.setText(newLabelText);
                    } catch (Exception ex) {
                        System.err.println("Error updating countdown: " + ex.getMessage());
                    }
                }
                
                // Stop when reaching zero
                if (secondsRemaining[0] <= 0) {
                    ((Timer)e.getSource()).stop();
                }
            }
        });
        
        // Start the timer
        countdownTimer.setRepeats(true);
        countdownTimer.start();
        
        // Store the timer for cleanup
        synchronized(countdownTimers) {
            countdownTimers.put(frame, countdownTimer);
        }
        
        System.out.println("Started countdown timer for frame: " + frame);
    }
    
    private void flashScreenCrossPlatform(CalendarEvent event) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        GraphicsDevice[] screens = ge.getScreenDevices()
        
        // Create flash windows
        List<JFrame> newFrames = []
        for (GraphicsDevice screen : screens) {
            JFrame frame = createFlashWindowForScreen(screen, event)
            if (frame) {
                newFrames.add(frame)
            }
        }
        
        // Add new frames to tracking list
        synchronized(activeFlashFrames) {
            activeFlashFrames.addAll(newFrames)
        }
    }
    
    private JFrame createFlashWindowForScreen(GraphicsDevice screen, CalendarEvent event) {
        try {
            // Detect if this is the main screen
            boolean isMainScreen = (screen == GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice())
            System.out.println("Creating flash window for " + (isMainScreen ? "main" : "secondary") + " screen")
            
            // Create a special kind of frame for macOS
            JFrame frame = new JFrame("⚠️ Meeting Alert ⚠️", screen.getDefaultConfiguration()) {
                // Override paint to ensure our content is always drawn
                @Override
                public void paint(Graphics g) {
                    super.paint(g)
                    // Add additional custom painting if needed
                }
                
                // Override update to prevent automatic clearing
                @Override
                public void update(Graphics g) {
                    paint(g)
                }
            }
            
            // Set critical window properties - MUST do this before the frame is shown
            // Order matters - type and undecorated MUST be set before the frame is displayed
            frame.setUndecorated(true)
            frame.setType(javax.swing.JFrame.Type.UTILITY)  // UTILITY type is more likely to stay visible on macOS
            frame.setAlwaysOnTop(true)
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
            
            // Get colors and opacity from config
            Color alertColor = getAlertColor()
            Color textColor = getAlertTextColorWithOpacity()
            double opacity = getAlertOpacity()
            
            try {
                // Set opacity but ensure it's visible enough
                float actualOpacity = (float)Math.min(Math.max(opacity, 0.7f), 0.95f)
                frame.setOpacity(actualOpacity)
                System.out.println("Set frame opacity to: " + actualOpacity)
            } catch (Throwable t) {
                System.err.println("Could not set opacity: " + t.getMessage())
            }
            
            // Set the background color
            frame.setBackground(alertColor)

            // Calculate bounds to completely cover the screen
            // Note: We're using the full screen bounds without margins
            Rectangle bounds = screen.getDefaultConfiguration().getBounds()
            frame.setBounds(bounds)
            
            // Prevent focus stealing and auto-focus
            frame.setFocusableWindowState(true) // Changed to true to ensure visibility
            frame.setAutoRequestFocus(true)     // Changed to true for visibility
            
            // Set up the layout
            frame.setLayout(new GridBagLayout())
            GridBagConstraints gbc = new GridBagConstraints()
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.weightx = 1.0
            gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.BOTH
            
            // Create a custom JPanel with special painting capabilities
            JPanel customPanel = new JPanel(new GridBagLayout()) {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    // Fill with alert color for good measure
                    g.setColor(alertColor);
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            customPanel.setBackground(alertColor);
            customPanel.setOpaque(true);
            frame.setContentPane(customPanel);

            // Format the text color for HTML
            String textColorHex = String.format("#%02x%02x%02x", 
                textColor.getRed(), textColor.getGreen(), textColor.getBlue())
            
            // Prepare the label content
            String labelContent
            if (event.subject.startsWith("<html>")) {
                labelContent = event.subject // Subject already contains formatted HTML
            } else {
                // Create rich HTML content with strong visual indicators
                labelContent = "<html><center>" +
                    "<h1 style='color: " + textColorHex + "; font-size: 64px; margin-bottom: 30px'>⚠️ MEETING ALERT ⚠️</h1>" +
                    "<h2 style='color: " + textColorHex + "; font-size: 48px; margin-bottom: 20px'>" + event.subject + "</h2>" +
                    "<p style='color: " + textColorHex + "; font-size: 36px; margin-bottom: 40px'>Starting in " + 
                    (event.getMinutesToStart() + 1) + " minute(s)</p>" +
                    "<p></p><p></p><p id='countdownText' style='color: " + textColorHex + "; font-size: 12px'>This alert will close in " + 
                    (flashDurationMs / 1000) + " seconds</p>" +
                    "</center></html>"
            }
            
            // Create the label with the alert content
            JLabel label = new JLabel(labelContent, SwingConstants.CENTER)
            label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 48)) // Larger font
            label.setForeground(textColor)
            label.setBackground(alertColor)
            label.setOpaque(true)
            
            // Add the label to the panel
            customPanel.add(label, gbc)
            
            // Store the label in the map for access in the countdown timer
            countdownLabels.put(frame, label)
            
            // Ensure all components have the right background
            frame.getRootPane().setOpaque(true)
            frame.getContentPane().setBackground(alertColor)
            
            // Set to full screen mode
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH)
            
            // Show the frame
            frame.setVisible(true)
            frame.toFront()
            
            // Apply special macOS window settings with higher window level
            SwingUtilities.invokeLater({
                try {
                    // Get the native window handle
                    long windowHandle = Native.getWindowID(frame)
                    
                    // Use the highest possible window level
                    // CGFloatingWindowLevel (5) < CGStatusWindowLevel (25) < CGScreenSaverWindowLevel (1000)
                    int windowLevel = 1000 // CGScreenSaverWindowLevel - highest possible
                    
                    // Apply the window level
                    MacWindowHelper.setWindowLevel(windowHandle, windowLevel)
                    System.out.println("Set macOS window level to: " + windowLevel)
                    
                    // Force to front again
                    frame.toFront()
                    
                    // Request focus
                    frame.requestFocus()
                    label.requestFocusInWindow()
                } catch (Exception e) {
                    System.err.println("Could not set macOS window level: " + e.getMessage())
                }
            } as Runnable)
            
            // Start the flash sequence to keep it visible
            startFlashSequence(frame)
            
            return frame
        } catch (Exception e) {
            System.err.println("Error creating flash window: " + e.getMessage())
            e.printStackTrace()
            return null
        }
    }

    private void startFlashSequence(frame) {
        def flashFrame = frame as JFrame
        
        // Record the start time of the flash
        long startTimeMs = System.currentTimeMillis()
        println "Flash starting at: ${startTimeMs} ms, configured duration: ${flashDurationMs} ms"
        
        // Get the countdown label from the map
        JLabel countdownLabel = countdownLabels.get(flashFrame)
        if (countdownLabel != null) {
            // Start the countdown timer
            startCountdownTimer(flashFrame, countdownLabel, startTimeMs)
        }
        
        // Create a countdown latch that will be released when the duration is complete
        final CountDownLatch durationLatch = new CountDownLatch(1)
        
        // ------------------------------------------------------------------------
        // PART 1: SET UP INITIAL WINDOW WITH MAXIMUM VISIBILITY
        // ------------------------------------------------------------------------
        
        System.out.println("FLASH SEQUENCE STARTED - Target duration: " + flashDurationMs/1000 + " seconds")
        
        // Request focus and bring to front - don't attempt to set type or undecorated again
        // as those must be set before the frame is displayable
        flashFrame.setAlwaysOnTop(true)
        flashFrame.requestFocus()
        flashFrame.toFront()
        
        // Use native Mac window level with enhanced properties to prevent automatic dismissal
        try {
            long windowHandle = Native.getWindowID(flashFrame)
            
            // Use the CGScreenSaverWindowLevel (1000) which is very high
            MacWindowHelper.setEnhancedWindowProperties(windowHandle, 1000)
            System.out.println("Set enhanced window properties with CGScreenSaverWindowLevel (1000)")
            
            // Re-show the window and bring to front after setting properties
            flashFrame.setVisible(true)
            flashFrame.toFront()
            flashFrame.requestFocus()
        } catch (Exception e) {
            System.err.println("Error setting enhanced window properties: " + e.getMessage())
            e.printStackTrace()
        }
        
        // ------------------------------------------------------------------------
        // PART 2: CREATE A DEDICATED "KEEP ALIVE" THREAD FOR THE WINDOW
        // ------------------------------------------------------------------------
        
        Thread keepAliveThread = new Thread({
            println "Keep alive thread started"
            int tick = 0
            
            try {
                // Keep running until the duration latch is counted down
                while (!durationLatch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    tick++
                    
                    try {
                        SwingUtilities.invokeAndWait({
                            try {
                                // Ensure window exists and is visible
                                if (flashFrame != null && flashFrame.isDisplayable()) {
                                    // Force visibility on
                                    if (!flashFrame.isVisible()) {
                                        flashFrame.setVisible(true)
                                    }
                                    
                                    // Ensure it stays on top and has focus
                                    flashFrame.setAlwaysOnTop(true)
                                    flashFrame.toFront()
                                    
                                    // Request focus periodically
                                    if (tick % 10 == 0) {
                                        flashFrame.requestFocus()
                                    }
                                    
                                    // Toggle colors for visual effect (less frequently)
                                    if (tick % 5 == 0) {
                                        boolean isEvenTick = (tick % 10 == 0)
                                        Color color = isEvenTick ? 
                                            getAlertColor() : getAlertColor().brighter()
                                        flashFrame.getContentPane().setBackground(color)
                                        flashFrame.repaint()
                                    }
                                    
                                    // Use native window APIs to force window visibility (periodically)
                                    if (tick % 15 == 0) {
                                        try {
                                            long windowHandle = Native.getWindowID(flashFrame)
                                            
                                            // Use the enhanced window properties to keep it visible
                                            MacWindowHelper.setEnhancedWindowProperties(windowHandle, 1000)
                                            
                                            if (tick % 30 == 0) {
                                                System.out.println("Refreshed window visibility at tick " + tick + 
                                                    " - elapsed: " + (System.currentTimeMillis() - startTimeMs)/1000.0 + 
                                                    " seconds")
                                            }
                                        } catch (Exception e) {
                                            // Ignore errors here
                                        }
                                    }
                                    
                                    // Print diagnostic info every 20 ticks (2 seconds) for better visibility tracking
                                    if (tick % 20 == 0) {
                                        long elapsed = System.currentTimeMillis() - startTimeMs
                                        long remaining = Math.max(0, flashDurationMs - elapsed)
                                        System.out.println("Flash active for " + 
                                            (elapsed / 1000.0) + " seconds (target: " + 
                                            (flashDurationMs / 1000.0) + ", remaining: " +
                                            (remaining / 1000.0) + " seconds)");
                                        
                                        // Print if the window is still visible
                                        System.out.println("  Window status: visible=" + flashFrame.isVisible() + 
                                                          ", showing=" + flashFrame.isShowing() + 
                                                          ", displayable=" + flashFrame.isDisplayable() +
                                                          ", valid=" + flashFrame.isValid());
                                    }
                                }
                            } catch (Exception e) {
                                println "Error in keep-alive cycle: ${e.message}"
                            }
                        } as Runnable)
                    } catch (Exception e) {
                        // Just continue - we want this thread to be extremely resilient
                    }
                }
                
                println "Keep alive thread detected duration completion"
            } catch (Exception e) {
                println "Error in keep alive thread: ${e.message}"
            }
        })
        
        // Set as daemon thread and start
        keepAliveThread.setDaemon(true)
        keepAliveThread.setPriority(Thread.MAX_PRIORITY) // Give it highest priority
        keepAliveThread.start()
        
        // ------------------------------------------------------------------------
        // PART 3: CREATE TIMER TO MANAGE THE FLASH DURATION
        // ------------------------------------------------------------------------
        
        // Create a thread that will wait for the exact duration and then clean up
        Thread durationThread = new Thread({
            try {
                // Sleep for exactly the configured duration
                Thread.sleep(flashDurationMs)
                
                println "Flash duration timer completed after ${(System.currentTimeMillis() - startTimeMs)/1000.0} seconds"
                
                // Release the countdown latch to signal the keep-alive thread
                durationLatch.countDown()
                
                // Call the direct cleanup method to ensure windows are closed
                cleanupAllFrames()
                
                // Also clean up this specific frame
                SwingUtilities.invokeLater({
                    try {
                        // Ensure the window is properly disposed
                        if (flashFrame != null) {
                            System.out.println("Disposing flash frame from duration thread")
                            flashFrame.setVisible(false)
                            flashFrame.dispose()
                            
                            // Remove from the active frames list
                            synchronized(activeFlashFrames) {
                                activeFlashFrames.remove(flashFrame)
                                System.out.println("Removed frame from activeFlashFrames. Remaining: " + 
                                    activeFlashFrames.size())
                            }
                            
                            println "Flash window disposed after ${(System.currentTimeMillis() - startTimeMs)/1000.0} seconds"
                        }
                    } catch (Exception e) {
                        System.err.println("Error in duration cleanup: " + e.getMessage())
                        e.printStackTrace()
                    }
                })
                
            } catch (Exception e) {
                System.err.println("Error in duration thread: " + e.getMessage())
                e.printStackTrace()
                
                // Emergency cleanup - call the direct cleanup method
                cleanupAllFrames()
                
                // Make sure the latch gets counted down
                durationLatch.countDown()
            }
        })
        
        // Set as daemon thread and start
        durationThread.setDaemon(true)
        durationThread.setPriority(Thread.MAX_PRIORITY - 1)
        durationThread.start()
        
        // ------------------------------------------------------------------------
        // PART 4: CREATE A SAFETY TIMER FOR EMERGENCY CLEANUP
        // ------------------------------------------------------------------------
        
        // Add an extra safety timer as a backup (belt and suspenders approach)
        // This runs slightly after the main duration timer as a failsafe
        Timer safetyTimer = new Timer(flashDurationMs + 500, { safetyEvent ->
            try {
                System.out.println("SAFETY TIMER triggered after " + 
                    ((System.currentTimeMillis() - startTimeMs)/1000.0) + " seconds")
                
                // Make sure the latch is counted down
                durationLatch.countDown()
                
                // Directly call our cleanup method which is the most reliable approach
                cleanupAllFrames()
                
                // Final emergency cleanup - MUST use invokeLater to avoid thread issues
                // Force cleanup on EDT
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Force immediate cleanup of this specific frame first
                            if (flashFrame != null) {
                                System.out.println("SAFETY TIMER: Direct cleanup of flash frame")
                                flashFrame.setVisible(false)
                                flashFrame.dispose()
                                System.out.println("SAFETY TIMER: Flash frame directly disposed")
                            }
                            
                            // Now force cleanup of ALL tracked frames to be absolutely sure
                            System.out.println("SAFETY TIMER: Performing complete flash window cleanup")
                            synchronized(activeFlashFrames) {
                                for (JFrame trackedFrame : new ArrayList<JFrame>(activeFlashFrames)) {
                                    try {
                                        if (trackedFrame != null) {
                                            trackedFrame.setVisible(false)
                                            trackedFrame.dispose()
                                            System.out.println("SAFETY TIMER: Disposed tracked frame")
                                        }
                                    } catch (Exception e) {
                                        System.err.println("Error disposing frame in safety timer: " + e.getMessage())
                                    }
                                }
                                
                                // Clear all tracked frames
                                int count = activeFlashFrames.size();
                                activeFlashFrames.clear()
                                System.out.println("SAFETY TIMER: Cleared " + count + " tracked frames")
                            }
                        } catch (Exception e) {
                            System.err.println("Error in safety timer cleanup: " + e.getMessage())
                            e.printStackTrace()
                            
                            // Last resort - try one more time with a different approach
                            try {
                                cleanup(); // Call the main cleanup method
                                System.out.println("SAFETY TIMER: Called last-resort cleanup method")
                            } catch (Exception ex) {
                                // At this point we've tried everything
                                System.err.println("All cleanup attempts failed: " + ex.getMessage())
                            }
                        }
                    }
                });
            } finally {
                (safetyEvent.source as Timer).stop()
            }
        })
        safetyTimer.setRepeats(false)
        safetyTimer.start()
    }

    @Override
    void flashMultiple(List<CalendarEvent> events) {
        if (!events) return
        
        if (events.size() == 1) {
            flash(events[0])
            return
        }
        
        // Create a combined subject for multiple events
        Color textColor = getAlertTextColorWithOpacity()
        String textColorHex = String.format("#%02x%02x%02x", textColor.getRed(), textColor.getGreen(), textColor.getBlue())
        
        StringBuilder stringBuilder = new StringBuilder(512)
        stringBuilder.append("<html><center>")
        stringBuilder.append("<h1 style='color: " + textColorHex + "; font-size: 48px'>⚠️ MULTIPLE MEETING ALERT ⚠️</h1>")                
        events.each { event ->
            stringBuilder.append("<h2 style='color: " + textColorHex + "; font-size: 36px'>" + event.subject + "</h2>")
        }
        stringBuilder.append("<p style='color: " + textColorHex + "; font-size: 24px'>Starting in " + (events[0].getMinutesToStart() + 1) + " minute(s)</p>")
        stringBuilder.append("<p></p><p id='countdownText' style='color: " + textColorHex + "; font-size: 12px'>This alert will close in " + 
                    (flashDurationMs / 1000) + " seconds</p>")
        stringBuilder.append("</center></html>")
        
        def combinedEvent = new CalendarEvent(
            subject: stringBuilder.toString(),
            startTime: events.min { it.startTime }?.startTime,
            endTime: events.max { it.endTime }?.endTime
        )
        
        flash(combinedEvent)
    }

    private Color getAlertColor() {
        try {
            def configManager = ConfigManager.getInstance()
            String colorHex = configManager?.flashColor ?: "#800000"
            return Color.decode(colorHex)
        } catch (Exception e) {
            return new Color(128, 0, 0)
        }
    }

    private double getAlertOpacity() {
        try {
            def configManager = ConfigManager.getInstance()
            return configManager?.flashOpacity ?: 0.85d  // Reduced default opacity
        } catch (Exception e) {
            return 0.85d
        }
    }

    private Color getAlertTextColorWithOpacity() {
        try {
            def configManager = ConfigManager.getInstance()
            String colorHex = configManager?.flashTextColor ?: "#ffffff"
            double opacity = configManager?.flashOpacity ?: 0.85d
            Color base = Color.decode(colorHex)
            int alpha = (int)Math.round(opacity * 255)
            return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha)
        } catch (Exception e) {
            return new Color(255, 255, 255, (int)Math.round(getAlertOpacity() * 255))
        }
    }
}