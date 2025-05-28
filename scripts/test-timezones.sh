#!/bin/bash

# Timezone test script for OutlookAlerter
# This script tests how the application handles various timezones

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

# Create a simple Java program to test timezone handling
cat > "$SCRIPT_DIR/TimezoneTest.java" << 'EOL'
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

public class TimezoneTest {
    public static void main(String[] args) {
        System.out.println("===== OutlookAlerter Timezone Test =====");
        System.out.println("System timezone: " + ZoneId.systemDefault());
        System.out.println("Current time: " + ZonedDateTime.now());
        System.out.println("Current UTC time: " + ZonedDateTime.now(ZoneId.of("UTC")));
        
        // Test sample timezones
        List<String> timezones = Arrays.asList(
            "America/New_York", "America/Los_Angeles", "America/Chicago", 
            "Europe/London", "Europe/Paris", "Europe/Moscow",
            "Asia/Tokyo", "Asia/Singapore", "Asia/Dubai",
            "Australia/Sydney", "Pacific/Auckland"
        );
        
        System.out.println("\nTesting common timezones:");
        for (String timezone : timezones) {
            try {
                ZoneId zoneId = ZoneId.of(timezone);
                ZonedDateTime time = ZonedDateTime.now(zoneId);
                System.out.println(String.format("%-20s %s (offset: %s)", 
                    timezone + ":", 
                    time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    time.getOffset()));
            } catch (Exception e) {
                System.out.println(timezone + ": ERROR - " + e.getMessage());
            }
        }
        
        // Test timezone conversion for a sample event
        System.out.println("\nTesting timezone conversion for a sample event:");
        try {
            // Sample event from Graph API (UTC)
            String sampleEventTime = "2025-05-14T17:00:00";
            String sampleEventTimeZone = "UTC";
            
            // Parse the event time
            java.time.LocalDateTime localDateTime = java.time.LocalDateTime.parse(
                sampleEventTime,
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
            );
            
            // Show the event in its original timezone
            ZoneId originalZoneId = ZoneId.of(sampleEventTimeZone);
            ZonedDateTime eventTimeOriginal = localDateTime.atZone(originalZoneId);
            System.out.println("Original event time: " + eventTimeOriginal);
            
            // Test conversion to various timezones
            System.out.println("\nEvent time in different timezones:");
            for (String timezone : timezones) {
                try {
                    ZoneId zoneId = ZoneId.of(timezone);
                    ZonedDateTime convertedTime = eventTimeOriginal.withZoneSameInstant(zoneId);
                    System.out.println(String.format("%-20s %s", 
                        timezone + ":", 
                        convertedTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
                } catch (Exception e) {
                    System.out.println(timezone + ": ERROR - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.out.println("Error in timezone conversion test: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Test for timezone presence and validity
        if (args.length > 0) {
            System.out.println("\nTesting custom timezone: " + args[0]);
            try {
                ZoneId customZone = ZoneId.of(args[0]);
                ZonedDateTime customTime = ZonedDateTime.now(customZone);
                System.out.println("Valid timezone! Current time: " + customTime);
                System.out.println("Offset from UTC: " + customTime.getOffset());
            } catch (Exception e) {
                System.out.println("Invalid timezone: " + e.getMessage());
                System.out.println("Available Java timezone IDs: " + ZoneId.getAvailableZoneIds().size());
            }
        }
    }
}
EOL

# Compile the timezone test
javac -d "$SCRIPT_DIR" "$SCRIPT_DIR/TimezoneTest.java"

# Run the timezone test
if [ "$1" != "" ]; then
    java -cp "$SCRIPT_DIR" TimezoneTest "$1"
else
    java -cp "$SCRIPT_DIR" TimezoneTest
fi

# Clean up
rm "$SCRIPT_DIR/TimezoneTest.java" "$SCRIPT_DIR/TimezoneTest.class"
