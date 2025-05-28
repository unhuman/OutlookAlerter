package com.unhuman.outlookalerter

import com.unhuman.outlookalerter.ScreenFlasher
import com.unhuman.outlookalerter.CalendarEvent
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

/**
 * Windows-specific implementation of ScreenFlasher
 * Uses Windows-specific APIs when available, falls back to cross-platform approach
 */
@CompileStatic
class WindowsScreenFlasher implements ScreenFlasher {
    // Flash parameters
    private static final int FLASH_DURATION_MS = 500
    private static final int FLASH_COUNT = 5
    private static final int FLASH_INTERVAL_MS = 300
    
    /**
     * Flashes the screen to alert the user of an upcoming event
     */
    @Override
    void flash(CalendarEvent event) {
        // Try to use Windows-specific APIs if available using JNA
        boolean usedWindowsApi = tryWindowsSpecificFlash(event)
        
        if (!usedWindowsApi) {
            // Fall back to cross-platform approach
            flashScreenCrossPlatform(event)
        }
    }
    
    /**
     * Attempts to use Windows-specific APIs to flash the screen
     * @return true if successfully used Windows API, false otherwise
     */
    private boolean tryWindowsSpecificFlash(CalendarEvent event) {
        try {
            // Try to use JNA to access Windows API
            // This requires the JNA library to be in the classpath
            Class<?> userClass = Class.forName("com.sun.jna.platform.win32.User32")
            Class<?> winUserClass = Class.forName("com.sun.jna.platform.win32.WinUser")
            
            // Get constants
            Object flashwInfo = winUserClass.getField("FLASHWINFO").get(null)
            Class<?> flashwInfoClass = flashwInfo.getClass()
            
            // Create flash info structure
            Object flashInfo = flashwInfoClass.newInstance()
            
            // Set flash properties
            flashwInfoClass.getField("cbSize").set(flashInfo, flashwInfoClass.getDeclaredField("size").get(null))
            flashwInfoClass.getField("dwFlags").set(flashInfo, winUserClass.getField("FLASHW_ALL").get(null))
            flashwInfoClass.getField("uCount").set(flashInfo, FLASH_COUNT)
            flashwInfoClass.getField("dwTimeout").set(flashInfo, FLASH_INTERVAL_MS)
            
            // Get current foreground window
            Object user32 = userClass.getMethod("INSTANCE").invoke(null)
            Object hwnd = userClass.getMethod("GetForegroundWindow").invoke(user32)
            flashwInfoClass.getField("hwnd").set(flashInfo, hwnd)
            
            // Flash window
            userClass.getMethod("FlashWindowEx", flashwInfoClass).invoke(user32, flashInfo)
            
            // Show notification using system tray
            showSystemTrayNotification(event)
            
            return true
        } catch (Exception e) {
            println "Windows API not available, falling back to cross-platform approach: ${e.message}"
            return false
        }
    }
    
    /**
     * Shows a system tray notification with event details
     */
    private void showSystemTrayNotification(CalendarEvent event) {
        try {
            // Use Java's built-in system tray support
            if (java.awt.SystemTray.isSupported()) {
                java.awt.SystemTray tray = java.awt.SystemTray.getSystemTray()
                
                // Create a tray icon
                java.awt.Image image = java.awt.Toolkit.getDefaultToolkit()
                        .createImage(getClass().getResource("/resources/calendar_icon.png"))
                if (image == null) {
                    // Use a default image if resource not found
                    image = java.awt.Toolkit.getDefaultToolkit()
                            .createImage(new byte[0])
                }
                
                java.awt.TrayIcon trayIcon = new java.awt.TrayIcon(image, "Outlook Alerter")
                trayIcon.setImageAutoSize(true)
                
                // Show notification
                trayIcon.displayMessage(
                        "Meeting Reminder",
                        "${event.subject} starts in ${event.getMinutesToStart()} minute(s)",
                        java.awt.TrayIcon.MessageType.WARNING
                )
                
                // Remove tray icon after a delay
                Thread.start {
                    Thread.sleep(10000)
                    tray.remove(trayIcon)
                }
            }
        } catch (Exception e) {
            println "Error showing system tray notification: ${e.message}"
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
        frame.setType(javax.swing.JFrame.Type.POPUP)
        double opacity = getAlertOpacity();
        try { frame.setOpacity((float)opacity); } catch (Throwable t) {}
        Color alertColor = getAlertColor();
        Color textColor = getAlertTextColorWithOpacity();
        frame.setBackground(alertColor)
        // Set up layout
        frame.setLayout(new GridBagLayout())
        GridBagConstraints gbc = new GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 1.0
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        // Build HTML with placeholder for text color
        String textColorHex = String.format("#%02x%02x%02x", textColor.getRed(), textColor.getGreen(), textColor.getBlue());
        JLabel label = new JLabel("<html><center>" +
                "<h1 style='color: " + textColorHex + "; font-size: 48px'>⚠️ MEETING ALERT ⚠️</h1>" +
                "<h2 style='color: " + textColorHex + "; font-size: 36px'>" + event.subject + "</h2>" +
                "<p style='color: " + textColorHex + "; font-size: 24px'>Starting in " + event.getMinutesToStart() + " minute(s)</p>" +
                "</center></html>", SwingConstants.CENTER)
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36))
        label.setForeground(textColor)
        label.setBackground(alertColor)
        label.setOpaque(true)
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
    
