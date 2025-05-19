package com.unhuman.outlookalerter

import groovy.transform.CompileStatic
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.Timer
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.awt.Graphics2D

/**
 * Mac-specific implementation of ScreenFlasher
 * Uses Mac-specific APIs when available, falls back to cross-platform approach
 */
@CompileStatic
class MacScreenFlasher implements ScreenFlasher {
    // Flash parameters
    private static final int FLASH_DURATION_MS = 500
    private static final int FLASH_COUNT = 5
    private static final int FLASH_INTERVAL_MS = 300
    
    /**
     * Flashes the screen to alert the user of an upcoming event
     */
    @Override
    void flash(CalendarEvent event) {
        // Try to use Mac-specific APIs if available using reflection to avoid compile-time dependencies
        boolean usedMacApi = tryMacSpecificFlash(event)
        
        if (!usedMacApi) {
            // Fall back to cross-platform approach
            flashScreenCrossPlatform(event)
        }
    }
    
    /**
     * Attempts to use Mac-specific APIs to flash the screen
     * @return true if successfully used Mac API, false otherwise
     */
    private boolean tryMacSpecificFlash(CalendarEvent event) {
        try {
            // In Java 17, we'll use just the notification system since that works reliably on macOS
            // We don't strictly need the taskbar bounce as macOS handle notifications well
            boolean notificationShown = false
            
            // Display notification using system tray if available
            
            // Display notification using system tray if available
            if (SystemTray.isSupported()) {
                TrayIcon trayIcon = null
                try {
                    SystemTray tray = SystemTray.getSystemTray()
                    
                    // Create a simple icon
                    BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
                    Graphics2D g = img.createGraphics()
                    g.setColor(Color.RED)
                    g.fillRect(0, 0, 16, 16)
                    g.dispose()
                    
                    trayIcon = new TrayIcon(img)
                    trayIcon.setImageAutoSize(true)
                    tray.add(trayIcon)
                    
                    // Show notification
                    trayIcon.displayMessage(
                        "Meeting Reminder",
                        "${event.subject} starts in ${event.getMinutesToStart()} minute(s)",
                        TrayIcon.MessageType.WARNING
                    )
                    
                    // Clean up tray icon after a delay
                    Timer cleanupTimer = new Timer(10000, { e ->
                        SystemTray.getSystemTray().remove(trayIcon)
                    })
                    cleanupTimer.setRepeats(false)
                    cleanupTimer.start()
                    notificationShown = true
                } catch (Exception e) {
                    println "System tray notification failed: ${e.message}"
                }
            }
            
            // Only return true if we showed a notification
            return notificationShown
        } catch (Exception e) {
            println "Mac notification API not available, falling back to cross-platform approach: ${e.message}"
            return false
        }
    }
    
    /**
     * Cross-platform screen flashing using Java Swing
     */
    private void flashScreenCrossPlatform(CalendarEvent event) {
        // Get all screens
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        GraphicsDevice[] screens = ge.getScreenDevices()
        
        // Create a flash window for each screen
        for (GraphicsDevice screen : screens) {
            createFlashWindowForScreen(screen, event)
        }
    }
    
    /**
     * Creates a flashing window for a specific screen
     */
    private void createFlashWindowForScreen(GraphicsDevice screen, CalendarEvent event) {
        // Create frame
        JFrame frame = new JFrame("Meeting Alert", screen.getDefaultConfiguration())
        frame.setUndecorated(true)
        frame.setAlwaysOnTop(true)
        
        // Set up layout
        frame.setLayout(new GridBagLayout())
        GridBagConstraints gbc = new GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 1.0
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        
        // Create label with meeting info
        JLabel label = new JLabel("<html><center>" +
                "<h1>Meeting Starting Soon!</h1>" +
                "<h2>${event.subject}</h2>" +
                "<p>Starts in ${event.getMinutesToStart()} minute(s)</p>" +
                "</center></html>", SwingConstants.CENTER)
        
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36))
        label.setForeground(Color.WHITE)
        frame.add(label, gbc)
        
        // Position frame to cover the entire screen
        frame.setBounds(screen.getDefaultConfiguration().getBounds())
        
        // Start flash sequence
        startFlashSequence(frame)
    }
    
    /**
     * Starts the flash sequence animation
     */
    private void startFlashSequence(JFrame frame) {
        final int[] flashesRemaining = [FLASH_COUNT]
        final boolean[] isVisible = [true]
        
        // Create timer for flashing
        Timer timer = new Timer(FLASH_INTERVAL_MS, { event ->
            if (flashesRemaining[0] <= 0) {
                frame.dispose()
                ((Timer)event.getSource()).stop()
                return
            }
            
            if (isVisible[0]) {
                // Hide window
                frame.setVisible(false)
            } else {
                // Show window with alternating colors
                frame.getContentPane().setBackground(
                    flashesRemaining[0] % 2 == 0 ? Color.RED : Color.ORANGE
                )
                frame.setVisible(true)
                flashesRemaining[0]--
            }
            
            isVisible[0] = !isVisible[0]
        })
        
        // Configure and start timer
        timer.setInitialDelay(0)
        timer.start()
        
        // Show window
        frame.setVisible(true)
    }
}