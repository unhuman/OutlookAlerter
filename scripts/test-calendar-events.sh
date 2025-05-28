#!/bin/bash

# Calendar Events Test Script for OutlookAlerter
# This script retrieves and logs all calendar events and calendars from different retrieval methods

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

# Create a temporary Java program to test calendar events retrieval
cat > "$SCRIPT_DIR/CalendarEventsTest.java" << 'EOL'
import com.unhuman.outlookalerter.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

public class CalendarEventsTest {
    public static void main(String[] args) {
        System.out.println("===== OutlookAlerter Calendar Events Test =====");
        System.out.println("System timezone: " + ZoneId.systemDefault());
        System.out.println("Current time: " + ZonedDateTime.now());
        
        try {
            // Initialize the Outlook client
            ConfigManager configManager = new ConfigManager();
            OutlookClient client = new OutlookClient(configManager);
            
            // Test all event retrieval methods
            System.out.println("\n=== Events from standard method ===");
            List<CalendarEvent> standardEvents = client.getUpcomingEvents();
            printEvents(standardEvents, "Standard");
            
            System.out.println("\n=== Events from Calendar View method ===");
            List<CalendarEvent> calendarViewEvents = client.getUpcomingEventsUsingCalendarView();
            printEvents(calendarViewEvents, "CalendarView");
            
            System.out.println("\n=== Available Calendars ===");
            List<Map<String, Object>> calendars = client.getAvailableCalendars();
            printCalendars(calendars);
            
            System.out.println("\n=== Events from All Calendars ===");
            List<CalendarEvent> allCalendarEvents = client.getUpcomingEventsFromAllCalendars();
            printEvents(allCalendarEvents, "AllCalendars");
            
            // Compare the different sets to identify missing events
            System.out.println("\n=== Events in CalendarView but not in Standard ===");
            List<CalendarEvent> missingInStandard = findMissingEvents(calendarViewEvents, standardEvents);
            printEvents(missingInStandard, "MissingInStandard");
            
            System.out.println("\n=== Events in Standard but not in CalendarView ===");
            List<CalendarEvent> missingInCalendarView = findMissingEvents(standardEvents, calendarViewEvents);
            printEvents(missingInCalendarView, "MissingInCalendarView");
            
            System.out.println("\n=== Events in AllCalendars but not in Standard ===");
            List<CalendarEvent> missingInStandardFromAll = findMissingEvents(allCalendarEvents, standardEvents);
            printEvents(missingInStandardFromAll, "MissingInStandardFromAll");
            
            System.out.println("\n=== Combined Unique Events ===");
            Set<CalendarEvent> allUniqueEvents = new HashSet<>();
            allUniqueEvents.addAll(standardEvents);
            allUniqueEvents.addAll(calendarViewEvents);
            allUniqueEvents.addAll(allCalendarEvents);
            printEvents(new ArrayList<>(allUniqueEvents), "AllUnique");
            
            System.out.println("\n=== Event Counts ===");
            System.out.println("Standard method: " + standardEvents.size());
            System.out.println("CalendarView method: " + calendarViewEvents.size());
            System.out.println("All Calendars method: " + allCalendarEvents.size());
            System.out.println("Total unique events: " + allUniqueEvents.size());
            
        } catch (Exception e) {
            System.out.println("Error in calendar events test: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void printEvents(List<CalendarEvent> events, String source) {
        System.out.println("Found " + events.size() + " events from " + source + ":");
        System.out.println("| ID | Subject | Start Time | End Time | Calendar | Timezone | Has Ended | In Progress |");
        System.out.println("|---|---|---|---|---|---|---|---|");
        
        for (CalendarEvent event : events) {
            System.out.println(String.format("| %s | %s | %s | %s | %s | %s | %s | %s |",
                event.getId(),
                event.getSubject(),
                event.getStart(),
                event.getEnd(),
                event.getCalendarName() != null ? event.getCalendarName() : "Default",
                event.getStartTimeZone(),
                event.hasEnded(),
                event.isInProgress()
            ));
        }
    }
    
    private static void printCalendars(List<Map<String, Object>> calendars) {
        System.out.println("Found " + calendars.size() + " calendars:");
        System.out.println("| ID | Name | Owner | Can Edit |");
        System.out.println("|---|---|---|---|");
        
        for (Map<String, Object> calendar : calendars) {
            System.out.println(String.format("| %s | %s | %s | %s |",
                calendar.get("id"),
                calendar.get("name"),
                calendar.get("owner") != null ? ((Map)calendar.get("owner")).get("name") : "Unknown",
                calendar.get("canEdit")
            ));
        }
    }
    
    private static List<CalendarEvent> findMissingEvents(List<CalendarEvent> sourceList, List<CalendarEvent> comparisonList) {
        List<CalendarEvent> missingEvents = new ArrayList<>();
        
        for (CalendarEvent sourceEvent : sourceList) {
            boolean found = false;
            for (CalendarEvent comparisonEvent : comparisonList) {
                if (sourceEvent.getId().equals(comparisonEvent.getId())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                missingEvents.add(sourceEvent);
            }
        }
        
        return missingEvents;
    }
}
EOL

# Compile the calendar events test
echo "Compiling calendar events test..."
javac -cp "$CLASSPATH:$SCRIPT_DIR" "$SCRIPT_DIR/CalendarEventsTest.java"

# Run the calendar events test
echo "Running calendar events test..."
java -cp "$CLASSPATH:$SCRIPT_DIR" CalendarEventsTest

# Save output to a file for reference
java -cp "$CLASSPATH:$SCRIPT_DIR" CalendarEventsTest > "$SCRIPT_DIR/calendar-events-report.txt" 2>&1
echo "Results saved to calendar-events-report.txt"

# Clean up
rm "$SCRIPT_DIR/CalendarEventsTest.java" "$SCRIPT_DIR/CalendarEventsTest.class"

echo "Calendar events test completed."
