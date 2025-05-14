#!/bin/bash

# Time Comparison Diagnostic Script for OutlookAlerter
# This script tests the time comparison logic to ensure events are correctly identified as past, current, or upcoming

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

echo "===== OutlookAlerter Time Comparison Diagnostics ====="
echo "Current date: $(date)"
echo "System timezone: $(date +%Z)"
echo ""
echo "This script will test the time comparison logic in CalendarEvent.groovy"
echo "to ensure events are correctly identified as past, current, or upcoming"
echo ""

# Create a simple Java program to test time comparison logic
cat > "$SCRIPT_DIR/TimeComparisonDiagnostics.java" << 'EOL'
import com.unhuman.outlookalerter.CalendarEvent;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class TimeComparisonDiagnostics {
    public static void main(String[] args) {
        System.out.println("===== Time Comparison Logic Test =====");
        System.out.println("System timezone: " + ZoneId.systemDefault());
        
        // Create test event time points
        ZonedDateTime now = ZonedDateTime.now();
        System.out.println("Current time: " + now);
        
        // Test various time combinations
        testEventTimes(now);
        
        // Test different timezones
        testTimeZones();
        
        // Test edge cases
        testEdgeCases();
        
        System.out.println("\n===== Test complete =====");
    }
    
    private static void testEventTimes(ZonedDateTime now) {
        System.out.println("\n--- Testing various event time combinations ---");
        
        // Test past events
        System.out.println("\n1. PAST EVENTS (both start and end in the past)");
        testEvent(
            now.minus(2, ChronoUnit.HOURS), 
            now.minus(1, ChronoUnit.HOURS),
            "Past event (ended 1 hour ago)"
        );
        
        testEvent(
            now.minus(1, ChronoUnit.DAYS), 
            now.minus(23, ChronoUnit.HOURS),
            "Past event (ended yesterday)"
        );
        
        // Test current events
        System.out.println("\n2. CURRENT EVENTS (started in past, ends in future)");
        testEvent(
            now.minus(30, ChronoUnit.MINUTES), 
            now.plus(30, ChronoUnit.MINUTES),
            "Current event (30 minutes in, 30 minutes left)"
        );
        
        testEvent(
            now.minus(2, ChronoUnit.HOURS), 
            now.plus(10, ChronoUnit.MINUTES),
            "Current event (2 hours in, 10 minutes left)"
        );
        
        // Edge case: Just started
        testEvent(
            now, 
            now.plus(1, ChronoUnit.HOURS),
            "Just started event (starting right now)"
        );
        
        // Edge case: About to end
        testEvent(
            now.minus(59, ChronoUnit.MINUTES), 
            now.plus(1, ChronoUnit.MINUTES),
            "About to end event (ending in 1 minute)"
        );
        
        // Test future events
        System.out.println("\n3. FUTURE EVENTS (both start and end in future)");
        testEvent(
            now.plus(30, ChronoUnit.MINUTES), 
            now.plus(90, ChronoUnit.MINUTES),
            "Future event (starts in 30 minutes)"
        );
        
        testEvent(
            now.plus(1, ChronoUnit.DAYS), 
            now.plus(25, ChronoUnit.HOURS),
            "Future event (tomorrow)"
        );
        
        // Edge case: About to start
        testEvent(
            now.plus(2, ChronoUnit.MINUTES), 
            now.plus(1, ChronoUnit.HOURS),
            "About to start event (starts in 2 minutes)"
        );
    }
    
    private static void testTimeZones() {
        System.out.println("\n--- Testing events across different timezones ---");
        
        ZonedDateTime localNow = ZonedDateTime.now();
        
        // Get a set of common timezones to test
        List<String> timezonesToTest = Arrays.asList(
            "UTC", 
            "America/New_York", 
            "America/Los_Angeles", 
            "Europe/London", 
            "Asia/Tokyo", 
            "Australia/Sydney"
        );
        
        for (String timezone : timezonesToTest) {
            ZoneId zoneId = ZoneId.of(timezone);
            ZonedDateTime zoneNow = localNow.withZoneSameInstant(zoneId);
            
            System.out.println("\nTesting with timezone: " + timezone);
            System.out.println("Current time in " + timezone + ": " + zoneNow);
            
            // Test an event in this timezone that's happening now
            testEvent(
                zoneNow.minus(15, ChronoUnit.MINUTES),
                zoneNow.plus(15, ChronoUnit.MINUTES),
                "Current event in " + timezone
            );
            
            // Test a future event in this timezone
            testEvent(
                zoneNow.plus(1, ChronoUnit.HOURS),
                zoneNow.plus(2, ChronoUnit.HOURS),
                "Future event in " + timezone
            );
        }
    }
    
    private static void testEdgeCases() {
        System.out.println("\n--- Testing edge cases ---");
        
        ZonedDateTime now = ZonedDateTime.now();
        
        // Case 1: Null end time
        System.out.println("\nCase 1: Event with null end time");
        CalendarEvent nullEndEvent = new CalendarEvent();
        nullEndEvent.setId("null-end-test");
        nullEndEvent.setSubject("Test event with null end time");
        nullEndEvent.setStart(now.minus(30, ChronoUnit.MINUTES));
        nullEndEvent.setEnd(null);
        
        printEventStatus(nullEndEvent);
        
        // Case 2: Null start time
        System.out.println("\nCase 2: Event with null start time");
        CalendarEvent nullStartEvent = new CalendarEvent();
        nullStartEvent.setId("null-start-test");
        nullStartEvent.setSubject("Test event with null start time");
        nullStartEvent.setStart(null);
        nullStartEvent.setEnd(now.plus(30, ChronoUnit.MINUTES));
        
        printEventStatus(nullStartEvent);
        
        // Case 3: Both times null
        System.out.println("\nCase 3: Event with both times null");
        CalendarEvent nullBothEvent = new CalendarEvent();
        nullBothEvent.setId("null-both-test");
        nullBothEvent.setSubject("Test event with both times null");
        nullBothEvent.setStart(null);
        nullBothEvent.setEnd(null);
        
        printEventStatus(nullBothEvent);
        
        // Case 4: End time before start time (invalid case)
        System.out.println("\nCase 4: Event with end time before start time");
        CalendarEvent invalidTimesEvent = new CalendarEvent();
        invalidTimesEvent.setId("invalid-times-test");
        invalidTimesEvent.setSubject("Test event with end before start");
        invalidTimesEvent.setStart(now.plus(2, ChronoUnit.HOURS));
        invalidTimesEvent.setEnd(now.plus(1, ChronoUnit.HOURS));
        
        printEventStatus(invalidTimesEvent);
        
        // Case 5: Same start and end time
        System.out.println("\nCase 5: Event with same start and end time");
        CalendarEvent sameTimesEvent = new CalendarEvent();
        sameTimesEvent.setId("same-times-test");
        sameTimesEvent.setSubject("Test event with same start and end time");
        sameTimesEvent.setStart(now.plus(1, ChronoUnit.HOURS));
        sameTimesEvent.setEnd(now.plus(1, ChronoUnit.HOURS));
        
        printEventStatus(sameTimesEvent);
        
        // Case 6: All-day event today
        System.out.println("\nCase 6: All-day event today");
        ZonedDateTime startOfToday = now.truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime endOfToday = startOfToday.plus(1, ChronoUnit.DAYS).minus(1, ChronoUnit.SECONDS);
        
        CalendarEvent allDayEvent = new CalendarEvent();
        allDayEvent.setId("all-day-test");
        allDayEvent.setSubject("Test all-day event");
        allDayEvent.setStart(startOfToday);
        allDayEvent.setEnd(endOfToday);
        
        printEventStatus(allDayEvent);
    }
    
    private static void testEvent(ZonedDateTime start, ZonedDateTime end, String description) {
        System.out.println("\nTesting: " + description);
        
        CalendarEvent event = new CalendarEvent();
        event.setId("test-" + System.currentTimeMillis());
        event.setSubject(description);
        event.setStart(start);
        event.setEnd(end);
        
        printEventStatus(event);
    }
    
    private static void printEventStatus(CalendarEvent event) {
        try {
            System.out.println("Event: " + event.getSubject());
            System.out.println("Start: " + event.getStart());
            System.out.println("End: " + event.getEnd());
            System.out.println("Has ended: " + event.hasEnded());
            System.out.println("Is in progress: " + event.isInProgress());
            System.out.println("Minutes to start: " + event.getMinutesToStart());
            
            // Print current time in the event's timezone if possible
            if (event.getStart() != null) {
                ZonedDateTime nowInEventZone = ZonedDateTime.now(event.getStart().getZone());
                System.out.println("Current time in event timezone: " + nowInEventZone);
            }
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
EOL

# Compile the test program
javac -cp "$CLASSPATH" "$SCRIPT_DIR/TimeComparisonDiagnostics.java"

# Run the time comparison test
echo "Running time comparison test..."
java -cp "$CLASSPATH:$SCRIPT_DIR" TimeComparisonDiagnostics | tee "$SCRIPT_DIR/time-comparison-test.log"

# Clean up
rm "$SCRIPT_DIR/TimeComparisonDiagnostics.java" "$SCRIPT_DIR/TimeComparisonDiagnostics.class"

echo ""
echo "===== Time comparison test complete ====="
echo "The test results have been saved to: time-comparison-test.log"
echo "Review this file to verify that the time comparison logic is working correctly."
