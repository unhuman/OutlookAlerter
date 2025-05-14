package com.unhuman.outlookalerter

import groovy.transform.CompileStatic
import java.awt.Color
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Cross-platform implementation of the ScreenFlasher interface using Java Swing
 */
@CompileStatic
class CrossPlatformScreenFlasher implements ScreenFlasher {
    // Flash settings
    private static final int FLASH_COUNT = 3
    private static final int FLASH_DURATION_MS = 500
    private static final int FLASH_INTERVAL_MS = 200
    
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
            
            // Start flashing in a separate thread
            Thread flashThread = new Thread({
                try {
                    // Flash multiple times
                    for (int i = 0; i < FLASH_COUNT; i++) {
                        // Show flash
                        SwingUtilities.invokeAndWait({
                            flashWindows.each { window -> window.setVisible(true) }
                        } as Runnable)
                        
                        // Wait for duration
                        Thread.sleep(FLASH_DURATION_MS)
                        
                        // Hide flash
                        SwingUtilities.invokeAndWait({
                            flashWindows.each { window -> window.setVisible(false) }
                        } as Runnable)
                        
                        // Wait for interval if not the last flash
                        if (i < FLASH_COUNT - 1) {
                            Thread.sleep(FLASH_INTERVAL_MS)
                        }
                    }
                    
                    // Dispose all windows
                    SwingUtilities.invokeAndWait({
                        flashWindows.each { window -> window.dispose() }
                    } as Runnable)
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
                
                g2d.drawString(title, (width - titleWidth) / 2, height / 2 - titleHeight)
                
                // Set font for meeting details
                g2d.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 36))
                
                // Draw meeting subject
                String subject = event.getSubject()
                if (subject.length() > 60) {
                    subject = subject.substring(0, 57) + "..."
                }
                
                java.awt.FontMetrics fmSubject = g2d.getFontMetrics()
                int subjectWidth = fmSubject.stringWidth(subject)
                
                g2d.drawString(subject, (width - subjectWidth) / 2, height / 2)
                
                // Draw time info
                String timeInfo = "Starting in " + event.getMinutesToStart() + " minute" + 
                    (event.getMinutesToStart() == 1 ? "" : "s")
                
                java.awt.FontMetrics fmTime = g2d.getFontMetrics()
                int timeWidth = fmTime.stringWidth(timeInfo)
                
                g2d.drawString(timeInfo, (width - timeWidth) / 2, height / 2 + fmTime.getHeight() * 1.5)
                
                // Show response status if available
                if (event.responseStatus) {
                    g2d.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 30))
                    g2d.setColor(new Color(255, 255, 200))
                    
                    String statusMsg = "Status: " + event.responseStatus
                    java.awt.FontMetrics fmStatus = g2d.getFontMetrics()
                    int statusWidth = fmStatus.stringWidth(statusMsg)
                    
                    g2d.drawString(statusMsg, (width - statusWidth) / 2, height / 2 + fmTime.getHeight() * 3)
                }
                
                // If it's an online meeting, show the join info
                if (event.isOnlineMeeting && event.onlineMeetingUrl) {
                    g2d.setFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 24))
                    g2d.setColor(new Color(200, 200, 255))
                    
                    String joinMsg = "Online Meeting Available"
                    java.awt.FontMetrics fmJoin = g2d.getFontMetrics()
                    int joinWidth = fmJoin.stringWidth(joinMsg)
                    
                    int yOffset = event.responseStatus ? 5 : 3;
                    g2d.drawString(joinMsg, (width - joinWidth) / 2, height / 2 + fmTime.getHeight() * yOffset)
                }
                
            } finally {
                g2d.dispose()
            }
        }
    }
}