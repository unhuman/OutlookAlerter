package com.unhuman.outlookalerter.util;

import com.unhuman.outlookalerter.model.CalendarEvent;
import com.unhuman.outlookalerter.core.ConfigManager;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Cross-platform implementation of the ScreenFlasher interface using Java Swing
 */
public class CrossPlatformScreenFlasher implements ScreenFlasher {
    // Configurable flash duration, will be set from ConfigManager
    private int flashDurationMs = 5000; // Default 5 seconds

    // Track active flash windows for external cleanup
    private final List<JFrame> activeFlashWindows = new CopyOnWriteArrayList<>();
    private final List<javax.swing.Timer> activeTimers = new CopyOnWriteArrayList<>();

    /**
     * Constructor that initializes the flash duration from ConfigManager
     */
    public CrossPlatformScreenFlasher() {
        try {
            // Try to get ConfigManager instance
            ConfigManager configManager = ConfigManager.getInstance();
            if (configManager != null) {
                // Convert seconds to milliseconds
                flashDurationMs = configManager.getFlashDurationSeconds() * 1000;
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Screen flasher initialized with duration: " +
                        configManager.getFlashDurationSeconds() + " seconds");
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error initializing flash duration from config: " + e.getMessage());
            // Use default duration if there's an error
        }
    }

    @Override
    public void flash(CalendarEvent event) {
        try {
            // Get all screens
            GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();

            // Create flash windows for each screen
            List<JFrame> flashWindows = new ArrayList<>();

            for (GraphicsDevice screen : screens) {
                Rectangle bounds = screen.getDefaultConfiguration().getBounds();

                JFrame frame = new JFrame(screen.getDefaultConfiguration());
                frame.setUndecorated(true);
                frame.setAlwaysOnTop(true);

                // Create a panel with meeting info
                MeetingInfoPanel panel = new MeetingInfoPanel(event);
                frame.add(panel);

                // Set size and location to cover the entire screen
                frame.setBounds(bounds);

                flashWindows.add(frame);
            }

            // Track windows for external cleanup BEFORE showing them,
            // so forceCleanup() can dispose them even during setup
            activeFlashWindows.addAll(flashWindows);

            // Start flashing in a separate thread
            Thread flashThread = new Thread(() -> {
                try {
                    // Record start time for proper duration tracking
                    long startTimeMs = System.currentTimeMillis();
                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Cross-platform flash starting at: " + startTimeMs + ", configured duration: " + flashDurationMs + " ms");

                    // Show flash windows
                    SwingUtilities.invokeAndWait(() -> {
                        for (JFrame window : flashWindows) {
                            window.setAlwaysOnTop(true);
                            window.setVisible(true);
                            window.toFront();
                        }
                    });

                    // Create a timer to keep windows visible
                    javax.swing.Timer keepAliveTimer = new javax.swing.Timer(200, e -> {
                        SwingUtilities.invokeLater(() -> {
                            for (JFrame window : flashWindows) {
                                if (window.isDisplayable() && !window.isVisible()) {
                                    window.setVisible(true);
                                    window.toFront();
                                }
                            }
                        });
                    });
                    keepAliveTimer.setRepeats(true);
                    activeTimers.add(keepAliveTimer);
                    keepAliveTimer.start();

                    // Create color animation timer
                    javax.swing.Timer colorTimer = new javax.swing.Timer(500, e -> {
                        SwingUtilities.invokeLater(() -> {
                            for (JFrame window : flashWindows) {
                                if (window.isDisplayable()) {
                                    Color currentColor = window.getContentPane().getBackground();
                                    if (currentColor.equals(Color.RED)) {
                                        window.getContentPane().setBackground(Color.ORANGE);
                                    } else {
                                        window.getContentPane().setBackground(Color.RED);
                                    }
                                    window.repaint();
                                }
                            }
                        });
                    });
                    colorTimer.setRepeats(true);
                    activeTimers.add(colorTimer);
                    colorTimer.start();

                    // Wait exactly for the configured duration
                    Thread.sleep(flashDurationMs);

                    // Stop timers
                    keepAliveTimer.stop();
                    colorTimer.stop();
                    activeTimers.remove(keepAliveTimer);
                    activeTimers.remove(colorTimer);

                    // Dispose all windows after exact duration
                    SwingUtilities.invokeAndWait(() -> {
                        for (JFrame window : flashWindows) {
                            window.dispose();
                        }
                    });
                    activeFlashWindows.removeAll(flashWindows);

                    long endTimeMs = System.currentTimeMillis();
                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Flash completed after " + (endTimeMs - startTimeMs) / 1000.0 + " seconds");

                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error during screen flash: " + e.getMessage());
                }
            });

            flashThread.setDaemon(true);
            flashThread.start();

        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error initializing screen flash: " + e.getMessage());
        }
    }

