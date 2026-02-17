package com.unhuman.outlookalerter.util

import groovy.transform.CompileStatic
import java.awt.Color
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import com.unhuman.outlookalerter.model.CalendarEvent
import com.unhuman.outlookalerter.core.ConfigManager

/**
 * Cross-platform implementation of the ScreenFlasher interface using Java Swing
 */
@CompileStatic
class CrossPlatformScreenFlasher implements ScreenFlasher {
    // Configurable flash duration, will be set from ConfigManager
    private int flashDurationMs = 5000 // Default 5 seconds
    
    // Track active flash windows for external cleanup
    private final List<JFrame> activeFlashWindows = new java.util.concurrent.CopyOnWriteArrayList<JFrame>()
    private final List<javax.swing.Timer> activeTimers = new java.util.concurrent.CopyOnWriteArrayList<javax.swing.Timer>()
    
    /**
     * Constructor that initializes the flash duration from ConfigManager
     */
    CrossPlatformScreenFlasher() {
        try {
            // Try to get ConfigManager instance
            ConfigManager configManager = ConfigManager.getInstance()
            if (configManager != null) {
                // Convert seconds to milliseconds
                flashDurationMs = configManager.getFlashDurationSeconds() * 1000
                System.out.println("Screen flasher initialized with duration: " + 
                                 configManager.getFlashDurationSeconds() + " seconds")
            }
        } catch (Exception e) {
            System.err.println("Error initializing flash duration from config: " + e.getMessage())
            // Use default duration if there's an error
        }
    }
    
    @Override
    void flash(CalendarEvent event) {
        try {
            // Get all screens
            GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()
            
            // Create flash windows for each screen
            List<JFrame> flashWindows = []
            
            for (GraphicsDevice screen : screens) {
                Rectangle bounds = screen.getDefaultConfiguration().getBounds()
                
                JFrame frame = new JFrame(screen.getDefaultConfiguration())
                frame.setUndecorated(true)
                frame.setAlwaysOnTop(true)
                
                // Create a panel with meeting info
                MeetingInfoPanel panel = new MeetingInfoPanel(event)
                frame.add(panel)
                
                // Set size and location to cover the entire screen
                frame.setBounds(bounds)
                
                flashWindows.add(frame)
            }
            
            // Track windows for external cleanup BEFORE showing them,
            // so forceCleanup() can dispose them even during setup
            activeFlashWindows.addAll(flashWindows)

            // Start flashing in a separate thread
            Thread flashThread = new Thread({
                try {
                    // Record start time for proper duration tracking
                    long startTimeMs = System.currentTimeMillis()
                    println "Cross-platform flash starting at: ${startTimeMs}, configured duration: ${flashDurationMs} ms"
                    
                    // Show flash windows
                    SwingUtilities.invokeAndWait({
                        flashWindows.each { window -> 
                            window.setAlwaysOnTop(true)
                            window.setVisible(true)
                            window.toFront()
                        }
                    } as Runnable)
                    
                    // Create a timer to keep windows visible
                    javax.swing.Timer keepAliveTimer = new javax.swing.Timer(200, { e ->
                        SwingUtilities.invokeLater({
                            flashWindows.each { window ->
                                if (window.isDisplayable() && !window.isVisible()) {
                                    window.setVisible(true)
                                    window.toFront()
                                }
                            }
                        } as Runnable)
                    })
                    keepAliveTimer.setRepeats(true)
                    activeTimers.add(keepAliveTimer)
                    keepAliveTimer.start()
                    
                    // Create color animation timer
                    javax.swing.Timer colorTimer = new javax.swing.Timer(500, { e ->
                        SwingUtilities.invokeLater({
                            flashWindows.each { window ->
                                if (window.isDisplayable()) {
                                    Color currentColor = window.getContentPane().getBackground()
                                    if (currentColor == Color.RED) {
                                        window.getContentPane().setBackground(Color.ORANGE)
                                    } else {
                                        window.getContentPane().setBackground(Color.RED)
                                    }
                                    window.repaint()
                                }
                            }
                        } as Runnable)
                    })
                    colorTimer.setRepeats(true)
                    activeTimers.add(colorTimer)
                    colorTimer.start()
                    
                    // Wait exactly for the configured duration
                    Thread.sleep(flashDurationMs)
                    
                    // Stop timers
                    keepAliveTimer.stop()
                    colorTimer.stop()
                    activeTimers.remove(keepAliveTimer)
                    activeTimers.remove(colorTimer)
                    
                    // Dispose all windows after exact duration
                    SwingUtilities.invokeAndWait({
                        flashWindows.each { window -> window.dispose() }
                    } as Runnable)
                    activeFlashWindows.removeAll(flashWindows)
                    
                    long endTimeMs = System.currentTimeMillis()
                    println "Flash completed after ${(endTimeMs - startTimeMs)/1000.0} seconds"
                    
                } catch (Exception e) {
                    System.err.println("Error during screen flash: ${e.message}")
                }
            })
            
            flashThread.setDaemon(true)
            flashThread.start()
            
        } catch (Exception e) {
            System.err.println("Error initializing screen flash: ${e.message}")
        }
    }
    
