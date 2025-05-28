#!/bin/bash

# Quick Event Comparison Tool for OutlookAlerter
# This script helps identify which calendar events are missing from different retrieval methods

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

echo "===== OutlookAlerter Event Comparison Tool ====="
echo "Current date: $(date)"
echo "System timezone: $(date +%Z)"
echo ""
echo "This tool compares events from different retrieval methods to identify missing events"

# Create a simple Java program for event comparison
cat > "$SCRIPT_DIR/EventComparisonTool.java" << 'EOL'
import com.unhuman.outlookalerter.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class EventComparisonTool {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    
    public static void main(String[] args) {
        try {
            System.out.println("===== Calendar Event Comparison Tool =====");
            System.out.println("This tool will help identify which events might be missing from different retrieval methods");
            
            // Initialize the OutlookClient
            ConfigManager configManager = new ConfigManager();
            OutlookClient client = new OutlookClient(configManager);
            
            // Retrieve events using all three methods
            System.out.println("\nRetrieving events using all available methods...");
            
            List<CalendarEvent> standardEvents = client.getUpcomingEvents();
            System.out.println("Standard method: " + standardEvents.size() + " events");
            
            List<CalendarEvent> calendarViewEvents = client.getUpcomingEventsUsingCalendarView();
            System.out.println("Calendar view method: " + calendarViewEvents.size() + " events");
            
            List<CalendarEvent> allCalendarEvents = client.getUpcomingEventsFromAllCalendars();
            System.out.println("All calendars method: " + allCalendarEvents.size() + " events");
            
            // Compare the three sets
            compareEventSets(standardEvents, calendarViewEvents, "Standard", "Calendar View");
            compareEventSets(standardEvents, allCalendarEvents, "Standard", "All Calendars");
            compareEventSets(calendarViewEvents, allCalendarEvents, "Calendar View", "All Calendars");
            
            // Find unique events across all methods
            Map<String, CalendarEvent> uniqueEvents = new HashMap<>();
            addEventsToMap(uniqueEvents, standardEvents);
            addEventsToMap(uniqueEvents, calendarViewEvents);
            addEventsToMap(uniqueEvents, allCalendarEvents);
            
            System.out.println("\n===== COMBINED RESULTS =====");
            System.out.println("Total unique events across all methods: " + uniqueEvents.size());
            
            // Print all unique events
            List<CalendarEvent> allEvents = new ArrayList<>(uniqueEvents.values());
            allEvents.sort((e1, e2) -> {
                if (e1.getStart() == null && e2.getStart() == null) return 0;
                if (e1.getStart() == null) return 1;
                if (e2.getStart() == null) return -1;
                return e1.getStart().compareTo(e2.getStart());
            });
            
            System.out.println("\nAll upcoming events (sorted by start time):");
            System.out.println("+------+------------------------+------------------------+------------------------+------------------------+");
            System.out.println("| No.  | Subject                | Start Time             | Calendar               | Status                 |");
            System.out.println("+------+------------------------+------------------------+------------------------+------------------------+");
            
            for (int i = 0; i < allEvents.size(); i++) {
                CalendarEvent event = allEvents.get(i);
                
                // Skip events that have ended
                if (event.hasEnded()) continue;
                
                String subject = formatForTable(event.getSubject(), 22);
                String startTime = event.getStart() != null 
                    ? formatForTable(event.getStart().format(TIME_FORMATTER), 22) 
                    : "N/A";
                String calendar = formatForTable(
                    event.getCalendarName() != null ? event.getCalendarName() : "Default", 22);
                
                String status = "Upcoming";
                if (event.isInProgress()) {
                    status = "In Progress";
                } else if (event.getMinutesToStart() <= 5) {
                    status = "Starting Soon (" + event.getMinutesToStart() + "m)";
                }
                status = formatForTable(status, 22);
                
                System.out.println(String.format("| %-4d | %-22s | %-22s | %-22s | %-22s |", 
                                 i+1, subject, startTime, calendar, status));
            }
            
            System.out.println("+------+------------------------+------------------------+------------------------+------------------------+");
            
            // Summarize which retrieval methods are most effective
            System.out.println("\n===== RECOMMENDATIONS =====");
            
            if (calendarViewEvents.size() > standardEvents.size()) {
                System.out.println("✅ The Calendar View method found more events than the Standard method.");
                System.out.println("   Continue using both methods for maximum coverage.");
            } else if (standardEvents.size() > calendarViewEvents.size()) {
                System.out.println("✅ The Standard method found more events than the Calendar View method.");
                System.out.println("   Continue using both methods for maximum coverage.");
            } else {
                System.out.println("ℹ️ The Standard and Calendar View methods found the same number of events.");
            }
            
            if (allCalendarEvents.size() > standardEvents.size() || 
                allCalendarEvents.size() > calendarViewEvents.size()) {
                System.out.println("✅ The All Calendars method found events that weren't in other methods.");
                System.out.println("   This suggests you have events in secondary calendars that should be included.");
            }
            
            if (uniqueEvents.size() > Math.max(standardEvents.size(), 
                Math.max(calendarViewEvents.size(), allCalendarEvents.size()))) {
                System.out.println("✅ Using all three methods together provides the most complete set of events.");
                System.out.println("   The current implementation in OutlookAlerter is correctly using all methods.");
            }
            
        } catch (Exception e) {
            System.out.println("Error comparing events: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void compareEventSets(List<CalendarEvent> set1, List<CalendarEvent> set2,
                                      String set1Name, String set2Name) {
        System.out.println("\n===== COMPARING " + set1Name.toUpperCase() + 
                          " vs " + set2Name.toUpperCase() + " =====");
        
        // First pass: Check for missing events based on ID
        List<CalendarEvent> inSet1NotInSet2 = findMissingEvents(set1, set2);
        List<CalendarEvent> inSet2NotInSet1 = findMissingEvents(set2, set1);
        
        if (inSet1NotInSet2.isEmpty() && inSet2NotInSet1.isEmpty()) {
            System.out.println("✅ Both methods return the exact same set of events.");
            return;
        }
        
        if (!inSet1NotInSet2.isEmpty()) {
            System.out.println("\n📋 Events in " + set1Name + " but missing from " + set2Name + " (" + 
                             inSet1NotInSet2.size() + "):");
            printEventsList(inSet1NotInSet2);
        } else {
            System.out.println("✅ All events from " + set1Name + " are also in " + set2Name);
        }
        
        if (!inSet2NotInSet1.isEmpty()) {
            System.out.println("\n📋 Events in " + set2Name + " but missing from " + set1Name + " (" + 
                             inSet2NotInSet1.size() + "):");
            printEventsList(inSet2NotInSet1);
        } else {
            System.out.println("✅ All events from " + set2Name + " are also in " + set1Name);
        }
    }
    
    private static List<CalendarEvent> findMissingEvents(List<CalendarEvent> source, 
                                                      List<CalendarEvent> target) {
        List<CalendarEvent> missingEvents = new ArrayList<>();
        Set<String> targetIds = new HashSet<>();
        
        // Create a set of all IDs in the target
        for (CalendarEvent event : target) {
            if (event.getId() != null) {
                targetIds.add(event.getId());
            }
        }
        
        // Find events in source that don't have their ID in the target
        for (CalendarEvent event : source) {
            if (event.getId() != null && !targetIds.contains(event.getId())) {
                missingEvents.add(event);
            }
        }
        
        return missingEvents;
    }
    
    private static void printEventsList(List<CalendarEvent> events) {
        if (events.isEmpty()) {
            System.out.println("  No events found");
            return;
        }
        
        // Sort by start time
        events.sort((e1, e2) -> {
            if (e1.getStart() == null && e2.getStart() == null) return 0;
            if (e1.getStart() == null) return 1;
            if (e2.getStart() == null) return -1;
            return e1.getStart().compareTo(e2.getStart());
        });
        
        System.out.println("+------+------------------------+------------------------+------------------------+");
        System.out.println("| No.  | Subject                | Start Time             | Calendar               |");
        System.out.println("+------+------------------------+------------------------+------------------------+");
        
        for (int i = 0; i < events.size(); i++) {
            CalendarEvent event = events.get(i);
            
            String subject = formatForTable(event.getSubject(), 22);
            String startTime = event.getStart() != null 
                ? formatForTable(event.getStart().format(TIME_FORMATTER), 22) 
                : "N/A";
            String calendar = formatForTable(
                event.getCalendarName() != null ? event.getCalendarName() : "Default", 22);
            
            System.out.println(String.format("| %-4d | %-22s | %-22s | %-22s |", 
                             i+1, subject, startTime, calendar));
        }
        
        System.out.println("+------+------------------------+------------------------+------------------------+");
    }
    
    private static void addEventsToMap(Map<String, CalendarEvent> eventsMap, List<CalendarEvent> events) {
        for (CalendarEvent event : events) {
            if (event.getId() != null) {
                eventsMap.put(event.getId(), event);
            }
        }
    }
    
    private static String formatForTable(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 3) + "...";
    }
}
EOL

# Compile the event comparison tool
javac -cp "$CLASSPATH" "$SCRIPT_DIR/EventComparisonTool.java"

# Run the comparison tool
echo "Running event comparison..."
java -cp "$CLASSPATH:$SCRIPT_DIR" EventComparisonTool | tee "$SCRIPT_DIR/event-comparison-report.txt"

# Clean up
rm "$SCRIPT_DIR/EventComparisonTool.java" "$SCRIPT_DIR/EventComparisonTool.class"

echo ""
echo "===== Event comparison complete ====="
echo "The comparison results have been saved to: event-comparison-report.txt"
echo "This report shows which events might be missing from different retrieval methods"
echo "and provides recommendations for getting the most complete set of events."
