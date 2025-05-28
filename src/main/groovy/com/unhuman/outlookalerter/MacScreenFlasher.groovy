package com.unhuman.outlookalerter

import groovy.transform.CompileStatic
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.*
import com.sun.jna.*
import com.unhuman.outlookalerter.MacWindowHelper
import com.unhuman.outlookalerter.ScreenFlasher
import com.unhuman.outlookalerter.CalendarEvent
import java.util.List

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
            // We don't create a temporary tray icon for notifications on macOS
            // This avoids the red square appearing in the menu bar during flashing
            // Notifications will still be shown through the main application's tray icon instead
            // This was changed to improve the user experience on macOS
            
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
        // Create frame with specific type for better visibility
        JFrame frame = new JFrame("⚠️ Meeting Alert ⚠️", screen.getDefaultConfiguration())
        frame.setUndecorated(true)
        frame.setAlwaysOnTop(true)
        frame.setType(javax.swing.JFrame.Type.POPUP) // POPUP type may work better than UTILITY
        
        // Use configured color and opacity
        Color alertColor = getAlertColor();
        double opacity = getAlertOpacity();
        Color textColor = getAlertTextColorWithOpacity();
        try { frame.setOpacity((float)opacity); } catch (Throwable t) {}
        frame.setBackground(alertColor)

        // Position frame to cover the entire screen before showing
        frame.setBounds(screen.getDefaultConfiguration().getBounds())
        
        // Set window to be non-focusable but still visible
        frame.setFocusableWindowState(false)
        frame.setAutoRequestFocus(false)

        // Show the frame to create native window
        frame.setVisible(true)
        frame.toFront()

        // Set the window level to appear above full screen apps
        try {
            // Wait briefly for the native window to be created
            Thread.sleep(100)
            
            // Get the window handle and set its level with enhanced properties
            long windowHandle = Native.getWindowID(frame)
            MacWindowHelper.setWindowLevel(windowHandle, 2147483647) // CGMaximumWindowLevel
            
            // Force it to the front again after setting level
            frame.toFront()
        } catch (Exception e) {
            println "Could not set macOS window level: ${e.message}"
        }
        
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
        label.setBackground(alertColor);
        label.setOpaque(true);
        frame.add(label, gbc)
        
        // Force frame to be fully opaque
        frame.getRootPane().setOpaque(true)
        frame.getContentPane().setBackground(alertColor)
        
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
    
    /**
     * Flashes the screen to alert the user of multiple events in the same time window
     * @param events List of calendar events starting soon
     */
    void flashMultiple(List<CalendarEvent> events) {
        if (events == null || events.isEmpty()) return;
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
        JFrame frame = new JFrame("⚠️ Meeting Alert ⚠️", screen.getDefaultConfiguration());
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        frame.setType(javax.swing.JFrame.Type.POPUP);
        double opacity = getAlertOpacity();
        try { frame.setOpacity((float)opacity); } catch (Throwable t) {}
        Color alertColor = getAlertColor();
        Color textColor = getAlertTextColorWithOpacity();
        frame.setBackground(alertColor);
        frame.setBounds(screen.getDefaultConfiguration().getBounds());
        frame.setFocusableWindowState(false);
        frame.setAutoRequestFocus(false);
        frame.setVisible(true);
        frame.toFront();
        try {
            Thread.sleep(100);
            long windowHandle = Native.getWindowID(frame);
            MacWindowHelper.setWindowLevel(windowHandle, 2147483647);
            frame.toFront();
        } catch (Exception e) {
            println "Could not set macOS window level: ${e.message}";
        }
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
        frame.getRootPane().setOpaque(true);
        frame.getContentPane().setBackground(alertColor);
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        startFlashSequence(frame);
    }

    private Color getAlertColor() {
        try {
            def configManager = com.unhuman.outlookalerter.ConfigManager.getInstance()
            String colorHex = configManager?.flashColor ?: "#800000"
            return Color.decode(colorHex)
        } catch (Exception e) {
            return new Color(128, 0, 0)
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
    private Color getAlertTextColorWithOpacity() {
        try {
            def configManager = com.unhuman.outlookalerter.ConfigManager.getInstance()
            String colorHex = configManager?.flashTextColor ?: "#ffffff"
            double opacity = configManager?.flashOpacity ?: 1.0d
            Color base = Color.decode(colorHex)
            int alpha = (int)Math.round(opacity * 255)
            return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha)
        } catch (Exception e) {
            return new Color(255, 255, 255, (int)Math.round(getAlertOpacity() * 255))
        }
    }
}