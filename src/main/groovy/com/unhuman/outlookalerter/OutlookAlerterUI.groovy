package com.unhuman.outlookalerter

import groovy.transform.CompileStatic

import javax.swing.*
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.List
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.awt.Taskbar;

/**
 * Main application window for Outlook Alerter
 * Provides a user interface for monitoring upcoming meetings
 */
@CompileStatic
class OutlookAlerterUI extends JFrame {
    // Time constants
    private static final int POLLING_INTERVAL_MINUTES = 1
    private static final int CALENDAR_REFRESH_INTERVAL_MINUTES = 60  // Hourly calendar refresh
    
    // Components
    private final ConfigManager configManager
    private final OutlookClient outlookClient
    private final ScreenFlasher screenFlasher
    
    // UI Components
    private JTextArea eventsTextArea
    private JButton refreshButton
    private JLabel statusLabel
    private JLabel lastUpdateLabel
    private TrayIcon trayIcon
    private SystemTray systemTray

    // Schedulers for periodic tasks
    private final ScheduledExecutorService alertScheduler
    private final ScheduledExecutorService calendarScheduler
    private volatile boolean schedulersRunning = false
    
    // Track which events we've already alerted for
    private final Set<String> alertedEventIds = new java.util.concurrent.ConcurrentHashMap<String, Boolean>().keySet(true)
    
    // Store last fetched events to avoid frequent API calls
    private List<CalendarEvent> lastFetchedEvents = []

    // Track the current icon state
    private Boolean currentIconInvalidState = null

    // Track settings dialog instance
    private JDialog settingsDialog = null;

    // Added a flag to track if the token dialog is active
    private boolean isTokenDialogActive = false;

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
        
