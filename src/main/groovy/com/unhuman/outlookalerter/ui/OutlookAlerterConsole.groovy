package com.unhuman.outlookalerter.ui

import groovy.transform.CompileStatic
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.unhuman.outlookalerter.core.ConfigManager
import com.unhuman.outlookalerter.core.OutlookClient
import com.unhuman.outlookalerter.util.LogManager
import com.unhuman.outlookalerter.util.LogCategory
import com.unhuman.outlookalerter.model.CalendarEvent
import com.unhuman.outlookalerter.util.ScreenFlasher
import com.unhuman.outlookalerter.util.ScreenFlasherFactory

/**
 * Console version of OutlookAlerter
 * This maintains the original console-based functionality
 */
@CompileStatic
class OutlookAlerterConsole {
    // Time constants
    private static final int POLLING_INTERVAL_MINUTES = 1
    
    // Components
    private final ConfigManager configManager
    private final OutlookClient outlookClient
    private final ScreenFlasher screenFlasher
    
    // Scheduler for periodic tasks (daemon threads so JVM can exit if stop() fails)
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, { Runnable r ->
        Thread t = new Thread(r, "ConsoleScheduler")
        t.setDaemon(true)
        return t
    } as java.util.concurrent.ThreadFactory)
    
    // Track which events we've already alerted for
    private final Set<String> alertedEventIds = new java.util.concurrent.ConcurrentHashMap<String, Boolean>().keySet(true)
    
    /**
     * Constructs a new OutlookAlerterConsole
     */
    OutlookAlerterConsole(String configPath) {
        // Initialize components
        this.configManager = new ConfigManager(configPath)
        this.configManager.loadConfiguration()
        
        // Load certificate validation setting from config
        boolean ignoreCertValidation = this.configManager.getDefaultIgnoreCertValidation()
        LogManager.getInstance().info(LogCategory.GENERAL, "Loaded certificate validation setting: " + (ignoreCertValidation ? "disabled" : "enabled"))
        
        this.outlookClient = new OutlookClient(configManager)
        this.screenFlasher = ScreenFlasherFactory.createScreenFlasher()
    }
    
    /**
     * Start the application
     * @param daemonMode Whether to run as a daemon (background service)
     */
    void start(boolean daemonMode) {
        LogManager.getInstance().info(LogCategory.GENERAL, "Starting Outlook Alerter in console mode...")
        
        // Print timezone information
        printSystemTimezoneInfo()
        
        // Authenticate with Outlook/Microsoft
        if (!outlookClient.authenticate()) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Failed to authenticate with Outlook. Exiting.")
            System.exit(1)
        }
        
        LogManager.getInstance().info(LogCategory.DATA_FETCH, "Successfully authenticated with Outlook.")
        
        // Schedule periodic calendar checks
        scheduler.scheduleAtFixedRate(
            { checkForUpcomingMeetings() } as Runnable,
            0, // Start immediately
            POLLING_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
        
        if (daemonMode) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Running in daemon mode. Press Ctrl+C to exit.")
        } else {
            LogManager.getInstance().info(LogCategory.GENERAL, "Press Enter to exit.")
            System.in.read()
            stop()
        }
    }
    
    /**
     * Print system timezone information to help diagnose timezone issues
     */
    private void printSystemTimezoneInfo() {
        LogManager.getInstance().info(LogCategory.GENERAL, "\n=============== SYSTEM TIMEZONE INFO ===============")
        LogManager.getInstance().info(LogCategory.GENERAL, "System default timezone: ${ZoneId.systemDefault()}")
        LogManager.getInstance().info(LogCategory.GENERAL, "Current time (system): ${ZonedDateTime.now()}")
        LogManager.getInstance().info(LogCategory.GENERAL, "Current time (UTC): ${ZonedDateTime.now(ZoneId.of("UTC"))}")
        LogManager.getInstance().info(LogCategory.GENERAL, "System timezone offset: ${ZonedDateTime.now().getOffset()}")
        LogManager.getInstance().info(LogCategory.GENERAL, "Available timezone IDs: ${ZoneId.getAvailableZoneIds().size()} zones available")
        
        // Show configured timezone if set
        if (configManager.preferredTimezone && !configManager.preferredTimezone.isEmpty()) {
            try {
                ZoneId configuredZone = ZoneId.of(configManager.preferredTimezone)
                LogManager.getInstance().info(LogCategory.GENERAL, "Configured timezone: ${configManager.preferredTimezone}")
                LogManager.getInstance().info(LogCategory.GENERAL, "Current time (configured timezone): ${ZonedDateTime.now(configuredZone)}")
                LogManager.getInstance().info(LogCategory.GENERAL, "Configured timezone offset: ${ZonedDateTime.now(configuredZone).getOffset()}")
            } catch (Exception e) {
                LogManager.getInstance().warn(LogCategory.GENERAL, "Configured timezone (INVALID): ${configManager.preferredTimezone}")
                LogManager.getInstance().error(LogCategory.GENERAL, "Error: ${e.message}")
            }
        } else {
            LogManager.getInstance().info(LogCategory.GENERAL, "No preferred timezone configured, using system default")
        }
        
        LogManager.getInstance().info(LogCategory.GENERAL, "===================================================")
    }
    
    /**
     * Stop the application
     */
    void stop() {
        LogManager.getInstance().info(LogCategory.GENERAL, "Stopping Outlook Alerter...")
        scheduler.shutdown()
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        LogManager.getInstance().info(LogCategory.GENERAL, "Outlook Alerter stopped.")
    }
    
    /**
     * Set the preferred timezone
     * @param timezone The timezone ID to use (e.g., "America/New_York")
     */
    void setTimezone(String timezone) {
        if (timezone == null || timezone.isEmpty()) {
            LogManager.getInstance().info(LogCategory.GENERAL, "No timezone specified, using system default")
            return
        }
        
        try {
            // Validate the timezone ID
            ZoneId zoneId = ZoneId.of(timezone)
            configManager.updatePreferredTimezone(timezone)
            LogManager.getInstance().info(LogCategory.GENERAL, "Timezone set to: ${timezone} (${ZonedDateTime.now(zoneId)})")
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Invalid timezone: ${timezone}")
            LogManager.getInstance().error(LogCategory.GENERAL, "Error: ${e.message}")
            LogManager.getInstance().info(LogCategory.GENERAL, "Available timezones: ${ZoneId.getAvailableZoneIds().size()} zones available")
        }
    }
    
    /**
     * Check for upcoming meetings and alert if needed
     */
    private void checkForUpcomingMeetings() {
        try {
            // Try all available methods for retrieving events
            List<CalendarEvent> calendarViewEvents = outlookClient.getUpcomingEventsUsingCalendarView()
            
            // Combine events, avoiding duplicates
            Map<String, CalendarEvent> combinedEventsMap = new HashMap<>()
            
            // Add calendar view events (will overwrite duplicates)
            calendarViewEvents.each { event ->
                combinedEventsMap.put(event.id, event)
            }
            
            // Convert back to list and sort by start time for easier consumption
            List<CalendarEvent> combinedEvents = new ArrayList<>(combinedEventsMap.values())
            
            // Sort events by minutes to start (ascending) so closest events are first
            combinedEvents.sort { event -> event.getMinutesToStart() }
            
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "\n=========== CALENDAR UPDATE ===========")
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "Found ${combinedEvents.size()} calendar events.")
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "====================================================")
            
            if (combinedEvents.isEmpty()) {
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "No upcoming events found.")
                return
            }
            
            // Filter to include upcoming events and in-progress events
            List<CalendarEvent> relevantEvents = combinedEvents.findAll { event -> 
                int minutesToStart = event.getMinutesToStart()
                boolean isUpcoming = minutesToStart >= 0  // Future event
                boolean isInProgress = event.isInProgress() // Currently happening event
                
                if (!isUpcoming && !isInProgress) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "Filtering out past event: ${event.subject} (started ${-minutesToStart} minutes ago, already ended)")
                    return false
                }
                return true
            }
            
            if (relevantEvents.isEmpty()) {
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "Note: No current or upcoming events found.")
                return
            }
            
            // Sort events by minutes to start (ascending) so closest events are first
            relevantEvents.sort { event -> event.getMinutesToStart() }
            
            // Separate in-progress events from upcoming events
            List<CalendarEvent> inProgressEvents = relevantEvents.findAll { event -> event.isInProgress() }
            List<CalendarEvent> upcomingEvents = relevantEvents.findAll { event -> !event.isInProgress() }
            
            // Show in-progress events first
            if (!inProgressEvents.isEmpty()) {
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "\nCurrently in progress:")
                inProgressEvents.each { CalendarEvent event ->
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "  - ${event.subject}" +
                          (event.isOnlineMeeting ? " (Online)" : "") +
                          (event.organizer ? " - Organized by: ${event.organizer}" : "") +
                          (event.responseStatus ? " - Status: ${event.responseStatus}" : "") +
                          " (started ${-event.getMinutesToStart()} minutes ago)")
                }
            }
            
            // Show upcoming events next, if any
            if (!upcomingEvents.isEmpty()) {
                // Get the minutes to start for the earliest upcoming event
                int earliestMinutesToStart = upcomingEvents[0].getMinutesToStart()
                
                // Determine which events should be in the "next meetings" section
                // Include all meetings in the next 60 minutes, or just the next meeting(s) if none in 60 min
                List<CalendarEvent> nextTimeEvents = upcomingEvents.findAll { event -> 
                    int minutesToStart = event.getMinutesToStart()
                    // Include any meeting starting within the next 60 minutes
                    // OR those within 5 minutes of the earliest upcoming meeting (to group meetings happening at similar times)
                    return (minutesToStart <= 60) || (minutesToStart <= earliestMinutesToStart + 5)
                }
                
                // Ensure all meetings are properly sorted by start time
                nextTimeEvents.sort { a, b -> a.getMinutesToStart() <=> b.getMinutesToStart() }
                
                // Include time information in the header
                ZonedDateTime now = ZonedDateTime.now()
                String displayTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                String displayZone = now.getZone().toString()
                
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "\nNext meetings at ${displayTime} (${displayZone}):")
                nextTimeEvents.each { CalendarEvent event ->
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "  - ${event.subject}" + 
                          (event.isOnlineMeeting ? " (Online)" : "") +
                          (event.organizer ? " - Organized by: ${event.organizer}" : "") +
                          (event.responseStatus ? " - Status: ${event.responseStatus}" : "") +
                          " (starts in ${event.getMinutesToStart()} minutes)")
                }
                
                // List subsequent events if there are any
                List<CalendarEvent> laterEvents = upcomingEvents.findAll { event -> 
                    !nextTimeEvents.contains(event)
                }
                
                if (!laterEvents.isEmpty()) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "\nLater meetings:")
                    laterEvents.take(5).each { CalendarEvent event ->
                        LogManager.getInstance().info(LogCategory.DATA_FETCH, "  - ${event.subject} at ${event.startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}" +
                              (event.responseStatus ? " - Status: ${event.responseStatus}" : "") +
                              " (starts in ${event.getMinutesToStart()} minutes)")
                    }
                    
                    if (laterEvents.size() > 5) {
                        LogManager.getInstance().info(LogCategory.DATA_FETCH, "  - ...and ${laterEvents.size() - 5} more later events")
                    }
                }
            }
            
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "=============================================\n")
            
            // Check each event for alerts
            List<CalendarEvent> eventsToAlert = []
            for (CalendarEvent event : combinedEvents) {
                // Skip events that have already ended
                if (event.hasEnded()) {
                    continue
                }
                int minutesToStart = event.getMinutesToStart()
                // Skip events we've already alerted for
                if (alertedEventIds.contains(event.id)) {
                    continue
                }
                // Alert for events about to start
                if (minutesToStart <= configManager.alertMinutes && minutesToStart >= -1) {
                    eventsToAlert.add(event)
                }
            }
            if (!eventsToAlert.isEmpty()) {
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Alerting for events: " + eventsToAlert.collect { it.subject }.join(", "))
                screenFlasher.flashMultiple(eventsToAlert)
                // Mark as alerted
                for (CalendarEvent event : eventsToAlert) {
                    alertedEventIds.add(event.id)
                }
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Error checking for upcoming meetings: ${e.message}", e)
        }
    }
}
