package com.unhuman.outlookalerter.ui

import groovy.transform.CompileStatic
import javax.swing.*
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import com.unhuman.outlookalerter.core.ConfigManager
import com.unhuman.outlookalerter.core.OutlookClient
import com.unhuman.outlookalerter.core.OutlookClient.AuthenticationCancelledException
import com.unhuman.outlookalerter.model.CalendarEvent
import com.unhuman.outlookalerter.util.ScreenFlasher
import com.unhuman.outlookalerter.util.ScreenFlasherFactory
import com.unhuman.outlookalerter.util.MacSleepWakeMonitor
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.List
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.io.OutputStream
import java.io.PrintStream
import java.io.StringWriter
import java.io.PrintWriter
import java.awt.Taskbar;
import com.unhuman.outlookalerter.util.LogManager;

/**
 * Main application window for Outlook Alerter
 * Provides a user interface for monitoring upcoming meetings
 */
@CompileStatic
class OutlookAlerterUI extends JFrame {
    // Initialize logging interceptor
    static {
        // First store the original streams in LogManager
        final PrintStream originalOut = System.out
        final PrintStream originalErr = System.err
        LogManager.setOriginalOutStream(originalOut)
        LogManager.setOriginalErrStream(originalErr)
        
        // Replace System.out with our intercepting PrintStream
        System.setOut(new PrintStream(new OutputStream() {
            private StringBuilder buffer = new StringBuilder()
            
            @Override
            public void write(int b) {
                // Write to original stream
                originalOut.write(b)
                
                // Add to buffer
                char c = (char) b
                buffer.append(c)
                
                // Process buffer when we get a newline
                if (c == '\n') {
                    String message = buffer.toString().trim()
                    if (!message.isEmpty()) {
                        LogManager.getInstance().info(message)
                    }
                    buffer.setLength(0)
                }
            }
        }))
        
        // Replace System.err with our intercepting PrintStream
        System.setErr(new PrintStream(new OutputStream() {
            private StringBuilder buffer = new StringBuilder()
            
            @Override
            public void write(int b) {
                // Write to original stream
                originalErr.write(b)
                
                // Add to buffer
                char c = (char) b
                buffer.append(c)
                
                // Process buffer when we get a newline
                if (c == '\n') {
                    String message = buffer.toString().trim()
                    if (!message.isEmpty()) {
                        LogManager.getInstance().error(message)
                    }
                    buffer.setLength(0)
                }
            }
        }))
    }
    // Time constants
    private static final int POLLING_INTERVAL_SECONDS = 60
    
    // Components
    private final ConfigManager configManager
    private final OutlookClient outlookClient
    private ScreenFlasher screenFlasher
    
    // UI Components
    private JTextArea eventsTextArea
    private JButton refreshButton
    private JLabel statusLabel
    private JLabel lastUpdateLabel
    private TrayIcon trayIcon
    private SystemTray systemTray

    // Schedulers for periodic tasks
    private ScheduledExecutorService alertScheduler
    private ScheduledExecutorService calendarScheduler
    private volatile boolean schedulersRunning = false
    
    // Track which events we've already alerted for
    private final Set<String> alertedEventIds = new java.util.concurrent.ConcurrentHashMap<String, Boolean>().keySet(true)
    
    // Store last fetched events to avoid frequent API calls
    private List<CalendarEvent> lastFetchedEvents = []
    
    // Track last calendar refresh time
    private volatile ZonedDateTime lastCalendarRefresh = null
    
    // Track the current icon state
    private Boolean currentIconInvalidState = null

    // Track settings dialog instance
    private SettingsDialog settingsDialog = null;

    // Track logs dialog instance
    private LogViewer logViewer = null;

    // Added a flag to track if the token dialog is active
    private boolean isTokenDialogActive = false;

    // Track last system wake time for safe UI display
    private long lastSystemWakeTime = System.currentTimeMillis()

    // Banner components
    private JWindow alertBannerWindow

    /**
     * Create a new OutlookAlerterUI
     */
    OutlookAlerterUI(String configPath) {
        super("Outlook Alerter - Meeting Alerts")
        
        // Initialize components first so we can check token status
        this.configManager = new ConfigManager(configPath)
        this.configManager.loadConfiguration()
        this.outlookClient = new OutlookClient(configManager, this)
        
        // Set window icon based on initial token validity
        boolean tokenInvalid = !outlookClient.isTokenAlreadyValid() // Use isTokenAlreadyValid for initial check
        updateIcons(tokenInvalid)

        this.configManager.loadConfiguration()
        this.screenFlasher = ScreenFlasherFactory.createScreenFlasher()
        
        // Initialize schedulers with daemon threads to prevent blocking JVM shutdown
        this.alertScheduler = Executors.newScheduledThreadPool(1, { r ->
            Thread t = new Thread(r, "AlertScheduler")
            t.setDaemon(true)
            return t
        })
        this.calendarScheduler = Executors.newScheduledThreadPool(1, { r ->
            Thread t = new Thread(r, "CalendarScheduler")
            t.setDaemon(true)
            return t
        })

        // Set up the UI
        initializeUI()

        // Configure window behavior for system tray support
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE)  // We'll handle window closing ourselves
        addWindowListener(new WindowAdapter() {
            @Override
            void windowClosing(WindowEvent e) {
                // If system tray is supported, just minimize to tray
                if (systemTray != null && trayIcon != null) {
                    setVisible(false)
                    trayIcon.displayMessage(
                        "Outlook Alerter - Meeting Alerts",
                        "Outlook Alerter is still running in the background. Right-click the tray icon to exit.",
                        TrayIcon.MessageType.INFO
                    )
                } else {
                    // If no system tray, prompt to exit
                    int option = JOptionPane.showConfirmDialog(
                        OutlookAlerterUI.this,
                        "Do you want to exit Outlook Alerter?\nYou will no longer receive meeting alerts.",
                        "Confirm Exit",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                    )
                    
                    if (option == JOptionPane.YES_OPTION) {
                        shutdown()
                        System.exit(0)
                    }
                }
            }
        })
        
        // Set size and position
        setSize(800, 600)
        setLocationRelativeTo(null) // Center on screen
        
