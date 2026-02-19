package com.unhuman.outlookalerter.util;

import com.unhuman.outlookalerter.model.CalendarEvent;
import com.unhuman.outlookalerter.core.ConfigManager;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows-specific implementation of ScreenFlasher
 * Uses Windows-specific APIs when available, falls back to cross-platform approach
 */
public class WindowsScreenFlasher implements ScreenFlasher {
    // Default flash parameters
    private static final int DEFAULT_FLASH_COUNT = 5;
    private static final int DEFAULT_FLASH_INTERVAL_MS = 300;

    // Instance variables for flash settings
    private int flashDurationMs;
    private int flashCount = DEFAULT_FLASH_COUNT;
    private int flashIntervalMs = DEFAULT_FLASH_INTERVAL_MS;

    // Map to store countdown timers for each frame (synchronized for cross-thread access)
    private final Map<JFrame, Timer> countdownTimers = Collections.synchronizedMap(new HashMap<>());

    // Map to store labels for each frame for countdown updates (synchronized for cross-thread access)
    private final Map<JFrame, JLabel> countdownLabels = Collections.synchronizedMap(new HashMap<>());

    // Track active flash windows for forceCleanup
    private final List<JFrame> activeFlashFrames = new CopyOnWriteArrayList<>();

    // Cached tray icon to avoid creating a new one for each notification
    private java.awt.TrayIcon cachedTrayIcon = null;

