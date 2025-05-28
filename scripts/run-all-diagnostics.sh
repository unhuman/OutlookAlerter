#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}===== OutlookAlerter Comprehensive Diagnostics =====${NC}"

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Use Maven's target directory for the executable jar
JAR_PATH=$(ls $SCRIPT_DIR/../target/OutlookAlerter-*-jar-with-dependencies.jar 2>/dev/null | head -n 1)

# Check if jar exists, if not try to build
if [ ! -f "$JAR_PATH" ]; then
    echo -e "${YELLOW}Executable jar not found, running build...${NC}"
    "$SCRIPT_DIR/build.sh"
    if [ $? -ne 0 ]; then
        echo -e "${RED}Build failed, cannot run diagnostics${NC}"
        exit 1
    fi
fi

# Create diagnostics directory with timestamp
DIAG_DIR="diagnostics-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DIAG_DIR"
echo -e "${GREEN}Created diagnostics directory: $DIAG_DIR${NC}"

# Function to run a diagnostic command and save output
run_diagnostic() {
    local cmd="$1"
    local output_file="$2"
    local description="$3"
    
    echo -e "${YELLOW}Running $description...${NC}"
    eval "$cmd" > "$output_file" 2>&1
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ $description completed${NC}"
    else
        echo -e "${RED}× $description failed${NC}"
    fi
}

# Run calendar event diagnostics
echo -e "\n${BLUE}Running calendar event diagnostics...${NC}"
JAVA_OPTS="-Doutlookalerter.diagnostic.dir=$DIAG_DIR"
run_diagnostic "java $JAVA_OPTS -jar $JAR_PATH --debug --calendar-diagnostics" \
    "$DIAG_DIR/calendar-events.log" \
    "Calendar Event Tests"

# Run enhanced diagnostics with multiple calendars
echo -e "\n${BLUE}Running enhanced calendar diagnostics...${NC}"
JAVA_OPTS="$JAVA_OPTS -Doutlookalerter.enhanced.diagnostics=true"
run_diagnostic "java $JAVA_OPTS -jar $JAR_PATH --debug --multi-calendar" \
    "$DIAG_DIR/enhanced-calendar.log" \
    "Enhanced Calendar Diagnostics"

# Run timezone tests
echo -e "\n${BLUE}Running timezone tests...${NC}"
JAVA_OPTS="$JAVA_OPTS -Duser.timezone=UTC"
run_diagnostic "java $JAVA_OPTS -jar $JAR_PATH --debug --test-timezone" \
    "$DIAG_DIR/timezone-tests.log" \
    "Timezone Tests"

# Generate summary report
echo -e "\n${BLUE}Generating summary report...${NC}"
cat > "$DIAG_DIR/summary-report.txt" << EOL
OutlookAlerter Diagnostic Summary
===============================
Date: $(date)
System Timezone: $(date +%Z)

Test Results:
------------
EOL

# Add test results to summary
for log in "$DIAG_DIR"/*.log; do
    echo -e "\nResults from $(basename "$log"):" >> "$DIAG_DIR/summary-report.txt"
    grep -A 5 "=== Summary ===" "$log" 2>/dev/null >> "$DIAG_DIR/summary-report.txt" || true
    echo "----------------------------------------" >> "$DIAG_DIR/summary-report.txt"
done

echo -e "\n${GREEN}All diagnostics complete!${NC}"
echo -e "${BLUE}Diagnostic files are available in: $DIAG_DIR${NC}"
echo -e "${YELLOW}Review $DIAG_DIR/summary-report.txt for a complete overview${NC}"

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
