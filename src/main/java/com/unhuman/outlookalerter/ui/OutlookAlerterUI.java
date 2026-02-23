package com.unhuman.outlookalerter.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import com.unhuman.outlookalerter.core.ConfigManager;
import com.unhuman.outlookalerter.core.OutlookClient;
import com.unhuman.outlookalerter.core.OutlookClient.AuthenticationCancelledException;
import com.unhuman.outlookalerter.model.CalendarEvent;
import com.unhuman.outlookalerter.util.ScreenFlasher;
import com.unhuman.outlookalerter.util.ScreenFlasherFactory;
import com.unhuman.outlookalerter.util.MacScreenFlasher;
import com.unhuman.outlookalerter.util.MacSleepWakeMonitor;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.io.OutputStream;
import java.io.PrintStream;
import com.unhuman.outlookalerter.util.LogManager;
import com.unhuman.outlookalerter.util.LogCategory;

/**
 * Main application window for Outlook Alerter
 * Provides a user interface for monitoring upcoming meetings
 */
public class OutlookAlerterUI extends JFrame {
    // Initialize logging interceptor
    static {
        // First store the original streams in LogManager
        final PrintStream originalOut = System.out;
        final PrintStream originalErr = System.err;
        LogManager.setOriginalOutStream(originalOut);
        LogManager.setOriginalErrStream(originalErr);

        // Replace System.out with our intercepting PrintStream
        // Uses ByteArrayOutputStream to correctly handle multi-byte UTF-8 characters
        System.setOut(new PrintStream(new OutputStream() {
            private final java.io.ByteArrayOutputStream byteBuffer = new java.io.ByteArrayOutputStream();

            @Override
            public synchronized void write(int b) {
                originalOut.write(b);
                byteBuffer.write(b);
                if (b == 10) {  // newline
                    flushBuffer();
                }
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) {
                originalOut.write(b, off, len);
                byteBuffer.write(b, off, len);
                // Process any complete lines in the buffer
                for (int i = off; i < off + len; i++) {
                    if (b[i] == (byte) 10) {  // newline
                        flushBuffer();
                        break;  // flushBuffer handles all complete lines
                    }
                }
            }

            private void flushBuffer() {
                String text = byteBuffer.toString(java.nio.charset.StandardCharsets.UTF_8);
                byteBuffer.reset();
                // Process each complete line
                int start = 0;
                int idx;
                while ((idx = text.indexOf('\n', start)) >= 0) {
                    String line = text.substring(start, idx).trim();
                    if (!line.isEmpty()) {
                        LogManager.getInstance().info(line);
                    }
                    start = idx + 1;
                }
                // Put back any trailing incomplete line
                if (start < text.length()) {
                    byte[] remainder = text.substring(start).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    byteBuffer.write(remainder, 0, remainder.length);
                }
            }
        }));

        // Replace System.err with our intercepting PrintStream
        System.setErr(new PrintStream(new OutputStream() {
            private final java.io.ByteArrayOutputStream byteBuffer = new java.io.ByteArrayOutputStream();

            @Override
            public synchronized void write(int b) {
                originalErr.write(b);
                byteBuffer.write(b);
                if (b == 10) {  // newline
                    flushBuffer();
                }
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) {
                originalErr.write(b, off, len);
                byteBuffer.write(b, off, len);
                for (int i = off; i < off + len; i++) {
                    if (b[i] == (byte) 10) {  // newline
                        flushBuffer();
                        break;
                    }
                }
            }

            private void flushBuffer() {
                String text = byteBuffer.toString(java.nio.charset.StandardCharsets.UTF_8);
                byteBuffer.reset();
                int start = 0;
                int idx;
                while ((idx = text.indexOf('\n', start)) >= 0) {
                    String line = text.substring(start, idx).trim();
                    if (!line.isEmpty()) {
                        LogManager.getInstance().error(line);
                    }
                    start = idx + 1;
                }
                if (start < text.length()) {
                    byte[] remainder = text.substring(start).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    byteBuffer.write(remainder, 0, remainder.length);
                }
            }
        }));
    }

    // Time constants
    private static final int POLLING_INTERVAL_SECONDS = 60;

    // Components
    private final ConfigManager configManager;
    private final OutlookClient outlookClient;
    private ScreenFlasher screenFlasher;

    // UI Components
    private JTextArea eventsTextArea;
    private JButton refreshButton;
    private JLabel statusLabel;
    private JLabel lastUpdateLabel;
    private TrayIcon trayIcon;
    private SystemTray systemTray;

    // Schedulers for periodic tasks
    private ScheduledExecutorService alertScheduler;
    private ScheduledExecutorService calendarScheduler;
    private volatile boolean schedulersRunning = false;

    // Track which events we've already alerted for
    private final Set<String> alertedEventIds = new java.util.concurrent.ConcurrentHashMap<String, Boolean>().keySet(true);

    // Store last fetched events to avoid frequent API calls (volatile for cross-thread visibility)
    private volatile List<CalendarEvent> lastFetchedEvents = new ArrayList<>();

    // Throttle token validation checks (every 5 minutes instead of every minute)
    private volatile long lastTokenValidationTime = 0L;
    private static final long TOKEN_VALIDATION_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes

    // Track last calendar refresh time
    private volatile ZonedDateTime lastCalendarRefresh = null;

    // Guard against overlapping calendar fetch threads
    private final java.util.concurrent.atomic.AtomicBoolean fetchInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Track when the current fetch started (for stale-fetch watchdog)
    private volatile long fetchStartTimeMs = 0L;
    private static final long FETCH_TIMEOUT_MS = 5 * 60 * 1000L; // 5 minutes max for a fetch

    // Track the current icon state
    private Boolean currentIconInvalidState = null;

    // Track settings dialog instance
    private SettingsDialog settingsDialog = null;

    // Track logs dialog instance
    private LogViewer logViewer = null;

    // Added a flag to track if the token dialog is active (volatile for cross-thread visibility)
    private volatile boolean isTokenDialogActive = false;

    // Track last system wake time for safe UI display (volatile for cross-thread visibility)
    // Initialized to 0 so that isSafeToShowUI() returns true at app startup.
    // Only actual system wake events (from MacSleepWakeMonitor) update this value.
    private volatile long lastSystemWakeTime = 0L;