    /**
     * Constructor
     */
    public WindowsScreenFlasher() {
        // Read flash duration from configuration
        try {
            ConfigManager configManager = ConfigManager.getInstance();
            if (configManager != null) {
                // Convert seconds to milliseconds
                flashDurationMs = configManager.getFlashDurationSeconds() * 1000;
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Windows screen flasher initialized with duration: " +
                        configManager.getFlashDurationSeconds() + " seconds");
            } else {
                // Default value if config manager not available
                flashDurationMs = 5000; // 5 seconds
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Windows screen flasher using default duration: 5 seconds (config not available)");
            }
        } catch (Exception e) {
            // Use default value on error
            flashDurationMs = 5000; // 5 seconds
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error initializing flash duration: " + e.getMessage());
        }
    }

    /**
     * Flashes the screen to alert the user of an upcoming event
     */
    @Override
    public void flash(CalendarEvent event) {
        // Try to use Windows-specific APIs if available using JNA
        boolean usedWindowsApi = tryWindowsSpecificFlash(event);

        if (!usedWindowsApi) {
            // Fall back to cross-platform approach
            flashScreenCrossPlatform(event);
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
            Class<?> userClass = Class.forName("com.sun.jna.platform.win32.User32");

            // INSTANCE is a static field, not a method
            Object user32 = userClass.getField("INSTANCE").get(null);

            // FLASHWINFO is a nested class under WinUser
            Class<?> flashwInfoClass = Class.forName("com.sun.jna.platform.win32.WinUser$FLASHWINFO");

            // Create flash info structure
            Object flashInfo = flashwInfoClass.getDeclaredConstructor().newInstance();

            // Set cbSize using the structure's size() method
            int structSize = ((Number) flashwInfoClass.getMethod("size").invoke(flashInfo)).intValue();
            flashwInfoClass.getField("cbSize").set(flashInfo, structSize);

            // FLASHW_ALL = FLASHW_CAPTION | FLASHW_TRAY = 0x00000003
            flashwInfoClass.getField("dwFlags").set(flashInfo, 0x00000003);
            flashwInfoClass.getField("uCount").set(flashInfo, flashCount);
            flashwInfoClass.getField("dwTimeout").set(flashInfo, flashIntervalMs);

            // Get current foreground window
            Object hwnd = userClass.getMethod("GetForegroundWindow").invoke(user32);
            flashwInfoClass.getField("hwnd").set(flashInfo, hwnd);

            // Flash window
            userClass.getMethod("FlashWindowEx", flashwInfoClass).invoke(user32, flashInfo);

            // Show notification using system tray
            showSystemTrayNotification(event);

            return true;
        } catch (Exception e) {
            LogManager.getInstance().warn(LogCategory.ALERT_PROCESSING, "Windows API not available, falling back to cross-platform approach: " + e.getMessage());
            return false;
        }
    }

    /**
     * Shows a system tray notification with event details.
     * Reuses a single cached TrayIcon to avoid icon churn.
     */
    private void showSystemTrayNotification(CalendarEvent event) {
        try {
            // Use Java's built-in system tray support
            if (java.awt.SystemTray.isSupported()) {
                java.awt.SystemTray tray = java.awt.SystemTray.getSystemTray();

                // Reuse cached tray icon or create one
                if (cachedTrayIcon == null) {
                    // Try to load icon; fall back to a simple generated image
                    java.awt.Image image = null;
                    try {
                        URL iconUrl = getClass().getResource("/calendar_icon.png");
                        if (iconUrl != null) {
                            image = java.awt.Toolkit.getDefaultToolkit().createImage(iconUrl);
                        }
                    } catch (Exception ignored) {
                    }
                    if (image == null) {
                        // Create a simple 16x16 colored icon as fallback
                        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        java.awt.Graphics2D g = bi.createGraphics();
                        g.setColor(Color.RED);
                        g.fillRect(0, 0, 16, 16);
                        g.dispose();
                        image = bi;
                    }

                    cachedTrayIcon = new java.awt.TrayIcon(image, "Outlook Alerter");
                    cachedTrayIcon.setImageAutoSize(true);
                    tray.add(cachedTrayIcon);
                }

                // Show notification using the cached icon
                cachedTrayIcon.displayMessage(
                        "Meeting Reminder",
                        HtmlUtil.escapeHtml(event.getSubject()) + " starts in " + event.getMinutesToStart() + " minute(s)",
                        java.awt.TrayIcon.MessageType.WARNING
                );
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error showing system tray notification: " + e.getMessage());
        }
    }

    /**
     * Cross-platform screen flashing using Java Swing
     */
    private void flashScreenCrossPlatform(CalendarEvent event) {
        // Get all screens
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] screens = ge.getScreenDevices();

        // Create a flash window for each screen
        for (GraphicsDevice screen : screens) {
            createFlashWindowForScreen(screen, event);
        }
    }

    /**
     * Creates a flashing window for a specific screen
     */
    private void createFlashWindowForScreen(GraphicsDevice screen, CalendarEvent event) {
        try {
            JFrame frame = new JFrame("Meeting Alert", screen.getDefaultConfiguration());
            frame.setUndecorated(true);
            frame.setAlwaysOnTop(true);
            frame.setType(JFrame.Type.POPUP);
            double opacity = getAlertOpacity();
            try {
                frame.setOpacity((float) opacity);
            } catch (Throwable t) {
                LogManager.getInstance().warn(LogCategory.ALERT_PROCESSING, "Warning: Could not set frame opacity: " + t.getMessage());
            }
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
            String textColorHex = String.format("#%02x%02x%02x", textColor.getRed(), textColor.getGreen(), textColor.getBlue());
            JLabel label;
            try {
                label = new JLabel("<html><center>" +
                        "<h1 style='color: " + textColorHex + "; font-size: 48px'>⚠️ MEETING ALERT ⚠️</h1>" +
                        "<h2 style='color: " + textColorHex + "; font-size: 36px'>" + HtmlUtil.escapeHtml(event.getSubject()) + "</h2>" +
                        "<p style='color: " + textColorHex + "; font-size: 24px'>Starting in " + (event.getMinutesToStart() + 1) + " minute(s)</p>" +
                        "<p></p><p id='countdownText' style='color: " + textColorHex + "; font-size: 18px'>This alert will close in " +
                        (flashDurationMs / 1000) + " seconds</p>" +
                        "</center></html>", SwingConstants.CENTER);
                label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));
                label.setForeground(textColor);
                label.setBackground(alertColor);
                label.setOpaque(true);
                frame.add(label, gbc);

                // Store the label in the map for access by timer
                countdownLabels.put(frame, label);
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error creating JLabel for flash overlay: " + e.getMessage(), e);
            }
            frame.setBounds(screen.getDefaultConfiguration().getBounds());
            activeFlashFrames.add(frame);
            startFlashSequence(frame);
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error creating flash window: " + e.getMessage(), e);
        }
    }

    /**
     * Starts the flash sequence animation
     */
    private void startFlashSequence(JFrame frame) {
        // Record the start time of the flash
        long startTimeMs = System.currentTimeMillis();
        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Windows Flash starting at: " + startTimeMs + " ms, configured duration: " + flashDurationMs + " ms");

        // Get the countdown label from the map
        JLabel countdownLabel = countdownLabels.get(frame);
        if (countdownLabel != null) {
            // Start the countdown timer
            startCountdownTimer(frame, countdownLabel, startTimeMs);
        }

        // CRITICAL: Ensure the window is visible and on top initially
        frame.setVisible(true);
        frame.setAlwaysOnTop(true);
        frame.toFront();
        frame.requestFocus();
        frame.setExtendedState(JFrame.NORMAL);

        // Try to use Windows API for window visibility if available
        try {
            Class<?> user32Class = Class.forName("com.sun.jna.platform.win32.User32");
            Object user32 = user32Class.getField("INSTANCE").get(null);
            Object hwnd = user32Class.getMethod("GetForegroundWindow").invoke(user32);

            // Set window to topmost
            Class<?> hwndClass = Class.forName("com.sun.jna.platform.win32.WinDef$HWND");
            user32Class.getMethod("SetWindowPos",
                    hwndClass,
                    hwndClass,
                    int.class, int.class, int.class, int.class, int.class)
                    .invoke(user32, hwnd, null, 0, 0, 0, 0,
                            0x0001 | 0x0002 | 0x0040); // HWND_TOPMOST, SWP_NOMOVE, SWP_NOSIZE

            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Set window to topmost using Windows API");
        } catch (Exception e) {
            LogManager.getInstance().warn(LogCategory.ALERT_PROCESSING, "Could not use Windows API for window visibility: " + e.getMessage());
        }

        // Create a Swing Timer to keep the window visible and animate
        // instead of a tight invokeAndWait loop that monopolizes the EDT
        final long endTime = startTimeMs + flashDurationMs;
        final int[] colorToggleCount = {0};
        final boolean[] isColorToggle = {false};

        Timer elevationTimer = new Timer(100, (ActionEvent timerEvt) -> {
            try {
                if (System.currentTimeMillis() >= endTime || !frame.isDisplayable()) {
                    // Duration complete — stop timer and dispose
                    ((Timer) timerEvt.getSource()).stop();
                    Timer cdTimer = countdownTimers.get(frame);
                    if (cdTimer != null) {
                        cdTimer.stop();
                        countdownTimers.remove(frame);
                    }
                    if (frame.isDisplayable()) {
                        frame.dispose();
                    }
                    long actualDuration = System.currentTimeMillis() - startTimeMs;
                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Flash duration completed after " + actualDuration + "ms");
                    return;
                }

                // Ensure window stays visible
                if (!frame.isVisible()) {
                    frame.setVisible(true);
                }
                frame.setAlwaysOnTop(true);
                frame.toFront();

                // Color toggle animation every ~500ms (every 5th tick)
                if (colorToggleCount[0]++ % 5 == 0) {
                    isColorToggle[0] = !isColorToggle[0];
                    Color color = isColorToggle[0] ? Color.RED : Color.ORANGE;
                    frame.getContentPane().setBackground(color);
                    frame.repaint();
                }

                // Periodically try Windows API to force foreground
                if (colorToggleCount[0] % 20 == 0) {
                    try {
                        Class<?> user32Class = Class.forName("com.sun.jna.platform.win32.User32");
                        Object user32 = user32Class.getField("INSTANCE").get(null);
                        Object hwnd = user32Class.getMethod("GetForegroundWindow").invoke(user32);
                        Class<?> hwndClass = Class.forName("com.sun.jna.platform.win32.WinDef$HWND");
                        user32Class.getMethod("SetForegroundWindow", hwndClass)
                                .invoke(user32, hwnd);
                    } catch (Exception ignored) {
                        // Ignore errors here
                    }
                }
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error in flash control cycle: " + e.getMessage());
            }
        });
        elevationTimer.setInitialDelay(100);
        elevationTimer.start();

        // Add safety timer as backup
        Timer safetyTimer = new Timer(flashDurationMs + 5000, (ActionEvent safetyEvent) -> {
            SwingUtilities.invokeLater(() -> {
                try {
                    // Stop and remove the countdown timer
                    Timer timer = countdownTimers.get(frame);
                    if (timer != null) {
                        timer.stop();
                        countdownTimers.remove(frame);
                    }

                    if (frame.isDisplayable()) {
                        frame.dispose();
                        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Safety timer triggered cleanup after " + (System.currentTimeMillis() - startTimeMs) / 1000.0 + " seconds");
                    }
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error in safety timer: " + e.getMessage());
                }
                ((Timer) safetyEvent.getSource()).stop();
            });
        });
        safetyTimer.setRepeats(false);
        safetyTimer.start();

        // The countdown timer is already started through the countdownLabel retrieved earlier
    }

    /**
     * Starts a countdown timer that updates the label with remaining seconds
     * @param frame The JFrame containing the label
     * @param label The JLabel to update with countdown
     * @param startTimeMs The time when the flash started
     */
    private void startCountdownTimer(JFrame frame, JLabel label, long startTimeMs) {
        // Calculate initial seconds remaining
        int totalSeconds = (int) (flashDurationMs / 1000);
        final int[] secRemaining = {totalSeconds};

        // Create a timer that fires every second
        Timer timer = new Timer(1000, (ActionEvent actionEvent) -> {
            // Calculate remaining time
            long elapsedMs = System.currentTimeMillis() - startTimeMs;
            secRemaining[0] = totalSeconds - (int) (elapsedMs / 1000);

            if (secRemaining[0] <= 0) {
                // Stop the timer when countdown reaches zero
                Timer t = (Timer) actionEvent.getSource();
                t.stop();

                // Remove from the map
                countdownTimers.remove(frame);
                return;
            }

            // Update the label HTML
            final int currentSeconds = secRemaining[0];
            SwingUtilities.invokeLater(() -> {
                try {
                    if (frame.isDisplayable() && label != null) {
                        // Use regular expression to replace the countdown text
                        String text = label.getText();
                        // Check if it's the multiple events version or single event version
                        if (text.contains("This alert will close in")) {
                            String newText = text.replaceFirst(
                                    "This alert will close in \\d+ seconds",
                                    "This alert will close in " + currentSeconds + " seconds"
                            );
                            label.setText(newText);
                        } else if (text.contains("<p id='countdownText'")) {
                            // Extract the existing style color using Pattern/Matcher
                            Pattern stylePattern = Pattern.compile("(style='color: [^;]+;)");
                            Matcher styleMatcher = stylePattern.matcher(text);
                            String styleMatch = styleMatcher.find() ? styleMatcher.group(1) : "style='color: #ffffff;";
                            String newText = text.replaceFirst(
                                    "<p id='countdownText' style='color: [^>]+>This alert will close in \\d+ seconds</p>",
                                    "<p id='countdownText' " + styleMatch + " font-size: 18px'>This alert will close in " +
                                            currentSeconds + " seconds</p>"
                            );
                            label.setText(newText);
                        }
                    }
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error updating countdown: " + e.getMessage());
                }
            });
        });

        timer.setRepeats(true);
        timer.start();

        // Store the timer in the map
        countdownTimers.put(frame, timer);

        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Started countdown timer for flash window");
    }

    /**
     * Flashes the screen to alert the user of multiple events in the same time window
     * @param events List of calendar events starting soon
     */
    @Override
    public void flashMultiple(List<CalendarEvent> events) {
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
        try {
            JFrame frame = new JFrame("Meeting Alert", screen.getDefaultConfiguration());
            frame.setUndecorated(true);
            frame.setAlwaysOnTop(true);
            frame.setType(JFrame.Type.POPUP);
            double opacity = getAlertOpacity();
            try {
                frame.setOpacity((float) opacity);
            } catch (Throwable t) {
                LogManager.getInstance().warn(LogCategory.ALERT_PROCESSING, "Warning: Could not set frame opacity: " + t.getMessage());
            }
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
            String textColorHex = String.format("#%02x%02x%02x", textColor.getRed(), textColor.getGreen(), textColor.getBlue());
            JLabel label;
            try {
                StringBuilder html = new StringBuilder("<html><center><h1 style='color: " + textColorHex + "; font-size: 48px'>⚠️ MEETING ALERT ⚠️</h1>");
                for (CalendarEvent event : events) {
                    html.append("<h2 style='color: ").append(textColorHex).append("; font-size: 36px'>").append(HtmlUtil.escapeHtml(event.getSubject())).append("</h2>");
                    html.append("<p style='color: ").append(textColorHex).append("; font-size: 24px'>Starting in ").append(event.getMinutesToStart() + 1).append(" minute(s)</p>");
                }
                // Add countdown text for multiple events
                html.append("<p></p><p id='countdownText' style='color: ").append(textColorHex)
                        .append("; font-size: 18px'>This alert will close in ")
                        .append(flashDurationMs / 1000).append(" seconds</p>");
                html.append("</center></html>");
                label = new JLabel(html.toString(), SwingConstants.CENTER);
                label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
                label.setForeground(textColor);
                label.setBackground(alertColor);
                label.setOpaque(true);
                frame.add(label, gbc);

                // Store the label in the map for access by timer
                countdownLabels.put(frame, label);
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error creating JLabel for flash overlay (multiple): " + e.getMessage(), e);
            }
            frame.setBounds(screen.getDefaultConfiguration().getBounds());
            activeFlashFrames.add(frame);
            startFlashSequence(frame);
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error creating flash window (multiple): " + e.getMessage(), e);
        }
    }

    private Color getAlertTextColorWithOpacity() {
        try {
            ConfigManager configManager = ConfigManager.getInstance();
            String colorHex = (configManager != null && configManager.getFlashTextColor() != null)
                    ? configManager.getFlashTextColor() : "#ffffff";
            double opacity = (configManager != null) ? configManager.getFlashOpacity() : 1.0d;
            Color base = Color.decode(colorHex);
            int alpha = (int) Math.round(opacity * 255);
            return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
        } catch (Exception e) {
            return new Color(255, 255, 255, (int) Math.round(getAlertOpacity() * 255));
        }
    }

    private double getAlertOpacity() {
        try {
            ConfigManager configManager = ConfigManager.getInstance();
            return (configManager != null) ? configManager.getFlashOpacity() : 1.0d;
        } catch (Exception e) {
            return 1.0d;
        }
    }

    private Color getAlertColor() {
        try {
            ConfigManager configManager = ConfigManager.getInstance();
            String colorHex = (configManager != null && configManager.getFlashColor() != null)
                    ? configManager.getFlashColor() : "#800000";
            double opacity = (configManager != null) ? configManager.getFlashOpacity() : 1.0d;
            Color base = Color.decode(colorHex);
            int alpha = (int) Math.round(opacity * 255);
            return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
        } catch (Exception e) {
            return new Color(128, 0, 0, (int) Math.round(getAlertOpacity() * 255));
        }
    }

    @Override
    public void forceCleanup() {
        // Stop all countdown timers
        synchronized (countdownTimers) {
            for (Timer timer : countdownTimers.values()) {
                try {
                    timer.stop();
                } catch (Exception ignored) {
                }
            }
            countdownTimers.clear();
        }
        countdownLabels.clear();

        // Dispose all active flash windows
        SwingUtilities.invokeLater(() -> {
            for (JFrame frame : activeFlashFrames) {
                try {
                    if (frame.isDisplayable()) {
                        frame.dispose();
                    }
                } catch (Exception ignored) {
                }
            }
            activeFlashFrames.clear();
        });
    }
}