        // Initialize schedulers
        this.alertScheduler = Executors.newScheduledThreadPool(1)
        this.calendarScheduler = Executors.newScheduledThreadPool(1)
        
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
     * Start the UI and begin authentication process
     */
    void start() {
        SwingUtilities.invokeLater({
            try {
                // Set up window and components but don't show it
                initializeUI()
                setVisible(false)  // Start hidden
                
                // Show initial status
                statusLabel.setText("Status: Starting up...")
                
                // Run authentication in a background thread to avoid blocking UI
                Thread authThread = new Thread({
                    try {
                        System.out.println("Starting authentication process on background thread")
                        
                        // Attempt authentication - this will show the token dialog if needed
                        final boolean authenticated = outlookClient.authenticate()
                        System.out.println("Authentication process completed, result: " + authenticated)

                        // Apply icon updates
                        updateIcons(!authenticated)
                        
                        // Switch back to EDT for UI updates
                        SwingUtilities.invokeLater({
                            try {
                                // Restore cursor
                                setCursor(Cursor.getDefaultCursor())
                                
                                if (authenticated) {
                                    statusLabel.setText("Status: Authenticated successfully")
                                    
                                    // Stop any existing schedulers
                                    stopSchedulers()
                                    
                                    // Refresh calendar immediately
                                    refreshCalendarEvents()
                                    
                                    // Start periodic tasks
                                    startSchedulers()
                                    
                                    // Keep running in background after successful authentication
                                    setVisible(false)
                                } else {
                                    statusLabel.setText("Status: Authentication Failed")
                                    JOptionPane.showMessageDialog(
                                        this,
                                        "Failed to authenticate with Outlook. Please check your configuration and try again.",
                                        "Authentication Error",
                                        JOptionPane.ERROR_MESSAGE
                                    )
                                }
                            } catch (Exception e) {
                                System.err.println("Error in EDT after authentication: " + e.getMessage())
                                e.printStackTrace()
                                JOptionPane.showMessageDialog(
                                    this,
                                    "Error during authentication: " + e.getMessage() + "\n\nPlease check your configuration and try again.",
                                    "Authentication Error",
                                    JOptionPane.ERROR_MESSAGE
                                )
                            }
                        } as Runnable)
                    } catch (OutlookClient.AuthenticationCancelledException ace) {
                        System.out.println("Authentication was cancelled: " + ace.getMessage() + " (reason: " + ace.getReason() + ")")
                    } catch (Exception e) {
                        System.err.println("Error in authentication thread: " + e.getMessage())
                        e.printStackTrace()
                        SwingUtilities.invokeLater({
                            JOptionPane.showMessageDialog(
                                this,
                                "Error during authentication: " + e.getMessage() + "\n\nPlease check your configuration and try again.",
                                "Authentication Error",
                                JOptionPane.ERROR_MESSAGE
                            )
                        } as Runnable)
                    }
                })
                
                // Set thread name for debugging
                authThread.setName("Outlook-Alerter-Auth-Thread")
                // Start the authentication thread
                authThread.start()
                
            } catch (Exception e) {
                System.err.println("Error starting UI: " + e.getMessage())
                e.printStackTrace()
                JOptionPane.showMessageDialog(
                    null,
                    "Error starting application: " + e.getMessage(),
                    "Application Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        } as Runnable)
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
                            updateEventsList(events)
                            
                            // Also update our cache for alert checking
                            lastFetchedEvents = events
                            
                            // Update status to successful
                            String now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm:ss a"))
                            statusLabel.setText("Status: Ready")
                            lastUpdateLabel.setText("Last update: " + now)
                            refreshButton.setEnabled(true)
                            
                            // Update window title to indicate number of upcoming events
                            setTitle("Outlook Alerter - " + events.size() + " upcoming meetings")
                            
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
            
            // Ensure all meetings are properly sorted by start time
            nextTimeEvents.sort { event -> event.getMinutesToStart() }
            
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
                          .append(" (starts in ${event.getMinutesToStart()} minutes)")
                          .append("\n")
            }
            
            // List subsequent events if there are any
            List<CalendarEvent> laterEvents = upcomingEvents.findAll { event -> 
                !nextTimeEvents.contains(event)
            }
            
            if (!laterEvents.isEmpty()) {
                displayText.append("\n-- LATER MEETINGS --\n\n")
                laterEvents.take(10).each { CalendarEvent event ->
                    displayText.append("  • ${event.subject} at ${event.startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
                              .append(event.responseStatus ? " - Status: ${event.responseStatus}" : "")
                              .append(" (starts in ${event.getMinutesToStart()} minutes)")
                              .append("\n")
                }
                
                if (laterEvents.size() > 10) {
                    displayText.append("  • ...and ${laterEvents.size() - 10} more later events\n")
                }
            }
        }
        
        // Update UI on the EDT
        final String finalText = displayText.toString()
        SwingUtilities.invokeLater({
            eventsTextArea.setText(finalText)
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

        // Check token validity before alerting
        if (!outlookClient.hasValidToken()) {
            System.out.println("Token is invalid or expired. Prompting for new token and aborting alert.");
            // Only show the token dialog if not already active
            if (!isTokenDialogActive) {
                SwingUtilities.invokeLater({
                    promptForTokens(configManager.getSignInUrl())
                } as Runnable)
            }
            return;
        }

        // Check each event for alerts
        List<CalendarEvent> eventsToAlert = []
        for (CalendarEvent event : events) {
            // Debug log
            System.out.println("Checking event: ${event.subject}")
            // Skip events that have already ended
            if (event.hasEnded()) {
                System.out.println("  Skipping: Event has ended")
                continue
            }
            int minutesToStart = event.getMinutesToStart()
            System.out.println("  Minutes to start: ${minutesToStart}")
            // Skip events we've already alerted for
            if (alertedEventIds.contains(event.id)) {
                System.out.println("  Skipping: Already alerted for this event")
                continue
            }
            // Alert for events about to start
            if (minutesToStart <= configManager.alertMinutes && minutesToStart >= -1) {
                eventsToAlert.add(event)
            }
        }
        if (!eventsToAlert.isEmpty()) {
            System.out.println("  *** Triggering alert for events: " + eventsToAlert.collect { it.subject }.join(", "))
            SwingUtilities.invokeLater({
                statusLabel.setText("Status: Alerting for ${eventsToAlert.size()} event(s)")
            } as Runnable)
            // Flash the screen for all events at once
            screenFlasher.flashMultiple(eventsToAlert)
            // Show system tray notification if available (optional: keep per-event or aggregate)
            for (CalendarEvent event : eventsToAlert) {
                alertedEventIds.add(event.id)
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
            settingsDialog = new JDialog(this, "Settings", true);
            settingsDialog.setLayout(new BorderLayout());

            JPanel formPanel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(5, 5, 5, 5);

            // Timezone setting
            gbc.gridx = 0;
            gbc.gridy = 0;
            formPanel.add(new JLabel("Timezone:"), gbc);

            String currentTimezone = configManager.preferredTimezone ?: ZonedDateTime.now().getZone().toString();
            JTextField timezoneField = new JTextField(currentTimezone, 20);
            gbc.gridx = 1;
            gbc.gridy = 0;
            formPanel.add(timezoneField, gbc);

            // Alert minutes setting
            gbc.gridx = 0;
            gbc.gridy = 1;
            formPanel.add(new JLabel("Alert minutes before meeting:"), gbc);

            SpinnerModel spinnerModel = new SpinnerNumberModel(
                configManager.alertMinutes, // current
                1,  // min
                30, // max
                1   // step
            );
            JSpinner alertMinutesSpinner = new JSpinner(spinnerModel);
            gbc.gridx = 1;
            gbc.gridy = 1;
            formPanel.add(alertMinutesSpinner, gbc);

            // Okta SSO URL
            gbc.gridx = 0;
            gbc.gridy = 2;
            formPanel.add(new JLabel("Sign-in URL:"), gbc);

            JTextField signInUrlField = new JTextField(configManager.signInUrl ?: "", 20);
            gbc.gridx = 1;
            gbc.gridy = 2;
            formPanel.add(signInUrlField, gbc);

            // Note: SSL Certificate Validation setting moved to token dialog
            gbc.gridwidth = 1;

            // Button panel
            JPanel buttonPanel = new JPanel();
            JButton saveButton = new JButton("Save");
            saveButton.addActionListener(new ActionListener() {
                @Override
                void actionPerformed(ActionEvent e) {
                    // Save settings
                    String timezone = timezoneField.getText().trim();
                    String signInUrl = signInUrlField.getText().trim();
                    int alertMinutes = (Integer)alertMinutesSpinner.getValue();

                    try {
                        if (!timezone.isEmpty()) {
                            java.time.ZoneId.of(timezone); // Validate timezone
                            configManager.updatePreferredTimezone(timezone);
                        }

                        if (!signInUrl.isEmpty()) {
                            configManager.updateSignInUrl(signInUrl);
                        }

                        configManager.updateAlertMinutes(alertMinutes);

                        settingsDialog.dispose();
                        settingsDialog = null;
                        JOptionPane.showMessageDialog(OutlookAlerterUI.this, "Settings saved successfully", "Success", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(settingsDialog, "Invalid settings: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            });

            JButton cancelButton = new JButton("Cancel");
            cancelButton.addActionListener(new ActionListener() {
                @Override
                void actionPerformed(ActionEvent e) {
                    settingsDialog.dispose();
                    settingsDialog = null;
                }
            });

            buttonPanel.add(saveButton);
            buttonPanel.add(cancelButton);

            settingsDialog.add(formPanel, BorderLayout.CENTER);
            settingsDialog.add(buttonPanel, BorderLayout.SOUTH);

            settingsDialog.pack();
            settingsDialog.setLocationRelativeTo(this);
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
            }

            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
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
    private void restartSchedulers() {
        stopSchedulers()
        startSchedulers()
    }

    /**
     * Start periodic schedulers for calendar refresh and alerts
     */
    private void startSchedulers() {
        if (schedulersRunning) {
            System.out.println("Schedulers already running")
            return
        }

        System.out.println("Starting schedulers...")

        // Schedule periodic calendar data refresh (hourly)
        calendarScheduler.scheduleAtFixedRate(
            { refreshCalendarEvents() } as Runnable,
            CALENDAR_REFRESH_INTERVAL_MINUTES,
            CALENDAR_REFRESH_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )

        // Schedule frequent alert checks using cached events
        alertScheduler.scheduleAtFixedRate(
            { checkAlertsFromCache() } as Runnable,
            POLLING_INTERVAL_MINUTES,
            POLLING_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )

        schedulersRunning = true
        System.out.println("Schedulers started")
    }

    /**
     * Stop all schedulers
     */
    private void stopSchedulers() {
        System.out.println("Stopping schedulers...")

        if (!schedulersRunning) {
            System.out.println("Schedulers not running")
            return
        }

        calendarScheduler.shutdown()
        alertScheduler.shutdown()

        try {
            // Wait for schedulers to finish their current tasks
            if (!calendarScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                calendarScheduler.shutdownNow()
            }
            if (!alertScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                alertScheduler.shutdownNow()
            }
        } catch (InterruptedException e) {
            calendarScheduler.shutdownNow()
            alertScheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }

        schedulersRunning = false
        System.out.println("Schedulers stopped")
    }

    /**
     * Shutdown the application
     */
    void shutdown() {
        stopSchedulers()
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
     * Prompt the user for tokens using the SimpleTokenDialog.
     * @param signInUrl The URL for signing in.
     * @return A map containing the tokens, or null if the user cancels.
     */
    Map<String, String> promptForTokens(String signInUrl) {
        try {
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
                    boolean ignoreCertValidation = Boolean.valueOf(tokens.ignoreCertValidation)
                    println "UI mode: Certificate validation from dialog: " + 
                           (ignoreCertValidation ? "disabled" : "enabled")
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
}