    // Banner components (CopyOnWriteArrayList for thread-safe access from EDT and timer callbacks)
    private final List<JFrame> alertBannerWindows = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Create a new OutlookAlerterUI
     */
    public OutlookAlerterUI(String configPath) {
        super("Outlook Alerter - Meeting Alerts");

        // Initialize components first so we can check token status
        this.configManager = new ConfigManager(configPath);
        this.configManager.loadConfiguration();
        this.outlookClient = new OutlookClient(configManager, this);

        // Set window icon based on initial token validity
        boolean tokenInvalid = !outlookClient.isTokenAlreadyValid(); // Use isTokenAlreadyValid for initial check
        updateIcons(tokenInvalid);
        this.screenFlasher = ScreenFlasherFactory.createScreenFlasher();

        // Initialize schedulers with daemon threads to prevent blocking JVM shutdown
        this.alertScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "AlertScheduler");
            t.setDaemon(true);
            return t;
        });
        this.calendarScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "CalendarScheduler");
            t.setDaemon(true);
            return t;
        });

        // Set up the UI
        initializeUI();

        // Configure window behavior for system tray support
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);  // We'll handle window closing ourselves
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // If system tray is supported, just minimize to tray
                if (systemTray != null && trayIcon != null) {
                    setVisible(false);
                    trayIcon.displayMessage(
                        "Outlook Alerter - Meeting Alerts",
                        "Outlook Alerter is still running in the background. Right-click the tray icon to exit.",
                        TrayIcon.MessageType.INFO
                    );
                } else {
                    // If no system tray, prompt to exit
                    int option = JOptionPane.showConfirmDialog(
                        OutlookAlerterUI.this,
                        "Do you want to exit Outlook Alerter?\nYou will no longer receive meeting alerts.",
                        "Confirm Exit",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                    );

                    if (option == JOptionPane.YES_OPTION) {
                        shutdown();
                        System.exit(0);
                    }
                }
            }
        });

        // Set size and position
        setSize(800, 600);
        setLocationRelativeTo(null); // Center on screen

        // Set up system tray if supported
        setupSystemTray();
    }

    private void updateIcons(boolean invalidToken) {
        // Update icons based on token validity and state change
        if (currentIconInvalidState == null || invalidToken != currentIconInvalidState) {
            IconManager.clearIconCaches(); // Force regeneration of icons
            currentIconInvalidState = invalidToken;
            setIconImage(IconManager.getLargeIconImage(invalidToken));
            if (trayIcon != null) {
                trayIcon.setImage(IconManager.getIconImage(invalidToken));
            }
        }
    }

    /**
     * Set up system tray icon and menu if supported by the platform
     */
    private void setupSystemTray() {
        try {
            if (SystemTray.isSupported()) {
                systemTray = SystemTray.getSystemTray();

                // Create a popup menu
                PopupMenu popup = new PopupMenu();

                // Create menu items
                MenuItem showItem = new MenuItem("Show Outlook Alerter");
                showItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        activateWindow();
                    }
                });

                MenuItem refreshItem = new MenuItem("Refresh Calendar");
                refreshItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        alertedEventIds.clear(); // Clear alerted events cache
                        refreshCalendarEvents();
                    }
                });

                MenuItem settingsItem = new MenuItem("Settings");
                settingsItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        showSettingsDialog();
                    }
                });

                MenuItem exitItem = new MenuItem("Exit");
                exitItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        shutdown();
                        System.exit(0);
                    }
                });

                // Add menu items to popup
                popup.add(showItem);
                popup.add(refreshItem);
                popup.addSeparator();
                popup.add(settingsItem);
                popup.addSeparator();
                popup.add(exitItem);

                // Create tray icon with the popup menu
                try {
                    // Remove existing tray icon if present to prevent leaks
                    if (trayIcon != null) {
                        systemTray.remove(trayIcon);
                        trayIcon = null;
                    }

                    // Use the token validity state already determined in the constructor
                    // (stored in currentIconInvalidState) rather than an optimistic default.
                    // This avoids a brief blue flash when the token is actually invalid.
                    boolean trayIconInvalid = currentIconInvalidState != null ? currentIconInvalidState : false;
                    Image trayIconImage = IconManager.getIconImage(trayIconInvalid);

                    trayIcon = new TrayIcon(trayIconImage, "Outlook Alerter - Meeting Alerts", popup);
                    trayIcon.setImageAutoSize(true);

                    // Add double-click action to show window
                    trayIcon.addActionListener(new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            activateWindow();
                        }
                    });

                    // Add tray icon to the system tray
                    systemTray.add(trayIcon);

                    // Add window listener to minimize to tray on iconify
                    // Note: windowClosing is already handled by the WindowAdapter in the constructor
                    addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowIconified(WindowEvent e) {
                            setVisible(false);
                            trayIcon.displayMessage(
                                "Outlook Alerter - Meeting Alerts",
                                "Outlook Alerter is still running. Click here to open.",
                                TrayIcon.MessageType.INFO
                            );
                        }
                    });

                    // Note: Default close operation is already set in constructor
                    LogManager.getInstance().info(LogCategory.GENERAL, "System tray integration enabled successfully");

                    // Async token check: validate off-EDT and update the tray icon
                    calendarScheduler.submit((Runnable) () -> {
                        try {
                            boolean tokenInvalid = !outlookClient.hasValidToken();
                            if (tokenInvalid) {
                                SwingUtilities.invokeLater(() -> updateIcons(true));
                            }
                        } catch (Exception ex) {
                            LogManager.getInstance().error(LogCategory.GENERAL,
                                "Error checking initial token validity: " + ex.getMessage());
                        }
                    });
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.GENERAL, "Error adding system tray icon: " + e.getMessage(), e);
                    LogManager.getInstance().info(LogCategory.GENERAL, "Will continue without system tray integration");
                }
            } else {
                // System tray not supported, keep default behavior
                LogManager.getInstance().info(LogCategory.GENERAL, "System tray is not supported on this platform");
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Error setting up system tray: " + e.getMessage(), e);
        }
    }

    /**
     * Display a notification message in the system tray
     */
    public void showTrayNotification(String title, String message, TrayIcon.MessageType type) {
        if (trayIcon != null) {
            try {
                trayIcon.displayMessage(title, message, type);
            } catch (Exception e) {
                // Some platforms might not support notifications
                LogManager.getInstance().error(LogCategory.GENERAL, "Could not display tray notification: " + e.getMessage());
                // Fall back to console output
                LogManager.getInstance().info(LogCategory.GENERAL, "[NOTIFICATION] " + title + ": " + message);
            }
        } else {
            // No tray icon available, fall back to console output
            LogManager.getInstance().info(LogCategory.GENERAL, "[NOTIFICATION] " + title + ": " + message);
        }
    }

    /**
     * Initialize the user interface components
     */
    private void initializeUI() {
        // Main content panel
        final JFrame thisFrame = this;
        JPanel contentPanel = new JPanel(new BorderLayout(10, 10));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Top control panel
        JPanel controlPanel = new JPanel(new BorderLayout());
        controlPanel.setBorder(BorderFactory.createTitledBorder("Controls"));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        refreshButton = new JButton("Refresh Now");
        refreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                alertedEventIds.clear(); // Clear alerted events cache
                refreshCalendarEvents();
            }
        });
        buttonPanel.add(refreshButton);

        JButton settingsButton = new JButton("Settings");
        settingsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showSettingsDialog();
            }
        });
        buttonPanel.add(settingsButton);

        JButton logsButton = new JButton("Logs");
        logsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showLogsDialog();
            }
        });
        buttonPanel.add(logsButton);

        JButton exitButton = new JButton("Exit");
        exitButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int option = JOptionPane.showConfirmDialog(
                    thisFrame,
                    "Do you want to exit Outlook Alerter?\nYou will no longer receive meeting alerts.",
                    "Confirm Exit",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                );

                if (option == JOptionPane.YES_OPTION) {
                    shutdown();
                    System.exit(0);
                }
            }
        });
        buttonPanel.add(exitButton);

        // Button for testing all alert components (flash, banner, beeps)
        JButton testAlertsButton = new JButton("Test Alerts");
        testAlertsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Test Alerts: Starting test alert sequence");

                // Create a test event for the flash
                CalendarEvent testEvent = new CalendarEvent();
                testEvent.setSubject("Test Alert - Meeting Alert Demo");
                testEvent.setLocation("Test Location");
                testEvent.setStartTime(java.time.ZonedDateTime.now().plusMinutes(1));
                testEvent.setEndTime(java.time.ZonedDateTime.now().plusMinutes(31));
                testEvent.setIsOnlineMeeting(false);

                List<CalendarEvent> testEvents = new ArrayList<>();
                testEvents.add(testEvent);

                performFullAlert(
                    "Test meeting alert - All alert components",
                    "Test Alert",
                    "This is a test of all alert components",
                    testEvents
                );
            }
        });
        buttonPanel.add(testAlertsButton);

        JPanel statusPanel = new JPanel(new GridLayout(2, 1));
        statusLabel = new JLabel("Status: Ready");
        lastUpdateLabel = new JLabel("Last update: Never");
        statusPanel.add(statusLabel);
        statusPanel.add(lastUpdateLabel);

        controlPanel.add(buttonPanel, BorderLayout.WEST);
        controlPanel.add(statusPanel, BorderLayout.EAST);

        // Events display area
        eventsTextArea = new JTextArea();
        eventsTextArea.setEditable(false);
        eventsTextArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(eventsTextArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Upcoming Calendar Events"));

        // Add components to main panel
        contentPanel.add(controlPanel, BorderLayout.NORTH);
        contentPanel.add(scrollPane, BorderLayout.CENTER);

        // Set as content pane
        setContentPane(contentPanel);
    }

    /**
     * Start the application with window hidden by default
     */
    public void start() {
        start(false);
    }

    /**
     * Start the application
     * @param showWindow Whether to show the main window at startup
     */
    public void start(boolean showWindow) {
        // Set up sleep/wake monitoring for macOS
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            MacSleepWakeMonitor sleepMonitor = MacSleepWakeMonitor.getInstance();
            sleepMonitor.startMonitoring();

            sleepMonitor.addWakeListener(() -> {
                LogManager.getInstance().info(LogCategory.GENERAL, "[OutlookAlerterUI] Wake event detected - restarting schedulers");
                lastSystemWakeTime = System.currentTimeMillis();

                // Restart schedulers to ensure they're not stuck
                SwingUtilities.invokeLater(() -> {
                    try {
                        restartSchedulers();

                        // Also force a calendar refresh after wake
                        refreshCalendarEvents();
                    } catch (Exception e) {
                        LogManager.getInstance().error(LogCategory.GENERAL, "[OutlookAlerterUI] Error restarting after wake: " + e.getMessage(), e);
                    }
                });
            });
        }

        // Start periodic tasks if not already running
        if (!schedulersRunning) {
            // Schedule alert checks (every minute)
            // we need to calculate the next alert based on the current time difference to the next minute
            long initialDelay = (60 - ZonedDateTime.now().getSecond()) % 60; // Delay until next minute

            // Schedule calendar refreshes using the configured interval
            int resyncIntervalSeconds = configManager.getResyncIntervalMinutes() * 60;
            calendarScheduler.scheduleAtFixedRate(
                () -> safeRunScheduledTask("CalendarRefresh", () -> refreshCalendarEvents()),
                0, // Start immediately
                resyncIntervalSeconds,
                TimeUnit.SECONDS
            );

            // Schedule alert checks (every minute)
            alertScheduler.scheduleAtFixedRate(
                () -> safeRunScheduledTask("AlertCheck", () -> checkAlertsFromCache()),
                initialDelay, // Start immediately
                POLLING_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            );

            schedulersRunning = true;
        }

        // Only show window if explicitly requested
        if (showWindow) {
            setVisible(true);
        } else {
            minimizeToTray();
        }
    }

    /**
     * Minimize the application to the system tray
     */
    private void minimizeToTray() {
        setVisible(false);
        if (systemTray != null && trayIcon != null) {
            trayIcon.displayMessage(
                "Outlook Alerter",
                "Running in background. Double-click to show window.",
                TrayIcon.MessageType.INFO
            );
        }
    }

    /**
     * Clean up resources and shut down schedulers
     */
    private void shutdown() {
        // Stop schedulers
        if (schedulersRunning) {
            try {
                alertScheduler.shutdown();
                calendarScheduler.shutdown();

                // Wait a bit for tasks to complete
                alertScheduler.awaitTermination(5, TimeUnit.SECONDS);
                calendarScheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.GENERAL, "Error shutting down schedulers: " + e.getMessage());
            }
            schedulersRunning = false;
        }

        // Other cleanup...
        if (systemTray != null && trayIcon != null) {
            systemTray.remove(trayIcon);
        }
    }

    /**
     * Refresh calendar events from Outlook
     */
    private void refreshCalendarEvents() {
        // If token dialog is active, bring it to the front instead of starting a new refresh
        if (isTokenDialogActive) {
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "Skipping calendar refresh — token dialog is active, bringing it to front");
            bringTokenDialogToFront();
            return;
        }

        // Prevent overlapping fetch threads — if one is already running, skip
        // But also check for stale fetches that may have gotten stuck
        if (!fetchInProgress.compareAndSet(false, true)) {
            long elapsed = System.currentTimeMillis() - fetchStartTimeMs;
            if (elapsed > FETCH_TIMEOUT_MS) {
                LogManager.getInstance().warn(LogCategory.DATA_FETCH, "Calendar fetch appears stuck (running for " + (elapsed / 1000) + "s). Force-resetting fetchInProgress flag.");
                fetchInProgress.set(false);
                if (!fetchInProgress.compareAndSet(false, true)) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "Calendar refresh already in progress after reset, skipping");
                    return;
                }
            } else {
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "Calendar refresh already in progress (" + (elapsed / 1000) + "s elapsed), skipping");
                return;
            }
        }
        fetchStartTimeMs = System.currentTimeMillis();

        LogManager.getInstance().info(LogCategory.DATA_FETCH, "Starting calendar refresh...");

        // Update status label
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: Refreshing calendar events...");
            refreshButton.setEnabled(false);
        });

        // Perform the API call in a background thread
        Thread fetchThread = new Thread(() -> {
            try {
                // Fetch events and store them
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "Fetching events from Outlook API...");
                List<CalendarEvent> events = outlookClient.getUpcomingEventsUsingCalendarView();

                // Update token status based on refresh result
                boolean tokenInvalid = false;

                // Check token validator result
                String tokenResult = outlookClient.getLastTokenValidationResult();
                if (tokenResult == OutlookClient.TOKEN_REFRESHED) {
                    tokenInvalid = false;
                } else if (tokenResult == OutlookClient.TOKEN_NEW_AUTHENTICATION) {
                    tokenInvalid = false;
                } else if (!outlookClient.isTokenAlreadyValid()) {
                    tokenInvalid = true;
                }

                // Update icons based on token validity
                updateIcons(tokenInvalid);

                // Check if we got any events
                if (events != null) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "Retrieved " + events.size() + " events from Outlook");
                    // Update the UI with the results
                    final List<CalendarEvent> finalEvents = events;
                    SwingUtilities.invokeLater(() -> {
                        try {
                            updateEventsDisplay(finalEvents);

                            // Also update our cache for alert checking and track refresh time
                            // Use Collections.unmodifiableList to make the snapshot immutable
                            lastFetchedEvents = Collections.unmodifiableList(new ArrayList<>(finalEvents));
                            lastCalendarRefresh = ZonedDateTime.now();

                            // Update status to successful
                            String now = lastCalendarRefresh.format(DateTimeFormatter.ofPattern("hh:mm:ss a"));
                            statusLabel.setText("Status: Ready");
                            lastUpdateLabel.setText("Last update: " + now);
                            refreshButton.setEnabled(true);

                            // Count only today's meetings for the window title
                            LocalDate today = LocalDate.now();
                            long todayMeetingsCount = finalEvents.stream()
                                .filter(event -> event.getStartTime().toLocalDate().equals(today))
                                .count();
                            setTitle("Outlook Alerter - " + todayMeetingsCount + " meetings today");

                        } catch (Exception e) {
                            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Error updating UI after refresh: " + e.getMessage(), e);
                        }
                    });

                } else {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Status: No events found or error");
                        refreshButton.setEnabled(true);
                        setTitle("Outlook Alerter - Meeting Alerts");
                    });
                }
            } catch (AuthenticationCancelledException ace) {
                LogManager.getInstance().warn(LogCategory.DATA_FETCH, "Authentication was cancelled: " + ace.getMessage() + " (reason: " + ace.getReason() + ")");
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Status: Authentication cancelled");
                    refreshButton.setEnabled(true);
                    // Never show additional messages for authentication cancellation
                    // SimpleTokenDialog already shows the appropriate message

                    // Update events display with what we have in cache
                    updateEventsList(lastFetchedEvents);
                });
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.DATA_FETCH, "Error fetching calendar events: " + e.getMessage(), e);

                // Handle specific network errors more gracefully
                String errorMsg;
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Connection refused") || msg.contains("UnknownHostException"))) {
                    errorMsg = "Network error: Cannot connect to Microsoft servers.";
                } else if (msg != null && (msg.contains("authentication") || msg.contains("401"))) {
                    errorMsg = "Authentication error: Token may be invalid.";
                } else if (msg != null && (msg.contains("request timed out") || msg.contains("HttpTimeoutException"))) {
                    errorMsg = "Request timed out. Will retry on next refresh.";
                } else {
                    errorMsg = "Error refreshing calendar: " + msg;
                }

                // Use non-blocking status update instead of modal dialog
                // Modal JOptionPane can block the EDT and go unseen when minimized to tray
                final String statusMsg = errorMsg;
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Status: " + statusMsg);
                    refreshButton.setEnabled(true);

                    // Show tray notification instead of blocking modal dialog
                    showTrayNotification("Calendar Refresh Error", statusMsg, TrayIcon.MessageType.ERROR);
                });
            } finally {
                // Always release the fetch guard so future refreshes can proceed
                fetchInProgress.set(false);
                // Always re-enable the refresh button — the catch blocks above
                // already do this, but this covers unexpected Error types too.
                SwingUtilities.invokeLater(() -> refreshButton.setEnabled(true));
            }
        }, "CalendarFetchThread");

        fetchThread.setDaemon(true);
        fetchThread.start();
    }

    /**
     * Update the events display with the latest calendar events
     */
    private void updateEventsDisplay(List<CalendarEvent> events) {
        // Filter to include upcoming events and in-progress events
        List<CalendarEvent> relevantEvents = events.stream()
            .filter(event -> {
                int minutesToStart = event.getMinutesToStart();
                boolean isUpcoming = minutesToStart >= 0;  // Future event
                boolean isInProgress = event.isInProgress(); // Currently happening event
                return isUpcoming || isInProgress;
            })
            .collect(Collectors.toList());

        if (relevantEvents.isEmpty()) {
            SwingUtilities.invokeLater(() -> eventsTextArea.setText("No current or upcoming events found."));
            return;
        }

        // Sort events by minutes to start (ascending) so closest events are first
        relevantEvents.sort((a, b) -> Integer.compare(a.getMinutesToStart(), b.getMinutesToStart()));

        // Separate in-progress events from upcoming events
        List<CalendarEvent> inProgressEvents = relevantEvents.stream()
            .filter(CalendarEvent::isInProgress)
            .collect(Collectors.toList());
        List<CalendarEvent> upcomingEvents = relevantEvents.stream()
            .filter(event -> !event.isInProgress())
            .collect(Collectors.toList());

        // Build the display text
        StringBuilder displayText = new StringBuilder();

        // Show in-progress events first
        if (!inProgressEvents.isEmpty()) {
            displayText.append("\n-- CURRENTLY IN PROGRESS --\n\n");
            for (CalendarEvent event : inProgressEvents) {
                displayText.append("  \u2022 ").append(event.getSubject())
                          .append(event.getIsOnlineMeeting() ? " (Online)" : "")
                          .append(event.getOrganizer() != null ? " - Organized by: " + event.getOrganizer() : "")
                          .append(event.getResponseStatus() != null ? " - Status: " + event.getResponseStatus() : "")
                          .append(" (started ").append(-event.getMinutesToStart()).append(" minutes ago)")
                          .append("\n");
            }
        }

        // Show upcoming events
        if (!upcomingEvents.isEmpty()) {
            // Get the minutes to start for the earliest upcoming event
            int earliestMinutesToStart = upcomingEvents.get(0).getMinutesToStart();

            // Determine which events should be in the "next meetings" section
            List<CalendarEvent> nextTimeEvents = upcomingEvents.stream()
                .filter(event -> {
                    int minutesToStart = event.getMinutesToStart();
                    return (minutesToStart <= 60) || (minutesToStart <= earliestMinutesToStart + 5);
                })
                .collect(Collectors.toList());

            // Get remaining events
            List<CalendarEvent> laterEvents = upcomingEvents.stream()
                .filter(event -> !nextTimeEvents.contains(event))
                .collect(Collectors.toList());

            // Ensure all meetings are properly sorted by start time
            nextTimeEvents.sort((a, b) -> Integer.compare(a.getMinutesToStart(), b.getMinutesToStart()));
            laterEvents.sort((a, b) -> Integer.compare(a.getMinutesToStart(), b.getMinutesToStart()));

            // Include time information in the header
            ZonedDateTime now = ZonedDateTime.now();
            String displayTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            String displayZone = now.getZone().toString();

            displayText.append("\n-- NEXT MEETINGS at ").append(displayTime).append(" (").append(displayZone).append(") --\n\n");
            for (CalendarEvent event : nextTimeEvents) {
                displayText.append("  \u2022 ").append(event.getSubject())
                          .append(event.getIsOnlineMeeting() ? " (Online)" : "")
                          .append(event.getOrganizer() != null ? " - Organized by: " + event.getOrganizer() : "")
                          .append(event.getResponseStatus() != null ? " - Status: " + event.getResponseStatus() : "")
                          .append(" at ").append(event.getStartTime().format(DateTimeFormatter.ofPattern("HH:mm")))
                          .append(" (starts in ").append(event.getMinutesToStart() + 1).append(" minutes)") // + 1 minute to account for current time
                          .append("\n");
            }

            // Split later events into today, tomorrow, and beyond
            if (!laterEvents.isEmpty()) {
                LocalDate today = LocalDate.now();
                LocalDate tomorrow = today.plusDays(1);

                // Split events into today, tomorrow and later
                List<CalendarEvent> todayEvents = laterEvents.stream()
                    .filter(event -> event.getStartTime().toLocalDate().equals(today))
                    .collect(Collectors.toList());

                List<CalendarEvent> tomorrowEvents = laterEvents.stream()
                    .filter(event -> event.getStartTime().toLocalDate().equals(tomorrow))
                    .collect(Collectors.toList());

                List<CalendarEvent> beyondTomorrowEvents = laterEvents.stream()
                    .filter(event -> event.getStartTime().toLocalDate().isAfter(tomorrow))
                    .collect(Collectors.toList());

                // Show today's later events
                if (!todayEvents.isEmpty()) {
                    displayText.append("\n-- TODAY'S MEETINGS --\n\n");
                    for (CalendarEvent event : todayEvents) {
                        displayText.append("  \u2022 ").append(event.getSubject())
                                  .append(" at ").append(event.getStartTime().format(DateTimeFormatter.ofPattern("HH:mm")))
                                  .append(event.getIsOnlineMeeting() ? " (Online)" : "")
                                  .append(event.getResponseStatus() != null ? " - Status: " + event.getResponseStatus() : "")
                                  .append(" (starts in ").append(event.getMinutesToStart() + 1).append(" minutes)") // + 1 minute to account for current time
                                  .append("\n");
                    }
                }

                // Show tomorrow's events
                if (!tomorrowEvents.isEmpty()) {
                    displayText.append("\n-- TOMORROW'S MEETINGS --\n\n");
                    for (CalendarEvent event : tomorrowEvents) {
                        displayText.append("  \u2022 ").append(event.getSubject())
                                  .append(" at ").append(event.getStartTime().format(DateTimeFormatter.ofPattern("HH:mm")))
                                  .append(event.getIsOnlineMeeting() ? " (Online)" : "")
                                  .append(event.getResponseStatus() != null ? " - Status: " + event.getResponseStatus() : "")
                                  .append(" (starts in ").append(event.getMinutesToStart() + 1).append(" minutes)") // + 1 minute to account for current time
                                  .append("\n");
                    }
                }

                // Show events beyond tomorrow
                if (!beyondTomorrowEvents.isEmpty()) {
                    displayText.append("\n-- LATER MEETINGS --\n\n");
                    int limit = Math.min(10, beyondTomorrowEvents.size());
                    for (int i = 0; i < limit; i++) {
                        CalendarEvent event = beyondTomorrowEvents.get(i);
                        displayText.append("  \u2022 ").append(event.getSubject())
                                  .append(" at ").append(event.getStartTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                                  .append(event.getResponseStatus() != null ? " - Status: " + event.getResponseStatus() : "")
                                  .append(" (starts in ").append(event.getMinutesToStart()).append(" minutes)")
                                  .append("\n");
                    }

                    if (beyondTomorrowEvents.size() > 10) {
                        displayText.append("  \u2022 ...and ").append(beyondTomorrowEvents.size() - 10).append(" more later events\n");
                    }
                }
            }
        }

        // Update UI on the EDT and ensure scroll to top
        final String finalText = displayText.toString();
        SwingUtilities.invokeLater(() -> {
            eventsTextArea.setText(finalText);
            eventsTextArea.setCaretPosition(0); // Scroll back to top
            eventsTextArea.scrollRectToVisible(new Rectangle(0, 0)); // Double ensure scroll to top
        });
    }

    /**
     * Updates the events list in the UI (fallback for when full display is not needed).
     * Delegates to updateEventsDisplay() to avoid duplicating display logic.
     * @param events List of CalendarEvent objects to display
     */
    private void updateEventsList(List<CalendarEvent> events) {
        updateEventsDisplay(events != null ? events : new ArrayList<>());
    }

    /**
     * Check for events that need alerts
     */
    private void checkForEventAlerts(List<CalendarEvent> events) {
        // Suppress alerts while the token dialog is active (e.g., MSAL OAuth flow in progress)
        if (isTokenDialogActive) {
            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Skipping alert check — token dialog is active");
            return;
        }

        // Debug log
        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Checking " + events.size() + " events for alerts...");

        // Check each event for alerts
        List<CalendarEvent> eventsToAlert = new ArrayList<>();
        for (CalendarEvent event : events) {
            // Skip / cleanup events that have already ended
            if (event.hasEnded()) {
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, event.getSubject() + " Skipping: Event has ended");
                alertedEventIds.remove(event.getId()); // Remove from list if already ended
                continue;
            }
            int minutesToStart = event.getMinutesToStart() + 1; // +1 to account for current time
            LogManager.getInstance().info(LogCategory.MEETING_INFO, event.getSubject() + " Minutes to start: " + minutesToStart);
            // Skip events we've already alerted for
            if (alertedEventIds.contains(event.getId())) {
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, event.getSubject() + " Skipping: Already alerted for this event");
                continue;
            }
            // Alert for events about to start
            if (minutesToStart <= configManager.getAlertMinutes() && minutesToStart >= -1) {
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, event.getSubject() + " Alerting");
                eventsToAlert.add(event);
            }
        }
        if (!eventsToAlert.isEmpty()) {
            String alertSubjects = eventsToAlert.stream()
                .map(CalendarEvent::getSubject)
                .collect(Collectors.joining(", "));
            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "*** Triggering alert for events: " + alertSubjects);

            // Mark events as alerted FIRST to avoid duplicate alerts if any component fails
            for (CalendarEvent event : eventsToAlert) {
                alertedEventIds.add(event.getId());
            }

            // Prepare alert text (compute once, use in multiple components)
            String bannerText;
            String notificationTitle;
            String notificationMessage;
            if (eventsToAlert.size() == 1) {
                CalendarEvent ev = eventsToAlert.get(0);
                bannerText = "Upcoming meeting: " + ev.getSubject();
                notificationTitle = "Upcoming meeting";
                notificationMessage = ev.getSubject() + " in " + (ev.getMinutesToStart() + 1) + " minute(s)";
            } else {
                bannerText = eventsToAlert.size() + " upcoming meetings starting soon";
                notificationTitle = "Upcoming meetings";
                notificationMessage = eventsToAlert.size() + " meetings starting soon";
            }

            // Update status label (specific to meeting alerts)
            final int alertCount = eventsToAlert.size();
            SwingUtilities.invokeLater(() -> {
                try {
                    statusLabel.setText("Status: Alerting for " + alertCount + " event(s)");
                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "AlertStatus: Status label updated");
                } catch (Exception ex) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "AlertStatus: Error updating status label: " + ex.getMessage());
                }
            });

            // Fire all alert components via the common method
            performFullAlert(bannerText, notificationTitle, notificationMessage, eventsToAlert);
        }

        // Check token validity periodically (throttled to every 5 minutes to avoid
        // ~1,440 HTTP calls/day). hasValidToken() makes an HTTP call to /me.
        // IMPORTANT: MUST NOT run on the EDT — hasValidToken() is blocking HTTP,
        // and promptForTokens() uses invokeAndWait internally.
        long now = System.currentTimeMillis();
        if (now - lastTokenValidationTime >= TOKEN_VALIDATION_INTERVAL_MS) {
            lastTokenValidationTime = now;
            alertScheduler.submit((Runnable) () -> {
                try {
                    if (!outlookClient.hasValidToken()) {
                        LogManager.getInstance().info(LogCategory.DATA_FETCH, "Token is invalid or expired. Prompting for new token.");
                        if (!isTokenDialogActive) {
                            promptForTokens(configManager.getSignInUrl());
                        }
                    }
                } catch (Exception ex) {
                    LogManager.getInstance().error(LogCategory.DATA_FETCH, "Error checking token validity: " + ex.getMessage());
                }
            });
        }

        // Clean up alerted events list periodically
        synchronized (alertedEventIds) {
            if (alertedEventIds.size() > 100) {
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Clearing alertedEventIds list (size > 100)");
                alertedEventIds.clear();
            }
        }
    }

    /**
     * Check for alerts using the cached calendar events
     * This runs more frequently but doesn't make API calls
     */
    private void checkAlertsFromCache() {
        // Since this runs on a background thread, wrap everything in try-catch
        try {
            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "=== Checking Alerts (using cached events) ===");

            // Watchdog: detect and recover from stuck fetch threads
            if (fetchInProgress.get()) {
                long elapsed = System.currentTimeMillis() - fetchStartTimeMs;
                if (elapsed > FETCH_TIMEOUT_MS) {
                    LogManager.getInstance().warn(LogCategory.DATA_FETCH, "Watchdog: fetch has been stuck for " + (elapsed / 1000) + "s. Force-resetting fetchInProgress and updating UI.");
                    fetchInProgress.set(false);
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Status: Refresh recovered from timeout");
                        refreshButton.setEnabled(true);
                    });
                }
            }

            // Check if refresh needed (more than resyncInterval since last refresh)
            // this is to capture if device has been asleep for some time (ie weekend)
            long resyncMinutes = (long) configManager.getResyncIntervalMinutes();
            if (lastCalendarRefresh == null ||
                ZonedDateTime.now().minusMinutes(resyncMinutes).isAfter(lastCalendarRefresh)) {
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "More than " + resyncMinutes + " minutes since last refresh, triggering calendar update");
                refreshCalendarEvents();
                return;
            }

            // Make sure cached events exist
            if (lastFetchedEvents == null || lastFetchedEvents.isEmpty()) {
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "No cached events to check");
                return;
            }

            // Snapshot the cached events for thread-safe iteration
            final List<CalendarEvent> updatedEvents = new ArrayList<>(lastFetchedEvents);

            // Update UI and check alerts on the EDT since we're accessing Swing components
            SwingUtilities.invokeLater(() -> {
                try {
                    // Update the UI with recalculated times
                    updateEventsDisplay(updatedEvents);

                    // Check for alerts
                    checkForEventAlerts(updatedEvents);
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error in EDT processing cached alerts: " + e.getMessage(), e);
                }
            });

        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error checking alerts from cache: " + e.getMessage(), e);
        }
    }

    /**
     * Show settings dialog
     */
    private synchronized void showSettingsDialog() {
        // Token dialog is non-modal, so settings can coexist with it

        if (settingsDialog == null || !settingsDialog.isShowing()) {
            // Create and show the new settings dialog
            settingsDialog = new SettingsDialog(this, configManager, outlookClient);

            // Clean up the reference when closed
            settingsDialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    settingsDialog = null;
                }
            });

            // On macOS, use always-on-top temporarily to help bring focus
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                settingsDialog.setAlwaysOnTop(true);
                settingsDialog.toFront();
                settingsDialog.requestFocusInWindow();
            }

            settingsDialog.setVisible(true);
        } else {
            settingsDialog.toFront();
        }
    }

    /**
     * Set the preferred timezone
     * @param timezone The timezone ID to use (e.g., "America/New_York")
     */
    public void setTimezone(String timezone) {
        if (timezone == null || timezone.isEmpty()) {
            return;
        }

        try {
            // Validate the timezone ID
            ZoneId zoneId = ZoneId.of(timezone);
            configManager.updatePreferredTimezone(timezone);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                this,
                "Invalid timezone: " + timezone + "\nError: " + e.getMessage(),
                "Timezone Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    /**
     * Restart all schedulers with the current configuration settings
     * This method is called by the SettingsDialog when settings are saved
     */
    public void restartSchedulers() {
        LogManager.getInstance().info(LogCategory.GENERAL, "[OutlookAlerterUI] Restarting schedulers...");

        stopSchedulers();

        // Reinitialize schedulers to avoid using terminated executors
        // Use daemon threads to ensure they don't prevent JVM shutdown
        alertScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "AlertScheduler");
            t.setDaemon(true);
            return t;
        });

        calendarScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "CalendarScheduler");
            t.setDaemon(true);
            return t;
        });

        // Clean up the old screen flasher before creating a new one
        if (screenFlasher != null) {
            try {
                screenFlasher.forceCleanup();
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.GENERAL, "[OutlookAlerterUI] Error cleaning up old screen flasher: " + e.getMessage());
            }
        }

        // Recreate the screen flasher to pick up new configuration
        screenFlasher = ScreenFlasherFactory.createScreenFlasher();

        startSchedulers();

        LogManager.getInstance().info(LogCategory.GENERAL, "[OutlookAlerterUI] Schedulers restarted successfully");
    }

    /**
     * Start periodic schedulers for calendar refresh and alerts
     */
    private void startSchedulers() {
        if (schedulersRunning) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Schedulers already running");
            return;
        }

        int resyncIntervalSeconds = configManager.getResyncIntervalMinutes() * 60;

        LogManager.getInstance().info(LogCategory.GENERAL, "Starting schedulers... Resync interval: " + resyncIntervalSeconds + " seconds");

        // Schedule periodic calendar data refresh
        calendarScheduler.scheduleAtFixedRate(
            () -> safeRunScheduledTask("CalendarRefresh", () -> refreshCalendarEvents()),
            resyncIntervalSeconds,
            resyncIntervalSeconds,
            TimeUnit.SECONDS
        );

        // Schedule alert checks (every minute)
        alertScheduler.scheduleAtFixedRate(
            () -> safeRunScheduledTask("AlertCheck", () -> checkAlertsFromCache()),
            POLLING_INTERVAL_SECONDS,
            POLLING_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );

        schedulersRunning = true;
    }

    /**
     * Stop all schedulers
     */
    private void stopSchedulers() {
        LogManager.getInstance().info(LogCategory.GENERAL, "Stopping schedulers...");

        if (!schedulersRunning) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Schedulers not running");
            return;
        }

        try {
            // Attempt to shut down gracefully
            if (calendarScheduler != null) {
                calendarScheduler.shutdown();
            }
            if (alertScheduler != null) {
                alertScheduler.shutdown();
            }

            // Wait for schedulers to finish their current tasks (reduced timeout for faster recovery)
            if (calendarScheduler != null && !calendarScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                LogManager.getInstance().info(LogCategory.GENERAL, "Forcing shutdown of calendar scheduler");
                List<Runnable> pending = calendarScheduler.shutdownNow();
                if (pending != null && !pending.isEmpty()) {
                    LogManager.getInstance().info(LogCategory.GENERAL, "Cancelled " + pending.size() + " pending calendar tasks");
                }
            }
            if (alertScheduler != null && !alertScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                LogManager.getInstance().info(LogCategory.GENERAL, "Forcing shutdown of alert scheduler");
                List<Runnable> pending = alertScheduler.shutdownNow();
                if (pending != null && !pending.isEmpty()) {
                    LogManager.getInstance().info(LogCategory.GENERAL, "Cancelled " + pending.size() + " pending alert tasks");
                }
            }
        } catch (InterruptedException e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Schedulers interrupted during shutdown: " + e.getMessage());
            if (calendarScheduler != null) {
                calendarScheduler.shutdownNow();
            }
            if (alertScheduler != null) {
                alertScheduler.shutdownNow();
            }
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Error stopping schedulers: " + e.getMessage(), e);
            // Force shutdown on any error
            if (calendarScheduler != null) {
                try { calendarScheduler.shutdownNow(); } catch (Exception ignored) {}
            }
            if (alertScheduler != null) {
                try { alertScheduler.shutdownNow(); } catch (Exception ignored) {}
            }
        }

        schedulersRunning = false;
        LogManager.getInstance().info(LogCategory.GENERAL, "Schedulers stopped");
    }

    /**
     * Wrapper for scheduled tasks that catches ALL Throwable (including Error).
     * ScheduledExecutorService.scheduleAtFixedRate() silently cancels future executions
     * if the Runnable throws any uncaught exception. This wrapper prevents that.
     */
    private void safeRunScheduledTask(String taskName, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            // Catch Throwable (not just Exception) to prevent silent scheduler death.
            // ScheduledExecutorService will cancel future executions on uncaught exceptions.
            LogManager.getInstance().error(LogCategory.GENERAL, "[" + taskName + "] Uncaught error in scheduled task (scheduler will continue): " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * Activate window and request user attention, with special handling for macOS
     */
    private void activateWindow() {
        SwingUtilities.invokeLater(() -> {
            try {
                setVisible(true);
                setExtendedState(JFrame.NORMAL);

                // If token dialog is active, just make the main window visible
                // and bring the modal dialog to front — skip the main window's
                // focus-grabbing timers to avoid a focus war on macOS.
                if (isTokenDialogActive) {
                    toFront();
                    bringTokenDialogToFront();
                    return;
                }

                // On macOS, we need special handling to ensure the window comes to front
                if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                    // Set window always-on-top temporarily to help with focus
                    setAlwaysOnTop(true);

                    // First focus attempt
                    toFront();
                    requestFocusInWindow();

                    // Multiple focus requests with delays to ensure window stays on top
                    Timer focusTimer = new Timer(50, e -> {
                        toFront();
                        requestFocusInWindow();
                    });
                    focusTimer.setRepeats(false);
                    focusTimer.start();

                    // Final focus request and cleanup
                    Timer finalFocusTimer = new Timer(200, e -> {
                        toFront();
                        requestFocusInWindow();
                        setAlwaysOnTop(false);  // Remove always-on-top after final focus
                    });
                    finalFocusTimer.setRepeats(false);
                    finalFocusTimer.start();

                } else {
                    // For non-macOS platforms, simple activation is enough
                    toFront();
                    requestFocusInWindow();
                }
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.GENERAL, "Error during window activation: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Check if it's safe to show UI dialogs
     * Prevents showing dialogs immediately after system wake which can cause EDT deadlock
     */
    private boolean isSafeToShowUI() {
        long timeSinceWake = System.currentTimeMillis() - lastSystemWakeTime;
        if (timeSinceWake < 10000) {
            LogManager.getInstance().info(LogCategory.GENERAL, "UI: Delaying UI display - system recently woke (" + timeSinceWake + "ms ago)");
            return false;
        }
        return true;
    }

    /**
     * Bring the token dialog to the front if it's currently active.
     * Safe to call from any thread.
     */
    private void bringTokenDialogToFront() {
        SimpleTokenDialog dialog = SimpleTokenDialog.getCurrentInstance();
        if (dialog != null) {
            dialog.bringToFront();
        }
    }

    /**
     * Check if EDT is responsive
     * @return true if EDT is responsive, false otherwise
     */
    private boolean isEDTResponsive() {
        try {
            final boolean[] completed = new boolean[]{false};
            final CountDownLatch latch = new CountDownLatch(1);

            SwingUtilities.invokeLater(() -> {
                completed[0] = true;
                latch.countDown();
            });

            // Wait up to 2 seconds for EDT to respond
            boolean responded = latch.await(2, TimeUnit.SECONDS);
            return responded && completed[0];
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "EDT responsiveness check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Prompt the user for tokens using the SimpleTokenDialog.
     * Backwards-compatible overload that auto-resolves the MSAL provider from outlookClient.
     * @param signInUrl The URL for signing in.
     * @return A map containing the tokens, or null if the user cancels.
     */
    public Map<String, String> promptForTokens(String signInUrl) {
        com.unhuman.outlookalerter.core.MsalAuthProvider provider =
                (outlookClient != null) ? outlookClient.getMsalAuthProvider() : null;
        return promptForTokens(signInUrl, provider);
    }

    /**
     * Prompt the user for tokens using the SimpleTokenDialog.
     * @param signInUrl The URL for signing in.
     * @param msalAuthProvider The MSAL authentication provider (may be null).
     * @return A map containing the tokens, or null if the user cancels.
     */
    public Map<String, String> promptForTokens(String signInUrl, com.unhuman.outlookalerter.core.MsalAuthProvider msalAuthProvider) {
        try {
            // Wait until it's safe to show UI (e.g., after system wake stabilizes).
            // Block the calling thread instead of returning null, because returning
            // null is treated as "user cancelled" by getTokensFromUser() and the
            // dialog would never be shown.
            int safetyWaitAttempts = 0;
            while (!isSafeToShowUI() && safetyWaitAttempts < 10) {
                LogManager.getInstance().info(LogCategory.DATA_FETCH,
                        "UI: Waiting for system to stabilize before showing token dialog (attempt " + (safetyWaitAttempts + 1) + ")");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                safetyWaitAttempts++;
            }

            // Check EDT responsiveness
            // Wait for EDT to become responsive (flash/banner creation may temporarily
            // saturate the EDT, but that's transient — wait rather than give up).
            int edtWaitAttempts = 0;
            while (!isEDTResponsive() && edtWaitAttempts < 5) {
                LogManager.getInstance().info(LogCategory.DATA_FETCH,
                        "UI: EDT busy, waiting before showing token dialog (attempt " + (edtWaitAttempts + 1) + ")");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                edtWaitAttempts++;
            }

            isTokenDialogActive = true;
            SwingUtilities.invokeLater(() -> updateIcons(true));  // Show invalid token state

            // Make sure we have a valid sign-in URL, default to Microsoft Graph URL if not
            String effectiveSignInUrl = signInUrl;
            if (effectiveSignInUrl == null || effectiveSignInUrl.trim().isEmpty()) {
                effectiveSignInUrl = SimpleTokenDialog.DEFAULT_GRAPH_URL;
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "UI mode: Using default sign-in URL: " + effectiveSignInUrl);
            }

            SimpleTokenDialog dialog = SimpleTokenDialog.getInstance(effectiveSignInUrl, msalAuthProvider);
            dialog.show();
            Map<String, String> tokens = dialog.getTokens();

            LogManager.getInstance().info(LogCategory.DATA_FETCH, "UI mode: Token dialog returned: " + (tokens != null ? "tokens" : "null (cancelled)"));

            // If tokens were obtained and certificate validation setting might have changed,
            // ensure the HTTP client is updated
            if (tokens != null && outlookClient != null) {
                if (tokens.containsKey("ignoreCertValidation")) {
                    // Parse the string value explicitly by comparing to "true"
                    boolean ignoreCertValidation = "true".equalsIgnoreCase(tokens.get("ignoreCertValidation"));
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "UI mode: Certificate validation from dialog: " +
                           (ignoreCertValidation ? "disabled" : "enabled") +
                           " (Raw value: '" + tokens.get("ignoreCertValidation") + "')");
                    outlookClient.updateCertificateValidation(ignoreCertValidation);
                } else {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "UI mode: No certificate validation setting in tokens, updating HTTP client anyway");
                    outlookClient.updateHttpClient();
                }
            } else {
                // Only update status if dialog was cancelled or failed
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Status: Authentication cancelled");
                    refreshButton.setEnabled(true);
                });
            }

            // Ensure the dialog is properly disposed
            try {
                if (dialog != null) {
                    dialog.dispose();
                }
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.DATA_FETCH, "UI mode: Error disposing token dialog: " + e.getMessage());
            }

            return tokens;
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "UI mode: Error in promptForTokens: " + e.getMessage(), e);
            return null;
        } finally {
            isTokenDialogActive = false;
        }
    }

    /**
     * Fire all alert components: audio beep, banner frame, tray notification, and screen flash.
     * This is the single consistent entry point for ALL alerts (meeting alerts, token failures, test alerts).
     * Can be called from any thread — EDT and background operations are handled internally.
     *
     * @param bannerText Text shown in the on-screen banner frame
     * @param notificationTitle Title for the system tray notification
     * @param notificationMessage Body text for the system tray notification
     * @param events Calendar events to flash on screen (may be synthetic for non-meeting alerts)
     */
    public void performFullAlert(String bannerText, String notificationTitle, String notificationMessage, List<CalendarEvent> events) {
        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "performFullAlert: banner='" + bannerText + "' events=" + (events != null ? events.size() : 0));

        // ========== ALERT COMPONENT 1: Audio beep (highest priority, separate thread) ==========
        Thread beepThread = new Thread(() -> {
            try {
                int count = Math.max(0, configManager.getAlertBeepCount());
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "AlertBeep: Starting beep sequence (count: " + count + ")");
                int successCount = 0;
                for (int i = 0; i < count; i++) {
                    try {
                        Toolkit.getDefaultToolkit().beep();
                        successCount++;
                        if (i < count - 1) {
                            Thread.sleep(250);
                        }
                    } catch (InterruptedException ie) {
                        LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "AlertBeep: Interrupted during beep sequence");
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception ex) {
                        LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "AlertBeep: Error during beep " + (i + 1) + ": " + ex.getMessage());
                    }
                }
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "AlertBeep: Beep sequence completed (" + successCount + "/" + count + " beeps)");
            } catch (Exception ex) {
                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "AlertBeep: Error in beep sequence: " + ex.getMessage());
            }
        }, "AlertBeepThread");
        beepThread.setDaemon(true);
        beepThread.start();

        // ========== ALERT COMPONENT 3: Banner frame (shown once flash is visible) ==========
        final String bannerTextFinal = bannerText;

        // ========== ALERT COMPONENT 2: Screen flash (separate thread, started FIRST) ==========
        // Flash starts before the banner so when the banner appears it renders
        // directly on top of an already-visible flash — no z-order reversal flicker.
        if (events != null && !events.isEmpty()) {
            // On Mac, register a callback so the banner appears right after flash windows render,
            // avoiding both a guessed delay and z-order reversal flicker.
            // On other platforms, show the banner after a short delay.
            if (screenFlasher instanceof MacScreenFlasher) {
                MacScreenFlasher.setOnFlashReady(() -> {
                    try {
                        showAlertBanner(bannerTextFinal);
                        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "AlertBanner: Banner shown successfully (Mac callback)");
                    } catch (Exception ex) {
                        LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "AlertBanner: Error showing banner: " + ex.getMessage(), ex);
                    }
                });
            } else {
                // Non-Mac: show banner after a brief delay to let flash windows render first
                Timer bannerDelayTimer = new Timer(300, (ActionEvent timerEvt) -> {
                    try {
                        showAlertBanner(bannerTextFinal);
                        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "AlertBanner: Banner shown successfully (delayed)");
                    } catch (Exception ex) {
                        LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "AlertBanner: Error showing banner: " + ex.getMessage(), ex);
                    }
                });
                bannerDelayTimer.setRepeats(false);
                bannerDelayTimer.start();
            }

            new Thread(() -> {
                try {
                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "ScreenFlasher: Starting flashMultiple for " + events.size() + " events");
                    screenFlasher.flashMultiple(events);
                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "ScreenFlasher: Finished flashMultiple");
                    // Post-flash beep if enabled — wait for the flash duration first
                    // since flashMultiple() returns immediately (non-blocking)
                    if (configManager.getAlertBeepAfterFlash()) {
                        int flashDurationMs = configManager.getFlashDurationSeconds() * 1000;
                        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "AlertBeep: Waiting " + flashDurationMs + "ms for flash to complete before post-flash beep");
                        Thread.sleep(flashDurationMs);
                        int postBeepCount = Math.max(0, configManager.getAlertBeepCount());
                        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "AlertBeep: Starting post-flash beep sequence (count: " + postBeepCount + ")");
                        for (int i = 0; i < postBeepCount; i++) {
                            try {
                                Toolkit.getDefaultToolkit().beep();
                                if (i < postBeepCount - 1) {
                                    Thread.sleep(250);
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            } catch (Exception bex) {
                                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "AlertBeep: Post-flash beep error: " + bex.getMessage());
                            }
                        }
                        LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "AlertBeep: Post-flash beep sequence completed");
                    }
                } catch (Exception ex) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "ScreenFlasher: Exception during flashMultiple: " + ex.getMessage(), ex);
                }
            }, "ScreenFlasherThread").start();
        } else {
            // No flash events — show banner immediately
            SwingUtilities.invokeLater(() -> {
                try {
                    showAlertBanner(bannerText);
                    LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "AlertBanner: Banner shown (no flash)");
                } catch (Exception ex) {
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "AlertBanner: Error showing banner: " + ex.getMessage());
                }
            });
        }

        // ========== ALERT COMPONENT 4: Tray notification (EDT required) ==========
        SwingUtilities.invokeLater(() -> {
            try {
                showTrayNotification(notificationTitle, notificationMessage, TrayIcon.MessageType.INFO);
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "AlertTray: Tray notification shown successfully");
            } catch (Exception ex) {
                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "AlertTray: Error showing tray notification: " + ex.getMessage());
            }
        });
    }

    /**
     * Show a prominent notification banner at the top of the screen when an alert fires.
     * Creates a frame border around each monitor plus a text banner at the top.
     */
    private void showAlertBanner(String message) {
        try {
            // Note: This method is already called on the EDT by the orchestration code,
            // so no additional invokeLater wrapper is needed (double-nesting delays rendering).

            // Close any existing banners first
            for (JFrame banner : alertBannerWindows) {
                banner.dispose();
            }
            alertBannerWindows.clear();

            final Color bg = new Color(220, 0, 0);
            final Color fg = Color.WHITE;

            // Use a single full-screen transparent window per monitor to paint the
            // border frame + top text banner. This eliminates seam artifacts that
            // occur when multiple overlapping windows are composited by macOS.
            // The top banner text is ALSO rendered inside the flash window's HTML
            // as a fallback in case the banner window gets obscured by z-order.
            GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            for (GraphicsDevice screen : screens) {
                try {
                    Rectangle bounds = screen.getDefaultConfiguration().getBounds();

                    // Account for macOS menu bar (and any other OS chrome) so the
                    // top banner text isn't clipped behind it on regular desktops.
                    // Full-screen apps hide the menu bar, so insets will be 0 there.
                    Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(screen.getDefaultConfiguration());
                    int frameX = (int) bounds.getX();
                    int frameY = (int) bounds.getY() + screenInsets.top;
                    int frameW = (int) bounds.getWidth();
                    int frameH = (int) bounds.getHeight() - screenInsets.top;

                    // Calculate border thickness from text height
                    JLabel sizeRef = new JLabel("X");
                    sizeRef.setFont(sizeRef.getFont().deriveFont(Font.BOLD, 18f));
                    sizeRef.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
                    final int borderThickness = (int) (sizeRef.getPreferredSize().height + 10);
                    final String bannerMessage = message;

                    JFrame frameWindow = new JFrame(screen.getDefaultConfiguration());
                    frameWindow.setUndecorated(true);
                    frameWindow.setType(JFrame.Type.POPUP);
                    frameWindow.setBackground(new Color(0, 0, 0, 0));  // fully transparent

                    // Custom panel that paints only the border regions and top text
                    JPanel framePanel = new JPanel() {
                        @Override
                        protected void paintComponent(Graphics g) {
                            // Do NOT call super — we want transparency in the center
                            Graphics2D g2 = (Graphics2D) g.create();
                            int w = getWidth();
                            int h = getHeight();

                            g2.setColor(bg);

                            // Top bar
                            g2.fillRect(0, 0, w, borderThickness);
                            // Left strip
                            g2.fillRect(0, borderThickness, borderThickness, h - borderThickness);
                            // Right strip
                            g2.fillRect(w - borderThickness, borderThickness, borderThickness, h - borderThickness);
                            // Bottom strip
                            g2.fillRect(0, h - borderThickness, w, borderThickness);

                            // Draw banner text in the top bar
                            g2.setColor(fg);
                            g2.setFont(getFont().deriveFont(Font.BOLD, 18f));
                            FontMetrics fm = g2.getFontMetrics();
                            int textWidth = fm.stringWidth(bannerMessage);
                            int textX = (int) ((w - textWidth) / 2);
                            int textY = (int) ((borderThickness + fm.getAscent() - fm.getDescent()) / 2);
                            g2.drawString(bannerMessage, textX, textY);

                            g2.dispose();
                        }
                    };
                    framePanel.setOpaque(false);
                    frameWindow.setContentPane(framePanel);
                    frameWindow.setBounds(frameX, frameY, frameW, frameH);
                    frameWindow.setAlwaysOnTop(true);
                    frameWindow.setVisible(true);
                    alertBannerWindows.add(frameWindow);

                } catch (Exception screenEx) {
                    // Per-screen isolation: one monitor failure must not prevent others
                    LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error creating banner for screen '" + screen.getIDstring() + "': " + screenEx.getMessage());
                }
            }

            // Auto-hide after a few seconds (always set up, even if some screens failed)
            if (!alertBannerWindows.isEmpty()) {
                // Register banner windows with the flash's elevation timer so it
                // re-elevates them after each of its own toFront() calls.
                // This cooperative approach avoids two timers fighting each other.
                if (screenFlasher instanceof MacScreenFlasher) {
                    MacScreenFlasher.registerOverlayWindows(alertBannerWindows);
                }

                int bannerDurationMs = configManager.getFlashDurationSeconds() * 1000;
                Timer hideTimer = new Timer(bannerDurationMs, (ActionEvent e) -> {
                    if (screenFlasher instanceof MacScreenFlasher) {
                        MacScreenFlasher.clearOverlayWindows();
                    }
                    for (JFrame banner : alertBannerWindows) {
                        banner.dispose();
                    }
                    alertBannerWindows.clear();
                });
                hideTimer.setRepeats(false);
                hideTimer.start();
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING, "Error showing alert banner: " + e.getMessage());
        }
    }

    /**
     * Show the log viewer dialog
     * Creates a new one if none exists or reuses the existing one
     */
    private synchronized void showLogsDialog() {
        if (logViewer == null || !logViewer.isDisplayable()) {
            // Create and show the new log viewer dialog
            logViewer = new LogViewer(this);

            // Clean up the reference when closed
            logViewer.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    logViewer = null;
                }
            });

            // Display the dialog
            logViewer.setVisible(true);

            // Request user attention on macOS
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                try {
                    Taskbar taskbar = Taskbar.getTaskbar();
                    taskbar.requestUserAttention(true, false);
                } catch (Exception e) {
                    LogManager.getInstance().info(LogCategory.GENERAL, "Could not request user attention: " + e.getMessage());
                }
            }
        } else {
            // Bring to front if it already exists
            logViewer.toFront();
            logViewer.repaint();
        }
    }
}
