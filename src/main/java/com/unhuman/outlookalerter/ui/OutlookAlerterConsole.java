package com.unhuman.outlookalerter.ui;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.unhuman.outlookalerter.core.ConfigManager;
import com.unhuman.outlookalerter.core.OutlookClient;
import com.unhuman.outlookalerter.util.LogManager;
import com.unhuman.outlookalerter.util.LogCategory;
import com.unhuman.outlookalerter.model.CalendarEvent;
import com.unhuman.outlookalerter.util.ScreenFlasher;
import com.unhuman.outlookalerter.util.ScreenFlasherFactory;

/**
 * Console version of OutlookAlerter
 * This maintains the original console-based functionality
 */
public class OutlookAlerterConsole {
    // Time constants
    private static final int POLLING_INTERVAL_MINUTES = 1;
    
    // Components
    private final ConfigManager configManager;
    private final OutlookClient outlookClient;
    private final ScreenFlasher screenFlasher;
    
    // Scheduler for periodic tasks (daemon threads so JVM can exit if stop() fails)
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ConsoleScheduler");
            t.setDaemon(true);
            return t;
        }
    });
    
    // Track which events we've already alerted for
    private final Set<String> alertedEventIds = ConcurrentHashMap.newKeySet();
    
    /**
     * Constructs a new OutlookAlerterConsole
     */
    public OutlookAlerterConsole(String configPath) {
        // Initialize components
        this.configManager = new ConfigManager(configPath);
        this.configManager.loadConfiguration();
        
        // Load certificate validation setting from config
        boolean ignoreCertValidation = this.configManager.getDefaultIgnoreCertValidation();
        LogManager.getInstance().info(LogCategory.GENERAL, "Loaded certificate validation setting: " + (ignoreCertValidation ? "disabled" : "enabled"));
        
        this.outlookClient = new OutlookClient(configManager);
        this.screenFlasher = ScreenFlasherFactory.createScreenFlasher();
    }
    
    /**
     * Start the application
     * @param daemonMode Whether to run as a daemon (background service)
     */
    public void start(boolean daemonMode) {
        LogManager.getInstance().info(LogCategory.GENERAL, "Starting Outlook Alerter in console mode...");
        
        // Print timezone information
        printSystemTimezoneInfo();
        
        // Authenticate with Outlook/Microsoft
        if (!outlookClient.authenticate()) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Failed to authenticate with Outlook. Exiting.");
            System.exit(1);
        }
        
        LogManager.getInstance().info(LogCategory.DATA_FETCH, "Successfully authenticated with Outlook.");
        
        // Schedule periodic calendar checks
        scheduler.scheduleAtFixedRate(
            () -> checkForUpcomingMeetings(),
            0, // Start immediately
            POLLING_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        );
        
        if (daemonMode) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Running in daemon mode. Press Ctrl+C to exit.");
        } else {
            LogManager.getInstance().info(LogCategory.GENERAL, "Press Enter to exit.");
            try {
                System.in.read();
            } catch (IOException e) {
                LogManager.getInstance().error(LogCategory.GENERAL, "Error reading input: " + e.getMessage());
            }
            stop();
        }
    }
    
    /**
     * Print system timezone information to help diagnose timezone issues
     */
    private void printSystemTimezoneInfo() {
        LogManager.getInstance().info(LogCategory.GENERAL, "\n=============== SYSTEM TIMEZONE INFO ===============");
        LogManager.getInstance().info(LogCategory.GENERAL, "System default timezone: " + ZoneId.systemDefault());
        LogManager.getInstance().info(LogCategory.GENERAL, "Current time (system): " + ZonedDateTime.now());
        LogManager.getInstance().info(LogCategory.GENERAL, "Current time (UTC): " + ZonedDateTime.now(ZoneId.of("UTC")));
        LogManager.getInstance().info(LogCategory.GENERAL, "System timezone offset: " + ZonedDateTime.now().getOffset());
        LogManager.getInstance().info(LogCategory.GENERAL, "Available timezone IDs: " + ZoneId.getAvailableZoneIds().size() + " zones available");
        
        // Show configured timezone if set
        String preferredTimezone = configManager.getPreferredTimezone();
        if (preferredTimezone != null && !preferredTimezone.isEmpty()) {
            try {
                ZoneId configuredZone = ZoneId.of(preferredTimezone);
                LogManager.getInstance().info(LogCategory.GENERAL, "Configured timezone: " + preferredTimezone);
                LogManager.getInstance().info(LogCategory.GENERAL, "Current time (configured timezone): " + ZonedDateTime.now(configuredZone));
                LogManager.getInstance().info(LogCategory.GENERAL, "Configured timezone offset: " + ZonedDateTime.now(configuredZone).getOffset());
            } catch (Exception e) {
                LogManager.getInstance().warn(LogCategory.GENERAL, "Configured timezone (INVALID): " + preferredTimezone);
                LogManager.getInstance().error(LogCategory.GENERAL, "Error: " + e.getMessage());
            }
        } else {
            LogManager.getInstance().info(LogCategory.GENERAL, "No preferred timezone configured, using system default");
        }
        
        LogManager.getInstance().info(LogCategory.GENERAL, "===================================================");
    }
    
    /**
     * Stop the application
     */
    public void stop() {
        LogManager.getInstance().info(LogCategory.GENERAL, "Stopping Outlook Alerter...");
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LogManager.getInstance().info(LogCategory.GENERAL, "Outlook Alerter stopped.");
    }
    
    /**
     * Set the preferred timezone
     * @param timezone The timezone ID to use (e.g., "America/New_York")
     */
    public void setTimezone(String timezone) {
        if (timezone == null || timezone.isEmpty()) {
            LogManager.getInstance().info(LogCategory.GENERAL, "No timezone specified, using system default");
            return;
        }
        
        try {
            // Validate the timezone ID
            ZoneId zoneId = ZoneId.of(timezone);
            configManager.updatePreferredTimezone(timezone);
            LogManager.getInstance().info(LogCategory.GENERAL, "Timezone set to: " + timezone + " (" + ZonedDateTime.now(zoneId) + ")");
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Invalid timezone: " + timezone);
            LogManager.getInstance().error(LogCategory.GENERAL, "Error: " + e.getMessage());
            LogManager.getInstance().info(LogCategory.GENERAL, "Available timezones: " + ZoneId.getAvailableZoneIds().size() + " zones available");
        }
    }
    
    /**
     * Check for upcoming meetings and alert if needed
     */
    private void checkForUpcomingMeetings() {
        try {
            // Try all available methods for retrieving events
            List<CalendarEvent> calendarViewEvents = outlookClient.getUpcomingEventsUsingCalendarView();
            
            // Combine events, avoiding duplicates
            Map<String, CalendarEvent> combinedEventsMap = new HashMap<>();
            
            // Add calendar view events (will overwrite duplicates)
            for (CalendarEvent event : calendarViewEvents) {
                combinedEventsMap.put(event.getId(), event);
            }
            
            // Convert back to list and sort by start time for easier consumption
            List<CalendarEvent> combinedEvents = new ArrayList<>(combinedEventsMap.values());
            
            // Sort events by minutes to start (ascending) so closest events are first
            combinedEvents.sort((a, b) -> Integer.compare(a.getMinutesToStart(), b.getMinutesToStart()));
            
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "\n=========== CALENDAR UPDATE ===========");
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "Found " + combinedEvents.size() + " calendar events.");
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "====================================================");
            
            if (combinedEvents.isEmpty()) {
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "No upcoming events found.");
                return;
            }
            
            // Filter to include upcoming events and in-progress events
            List<CalendarEvent> relevantEvents = combinedEvents.stream().filter(event -> {
                int minutesToStart = event.getMinutesToStart();
                boolean isUpcoming = minutesToStart >= 0;  // Future event
                boolean isInProgress = event.isInProgress(); // Currently happening event
                
                if (!isUpcoming && !isInProgress) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "Filtering out past event: " + event.getSubject() + " (started " + (-minutesToStart) + " minutes ago, already ended)");
                    return false;
                }
                return true;
            }).collect(Collectors.toList());
            
            if (relevantEvents.isEmpty()) {
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "Note: No current or upcoming events found.");
                return;
            }
            
            // Sort events by minutes to start (ascending) so closest events are first
            relevantEvents.sort((a, b) -> Integer.compare(a.getMinutesToStart(), b.getMinutesToStart()));
            
            // Separate in-progress events from upcoming events
            List<CalendarEvent> inProgressEvents = relevantEvents.stream().filter(CalendarEvent::isInProgress).collect(Collectors.toList());
            List<CalendarEvent> upcomingEvents = relevantEvents.stream().filter(event -> !event.isInProgress()).collect(Collectors.toList());
            
            // Show in-progress events first
            if (!inProgressEvents.isEmpty()) {
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "\nCurrently in progress:");
                for (CalendarEvent event : inProgressEvents) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "  - " + event.getSubject() +
                          (event.isOnlineMeeting() ? " (Online)" : "") +
                          (event.getOrganizer() != null ? " - Organized by: " + event.getOrganizer() : "") +
                          (event.getResponseStatus() != null ? " - Status: " + event.getResponseStatus() : "") +
                          " (started " + (-event.getMinutesToStart()) + " minutes ago)");
                }
            }
            
            // Show upcoming events next, if any
            if (!upcomingEvents.isEmpty()) {
                // Get the minutes to start for the earliest upcoming event
                int earliestMinutesToStart = upcomingEvents.get(0).getMinutesToStart();
                
                // Determine which events should be in the "next meetings" section
                final int ems = earliestMinutesToStart;
                List<CalendarEvent> nextTimeEvents = upcomingEvents.stream().filter(event -> {
                    int minutesToStart = event.getMinutesToStart();
                    return (minutesToStart <= 60) || (minutesToStart <= ems + 5);
                }).collect(Collectors.toList());
                
                // Ensure all meetings are properly sorted by start time
                nextTimeEvents.sort((a, b) -> Integer.compare(a.getMinutesToStart(), b.getMinutesToStart()));
                
                // Include time information in the header
                ZonedDateTime now = ZonedDateTime.now();
                String displayTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                String displayZone = now.getZone().toString();
                
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "\nNext meetings at " + displayTime + " (" + displayZone + "):");
                for (CalendarEvent event : nextTimeEvents) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "  - " + event.getSubject() +
                          (event.isOnlineMeeting() ? " (Online)" : "") +
                          (event.getOrganizer() != null ? " - Organized by: " + event.getOrganizer() : "") +
                          (event.getResponseStatus() != null ? " - Status: " + event.getResponseStatus() : "") +
                          " (starts in " + event.getMinutesToStart() + " minutes)");
                }
                
                // List subsequent events if there are any
                List<CalendarEvent> laterEvents = upcomingEvents.stream().filter(event -> 
                    !nextTimeEvents.contains(event)
                ).collect(Collectors.toList());
                
                if (!laterEvents.isEmpty()) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "\nLater meetings:");
                    List<CalendarEvent> laterEventsToShow = laterEvents.subList(0, Math.min(5, laterEvents.size()));
                    for (CalendarEvent event : laterEventsToShow) {
                        LogManager.getInstance().info(LogCategory.DATA_FETCH, "  - " + event.getSubject() + " at " + event.getStartTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) +
                              (event.getResponseStatus() != null ? " - Status: " + event.getResponseStatus() : "") +
                              " (starts in " + event.getMinutesToStart() + " minutes)");
                    }
                    
                    if (laterEvents.size() > 5) {
                        LogManager.getInstance().info(LogCategory.DATA_FETCH, "  - ...and " + (laterEvents.size() - 5) + " more later events");
                    }
                }
            }
            
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "=============================================\n");
            
            // Check each event for alerts
            List<CalendarEvent> eventsToAlert = new ArrayList<>();
            for (CalendarEvent event : combinedEvents) {
                // Skip events that have already ended
                if (event.hasEnded()) {
                    continue;
                }
                int minutesToStart = event.getMinutesToStart();
                // Skip events we've already alerted for
                if (alertedEventIds.contains(event.getId())) {
                    continue;
                }
                // Alert for events about to start
                if (minutesToStart <= configManager.getAlertMinutes() && minutesToStart >= -1) {
                    eventsToAlert.add(event);
                }
            }
            if (!eventsToAlert.isEmpty()) {
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Alerting for events: " + eventsToAlert.stream().map(CalendarEvent::getSubject).collect(Collectors.joining(", ")));
                screenFlasher.flashMultiple(eventsToAlert);
                // Mark as alerted
                for (CalendarEvent event : eventsToAlert) {
                    alertedEventIds.add(event.getId());
                }
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Error checking for upcoming meetings: " + e.getMessage(), e);
        }
    }
}