    @Override
    public void flashMultiple(List<CalendarEvent> events) {
        if (events == null || events.isEmpty()) return;
        try {
            GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            List<JFrame> flashWindows = new ArrayList<>();
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
            activeFlashWindows.addAll(flashWindows);
            Thread flashThread = new Thread(() -> {
                try {
                    // Record start time for proper duration tracking
                    long startTimeMs = System.currentTimeMillis();
                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Cross-platform flash (multiple) starting at: " + startTimeMs + ", configured duration: " + flashDurationMs + " ms");

                    // Show all flash windows
                    SwingUtilities.invokeAndWait(() -> {
                        for (JFrame window : flashWindows) {
                            window.setAlwaysOnTop(true);
                            window.setVisible(true);
                            window.toFront();
                        }
                    });

                    // Create a timer to keep windows visible
                    javax.swing.Timer keepAliveTimer = new javax.swing.Timer(200, e -> {
                        SwingUtilities.invokeLater(() -> {
                            for (JFrame window : flashWindows) {
                                if (window.isDisplayable() && !window.isVisible()) {
                                    window.setVisible(true);
                                    window.toFront();
                                }
                            }
                        });
                    });
                    keepAliveTimer.setRepeats(true);
                    activeTimers.add(keepAliveTimer);
                    keepAliveTimer.start();

                    // Create color animation timer
                    javax.swing.Timer colorTimer = new javax.swing.Timer(500, e -> {
                        SwingUtilities.invokeLater(() -> {
                            for (JFrame window : flashWindows) {
                                if (window.isDisplayable()) {
                                    Color currentColor = window.getContentPane().getBackground();
                                    if (currentColor.equals(Color.RED)) {
                                        window.getContentPane().setBackground(Color.ORANGE);
                                    } else {
                                        window.getContentPane().setBackground(Color.RED);
                                    }
                                    window.repaint();
                                }
                            }
                        });
                    });
                    colorTimer.setRepeats(true);
                    activeTimers.add(colorTimer);
                    colorTimer.start();

                    // Wait exactly for the configured duration
                    Thread.sleep(flashDurationMs);

                    // Stop timers
                    keepAliveTimer.stop();
                    colorTimer.stop();
                    activeTimers.remove(keepAliveTimer);
                    activeTimers.remove(colorTimer);

                    // Dispose all windows after exact duration
                    SwingUtilities.invokeAndWait(() -> {
                        for (JFrame window : flashWindows) {
                            window.dispose();
                        }
                    });
                    activeFlashWindows.removeAll(flashWindows);

                    long endTimeMs = System.currentTimeMillis();
                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Flash (multiple) completed after " + (endTimeMs - startTimeMs) / 1000.0 + " seconds");

                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error during screen flash: " + e.getMessage());
                }
            });
            flashThread.setDaemon(true);
            flashThread.start();
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error initializing screen flash: " + e.getMessage());
        }
    }

    @Override
    public void forceCleanup() {
        // Stop all active timers
        for (javax.swing.Timer timer : activeTimers) {
            try {
                timer.stop();
            } catch (Exception ignored) {
            }
        }
        activeTimers.clear();

        // Dispose all active flash windows
        SwingUtilities.invokeLater(() -> {
            for (JFrame frame : activeFlashWindows) {
                try {
                    if (frame.isDisplayable()) {
                        frame.dispose();
                    }
                } catch (Exception ignored) {
                }
            }
            activeFlashWindows.clear();
        });
    }

    /**
     * Panel that displays meeting information during the flash
     */
    private static class MeetingInfoPanel extends JPanel {
        private final CalendarEvent event;