    @Override
    void flashMultiple(List<CalendarEvent> events) {
        if (events == null || events.isEmpty()) return;
        try {
            GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            List<JFrame> flashWindows = [];
            for (GraphicsDevice screen : screens) {
                Rectangle bounds = screen.getDefaultConfiguration().getBounds();
                JFrame frame = new JFrame(screen.getDefaultConfiguration());
                frame.setUndecorated(true);
                frame.setAlwaysOnTop(true);
                frame.add(new MultipleMeetingsPanel(events));
                frame.setBounds(bounds);
                flashWindows.add(frame);
            }
            // Track windows for external cleanup BEFORE starting flash thread,
            // so forceCleanup() can dispose them even during setup
            activeFlashWindows.addAll(flashWindows)
            Thread flashThread = new Thread({
                try {
                    // Record start time for proper duration tracking
                    long startTimeMs = System.currentTimeMillis()
                    println "Cross-platform flash (multiple) starting at: ${startTimeMs}, configured duration: ${flashDurationMs} ms"
                    
                    // Show all flash windows
                    SwingUtilities.invokeAndWait({ 
                        flashWindows.each { window -> 
                            window.setAlwaysOnTop(true)
                            window.setVisible(true)
                            window.toFront()
                        } 
                    } as Runnable);
                    
                    // Create a timer to keep windows visible
                    javax.swing.Timer keepAliveTimer = new javax.swing.Timer(200, { e ->
                        SwingUtilities.invokeLater({
                            flashWindows.each { window ->
                                if (window.isDisplayable() && !window.isVisible()) {
                                    window.setVisible(true)
                                    window.toFront()
                                }
                            }
                        } as Runnable)
                    })
                    keepAliveTimer.setRepeats(true)
                    activeTimers.add(keepAliveTimer)
                    keepAliveTimer.start()
                    
                    // Create color animation timer
                    javax.swing.Timer colorTimer = new javax.swing.Timer(500, { e ->
                        SwingUtilities.invokeLater({
                            flashWindows.each { window ->
                                if (window.isDisplayable()) {
                                    Color currentColor = window.getContentPane().getBackground()
                                    if (currentColor == Color.RED) {
                                        window.getContentPane().setBackground(Color.ORANGE)
                                    } else {
                                        window.getContentPane().setBackground(Color.RED)
                                    }
                                    window.repaint()
                                }
                            }
                        } as Runnable)
                    })
                    colorTimer.setRepeats(true)
                    activeTimers.add(colorTimer)
                    colorTimer.start()
                    
                    // Wait exactly for the configured duration
                    Thread.sleep(flashDurationMs);
                    
                    // Stop timers
                    keepAliveTimer.stop()
                    colorTimer.stop()
                    activeTimers.remove(keepAliveTimer)
                    activeTimers.remove(colorTimer)
                    
                    // Dispose all windows after exact duration
                    SwingUtilities.invokeAndWait({ flashWindows.each { it.dispose() } } as Runnable);
                    activeFlashWindows.removeAll(flashWindows)
                    
                    long endTimeMs = System.currentTimeMillis()
                    println "Flash (multiple) completed after ${(endTimeMs - startTimeMs)/1000.0} seconds"
                    
                } catch (Exception e) {
                    System.err.println("Error during screen flash: ${e.message}");
                }
            });
            flashThread.setDaemon(true);
            flashThread.start();
        } catch (Exception e) {
            System.err.println("Error initializing screen flash: ${e.message}");
        }
    }

    @Override
    void forceCleanup() {
        // Stop all active timers
        for (javax.swing.Timer timer : activeTimers) {
            try { timer.stop() } catch (Exception ignored) {}
        }
        activeTimers.clear()
        
        // Dispose all active flash windows
        SwingUtilities.invokeLater({
            for (JFrame frame : activeFlashWindows) {
                try {
                    if (frame.isDisplayable()) {
                        frame.dispose()
                    }
                } catch (Exception ignored) {}
            }
            activeFlashWindows.clear()
        } as Runnable)
    }

