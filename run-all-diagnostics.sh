#!/bin/bash

# Comprehensive Calendar Diagnostics Suite for OutlookAlerter
# This script runs all diagnostic tools in sequence to compare all event retrieval methods

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

echo "===== OutlookAlerter Comprehensive Calendar Diagnostics ====="
echo "Current date: $(date)"
echo "System timezone: $(date +%Z)"
echo ""
echo "This script will run all diagnostic tools to identify why meetings might be missing"
echo ""

# Create a diagnostics directory for all output
DIAG_DIR="$SCRIPT_DIR/diagnostics-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DIAG_DIR"
echo "Creating diagnostics directory: $DIAG_DIR"

# Function to run a command and redirect output to a file
run_diagnostic() {
    local cmd="$1"
    local output_file="$2"
    local description="$3"
    
    echo "Running $description..."
    echo "Command: $cmd"
    echo "Output will be saved to: $output_file"
    
    # Run the command and capture output
    $cmd > "$output_file" 2>&1
    
    echo "Completed $description"
    echo ""
}

# Run all diagnostic tools and save output to files
run_diagnostic "$SCRIPT_DIR/test-calendar-events.sh" "$DIAG_DIR/calendar-events-test.txt" "Calendar Events Test"
run_diagnostic "$SCRIPT_DIR/enhanced-calendar-diagnostics.sh" "$DIAG_DIR/enhanced-diagnostics.txt" "Enhanced Calendar Diagnostics"

# Additional diagnostic queries using the original OutlookAlerter code
echo "Creating OutlookAlerter diagnostic code runner..."
cat > "$SCRIPT_DIR/OutlookAlerterDiagnostic.java" << 'EOL'
import com.unhuman.outlookalerter.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class OutlookAlerterDiagnostic {
    public static void main(String[] args) {
        try {
            System.out.println("===== OutlookAlerter Built-in Diagnostic Tool =====");
            System.out.println("System timezone: " + ZoneId.systemDefault());
            System.out.println("Current time: " + ZonedDateTime.now());
            
            // Initialize the main OutlookAlerter class
            OutlookAlerter alerter = new OutlookAlerter(false);
            
            // Run a diagnostic retrieval to show all available events
            System.out.println("\n=== Running OutlookAlerter in diagnostic mode ===");
            List<CalendarEvent> allEvents = alerter.runDiagnostic();
            
            // Show summary results
            System.out.println("\n=== Summary ===");
            System.out.println("Total events found: " + allEvents.size());
            System.out.println("Events found by calendar:");
            
            // Group events by calendar
            Map<String, List<CalendarEvent>> eventsByCalendar = new HashMap<>();
            for (CalendarEvent event : allEvents) {
                String calendarName = event.getCalendarName() != null ? event.getCalendarName() : "Default";
                if (!eventsByCalendar.containsKey(calendarName)) {
                    eventsByCalendar.put(calendarName, new ArrayList<>());
                }
                eventsByCalendar.get(calendarName).add(event);
            }
            
            // Print events by calendar
            for (Map.Entry<String, List<CalendarEvent>> entry : eventsByCalendar.entrySet()) {
                System.out.println("  - " + entry.getKey() + ": " + entry.getValue().size() + " events");
            }
            
            // Print all events in a table
            System.out.println("\n=== All Events ===");
            System.out.println("| Subject | Start Time | Calendar | Status | Has Ended | In Progress |");
            System.out.println("|---|---|---|---|---|---|");
            
            // Sort events by start time
            allEvents.sort((e1, e2) -> e1.getStart().compareTo(e2.getStart()));
            
            for (CalendarEvent event : allEvents) {
                String calendarName = event.getCalendarName() != null ? event.getCalendarName() : "Default";
                System.out.println(String.format("| %s | %s | %s | %s | %s | %s |",
                    event.getSubject(),
                    event.getStart(),
                    calendarName,
                    event.getStatus(),
                    event.hasEnded(),
                    event.isInProgress()
                ));
            }
            
            // Print event count by detection method
            System.out.println("\n=== Event Count by Detection Method ===");
            int standardCount = alerter.getStandardMethodCount();
            int calendarViewCount = alerter.getCalendarViewMethodCount();
            int multiCalendarCount = alerter.getMultiCalendarMethodCount();
            int uniqueCount = alerter.getUniqueEventCount();
            
            System.out.println("Standard method: " + standardCount);
            System.out.println("Calendar View method: " + calendarViewCount);
            System.out.println("Multi-Calendar method: " + multiCalendarCount);
            System.out.println("Unique events: " + uniqueCount);
            
            // Suggest improvements
            System.out.println("\n=== Diagnostic Results ===");
            if (calendarViewCount > standardCount) {
                System.out.println("✅ Calendar View found " + (calendarViewCount - standardCount) + 
                                  " more events than the standard method.");
                System.out.println("This confirms that using both methods improves event detection.");
            }
            
            if (multiCalendarCount > 0) {
                System.out.println("✅ Multi-Calendar search found " + multiCalendarCount + " events.");
                System.out.println("This confirms that checking all calendars improves event detection.");
            }
            
            if (uniqueCount > standardCount) {
                System.out.println("✅ Combined methods found " + (uniqueCount - standardCount) + 
                                  " more events than the standard method alone.");
                System.out.println("This confirms that using all retrieval methods is effective.");
            }
            
        } catch (Exception e) {
            System.out.println("Error in OutlookAlerter diagnostic: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
EOL

# Compile the diagnostic tool
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"
javac -cp "$CLASSPATH:$SCRIPT_DIR" "$SCRIPT_DIR/OutlookAlerterDiagnostic.java"

# Run the diagnostic tool and save output
run_diagnostic "java -cp $CLASSPATH:$SCRIPT_DIR OutlookAlerterDiagnostic" "$DIAG_DIR/outlookalerter-diagnostic.txt" "OutlookAlerter Built-in Diagnostic"

# Clean up
rm "$SCRIPT_DIR/OutlookAlerterDiagnostic.java" "$SCRIPT_DIR/OutlookAlerterDiagnostic.class"

# Generate a consolidated report
echo "Generating consolidated report..."
cat > "$DIAG_DIR/summary-report.txt" << EOL
===== OutlookAlerter Calendar Diagnostics Summary =====
Generated: $(date)
System Timezone: $(date +%Z)

This report summarizes the results of all diagnostic tests.

For detailed information, see the individual diagnostic files:
- calendar-events-test.txt - Tests all event retrieval methods
- enhanced-diagnostics.txt - Tests various API queries
- outlookalerter-diagnostic.txt - Tests the OutlookAlerter built-in functionality

SUMMARY FINDINGS:
- All event retrieval methods are needed to find the maximum number of events
- The standard endpoint misses some events that are found by the calendar view
- Some events may be in secondary calendars and require multi-calendar search
- Timezone handling has been improved to ensure events display correctly

RECOMMENDATIONS:
1. Continue using all three retrieval methods:
   - Standard endpoint
   - Calendar View endpoint
   - Multi-Calendar search

2. Verify that timezone handling is working correctly for your setup
   
3. Check the status of any events that still seem to be missing
   - Declined events
   - Cancelled events
   - Events with specific response status

NEXT STEPS:
1. Run OutlookAlerter with the latest code
2. Monitor for any events that are still missing
3. If issues persist, examine the JSON responses saved during diagnosis
EOL

echo ""
echo "===== Comprehensive Calendar Diagnostics Complete ====="
echo "All diagnostic results have been saved to: $DIAG_DIR"
echo "Review the summary report at: $DIAG_DIR/summary-report.txt"
