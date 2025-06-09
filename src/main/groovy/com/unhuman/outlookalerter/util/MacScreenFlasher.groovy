package com.unhuman.outlookalerter.util

import groovy.transform.CompileStatic
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.*
import com.unhuman.outlookalerter.core.ConfigManager
import com.sun.jna.*
import com.unhuman.outlookalerter.util.MacWindowHelper
import com.unhuman.outlookalerter.util.ScreenFlasher
import com.unhuman.outlookalerter.model.CalendarEvent
import java.util.List

/**
 * Mac-specific implementation of ScreenFlasher
 * Uses Mac-specific APIs when available, falls back to cross-platform approach
 */
@CompileStatic
class MacScreenFlasher implements ScreenFlasher {
    // Flash parameters - reduced for better stability
    private static final int FLASH_DURATION_MS = 500
    private static final int FLASH_COUNT = 3
    private static final int FLASH_INTERVAL_MS = 400
    
    // Track active flash frames for cleanup
    private final List<JFrame> activeFlashFrames = Collections.synchronizedList([])
    
    /**
     * Constructor
     */
    MacScreenFlasher() {
        // Register shutdown hook to ensure cleanup
        Runtime.runtime.addShutdownHook(new Thread({ cleanup() } as Runnable))
    }
    
    /**
     * Cleanup method to dispose of any active flash frames
     */
    private void cleanup() {
        synchronized(activeFlashFrames) {
            activeFlashFrames.each { frame ->
                try {
                    if (frame && frame.isDisplayable()) {
                        frame.dispose()
                    }
                } catch (Exception e) {
                    // Ignore cleanup errors
                }
            }
            activeFlashFrames.clear()
        }
    }

    @Override
    void flash(CalendarEvent event) {
        flashScreenCrossPlatform(event)
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
            JFrame frame = new JFrame("⚠️ Meeting Alert ⚠️", screen.getDefaultConfiguration())
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
            frame.setUndecorated(true)
            frame.setAlwaysOnTop(true)
            frame.setType(javax.swing.JFrame.Type.POPUP)
            
            Color alertColor = getAlertColor()
            Color textColor = getAlertTextColorWithOpacity()
            double opacity = getAlertOpacity()
            
            try {
                frame.setOpacity((float)Math.min(Math.max(opacity, 0.5f), 0.95f))
            } catch (Throwable t) {
                println "Could not set opacity: ${t.message}"
            }
            frame.setBackground(alertColor)

            // Add margins to avoid screen edge conflicts
            Rectangle bounds = screen.getDefaultConfiguration().getBounds()
            bounds.x += 2
            bounds.y += 2
            bounds.width -= 4
            bounds.height -= 4
            frame.setBounds(bounds)
            
            frame.setFocusableWindowState(false)
            frame.setAutoRequestFocus(false)
            
            frame.setLayout(new GridBagLayout())
            GridBagConstraints gbc = new GridBagConstraints()
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.weightx = 1.0
            gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.BOTH

            String textColorHex = String.format("#%02x%02x%02x", textColor.getRed(), textColor.getGreen(), textColor.getBlue())
            String labelContent
            if (event.subject.startsWith("<html>")) {
                labelContent = event.subject // Subject already contains formatted HTML
            } else {
                labelContent = "<html><center>" +
                    "<h1 style='color: " + textColorHex + "; font-size: 48px'>⚠️ MEETING ALERT ⚠️</h1>" +
                    "<h2 style='color: " + textColorHex + "; font-size: 36px'>" + event.subject + "</h2>" +
                    "<p style='color: " + textColorHex + "; font-size: 24px'>Starting in " + (event.getMinutesToStart() + 1) + " minute(s)</p>" +
                    "</center></html>"
            }
            JLabel label = new JLabel(labelContent, SwingConstants.CENTER)

            label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36))
            label.setForeground(textColor)
            label.setBackground(alertColor)
            label.setOpaque(true)
            frame.add(label, gbc)
            
            frame.getRootPane().setOpaque(true)
            frame.getContentPane().setBackground(alertColor)
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH)
            
            frame.setVisible(true)
            
            // Set window level with a more reasonable value
            SwingUtilities.invokeLater({
                try {
                    long windowHandle = Native.getWindowID(frame)
                    MacWindowHelper.setWindowLevel(windowHandle, 20) // NSFloatingWindowLevel + 1
                    frame.toFront()
                } catch (Exception e) {
                    println "Could not set macOS window level: ${e.message}"
                }
            } as Runnable)
            
            startFlashSequence(frame)
            
            return frame
        } catch (Exception e) {
            println "Error creating flash window: ${e.message}"
            return null
        }
    }

    private void startFlashSequence(frame) {
        def flashFrame = frame as JFrame
        def flashesRemaining = FLASH_COUNT * 2
        def isVisible = true
        Timer timer
        
        // Safety cleanup timer
        def safetyTimer = new Timer(FLASH_INTERVAL_MS * (FLASH_COUNT * 2 + 2), { safetyEvent ->
            SwingUtilities.invokeLater {
                try {
                    if (timer) {
                        timer.stop()
                    }
                    if (flashFrame && flashFrame.isDisplayable()) {
                        flashFrame.dispose()
                    }
                    synchronized(activeFlashFrames) {
                        activeFlashFrames.remove(flashFrame)
                    }
                } catch (Exception e) {
                    println "Error in safety cleanup: ${e.message}"
                }
                (safetyEvent.source as Timer).stop()
            }
        })
        safetyTimer.repeats = false
        safetyTimer.start()
        
        // Main flash timer
        timer = new Timer(FLASH_INTERVAL_MS, { event ->
            SwingUtilities.invokeLater {
                try {
                    if (flashesRemaining <= 0 || !flashFrame.isDisplayable()) {
                        (event.source as Timer).stop()
                        safetyTimer.stop()
                        if (flashFrame.isDisplayable()) {
                            flashFrame.dispose()
                        }
                        synchronized(activeFlashFrames) {
                            activeFlashFrames.remove(flashFrame)
                        }
                        return
                    }
                    
                    def color = isVisible ? getAlertColor() : getAlertColor().brighter()
                    flashFrame.contentPane.background = color
                    
                    // Only bring to front occasionally to reduce window manager conflicts
                    if (flashesRemaining % 4 == 0) {
                        flashFrame.toFront()
                    }
                    
                    flashFrame.repaint()
                    flashesRemaining--
                    isVisible = !isVisible
                } catch (Exception e) {
                    println "Error in flash sequence: ${e.message}"
                    (event.source as Timer).stop()
                    safetyTimer.stop()
                    flashFrame.dispose()
                    synchronized(activeFlashFrames) {
                        activeFlashFrames.remove(flashFrame)
                    }
                }
            }
        })
        
        timer.initialDelay = 0
        timer.start()
    }

    @Override
    void flashMultiple(List<CalendarEvent> events) {
        if (!events) return
        
        if (events.size() == 1) {
            flash(events[0])
            return
        }
        
        StringBuilder subjectBuilder = new StringBuilder("<html><center>")
        subjectBuilder.append("<h1>Multiple Meetings:</h1>")
        events.each { event ->
            subjectBuilder.append("<h3>• ${event.subject}</h3>")
        }
        subjectBuilder.append("</center></html>")
        
        def combinedEvent = new CalendarEvent(
            subject: subjectBuilder.toString(),
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