    /**
     * Panel that displays meeting information during the flash
     */
    private static class MeetingInfoPanel extends JPanel {
        private final CalendarEvent event
        
        MeetingInfoPanel(CalendarEvent event) {
            this.event = event
            setBackground(new Color(0, 0, 0, 200))
        }
        
        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g)
            
            java.awt.Graphics2D g2d = (java.awt.Graphics2D) g.create()
            try {
                // Set anti-aliasing for better text rendering
                g2d.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                )
                
                // Calculate center position
                int width = getWidth()
                int height = getHeight()
                
                // Set font and color for title
                g2d.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 48))
                g2d.setColor(java.awt.Color.WHITE)
                
                // Draw title
                String title = "MEETING STARTING SOON"
                java.awt.FontMetrics fmTitle = g2d.getFontMetrics()
                int titleWidth = fmTitle.stringWidth(title)
                int titleHeight = fmTitle.getHeight()
                
                g2d.drawString(title, ((width - titleWidth) / 2) as int, (height / 2 - titleHeight) as int)
                
                // Set font for meeting details
                g2d.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 36))
                
                // Draw meeting subject
                String subject = event.getSubject()
                if (subject.length() > 60) {
                    subject = subject.substring(0, 57) + "..."
                }
                
                java.awt.FontMetrics fmSubject = g2d.getFontMetrics()
                int subjectWidth = fmSubject.stringWidth(subject)
                
                g2d.drawString(subject, ((width - subjectWidth) / 2) as int, (height / 2) as int)
                
                // Draw time info
                String timeInfo = "Starting in " + (event.getMinutesToStart() + 1) + " minute" + 
                    (event.getMinutesToStart() == 1 ? "" : "s")
                
                java.awt.FontMetrics fmTime = g2d.getFontMetrics()
                int timeWidth = fmTime.stringWidth(timeInfo)
                
                g2d.drawString(timeInfo, ((width - timeWidth) / 2) as int, (height / 2 + fmTime.getHeight() * 1.5) as int)
                
                // Show response status if available
                if (event.responseStatus) {
                    g2d.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 30))
                    g2d.setColor(new Color(255, 255, 200))
                    
                    String statusMsg = "Status: " + event.responseStatus
                    java.awt.FontMetrics fmStatus = g2d.getFontMetrics()
                    int statusWidth = fmStatus.stringWidth(statusMsg)
                    
                    g2d.drawString(statusMsg, ((width - statusWidth) / 2) as int, (height / 2 + fmTime.getHeight() * 3) as int)
                }
                
                // If it's an online meeting, show the join info
                if (event.isOnlineMeeting && event.onlineMeetingUrl) {
                    g2d.setFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 24))
                    g2d.setColor(new Color(200, 200, 255))
                    
                    String joinMsg = "Online Meeting Available"
                    java.awt.FontMetrics fmJoin = g2d.getFontMetrics()
                    int joinWidth = fmJoin.stringWidth(joinMsg)
                    
                    double yOffset = event.responseStatus ? 5 : 3
                    g2d.drawString(joinMsg, ((width - joinWidth) / 2) as int, (height / 2 + fmTime.getHeight() * yOffset) as int)
                }
                
            } finally {
                g2d.dispose()
            }
        }
    }
    
    /**
     * Panel that displays multiple meeting information during the flash
     */
    private static class MultipleMeetingsPanel extends JPanel {
        private final List<CalendarEvent> events;
        MultipleMeetingsPanel(List<CalendarEvent> events) {
            this.events = events;
            setBackground(new Color(0, 0, 0, 200));
        }
        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            java.awt.Graphics2D g2d = (java.awt.Graphics2D) g.create();
            try {
                g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth();
                int height = getHeight();
                g2d.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 48));
                g2d.setColor(java.awt.Color.WHITE);
                String title = "MEETINGS STARTING SOON";
                java.awt.FontMetrics fmTitle = g2d.getFontMetrics();
                int y = (int) (height / 4);
                g2d.drawString(title, ((width - fmTitle.stringWidth(title)) / 2).toInteger(), y);
                g2d.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 32));
                y += fmTitle.getHeight() + 20;
                for (CalendarEvent event : events) {
                    String subject = event.getSubject();
                    if (subject.length() > 60) subject = subject.substring(0, 57) + "...";
                    String line = subject + " — starts in " + event.getMinutesToStart() + " min";
                    g2d.drawString(line, ((width - g2d.getFontMetrics().stringWidth(line)) / 2).toInteger(), y);
                    y += g2d.getFontMetrics().getHeight() + 10;
                }
            } finally {
                g2d.dispose();
            }
        }
    }
}