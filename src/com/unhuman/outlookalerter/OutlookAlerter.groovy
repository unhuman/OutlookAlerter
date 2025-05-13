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
 * Main application class for OutlookAlerter
 * Monitors Outlook calendar events and flashes screen when meetings are about to start
 */
@CompileStatic
class OutlookAlerter {
    // Time constants
    private static final int POLLING_INTERVAL_MINUTES = 1
    private static final int ALERT_THRESHOLD_MINUTES = 2
    
    // Debugging
    private static boolean DEBUG_MODE = false
    
    // Components
    private final ConfigManager configManager
    private final OutlookClient outlookClient
    private final ScreenFlasher screenFlasher
    
    // Scheduler for periodic tasks
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1)
    
    // Track which events we've already alerted for
    private final Set<String> alertedEventIds = ConcurrentHashMap.newKeySet()
    
    /**
     * Main entry point
     */
    static void main(String[] args) {
        // Parse command-line arguments
        String configPath = System.getProperty("user.home") + "/.outlookalerter/config.properties"
        boolean daemonMode = false
        String timezoneOverride = null
        
        for (int i = 0; i < args.length; i++) {
            if (args[i] == "--config" && i < args.length - 1) {
                configPath = args[i + 1]
                i++
            } else if (args[i] == "--daemon") {
                daemonMode = true
            } else if (args[i] == "--debug") {
                DEBUG_MODE = true
                println "Debug mode enabled - detailed timezone information will be logged"
            } else if (args[i] == "--timezone" && i < args.length - 1) {
                timezoneOverride = args[i + 1]
                i++
                println "Timezone override: ${timezoneOverride}"
            } else if (args[i] == "--help") {
                printUsage()
                return
            }
        }
        
        // Create and start the application
        OutlookAlerter app = new OutlookAlerter(configPath)
        
        // Apply timezone override if specified
        if (timezoneOverride) {
            app.setTimezone(timezoneOverride)
        }
        
        app.start(daemonMode)
    }
    
    /**
     * Print usage information
     */
    private static void printUsage() {
        println """
        OutlookAlerter - Monitor Outlook calendar events and alert before meetings
        
        Usage: java -jar OutlookAlerter.jar [options]
        
        Options:
          --config <path>   Path to configuration file (default: ~/.outlookalerter/config.properties)
          --daemon          Run in daemon mode (background)
          --debug           Enable debug mode with detailed timezone logging
          --timezone <zone> Override the timezone for displaying events (e.g., America/New_York)
          --help            Show this help message
        
        Description:
          This tool connects to your Outlook/Microsoft 365 calendar using OAuth authentication
          and monitors for upcoming meetings. When a meeting is about to start (within 2 minutes),
          it flashes the screen to alert you.
        """
    }
    
    /**
     * Constructs a new OutlookAlerter
     */
    OutlookAlerter(String configPath) {
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
        println "Starting OutlookAlerter..."
        
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
        if (DEBUG_MODE) {
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
            if (DEBUG_MODE) {
                // Print some example timezones
                println "Examples: America/New_York, Europe/London, Asia/Tokyo, Australia/Sydney"
                println "Full list available at: https://docs.oracle.com/javase/8/docs/api/java/time/ZoneId.html"
            }
        }
    }
    
    /**
     * Check for upcoming meetings and alert if needed
     */
    private void checkForUpcomingMeetings() {
        try {
            List<CalendarEvent> events = outlookClient.getUpcomingEvents()
            
            if (events.isEmpty()) {
                println "No upcoming events found."
                return
            }
            
            println "\n=============== CALENDAR UPDATE ==============="
            println "Found ${events.size()} calendar events in response."
            
            if (DEBUG_MODE) {
                // Show timezone information
                println "Current system time: ${ZonedDateTime.now()} (${ZonedDateTime.now().getZone()})"
                println "Current UTC time: ${ZonedDateTime.now(ZoneId.of("UTC"))} (UTC)"
                if (configManager.preferredTimezone && !configManager.preferredTimezone.isEmpty()) {
                    try {
                        ZoneId preferredZone = ZoneId.of(configManager.preferredTimezone)
                        println "Current time in preferred timezone: ${ZonedDateTime.now(preferredZone)} (${preferredZone})"
                    } catch (Exception e) {
                        println "Invalid preferred timezone configured: ${configManager.preferredTimezone}"
                    }
                }
            }
            
            // Add debug information for each event
            events.each { CalendarEvent event ->
                println "Event: ${event.subject}"
                println "  Start: ${event.startTime} (${event.startTime.getZone()})"
                println "  Minutes to start: ${event.getMinutesToStart()}"
                println "  Is in progress: ${event.isInProgress()}"
                if (DEBUG_MODE) {
                    // More detailed timezone diagnostics
                    ZonedDateTime now = ZonedDateTime.now(event.startTime.getZone())
                    println "  Current time in event's timezone: ${now}"
                    println "  Comparison: event ${event.startTime.isBefore(now) ? 'is before' : 'is after or equal to'} current time"
                }
            }
            
            // Filter to include upcoming events and in-progress events
            List<CalendarEvent> relevantEvents = events.findAll { event -> 
                int minutesToStart = event.getMinutesToStart()
                boolean isUpcoming = minutesToStart >= 0  // Future event
                boolean isInProgress = event.isInProgress() // Currently happening event
                
                if (!isUpcoming && !isInProgress) {
                    println "Filtering out past event: ${event.subject} (started ${-minutesToStart} minutes ago, already ended)"
                    if (DEBUG_MODE) {
                        println "  Event start: ${event.startTime} (${event.startTime.getZone()})"
                        println "  Event end: ${event.endTime} (${event.endTime.getZone()})"
                        println "  Current time: ${ZonedDateTime.now(event.startTime.getZone())} (in event's timezone)"
                        println "  System time: ${ZonedDateTime.now()} (${ZonedDateTime.now().getZone()})"
                    }
                    return false
                }
                return true
            }
            
            if (relevantEvents.isEmpty()) {
                println "Note: No current or upcoming events found."
                return
            }
            
            // Sort events by start time
            relevantEvents.sort { event -> event.startTime }
            
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
                          " (started ${-event.getMinutesToStart()} minutes ago)" +
                          (DEBUG_MODE ? " [Time: ${event.startTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}, " +
                                       "Zone: ${event.startTime.getZone()}, " +
                                       "Offset: ${event.startTime.getOffset()}]" : "")
                }
            }
            
            // Show upcoming events next, if any
            if (!upcomingEvents.isEmpty()) {
                // Find the next meeting time (earliest start time among upcoming events)
                ZonedDateTime nextMeetingTime = upcomingEvents[0].startTime
                
                // Log all events at the next meeting time
                List<CalendarEvent> nextTimeEvents = upcomingEvents.findAll { event -> 
                    event.startTime.isEqual(nextMeetingTime) 
                }
                
                // Always list the next events regardless of how many there are
                println "\nNext meetings at ${nextMeetingTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} " +
                       "${DEBUG_MODE ? '(' + nextMeetingTime.getZone().toString() + ')' : ''}:"
                nextTimeEvents.each { CalendarEvent event ->
                    println "  - ${event.subject}" + 
                          (event.isOnlineMeeting ? " (Online)" : "") +
                          (event.organizer ? " - Organized by: ${event.organizer}" : "") +
                          " (starts in ${event.getMinutesToStart()} minutes)" +
                          (DEBUG_MODE ? " [Time: ${event.startTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}, " +
                                       "Zone: ${event.startTime.getZone()}, " +
                                       "Offset: ${event.startTime.getOffset()}]" : "")
                }
                
                // List subsequent events if there are any
                List<CalendarEvent> laterEvents = upcomingEvents.findAll { event -> 
                    !event.startTime.isEqual(nextMeetingTime) 
                }
                
                if (!laterEvents.isEmpty()) {
                    println "\nLater meetings:"
                    laterEvents.take(5).each { CalendarEvent event ->
                        println "  - ${event.subject} at ${event.startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}" +
                              " (starts in ${event.getMinutesToStart()} minutes)" +
                              (DEBUG_MODE ? " [Zone: ${event.startTime.getZone()}, " +
                                           "Offset: ${event.startTime.getOffset()}]" : "")
                    }
                    
                    if (laterEvents.size() > 5) {
                        println "  - ...and ${laterEvents.size() - 5} more later events"
                    }
                }
            }
            
            println "=============================================\n"
            
            // Check each event for alerts
            for (CalendarEvent event : events) {
                // Skip events that have already ended
                if (ZonedDateTime.now().isAfter(event.endTime)) {
                    if (DEBUG_MODE) {
                        println "Skipping alerting for ended event: ${event.subject}"
                    }
                    continue
                }
                
                int minutesToStart = event.getMinutesToStart()
                
                // Skip events we've already alerted for
                if (alertedEventIds.contains(event.id)) {
                    if (DEBUG_MODE) {
                        println "Skipping alerting for already alerted event: ${event.subject}"
                    }
                    continue
                }
                
                // Alert for events about to start
                if (minutesToStart <= ALERT_THRESHOLD_MINUTES && minutesToStart >= -ALERT_THRESHOLD_MINUTES) {
                    println "Alerting for event: ${event.subject} (${minutesToStart >= 0 ? 
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