        // Set up system tray if supported
        setupSystemTray()
    }
    
    private void updateIcons(boolean invalidToken) {
        // Update icons based on token validity and state change
        if (invalidToken != currentIconInvalidState) {
            IconManager.clearIconCaches() // Force regeneration of icons
            currentIconInvalidState = invalidToken
            setIconImage(IconManager.getLargeIconImage(invalidToken))
            if (trayIcon != null) {
                trayIcon.setImage(IconManager.getIconImage(invalidToken))
            }
        }
    }

    /**
     * Set up system tray icon and menu if supported by the platform
     */
    private void setupSystemTray() {
        try {
            if (SystemTray.isSupported()) {
                systemTray = SystemTray.getSystemTray()
                
                // Create a popup menu
                PopupMenu popup = new PopupMenu()
                
                // Create menu items
                MenuItem showItem = new MenuItem("Show Outlook Alerter")
                showItem.addActionListener(new ActionListener() {
                    @Override
                    void actionPerformed(ActionEvent e) {
                        activateWindow()
                    }
                })
                
                MenuItem refreshItem = new MenuItem("Refresh Calendar")
                refreshItem.addActionListener(new ActionListener() {
                    @Override
                    void actionPerformed(ActionEvent e) {
                        alertedEventIds.clear() // Clear alerted events cache
                        refreshCalendarEvents()
                    }
                })
                
                MenuItem settingsItem = new MenuItem("Settings")
                settingsItem.addActionListener(new ActionListener() {
                    @Override
                    void actionPerformed(ActionEvent e) {
                        if (isTokenDialogActive) {
                            System.out.println("Cannot display settings dialog while token dialog is active.");
                        } else {
                            // activateWindow()
                            showSettingsDialog()
                        }
                    }
                })
                
                MenuItem exitItem = new MenuItem("Exit")
                exitItem.addActionListener(new ActionListener() {
                    @Override
                    void actionPerformed(ActionEvent e) {
                        shutdown()
                        System.exit(0)
                    }
                })
                
                // Add menu items to popup
                popup.add(showItem)
                popup.add(refreshItem)
                popup.addSeparator()
                popup.add(settingsItem)
                popup.addSeparator()
                popup.add(exitItem)
                
                // Create tray icon with the popup menu
                try {
                    // Create icon based on token validity
                    boolean tokenInvalid = !outlookClient.hasValidToken()
                    Image trayIconImage = IconManager.getIconImage(tokenInvalid)
                    
                    trayIcon = new TrayIcon(trayIconImage, "Outlook Alerter - Meeting Alerts", popup)
                    trayIcon.setImageAutoSize(true)
                    
                    // Add double-click action to show window
                    trayIcon.addActionListener(new ActionListener() {
                        @Override
                        void actionPerformed(ActionEvent e) {
                            activateWindow()
                        }
                    })
                    
                    // Add tray icon to the system tray
                    systemTray.add(trayIcon)
                    
                    // Add window listener to minimize to tray when closed
                    addWindowListener(new WindowAdapter() {
                        @Override
                        void windowIconified(WindowEvent e) {
                            setVisible(false)
                            trayIcon.displayMessage(
                                "Outlook Alerter - Meeting Alerts",
                                "Outlook Alerter is still running. Click here to open.",
                                TrayIcon.MessageType.INFO
                            )
                        }
                        
                        @Override
                        void windowClosing(WindowEvent e) {
                            // Don't exit application, just hide to system tray
                            setVisible(false)
                            trayIcon.displayMessage(
                                "Outlook Alerter - Meeting Alerts",
                                "Outlook Alerter is still running in the background. Right-click the tray icon to exit.",
                                TrayIcon.MessageType.INFO
                            )
                        }
                    })
                    
                    // Note: Default close operation is already set in constructor
                    System.out.println("System tray integration enabled successfully")
                } catch (Exception e) {
                    System.err.println("Error adding system tray icon: " + e.getMessage())
                    e.printStackTrace()
                    System.out.println("Will continue without system tray integration")
                }
            } else {
                // System tray not supported, keep default behavior
                System.out.println("System tray is not supported on this platform")
            }
        } catch (Exception e) {
            System.err.println("Error setting up system tray: " + e.getMessage())
            e.printStackTrace()
        }
    }

    /**
     * Create a simple icon for the system tray
     */
    @CompileStatic
    private java.awt.Image createTrayIconImage() {
        return IconManager.getIconImage()
    }
    
    /**
     * Display a notification message in the system tray
     */
    private void showTrayNotification(String title, String message, TrayIcon.MessageType type) {
        if (trayIcon != null) {
            try {
                trayIcon.displayMessage(title, message, type)
            } catch (Exception e) {
                // Some platforms might not support notifications
                System.err.println("Could not display tray notification: " + e.getMessage())
                // Fall back to console output
                System.out.println("[NOTIFICATION] " + title + ": " + message)
            }
        } else {
            // No tray icon available, fall back to console output
            System.out.println("[NOTIFICATION] " + title + ": " + message)
        }
    }
    
    /**
     * Initialize the user interface components
     */
    private void initializeUI() {
        // Main content panel
        final JFrame thisFrame = this
        JPanel contentPanel = new JPanel(new BorderLayout(10, 10))
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10))
        
        // Top control panel
        JPanel controlPanel = new JPanel(new BorderLayout())
        controlPanel.setBorder(BorderFactory.createTitledBorder("Controls"))
        
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT))
        refreshButton = new JButton("Refresh Now")
        refreshButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                alertedEventIds.clear() // Clear alerted events cache
                refreshCalendarEvents()
            }
        })
        buttonPanel.add(refreshButton)
        
        JButton settingsButton = new JButton("Settings")
        settingsButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                //activateWindow()
                showSettingsDialog()
            }
        })
        buttonPanel.add(settingsButton)
        
        JButton logsButton = new JButton("Logs")
        logsButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                showLogsDialog()
            }
        })
        buttonPanel.add(logsButton)
        
        JButton exitButton = new JButton("Exit")
        exitButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                int option = JOptionPane.showConfirmDialog(
                    thisFrame,
                    "Do you want to exit Outlook Alerter?\nYou will no longer receive meeting alerts.",
                    "Confirm Exit",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                )
                
                if (option == JOptionPane.YES_OPTION) {
                    shutdown()
                    System.exit(0)
                }
            }
        })
        buttonPanel.add(exitButton)
        
        // Button for testing all alert components (flash, banner, beeps)
        JButton testAlertsButton = new JButton("Test Alerts")
        testAlertsButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                System.out.println("Test Alerts: Starting test alert sequence")

                // Create a test event for the flash
                CalendarEvent testEvent = new CalendarEvent()
                testEvent.subject = "Test Alert - Meeting Alert Demo"
                testEvent.location = "Test Location"
                testEvent.startTime = java.time.ZonedDateTime.now().plusMinutes(1)
                testEvent.endTime = java.time.ZonedDateTime.now().plusMinutes(31)
                testEvent.isOnlineMeeting = false

                String bannerText = "Test meeting alert - All alert components"
                String notificationTitle = "Test Alert"
                String notificationMessage = "This is a test of all alert components"

                // ========== ALERT COMPONENT 1: Audio beep (separate thread) ==========
                new Thread({
                    try {
                        int count = Math.max(0, configManager.getAlertBeepCount())
                        System.out.println("Test Alerts - Beep: Starting beep sequence (count: ${count})")
                        int successCount = 0
                        for (int i = 0; i < count; i++) {
                            try {
                                Toolkit.getDefaultToolkit().beep()
                                successCount++
                                if (i < count - 1) {
                                    Thread.sleep(250)
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt()
                                break
                            } catch (Exception ex) {
                                System.err.println("Test Alerts - Beep: Error during beep: " + ex.getMessage())
                            }
                        }
                        System.out.println("Test Alerts - Beep: Completed (${successCount}/${count} beeps)")
                    } catch (Exception ex) {
                        System.err.println("Test Alerts - Beep: Error in beep sequence: " + ex.getMessage())
                    }
                }, "TestAlertBeepThread").start()

                // ========== ALERT COMPONENT 2: Banner (EDT) ==========
                SwingUtilities.invokeLater({
                    try {
                        showAlertBanner(bannerText)
                        System.out.println("Test Alerts - Banner: Shown successfully")
                    } catch (Exception ex) {
                        System.err.println("Test Alerts - Banner: Error: " + ex.getMessage())
                    }
                } as Runnable)

                // ========== ALERT COMPONENT 3: Tray notification (EDT) ==========
                SwingUtilities.invokeLater({
                    try {
                        showTrayNotification(notificationTitle, notificationMessage, TrayIcon.MessageType.INFO)
                        System.out.println("Test Alerts - Tray: Notification shown successfully")
                    } catch (Exception ex) {
                        System.err.println("Test Alerts - Tray: Error: " + ex.getMessage())
                    }
                } as Runnable)

                // ========== ALERT COMPONENT 4: Screen flash (separate thread) ==========
                new Thread({
                    try {
                        System.out.println("Test Alerts - Flash: Starting screen flash")
                        screenFlasher.flash(testEvent)
                        System.out.println("Test Alerts - Flash: Completed")
                    } catch (Exception ex) {
                        System.err.println("Test Alerts - Flash: Error: " + ex.getMessage())
                        ex.printStackTrace()
                    }
                }, "TestAlertFlashThread").start()
            }
        })
        buttonPanel.add(testAlertsButton)

        JPanel statusPanel = new JPanel(new GridLayout(2, 1))
        statusLabel = new JLabel("Status: Ready")
        lastUpdateLabel = new JLabel("Last update: Never")
        statusPanel.add(statusLabel)
        statusPanel.add(lastUpdateLabel)
        
        controlPanel.add(buttonPanel, BorderLayout.WEST)
        controlPanel.add(statusPanel, BorderLayout.EAST)
        
        // Events display area
        eventsTextArea = new JTextArea()
        eventsTextArea.setEditable(false)
        eventsTextArea.setFont(new Font("Monospaced", Font.PLAIN, 12))
        JScrollPane scrollPane = new JScrollPane(eventsTextArea)
        scrollPane.setBorder(BorderFactory.createTitledBorder("Upcoming Calendar Events"))
        
        // Add components to main panel
        contentPanel.add(controlPanel, BorderLayout.NORTH)
        contentPanel.add(scrollPane, BorderLayout.CENTER)
        
        // Set as content pane
        setContentPane(contentPanel)
    }
    
    /**
     * Start the application
     * @param showWindow Whether to show the main window at startup
     */
    void start(boolean showWindow = false) {
        // Set up sleep/wake monitoring for macOS
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            MacSleepWakeMonitor sleepMonitor = MacSleepWakeMonitor.getInstance()
            sleepMonitor.startMonitoring()

            sleepMonitor.addWakeListener({
                System.out.println("[OutlookAlerterUI] Wake event detected - restarting schedulers")
                lastSystemWakeTime = System.currentTimeMillis()

                // Restart schedulers to ensure they're not stuck
                SwingUtilities.invokeLater({
                    try {
                        restartSchedulers()

                        // Also force a calendar refresh after wake
                        refreshCalendarEvents()
                    } catch (Exception e) {
                        System.err.println("[OutlookAlerterUI] Error restarting after wake: " + e.getMessage())
                        e.printStackTrace()
                    }
                } as Runnable)
            } as Runnable)
        }

        // Start periodic tasks if not already running
        if (!schedulersRunning) {
            // Schedule alert checks (every minute)
            // we need to calculate the next alert based on the current time difference to the next minute
            long initialDelay = (60 - ZonedDateTime.now().getSecond()) % 60 // Delay until next minute

            // Schedule calendar refreshes using the configured interval
            int resyncIntervalSeconds = configManager.getResyncIntervalMinutes() * 60;
            calendarScheduler.scheduleAtFixedRate(
                { refreshCalendarEvents() } as Runnable,
                0, // Start immediately
                resyncIntervalSeconds,
                TimeUnit.SECONDS
            )
            
            // Schedule alert checks (every minute)
            alertScheduler.scheduleAtFixedRate(
                { checkAlertsFromCache() } as Runnable,
                initialDelay, // Start immediately
                POLLING_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
            
            schedulersRunning = true
        }
        
        // Only show window if explicitly requested
        if (showWindow) {
            setVisible(true)
        } else {
            minimizeToTray()
        }
    }

    /**
     * Minimize the application to the system tray
     */
    private void minimizeToTray() {
        setVisible(false)
        if (systemTray != null && trayIcon != null) {
            trayIcon.displayMessage(
                "Outlook Alerter",
                "Running in background. Double-click to show window.",
                TrayIcon.MessageType.INFO
            )
        }
    }

    /**
     * Clean up resources and shut down schedulers
     */
    private void shutdown() {
        // Stop schedulers
        if (schedulersRunning) {
            try {
                alertScheduler.shutdown()
                calendarScheduler.shutdown()
                
                // Wait a bit for tasks to complete
                alertScheduler.awaitTermination(5, TimeUnit.SECONDS)
                calendarScheduler.awaitTermination(5, TimeUnit.SECONDS)
            } catch (Exception e) {
                System.err.println("Error shutting down schedulers: " + e.getMessage())
            }
            schedulersRunning = false
        }
        
        // Other cleanup...
        if (systemTray != null && trayIcon != null) {
            systemTray.remove(trayIcon)
        }
    }

    /**
     * Refresh calendar events from Outlook
     */
    private void refreshCalendarEvents() {
        // Update status label
        SwingUtilities.invokeLater({
            statusLabel.setText("Status: Refreshing calendar events...")
            refreshButton.setEnabled(false)
        } as Runnable)
        
        // Perform the API call in a background thread
        Thread fetchThread = new Thread({
            try {
                // Fetch events and store them
                List<CalendarEvent> events = outlookClient.getUpcomingEventsUsingCalendarView()
                
                // Update token status based on refresh result
                boolean tokenInvalid = false
                
                // Check token validator result
                String tokenResult = outlookClient.getLastTokenValidationResult()
                if (tokenResult == OutlookClient.TOKEN_REFRESHED) {
                    tokenInvalid = false
                } else if (tokenResult == OutlookClient.TOKEN_NEW_AUTHENTICATION) {
                    tokenInvalid = false
                } else if (!outlookClient.isTokenAlreadyValid()) {
                    tokenInvalid = true
                }
                
                // Update icons based on token validity
                updateIcons(tokenInvalid)
                
                // Check if we got any events
                if (events != null) {
                    // Update the UI with the results
                    SwingUtilities.invokeLater({
                        try {
                            updateEventsDisplay(events)
                            
                            // Also update our cache for alert checking and track refresh time
                            lastFetchedEvents = events
                            lastCalendarRefresh = ZonedDateTime.now()
                        
                            // Update status to successful
                            String now = lastCalendarRefresh.format(DateTimeFormatter.ofPattern("hh:mm:ss a"))
                            statusLabel.setText("Status: Ready")
                            lastUpdateLabel.setText("Last update: " + now)
                            refreshButton.setEnabled(true)
                            
                            // Count only today's meetings for the window title
                            LocalDate today = LocalDate.now()
                            int todayMeetingsCount = events.findAll { event ->
                                event.startTime.toLocalDate() == today
                            }.size()
                            setTitle("Outlook Alerter - " + todayMeetingsCount + " meetings today")
                            
                        } catch (Exception e) {
                            System.err.println("Error updating UI after refresh: " + e.getMessage())
                            e.printStackTrace()
                        }
                    } as Runnable)
                    
                } else {
                    SwingUtilities.invokeLater(new Runnable() {
                        void run() {
                            statusLabel.setText("Status: No events found or error")
                            refreshButton.setEnabled(true)
                            setTitle("Outlook Alerter - Meeting Alerts")
                        }
                    })
                }
            } catch (OutlookClient.AuthenticationCancelledException ace) {
                System.out.println("Authentication was cancelled: " + ace.getMessage() + " (reason: " + ace.getReason() + ")")
                final OutlookAlerterUI self = this  // Store reference to outer class
                SwingUtilities.invokeLater(new Runnable() {
                    void run() {
                        statusLabel.setText("Status: Authentication cancelled")
                        refreshButton.setEnabled(true)
                        // Never show additional messages for authentication cancellation
                        // SimpleTokenDialog already shows the appropriate message
                        
                        // Update events display with what we have in cache
                        updateEventsList(lastFetchedEvents ?: [])
                    }
                })
            } catch (Exception e) {
                System.err.println("Error fetching calendar events: " + e.getMessage())
                e.printStackTrace()
                
                // Handle specific network errors more gracefully
                String errorMsg
                if (e.message?.contains("Connection refused") || e.message?.contains("UnknownHostException")) {
                    errorMsg = "Network error: Cannot connect to Microsoft servers.\nPlease check your internet connection."
                } else if (e.message?.contains("authentication") || e.message?.contains("401")) {
                    errorMsg = "Authentication error: Token may be invalid.\nPlease click Refresh to re-authenticate."
                } else {
                    errorMsg = "Error refreshing calendar: " + e.message
                }
                
                SwingUtilities.invokeLater({
                    statusLabel.setText("Status: Error - " + e.message)
                    refreshButton.setEnabled(true)
                    
                    JOptionPane.showMessageDialog(
                        this,
                        errorMsg,
                        "Calendar Refresh Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                } as Runnable)
            }
        }, "CalendarFetchThread")
        
        fetchThread.setDaemon(true)
        fetchThread.start()
    }
    
    /**
     * Update the events display with the latest calendar events
     */
    private void updateEventsDisplay(List<CalendarEvent> events) {
        // Filter to include upcoming events and in-progress events
        List<CalendarEvent> relevantEvents = events.findAll { event -> 
            int minutesToStart = event.getMinutesToStart()
            boolean isUpcoming = minutesToStart >= 0  // Future event
            boolean isInProgress = event.isInProgress() // Currently happening event
            
            if (!isUpcoming && !isInProgress) {
                return false
            }
            return true
        }
        
        if (relevantEvents.isEmpty()) {
            SwingUtilities.invokeLater({
                eventsTextArea.setText("No current or upcoming events found.")
            } as Runnable)
            return
        }
        
        // Sort events by minutes to start (ascending) so closest events are first
        relevantEvents.sort { event -> event.getMinutesToStart() }
        
        // Separate in-progress events from upcoming events
        List<CalendarEvent> inProgressEvents = relevantEvents.findAll { event -> event.isInProgress() }
        List<CalendarEvent> upcomingEvents = relevantEvents.findAll { event -> !event.isInProgress() }
        
        // Build the display text
        StringBuilder displayText = new StringBuilder()
        
        // Show in-progress events first
        if (!inProgressEvents.isEmpty()) {
            displayText.append("\n-- CURRENTLY IN PROGRESS --\n\n")
            inProgressEvents.each { CalendarEvent event ->
                displayText.append("  • ${event.subject}")
                          .append(event.isOnlineMeeting ? " (Online)" : "")
                          .append(event.organizer ? " - Organized by: ${event.organizer}" : "")
                          .append(event.responseStatus ? " - Status: ${event.responseStatus}" : "")
                          .append(" (started ${-event.getMinutesToStart()} minutes ago)")
                          .append("\n")
            }
        }
        
        // Show upcoming events
        if (!upcomingEvents.isEmpty()) {
            // Get the minutes to start for the earliest upcoming event
            int earliestMinutesToStart = upcomingEvents[0].getMinutesToStart()
            
            // Determine which events should be in the "next meetings" section
            List<CalendarEvent> nextTimeEvents = upcomingEvents.findAll { event -> 
                int minutesToStart = event.getMinutesToStart()
                return (minutesToStart <= 60) || (minutesToStart <= earliestMinutesToStart + 5)
            }
            
            // Get remaining events
            List<CalendarEvent> laterEvents = upcomingEvents.findAll { event -> 
                !nextTimeEvents.contains(event)
            }
            
            // Ensure all meetings are properly sorted by start time
            nextTimeEvents.sort { event -> event.getMinutesToStart() }
            laterEvents.sort { event -> event.getMinutesToStart() }
            
            // Include time information in the header
            ZonedDateTime now = ZonedDateTime.now()
            String displayTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            String displayZone = now.getZone().toString()
            
            displayText.append("\n-- NEXT MEETINGS at ${displayTime} (${displayZone}) --\n\n")
            nextTimeEvents.each { CalendarEvent event ->
                displayText.append("  • ${event.subject}")
                          .append(event.isOnlineMeeting ? " (Online)" : "")
                          .append(event.organizer ? " - Organized by: ${event.organizer}" : "")
                          .append(event.responseStatus ? " - Status: ${event.responseStatus}" : "")
                          .append(" at ${event.startTime.format(DateTimeFormatter.ofPattern("HH:mm"))}")
                          .append(" (starts in ${event.getMinutesToStart() + 1} minutes)") // + 1 minute to account for current time
                          .append("\n")
            }
            
            // Split later events into today, tomorrow, and beyond
            if (!laterEvents.isEmpty()) {
                LocalDate today = LocalDate.now()
                LocalDate tomorrow = today.plusDays(1)
                
                // Split events into today, tomorrow and later
                List<CalendarEvent> todayEvents = laterEvents.findAll { event ->
                    event.startTime.toLocalDate() == today
                }
                
                List<CalendarEvent> tomorrowEvents = laterEvents.findAll { event ->
                    event.startTime.toLocalDate() == tomorrow
                }
                
                List<CalendarEvent> beyondTomorrowEvents = laterEvents.findAll { event ->
                    event.startTime.toLocalDate() > tomorrow
                }
                
                // Show today's later events
                if (!todayEvents.isEmpty()) {
                    displayText.append("\n-- TODAY'S MEETINGS --\n\n")
                    todayEvents.each { CalendarEvent event ->
                        displayText.append("  • ${event.subject} at ${event.startTime.format(DateTimeFormatter.ofPattern("HH:mm"))}")
                                  .append(event.isOnlineMeeting ? " (Online)" : "")
                                  .append(event.responseStatus ? " - Status: ${event.responseStatus}" : "")
                                  .append(" (starts in ${event.getMinutesToStart() + 1} minutes)") // + 1 minute to account for current time
                                  .append("\n")
                    }
                }

                // Show tomorrow's events
                if (!tomorrowEvents.isEmpty()) {
                    displayText.append("\n-- TOMORROW'S MEETINGS --\n\n")
                    tomorrowEvents.each { CalendarEvent event ->
                        displayText.append("  • ${event.subject} at ${event.startTime.format(DateTimeFormatter.ofPattern("HH:mm"))}")
                                  .append(event.isOnlineMeeting ? " (Online)" : "")
                                  .append(event.responseStatus ? " - Status: ${event.responseStatus}" : "")
                                  .append(" (starts in ${event.getMinutesToStart() + 1} minutes)") // + 1 minute to account for current time
                                  .append("\n")
                    }
                }
                
                // Show events beyond tomorrow
                if (!beyondTomorrowEvents.isEmpty()) {
                    displayText.append("\n-- LATER MEETINGS --\n\n")
                    beyondTomorrowEvents.take(10).each { CalendarEvent event ->
                        displayText.append("  • ${event.subject} at ${event.startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
                                  .append(event.responseStatus ? " - Status: ${event.responseStatus}" : "")
                                  .append(" (starts in ${event.getMinutesToStart()} minutes)")
                                  .append("\n")
                    }
                    
                    if (beyondTomorrowEvents.size() > 10) {
                        displayText.append("  • ...and ${beyondTomorrowEvents.size() - 10} more later events\n")
                    }
                }
            }
        }
        
        // Update UI on the EDT and ensure scroll to top
        final String finalText = displayText.toString()
        SwingUtilities.invokeLater({
            eventsTextArea.setText(finalText)
            eventsTextArea.setCaretPosition(0) // Scroll back to top
            eventsTextArea.scrollRectToVisible(new Rectangle(0, 0)) // Double ensure scroll to top
        } as Runnable)
    }
    
    /**
     * Updates the events list in the UI
     * @param events List of CalendarEvent objects to display
     */
    private void updateEventsList(List<CalendarEvent> events) {
        if (events == null || events.isEmpty()) {
            eventsTextArea.setText("No upcoming calendar events found.")
            return
        }
        
        // Clear existing text
        eventsTextArea.setText("")
        
        // Sort events by time
        events = events.sort { CalendarEvent a, CalendarEvent b ->
            a.startTime <=> b.startTime
        }
        
        // Format each event
        StringBuilder sb = new StringBuilder()
        sb.append("Upcoming meetings (next 24 hours):\n\n")
        
        ZoneId preferredZone = null
        try {
            if (configManager.preferredTimezone) {
                preferredZone = ZoneId.of(configManager.preferredTimezone)
            }
        } catch (Exception e) {
            System.err.println("Error parsing preferred timezone: " + e.getMessage())
        }
        
        ZoneId displayZone = preferredZone ?: ZoneId.systemDefault()
        
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("hh:mm a")
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("E, MMM d")
        
        String currentDate = null
        
        for (CalendarEvent event : events) {
            try {
                // Convert to preferred timezone if set
                ZonedDateTime startTime = event.startTime
                ZonedDateTime endTime = event.endTime
                
                if (preferredZone != null) {
                    startTime = event.startTime.withZoneSameInstant(preferredZone)
                    endTime = event.endTime.withZoneSameInstant(preferredZone)
                }
                
                // Format date header if this is a new date
                String eventDate = dateFormatter.format(startTime)
                if (currentDate == null || !currentDate.equals(eventDate)) {
                    if (currentDate != null) {
                        sb.append("\n")
                    }
                    sb.append("=== ").append(eventDate).append(" ===\n")
                    currentDate = eventDate
                }
                
                // Format the event details
                sb.append(timeFormatter.format(startTime))
                    .append(" - ")
                    .append(timeFormatter.format(endTime))
                    .append(": ")
                    .append(event.subject)
                
                // Add location if available
                if (event.location && !event.location.isEmpty() && event.location != "null") {
                    sb.append(" (").append(event.location).append(")")
                }
                
                // Add calendar name if multiple calendars
                if (event.calendarName && event.calendarName != "Calendar" && event.calendarName != "null") {
                    sb.append(" [").append(event.calendarName).append("]")
                }
                
                // Add online meeting info
                if (event.isOnlineMeeting) {
                    sb.append(" [Online Meeting]")
                }
                
                sb.append("\n")
            } catch (Exception e) {
                // If one event has issues, log it but continue with the others
                System.err.println("Error formatting event: " + e.getMessage())
                sb.append("[Error formatting event: ").append(event.subject ?: "unknown").append("]\n")
            }
        }
        
        // Update the text area
        eventsTextArea.setText(sb.toString())
        eventsTextArea.setCaretPosition(0) // Scroll to top
    }
    
    /**
     * Check for events that need alerts
     */
    private void checkForEventAlerts(List<CalendarEvent> events) {
        // Debug log
        System.out.println("Checking ${events.size()} events for alerts...")

        // Check each event for alerts
        List<CalendarEvent> eventsToAlert = []
        for (CalendarEvent event : events) {
            // Skip / cleanup events that have already ended
            if (event.hasEnded()) {
                System.out.println("${event.subject} Skipping: Event has ended")
                alertedEventIds.remove(event.id) // Remove from list if already ended
                continue
            }
            int minutesToStart = event.getMinutesToStart() + 1 // +1 to account for current time
            System.out.println("${event.subject} Minutes to start: ${minutesToStart}")
            // Skip events we've already alerted for
            if (alertedEventIds.contains(event.id)) {
                System.out.println("${event.subject} Skipping: Already alerted for this event")
                continue
            }
            // Alert for events about to start
            if (minutesToStart <= configManager.alertMinutes && minutesToStart >= -1) {
                System.out.println("${event.subject} Alerting")
                eventsToAlert.add(event)
            }
        }
        if (!eventsToAlert.isEmpty()) {
            System.out.println("  *** Triggering alert for events: " + eventsToAlert.collect { it.subject }.join(", "))

            // Mark events as alerted FIRST to avoid duplicate alerts if any component fails
            for (CalendarEvent event : eventsToAlert) {
                alertedEventIds.add(event.id)
            }

            // Prepare alert text (compute once, use in multiple components)
            String bannerText
            String notificationTitle
            String notificationMessage
            if (eventsToAlert.size() == 1) {
                CalendarEvent ev = eventsToAlert[0]
                bannerText = "Upcoming meeting: " + ev.subject
                notificationTitle = "Upcoming meeting"
                notificationMessage = "${ev.subject} in ${ev.getMinutesToStart() + 1} minute(s)"
            } else {
                bannerText = "${eventsToAlert.size()} upcoming meetings starting soon"
                notificationTitle = "Upcoming meetings"
                notificationMessage = "${eventsToAlert.size()} meetings starting soon"
            }

            // ========== ALERT COMPONENT 1: Audio beep (highest priority, runs on separate thread) ==========
            // Run on separate thread to ensure it fires even if EDT is busy or blocked
            new Thread({
                try {
                    int count = Math.max(0, configManager.getAlertBeepCount())
                    System.out.println("AlertBeep: Starting beep sequence (count: ${count})")
                    int successCount = 0
                    for (int i = 0; i < count; i++) {
                        try {
                            Toolkit.getDefaultToolkit().beep()
                            successCount++
                            // Sleep between beeps (not after the last one)
                            // Keep timing-critical section free of I/O operations
                            if (i < count - 1) {
                                Thread.sleep(250)
                            }
                        } catch (InterruptedException ie) {
                            System.err.println("AlertBeep: Interrupted during beep sequence")
                            Thread.currentThread().interrupt()
                            break
                        } catch (Exception ex) {
                            System.err.println("AlertBeep: Error during beep ${i + 1}: " + ex.getMessage())
                        }
                    }
                    System.out.println("AlertBeep: Beep sequence completed (${successCount}/${count} beeps)")
                } catch (Exception ex) {
                    System.err.println("AlertBeep: Error in beep sequence: " + ex.getMessage())
                }
            }, "AlertBeepThread").start()

            // ========== ALERT COMPONENT 2: Status label update (EDT required) ==========
            SwingUtilities.invokeLater({
                try {
                    statusLabel.setText("Status: Alerting for ${eventsToAlert.size()} event(s)")
                    System.out.println("AlertStatus: Status label updated")
                } catch (Exception ex) {
                    System.err.println("AlertStatus: Error updating status label: " + ex.getMessage())
                }
            } as Runnable)

            // ========== ALERT COMPONENT 3: Banner (EDT required, isolated) ==========
            SwingUtilities.invokeLater({
                try {
                    showAlertBanner(bannerText)
                    System.out.println("AlertBanner: Banner shown successfully")
                } catch (Exception ex) {
                    System.err.println("AlertBanner: Error showing banner: " + ex.getMessage())
                    ex.printStackTrace()
                }
            } as Runnable)

            // ========== ALERT COMPONENT 4: Tray notification (EDT required, isolated) ==========
            SwingUtilities.invokeLater({
                try {
                    showTrayNotification(notificationTitle, notificationMessage, TrayIcon.MessageType.INFO)
                    System.out.println("AlertTray: Tray notification shown successfully")
                } catch (Exception ex) {
                    System.err.println("AlertTray: Error showing tray notification: " + ex.getMessage())
                }
            } as Runnable)

            // ========== ALERT COMPONENT 5: Screen flash (separate thread, isolated) ==========
            new Thread({
                try {
                    System.out.println("ScreenFlasher: Starting flashMultiple for ${eventsToAlert.size()} events")
                    screenFlasher.flashMultiple(eventsToAlert)
                    System.out.println("ScreenFlasher: Finished flashMultiple")
                } catch (Exception ex) {
                    System.err.println("ScreenFlasher: Exception during flashMultiple: " + ex.getMessage())
                    ex.printStackTrace()
                }
            }, "ScreenFlasherThread").start()
        }

        // Check token validity after alerting, and prompt if needed
        if (!outlookClient.hasValidToken()) {
            System.out.println("Token is invalid or expired. Prompting for new token.");
            // Only show the token dialog if not already active
            if (!isTokenDialogActive) {
                SwingUtilities.invokeLater({
                    promptForTokens(configManager.getSignInUrl())
                } as Runnable)
            }
        }

        // Clean up alerted events list periodically
        if (alertedEventIds.size() > 100) {
            System.out.println("Clearing alertedEventIds list (size > 100)")
            alertedEventIds.clear()
        }
    }


    /**
     * Check for alerts using the cached calendar events
     * This runs more frequently but doesn't make API calls
     */
    private void checkAlertsFromCache() {
        // Since this runs on a background thread, wrap everything in try-catch
        try {
            System.out.println("\n=== Checking Alerts (using cached events) ===")
            
            // Check if refresh needed (more than 4 hour since last refresh)
            // this is to capture if device has been asleep for some time (ie weekend)
            if (lastCalendarRefresh == null || 
                ZonedDateTime.now().minusHours(4).isAfter(lastCalendarRefresh)) {
                System.out.println("More than 4 hour since last refresh, triggering calendar update")
                refreshCalendarEvents()
                return
            }
            
            // Make sure cached events exist
            if (lastFetchedEvents == null || lastFetchedEvents.isEmpty()) {
                System.out.println("No cached events to check")
                return
            }
            
            // Calculate updated times for events but reuse other data
            final List<CalendarEvent> updatedEvents = lastFetchedEvents.collect { event ->
                // MinutesToStart will be recalculated when accessed since it uses current time
                return event
            }
            
            // Update UI and check alerts on the EDT since we're accessing Swing components
            SwingUtilities.invokeLater({
                try {
                    // Update the UI with recalculated times
                    updateEventsDisplay(updatedEvents)
                    
                    // Check for alerts
                    checkForEventAlerts(updatedEvents)
                } catch (Exception e) {
                    System.err.println("Error in EDT processing cached alerts: " + e.getMessage())
                    e.printStackTrace()
                }
            } as Runnable)
            
        } catch (Exception e) {
            System.err.println("Error checking alerts from cache: " + e.getMessage())
            e.printStackTrace()
        }
    }
    
    /**
     * Show settings dialog
     */
    private synchronized void showSettingsDialog() {
        if (isTokenDialogActive) {
            System.out.println("Cannot display settings dialog while token dialog is active.");
            return;
        }

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

            // Request user attention on macOS
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                try {
                    Taskbar taskbar = Taskbar.getTaskbar();
                    // Fallback: Print a message to the console to indicate user attention is needed
                    // System.out.println("[INFO] Requesting user attention for settings dialog.");
                } catch (UnsupportedOperationException | SecurityException ex) {
                    System.err.println("Unable to request user attention: " + ex.getMessage());
                }

                // Set window always-on-top temporarily to help with focus
                settingsDialog.setAlwaysOnTop(true)
                
                // First focus attempt
                settingsDialog.toFront()
                settingsDialog.requestFocusInWindow()
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
    void setTimezone(String timezone) {
        if (timezone == null || timezone.isEmpty()) {
            return
        }
        
        try {
            // Validate the timezone ID
            ZoneId zoneId = ZoneId.of(timezone)
            configManager.updatePreferredTimezone(timezone)
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                this,
                "Invalid timezone: " + timezone + "\nError: " + e.getMessage(),
                "Timezone Error",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }
    
    /**
     * Stop and restart all schedulers
     */
    /**
     * Restart all schedulers with the current configuration settings
     * This method is called by the SettingsDialog when settings are saved
     */
    void restartSchedulers() {
        System.out.println("[OutlookAlerterUI] Restarting schedulers...")

        stopSchedulers();

        // Reinitialize schedulers to avoid using terminated executors
        // Use daemon threads to ensure they don't prevent JVM shutdown
        alertScheduler = Executors.newScheduledThreadPool(1, { r ->
            Thread t = new Thread(r, "AlertScheduler")
            t.setDaemon(true)
            return t
        })

        calendarScheduler = Executors.newScheduledThreadPool(1, { r ->
            Thread t = new Thread(r, "CalendarScheduler")
            t.setDaemon(true)
            return t
        })

        // Recreate the screen flasher to pick up new configuration
        screenFlasher = ScreenFlasherFactory.createScreenFlasher();
        
        startSchedulers();

        System.out.println("[OutlookAlerterUI] Schedulers restarted successfully")
    }

    /**
     * Start periodic schedulers for calendar refresh and alerts
     */
    private void startSchedulers() {
        if (schedulersRunning) {
            System.out.println("Schedulers already running");
            return;
        }

        int resyncIntervalSeconds = configManager.getResyncIntervalMinutes() * 60;

        System.out.println("Starting schedulers... Resync interval: ${resyncIntervalSeconds} seconds");

        // Schedule periodic calendar data refresh
        calendarScheduler.scheduleAtFixedRate(
            { refreshCalendarEvents() } as Runnable,
            resyncIntervalSeconds,
            resyncIntervalSeconds,
            TimeUnit.SECONDS
        );

        // Schedule alert checks (every minute)
        alertScheduler.scheduleAtFixedRate(
            { checkAlertsFromCache() } as Runnable,
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
        System.out.println("Stopping schedulers...");

        if (!schedulersRunning) {
            System.out.println("Schedulers not running");
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
                System.out.println("Forcing shutdown of calendar scheduler");
                List<Runnable> pending = calendarScheduler.shutdownNow();
                if (pending != null && !pending.isEmpty()) {
                    System.out.println("Cancelled " + pending.size() + " pending calendar tasks");
                }
            }
            if (alertScheduler != null && !alertScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                System.out.println("Forcing shutdown of alert scheduler");
                List<Runnable> pending = alertScheduler.shutdownNow();
                if (pending != null && !pending.isEmpty()) {
                    System.out.println("Cancelled " + pending.size() + " pending alert tasks");
                }
            }
        } catch (InterruptedException e) {
            System.err.println("Schedulers interrupted during shutdown: " + e.getMessage());
            if (calendarScheduler != null) {
                calendarScheduler.shutdownNow();
            }
            if (alertScheduler != null) {
                alertScheduler.shutdownNow();
            }
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Error stopping schedulers: " + e.getMessage());
            e.printStackTrace();
            // Force shutdown on any error
            if (calendarScheduler != null) {
                try { calendarScheduler.shutdownNow(); } catch (Exception ignored) {}
            }
            if (alertScheduler != null) {
                try { alertScheduler.shutdownNow(); } catch (Exception ignored) {}
            }
        }

        schedulersRunning = false;
        System.out.println("Schedulers stopped");
    }

    /**
     * Activate window and request user attention, with special handling for macOS
     */
    @CompileStatic
    private void activateWindow() {
        SwingUtilities.invokeLater({
            try {
                setVisible(true)
                setExtendedState(JFrame.NORMAL)
                
                // On macOS, we need special handling to ensure the window comes to front
                if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                    // Set window always-on-top temporarily to help with focus
                    setAlwaysOnTop(true)
                    
                    // First focus attempt
                    toFront()
                    requestFocusInWindow()
                    
                    // Request user attention via Taskbar API
                    Timer attentionTimer = new Timer(100, new ActionListener() {
                        int attempts = 0
                        @Override
                        void actionPerformed(ActionEvent e) {
                            try {
                                Class<?> taskbarClass = Class.forName("java.awt.Taskbar")
                                Object taskbar = taskbarClass.getMethod("getTaskbar").invoke(null)
                                // Fallback: Print a message to the console to indicate user attention is needed
                                // System.out.println("[INFO] Requesting user attention for application window.");
                                
                                attempts++
                                if (attempts >= 3) {
                                    ((Timer)e.getSource()).stop()
                                }
                            } catch (Exception ex) {
                                System.err.println("Could not request macOS user attention: " + ex.getMessage())
                                ((Timer)e.getSource()).stop()
                            }
                        }
                    })
                    attentionTimer.setInitialDelay(0)
                    attentionTimer.start()
                    
                    // Multiple focus requests with delays to ensure window stays on top
                    Timer focusTimer = new Timer(50, { e ->
                        toFront()
                        requestFocusInWindow()
                    })
                    focusTimer.setRepeats(false)
                    focusTimer.start()
                    
                    // Final focus request and cleanup
                    Timer finalFocusTimer = new Timer(200, { e ->
                        toFront()
                        requestFocusInWindow()
                        setAlwaysOnTop(false)  // Remove always-on-top after final focus
                    })
                    finalFocusTimer.setRepeats(false)
                    finalFocusTimer.start()
                    
                } else {
                    // For non-macOS platforms, simple activation is enough
                    toFront()
                    requestFocusInWindow()
                }
            } catch (Exception e) {
                System.err.println("Error during window activation: " + e.getMessage())
                e.printStackTrace()
            }
        } as Runnable)
    }

    /**
     * Shows and activates the window if it's not already visible
     */
    @CompileStatic
    private void showAndActivateWindow() {
        SwingUtilities.invokeLater({
            // Only try to activate if window is not already visible
            if (!isVisible()) {
                activateWindow()
            }
        } as Runnable)
    }

    /**
     * Check if it's safe to show UI dialogs
     * Prevents showing dialogs immediately after system wake which can cause EDT deadlock
     */
    private boolean isSafeToShowUI() {
        long timeSinceWake = System.currentTimeMillis() - lastSystemWakeTime
        if (timeSinceWake < 10000) {
            println "UI: Delaying UI display - system recently woke (${timeSinceWake}ms ago)"
            return false
        }
        return true
    }

    /**
     * Check if EDT is responsive
     * @return true if EDT is responsive, false otherwise
     */
    private boolean isEDTResponsive() {
        try {
            final boolean[] completed = [false] as boolean[]
            final CountDownLatch latch = new CountDownLatch(1)

            SwingUtilities.invokeLater({
                completed[0] = true
                latch.countDown()
            } as Runnable)

            // Wait up to 2 seconds for EDT to respond
            boolean responded = latch.await(2, TimeUnit.SECONDS)
            return responded && completed[0]
        } catch (Exception e) {
            println "EDT responsiveness check failed: ${e.message}"
            return false
        }
    }

    /**
     * Update the last system wake time
     * Should be called when system wake is detected
     */
    void updateLastWakeTime() {
        lastSystemWakeTime = System.currentTimeMillis()
        println "UI: Last system wake time updated: ${lastSystemWakeTime}"
    }

    /**
     * Prompt the user for tokens using the SimpleTokenDialog.
     * @param signInUrl The URL for signing in.
     * @return A map containing the tokens, or null if the user cancels.
     */
    Map<String, String> promptForTokens(String signInUrl) {
        try {
            // Check if it's safe to show UI
            if (!isSafeToShowUI()) {
                println "UI: Delaying token dialog - system not ready"
                // Schedule retry after delay
                Timer retryTimer = new Timer(2000, { e ->
                    SwingUtilities.invokeLater({
                        if (isSafeToShowUI()) {
                            promptForTokens(signInUrl)
                        }
                    } as Runnable)
                    (e.source as Timer).stop()
                } as ActionListener)
                retryTimer.setRepeats(false)
                retryTimer.start()
                return null
            }

            // Check EDT responsiveness
            if (!isEDTResponsive()) {
                println "UI: EDT is not responsive, cannot show token dialog"
                return null
            }

            isTokenDialogActive = true
            updateIcons(true)  // Show invalid token state
            
            // Make sure we have a valid sign-in URL, default to Microsoft Graph URL if not
            if (signInUrl == null || signInUrl.trim().isEmpty()) {
                signInUrl = SimpleTokenDialog.DEFAULT_GRAPH_URL
                println "UI mode: Using default sign-in URL: ${signInUrl}"
            }
            
            SimpleTokenDialog dialog = SimpleTokenDialog.getInstance(signInUrl)
            dialog.show()
            Map<String, String> tokens = dialog.getTokens()
            
            println "UI mode: Token dialog returned: ${tokens != null ? 'tokens' : 'null (cancelled)'}"
            
            // If tokens were obtained and certificate validation setting might have changed,
            // ensure the HTTP client is updated
            if (tokens != null && outlookClient != null) {
                if (tokens.containsKey("ignoreCertValidation")) {
                    // Parse the string value explicitly by comparing to "true"
                    boolean ignoreCertValidation = "true".equalsIgnoreCase(tokens.ignoreCertValidation)
                    println "UI mode: Certificate validation from dialog: " + 
                           (ignoreCertValidation ? "disabled" : "enabled") + 
                           " (Raw value: '" + tokens.ignoreCertValidation + "')"
                    outlookClient.updateCertificateValidation(ignoreCertValidation)
                } else {
                    println "UI mode: No certificate validation setting in tokens, updating HTTP client anyway"
                    outlookClient.updateHttpClient()
                }
            } else {
                // Only update status if dialog was cancelled or failed
                SwingUtilities.invokeLater({
                    statusLabel.setText("Status: Authentication cancelled")
                    refreshButton.setEnabled(true)
                } as Runnable)
            }
            
            // Ensure the dialog is properly disposed
            try {
                if (dialog != null) {
                    dialog.dispose()
                }
            } catch (Exception e) {
                println "UI mode: Error disposing token dialog: ${e.message}"
            }
            
            return tokens
        } catch (Exception e) {
            println "UI mode: Error in promptForTokens: ${e.message}"
            e.printStackTrace()
            return null
        } finally {
            isTokenDialogActive = false
        }
    }
    
    /**
     * Show a prominent notification banner at the top of the screen when an alert fires.
     */
    private void showAlertBanner(String message) {
        try {
            SwingUtilities.invokeLater({
                // Close any existing banner first
                if (alertBannerWindow != null) {
                    alertBannerWindow.dispose()
                    alertBannerWindow = null
                }

                // Create an undecorated always-on-top window
                alertBannerWindow = new JWindow(this)

                Color bg = new Color(220, 0, 0)
                Color fg = Color.WHITE

                JPanel panel = new JPanel(new BorderLayout())
                panel.setBackground(bg)

                JLabel label = new JLabel(message, SwingConstants.CENTER)
                label.setForeground(fg)
                label.setFont(label.getFont().deriveFont(Font.BOLD, 18f))
                label.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20))

                panel.add(label, BorderLayout.CENTER)
                alertBannerWindow.setContentPane(panel)

                // Position banner at top center of the primary screen
                Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice()
                        .getDefaultConfiguration()
                        .getBounds()

                int width = (int) Math.min(bounds.width as double, 800d)
                int height = (int) (panel.getPreferredSize().height + 10)
                int x = (int) (bounds.x + (bounds.width - width) / 2)
                int y = (int) bounds.y

                alertBannerWindow.setBounds(x, y, width, height)
                alertBannerWindow.setAlwaysOnTop(true)

                // Show banner
                alertBannerWindow.setVisible(true)

                // Auto-hide after a few seconds
                Timer hideTimer = new Timer(5000, { e ->
                    if (alertBannerWindow != null) {
                        alertBannerWindow.dispose()
                        alertBannerWindow = null
                    }
                } as ActionListener)
                hideTimer.setRepeats(false)
                hideTimer.start()
            } as Runnable)
        } catch (Exception e) {
            System.err.println("Error showing alert banner: " + e.getMessage())
        }
    }

    /**
     * Show the log viewer dialog
     * Creates a new one if none exists or reuses the existing one
     */
    private synchronized void showLogsDialog() {
        if (logViewer == null || !logViewer.isDisplayable()) {
            // Create and show the new log viewer dialog
            logViewer = new LogViewer(this)
            
            // Clean up the reference when closed
            logViewer.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    logViewer = null
                }
            })
            
            // Display the dialog
            logViewer.setVisible(true)
            
            // Request user attention on macOS
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                try {
                    Taskbar taskbar = Taskbar.getTaskbar()
                    taskbar.requestUserAttention(true, false)
                } catch (Exception e) {
                    System.out.println("Could not request user attention: " + e.getMessage())
                }
            }
        } else {
            // Bring to front if it already exists
            logViewer.toFront()
            logViewer.repaint()
        }
    }

    // Simple helper to allow external/diagnostic triggering of the banner
    void debugShowAlertBanner(String message) {
        showAlertBanner(message ?: "Debug meeting alert")
    }
}
