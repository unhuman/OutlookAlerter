package com.unhuman.outlookalerter

import groovy.transform.CompileStatic

import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
    
    // Scheduler for periodic tasks
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1)
    
    // Track which events we've already alerted for
    private final Set<String> alertedEventIds = new java.util.concurrent.ConcurrentHashMap<String, Boolean>().keySet(true)
    
    /**
     * Constructs a new OutlookAlerterConsole
     */
    OutlookAlerterConsole(String configPath) {
        // Initialize components
        this.configManager = new ConfigManager(configPath)
        this.configManager.loadConfiguration()
        
        this.outlookClient = new OutlookClient(configManager)
        this.screenFlasher = ScreenFlasherFactory.createScreenFlasher()
    }
    
    /**
     * Start the application
     * @param daemonMode Whether to run as a daemon (background service)
     */
    void start(boolean daemonMode) {
        println "Starting OutlookAlerter in console mode..."
        
        // Print timezone information
        printSystemTimezoneInfo()
        
        // Authenticate with Outlook/Microsoft
        if (!outlookClient.authenticate()) {
            println "Failed to authenticate with Outlook. Exiting."
            System.exit(1)
        }
        
        println "Successfully authenticated with Outlook."
        
        // Schedule periodic calendar checks
        scheduler.scheduleAtFixedRate(
            { checkForUpcomingMeetings() } as Runnable,
            0, // Start immediately
            POLLING_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
        
        if (daemonMode) {
            println "Running in daemon mode. Press Ctrl+C to exit."
        } else {
            println "Press Enter to exit."
            System.in.read()
            stop()
        }
    }
    
    /**
     * Print system timezone information to help diagnose timezone issues
     */
    private void printSystemTimezoneInfo() {
        println "\n=============== SYSTEM TIMEZONE INFO ==============="
        println "System default timezone: ${ZoneId.systemDefault()}"
        println "Current time (system): ${ZonedDateTime.now()}"
        println "Current time (UTC): ${ZonedDateTime.now(ZoneId.of("UTC"))}"
        println "System timezone offset: ${ZonedDateTime.now().getOffset()}"
        println "Available timezone IDs: ${ZoneId.getAvailableZoneIds().size()} zones available"
        
        // Show configured timezone if set
        if (configManager.preferredTimezone && !configManager.preferredTimezone.isEmpty()) {
            try {
                ZoneId configuredZone = ZoneId.of(configManager.preferredTimezone)
                println "Configured timezone: ${configManager.preferredTimezone}"
                println "Current time (configured timezone): ${ZonedDateTime.now(configuredZone)}"
                println "Configured timezone offset: ${ZonedDateTime.now(configuredZone).getOffset()}"
            } catch (Exception e) {
                println "Configured timezone (INVALID): ${configManager.preferredTimezone}"
                println "Error: ${e.message}"
            }
        } else {
            println "No preferred timezone configured, using system default"
        }
        
        println "==================================================="
    }
    
    /**
     * Stop the application
     */
    void stop() {
        println "Stopping OutlookAlerter..."
        scheduler.shutdown()
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        println "OutlookAlerter stopped."
    }
    
    /**
     * Set the preferred timezone
     * @param timezone The timezone ID to use (e.g., "America/New_York")
     */
    void setTimezone(String timezone) {
        if (timezone == null || timezone.isEmpty()) {
            println "No timezone specified, using system default"
            return
        }
        
        try {
            // Validate the timezone ID
            ZoneId zoneId = ZoneId.of(timezone)
            configManager.updatePreferredTimezone(timezone)
            println "Timezone set to: ${timezone} (${ZonedDateTime.now(zoneId)})"
        } catch (Exception e) {
            println "Invalid timezone: ${timezone}"
            println "Error: ${e.message}"
            println "Available timezones: ${ZoneId.getAvailableZoneIds().size()} zones available"
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
            
            println "\n=========== CALENDAR UPDATE ==========="
            println "Found ${combinedEvents.size()} calendar events."
            println "===================================================="
            
            if (combinedEvents.isEmpty()) {
                println "No upcoming events found."
                return
            }
            
            // Filter to include upcoming events and in-progress events
            List<CalendarEvent> relevantEvents = combinedEvents.findAll { event -> 
                int minutesToStart = event.getMinutesToStart()
                boolean isUpcoming = minutesToStart >= 0  // Future event
                boolean isInProgress = event.isInProgress() // Currently happening event
                
                if (!isUpcoming && !isInProgress) {
                    println "Filtering out past event: ${event.subject} (started ${-minutesToStart} minutes ago, already ended)"
                    return false
                }
                return true
            }
            
            if (relevantEvents.isEmpty()) {
                println "Note: No current or upcoming events found."
                return
            }
            
            // Sort events by minutes to start (ascending) so closest events are first
            relevantEvents.sort { event -> event.getMinutesToStart() }
            
            // Separate in-progress events from upcoming events
            List<CalendarEvent> inProgressEvents = relevantEvents.findAll { event -> event.isInProgress() }
            List<CalendarEvent> upcomingEvents = relevantEvents.findAll { event -> !event.isInProgress() }
            
            // Show in-progress events first
            if (!inProgressEvents.isEmpty()) {
                println "\nCurrently in progress:"
                inProgressEvents.each { CalendarEvent event ->
                    println "  - ${event.subject}" +
                          (event.isOnlineMeeting ? " (Online)" : "") +
                          (event.organizer ? " - Organized by: ${event.organizer}" : "") +
                          (event.responseStatus ? " - Status: ${event.responseStatus}" : "") +
                          " (started ${-event.getMinutesToStart()} minutes ago)"
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
                nextTimeEvents.sort { event -> event.getMinutesToStart() }
                
                // Include time information in the header
                ZonedDateTime now = ZonedDateTime.now()
                String displayTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                String displayZone = now.getZone().toString()
                
                // Always ensure next meetings are properly sorted by start time
                nextTimeEvents.sort { a, b -> a.getMinutesToStart() <=> b.getMinutesToStart() }
                
                println "\nNext meetings at ${displayTime} (${displayZone}):"
                nextTimeEvents.each { CalendarEvent event ->
                    println "  - ${event.subject}" + 
                          (event.isOnlineMeeting ? " (Online)" : "") +
                          (event.organizer ? " - Organized by: ${event.organizer}" : "") +
                          (event.responseStatus ? " - Status: ${event.responseStatus}" : "") +
                          " (starts in ${event.getMinutesToStart()} minutes)"
                }
                
                // List subsequent events if there are any
                List<CalendarEvent> laterEvents = upcomingEvents.findAll { event -> 
                    !nextTimeEvents.contains(event)
                }
                
                if (!laterEvents.isEmpty()) {
                    println "\nLater meetings:"
                    laterEvents.take(5).each { CalendarEvent event ->
                        println "  - ${event.subject} at ${event.startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}" +
                              (event.responseStatus ? " - Status: ${event.responseStatus}" : "") +
                              " (starts in ${event.getMinutesToStart()} minutes)"
                    }
                    
                    if (laterEvents.size() > 5) {
                        println "  - ...and ${laterEvents.size() - 5} more later events"
                    }
                }
            }
            
            println "=============================================\n"
            
            // Check each event for alerts
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
                    println "Alerting for event: ${event.subject}" + 
                           (event.responseStatus ? " (${event.responseStatus})" : "") + 
                           " (${minutesToStart >= 0 ? 
                              "starts in ${minutesToStart} min" : 
                              "started ${-minutesToStart} min ago"})"
                    screenFlasher.flash(event)
                    
                    // Mark as alerted
                    alertedEventIds.add(event.id)
                    
                    // Clean up alerted events list periodically
                    if (alertedEventIds.size() > 100) {
                        alertedEventIds.clear()
                    }
                }
            }
        } catch (Exception e) {
            println "Error checking for upcoming meetings: ${e.message}"
            e.printStackTrace() // Add stack trace for better debugging
        }
    }
}