    /**
     * Flashes the screen to alert the user of multiple events in the same time window
     * @param events List of calendar events starting soon
     */
    void flashMultiple(List<CalendarEvent> events) {
        if (events == null || events.isEmpty()) return;
        // Get all screens
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] screens = ge.getScreenDevices();
        for (GraphicsDevice screen : screens) {
            createFlashWindowForScreenMultiple(screen, events);
        }
    }

    /**
     * Creates a flashing window for a specific screen for multiple events
     */
    private void createFlashWindowForScreenMultiple(GraphicsDevice screen, List<CalendarEvent> events) {
        JFrame frame = new JFrame("Meeting Alert", screen.getDefaultConfiguration());
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        frame.setType(javax.swing.JFrame.Type.POPUP);
        double opacity = getAlertOpacity();
        try { frame.setOpacity((float)opacity); } catch (Throwable t) {}
        Color alertColor = getAlertColor();
        Color textColor = getAlertTextColorWithOpacity();
        frame.setBackground(alertColor);
        frame.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        // Build HTML for all events
        String textColorHex = String.format("#%02x%02x%02x", textColor.getRed(), textColor.getGreen(), textColor.getBlue());
        StringBuilder html = new StringBuilder("<html><center><h1 style='color: " + textColorHex + "; font-size: 48px'>⚠️ MEETING ALERT ⚠️</h1>");
        for (CalendarEvent event : events) {
            html.append("<h2 style='color: " + textColorHex + "; font-size: 36px'>").append(event.subject).append("</h2>");
            html.append("<p style='color: " + textColorHex + "; font-size: 24px'>Starting in ").append(event.getMinutesToStart()).append(" minute(s)</p>");
        }
        html.append("</center></html>");
        JLabel label = new JLabel(html.toString(), SwingConstants.CENTER);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        label.setForeground(textColor);
        label.setBackground(alertColor);
        label.setOpaque(true);
        frame.add(label, gbc);
        frame.setBounds(screen.getDefaultConfiguration().getBounds());
        startFlashSequence(frame);
    }

    private Color getAlertTextColorWithOpacity() {
        try {
            def configManager = com.unhuman.outlookalerter.ConfigManager.getInstance()
            String colorHex = configManager?.flashTextColor ?: "#ffffff"
            double opacity = configManager?.flashOpacity ?: 1.0d
            Color base = Color.decode(colorHex)
            int alpha = (int)Math.round(opacity * 255);
            return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha)
        } catch (Exception e) {
            return new Color(255, 255, 255, Math.round(getAlertOpacity() * 255))
        }
    }
    private double getAlertOpacity() {
        try {
            def configManager = com.unhuman.outlookalerter.ConfigManager.getInstance()
            return configManager?.flashOpacity ?: 1.0d
        } catch (Exception e) {
            return 1.0d
        }
    }
    private Color getAlertColor() {
        try {
            def configManager = com.unhuman.outlookalerter.ConfigManager.getInstance()
            String colorHex = configManager?.flashColor ?: "#800000"
            double opacity = configManager?.flashOpacity ?: 1.0d
            Color base = Color.decode(colorHex)
            int alpha = (int)Math.round(opacity * 255);
            return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha)
        } catch (Exception e) {
            return new Color(128, 0, 0, Math.round(getAlertOpacity() * 255))
        }
    }
}