        MeetingInfoPanel(CalendarEvent event) {
            this.event = event;
            setBackground(new Color(0, 0, 0, 200));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Graphics2D g2d = (Graphics2D) g.create();
            try {
                // Set anti-aliasing for better text rendering
                g2d.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                );

                // Calculate center position
                int width = getWidth();
                int height = getHeight();

                // Set font and color for title
                g2d.setFont(new Font("Arial", Font.BOLD, 48));
                g2d.setColor(Color.WHITE);

                // Draw title
                String title = "MEETING STARTING SOON";
                FontMetrics fmTitle = g2d.getFontMetrics();
                int titleWidth = fmTitle.stringWidth(title);
                int titleHeight = fmTitle.getHeight();

                g2d.drawString(title, (int) ((width - titleWidth) / 2), (int) (height / 2 - titleHeight));

                // Set font for meeting details
                g2d.setFont(new Font("Arial", Font.BOLD, 36));

                // Draw meeting subject
                String subject = event.getSubject();
                if (subject.length() > 60) {
                    subject = subject.substring(0, 57) + "...";
                }

                FontMetrics fmSubject = g2d.getFontMetrics();
                int subjectWidth = fmSubject.stringWidth(subject);

                g2d.drawString(subject, (int) ((width - subjectWidth) / 2), (int) (height / 2));

                // Draw time info
                String timeInfo = "Starting in " + (event.getMinutesToStart() + 1) + " minute" +
                        (event.getMinutesToStart() == 1 ? "" : "s");

                FontMetrics fmTime = g2d.getFontMetrics();
                int timeWidth = fmTime.stringWidth(timeInfo);

                g2d.drawString(timeInfo, (int) ((width - timeWidth) / 2), (int) (height / 2 + fmTime.getHeight() * 1.5));

                // Show response status if available
                if (event.getResponseStatus() != null && !event.getResponseStatus().isEmpty()) {
                    g2d.setFont(new Font("Arial", Font.BOLD, 30));
                    g2d.setColor(new Color(255, 255, 200));

                    String statusMsg = "Status: " + event.getResponseStatus();
                    FontMetrics fmStatus = g2d.getFontMetrics();
                    int statusWidth = fmStatus.stringWidth(statusMsg);

                    g2d.drawString(statusMsg, (int) ((width - statusWidth) / 2), (int) (height / 2 + fmTime.getHeight() * 3));
                }

                // If it's an online meeting, show the join info
                if (event.getIsOnlineMeeting() && event.getOnlineMeetingUrl() != null) {
                    g2d.setFont(new Font("Arial", Font.PLAIN, 24));
                    g2d.setColor(new Color(200, 200, 255));

                    String joinMsg = "Online Meeting Available";
                    FontMetrics fmJoin = g2d.getFontMetrics();
                    int joinWidth = fmJoin.stringWidth(joinMsg);

                    double yOffset = (event.getResponseStatus() != null && !event.getResponseStatus().isEmpty()) ? 5 : 3;
                    g2d.drawString(joinMsg, (int) ((width - joinWidth) / 2), (int) (height / 2 + fmTime.getHeight() * yOffset));
                }

            } finally {
                g2d.dispose();
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
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g.create();
            try {
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth();
                int height = getHeight();
                g2d.setFont(new Font("Arial", Font.BOLD, 48));
                g2d.setColor(Color.WHITE);
                String title = "MEETINGS STARTING SOON";
                FontMetrics fmTitle = g2d.getFontMetrics();
                int y = (int) (height / 4);
                g2d.drawString(title, (int) ((width - fmTitle.stringWidth(title)) / 2), y);
                g2d.setFont(new Font("Arial", Font.BOLD, 32));
                y += fmTitle.getHeight() + 20;
                for (CalendarEvent event : events) {
                    String subject = event.getSubject();
                    if (subject.length() > 60) subject = subject.substring(0, 57) + "...";
                    String line = subject + " — starts in " + event.getMinutesToStart() + " min";
                    g2d.drawString(line, (int) ((width - g2d.getFontMetrics().stringWidth(line)) / 2), y);
                    y += g2d.getFontMetrics().getHeight() + 10;
                }
            } finally {
                g2d.dispose();
            }
        }
    }
}
