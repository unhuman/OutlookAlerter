#!/bin/bash

# Test script for tentative meetings support
# This script runs the OutlookAlerter application in diagnostic mode
# to verify that it properly handles tentative meetings

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

echo "===== OutlookAlerter Tentative Meetings Test ====="
echo "Current date: $(date)"
echo "System timezone: $(date +%Z)"
echo ""
echo "This script will test the handling of tentative meetings"
echo ""

# Create a simple Java program to test tentative meetings support
cat > "$SCRIPT_DIR/TentativeMeetingsTest.java" << 'EOL'
import com.unhuman.outlookalerter.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class TentativeMeetingsTest {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    
    public static void main(String[] args) {
        try {
            System.out.println("===== Tentative Meetings Support Test =====");
            
            // Initialize with config file path
            String configPath = System.getProperty("user.home") + "/.outlookalerter/config.properties";
            ConfigManager configManager = new ConfigManager(configPath);
            OutlookClient client = new OutlookClient(configManager);
            
            System.out.println("\nRetrieving events to test response status support...");
            
            List<CalendarEvent> events = client.getUpcomingEvents();
            System.out.println("Found " + events.size() + " events");
            
            System.out.println("\n+----+------------------------+------------------------+------------------------+");
            System.out.println("| No | Subject                | Status                 | Start Time             |");
            System.out.println("+----+------------------------+------------------------+------------------------+");
            
            for (int i = 0; i < events.size(); i++) {
                CalendarEvent event = events.get(i);
                
                String subject = formatColumn(event.subject, 22);
                String status = formatColumn(event.responseStatus != null ? event.responseStatus : "Unknown", 22);
                String startTime = event.startTime != null ? formatColumn(event.startTime.format(TIME_FORMATTER), 22) : "N/A";
                
                System.out.printf("| %-2d | %-22s | %-22s | %-22s |\n", 
                                 i+1, subject, status, startTime);
            }
            
            System.out.println("+----+------------------------+------------------------+------------------------+");
            
            System.out.println("\nDetailed event information:");
            for (CalendarEvent event : events) {
                System.out.println("\n" + event);
            }
            
            System.out.println("\nTest completed successfully!");
            
        } catch (Exception e) {
            System.out.println("Error during test: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static String formatColumn(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 3) + "...";
    }
}
EOL

# Compile the test program
echo "Compiling tentative meetings test..."
javac -cp "$CLASSPATH" "$SCRIPT_DIR/TentativeMeetingsTest.java"

# Run the test program
echo "Running tentative meetings test..."
java -cp "$CLASSPATH:$SCRIPT_DIR" TentativeMeetingsTest

# Clean up
rm "$SCRIPT_DIR/TentativeMeetingsTest.java" "$SCRIPT_DIR/TentativeMeetingsTest.class"

echo "Tentative meetings test completed."
