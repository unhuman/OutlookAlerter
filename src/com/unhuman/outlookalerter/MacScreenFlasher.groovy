package com.unhuman.outlookalerter

import groovy.transform.CompileStatic
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
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
            // Always show notification if supported
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
                } catch (Exception e) {
                    println "System tray notification failed: ${e.message}"
                }
            }
            
            // Flash all screens using Java's built-in capabilities
            flashScreenCrossPlatform(event)
            
            return true
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
        frame.setType(javax.swing.JFrame.Type.UTILITY) // This helps ensure it stays on top
        
        // Make sure the window is opaque and visible
        frame.setOpacity(1.0f)
        frame.setBackground(Color.RED)
        
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
                "<h1 style='color: white; font-size: 48px'>⚠️ MEETING ALERT ⚠️</h1>" +
                "<h2 style='color: white; font-size: 36px'>${event.subject}</h2>" +
                "<p style='color: white; font-size: 24px'>Starting in ${event.getMinutesToStart()} minute(s)</p>" +
                "</center></html>", SwingConstants.CENTER)
        
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36))
        label.setForeground(Color.WHITE)
        frame.add(label, gbc)
        
        // Force frame to be fully opaque
        frame.getRootPane().setOpaque(true)
        frame.getContentPane().setBackground(Color.RED)
        
        // Position frame to cover the entire screen
        frame.setBounds(screen.getDefaultConfiguration().getBounds())
        
        // Make sure it's displayed in full screen
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH)
        
        // Start flash sequence
        startFlashSequence(frame)
    }
    
    /**
     * Starts the flash sequence animation
     */
    private void startFlashSequence(JFrame frame) {
        final int[] flashesRemaining = [FLASH_COUNT * 2]  // Double the flashes for more visibility
        final boolean[] isVisible = [true]
        
        // Ensure the frame starts visible and on top
        SwingUtilities.invokeLater({
            frame.setVisible(true)
            frame.toFront()
            
            // Create timer for flashing
            Timer timer = new Timer(FLASH_INTERVAL_MS, { event ->
                if (flashesRemaining[0] <= 0) {
                    frame.dispose()
                    ((Timer)event.getSource()).stop()
                    return
                }
                
                if (isVisible[0]) {
                    // Keep window visible but change color
                    frame.getContentPane().setBackground(Color.RED)
                    frame.toFront()
                } else {
                    frame.getContentPane().setBackground(Color.ORANGE)
                    frame.toFront()
                }
                
                frame.repaint()
                flashesRemaining[0]--
                isVisible[0] = !isVisible[0]
            })
            
            // Configure and start timer
            timer.setInitialDelay(0)
            timer.start()
        } as Runnable)
    }
}