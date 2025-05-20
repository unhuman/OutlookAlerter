#!/bin/bash

#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}===== OutlookAlerter Missing Meetings Diagnostics =====${NC}"

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Use Maven's target directory for the executable jar
JAR_PATH="$SCRIPT_DIR/target/OutlookAlerter-1.0-SNAPSHOT-jar-with-dependencies.jar"

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

# Print system info
echo -e "\n${BLUE}System Information:${NC}"
echo -e "${YELLOW}Current date: $(date)"
echo -e "System timezone: $(date +%Z)${NC}"

# Set up logging
LOGGING_PROPS="$DIAG_DIR/logging.properties"
cat > "$LOGGING_PROPS" << EOL
handlers=java.util.logging.FileHandler, java.util.logging.ConsoleHandler
java.util.logging.FileHandler.pattern=$DIAG_DIR/missing-meetings.log
java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
java.util.logging.FileHandler.level=ALL
java.util.logging.ConsoleHandler.level=ALL
java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter
java.net.http.level=ALL
EOL

# Run different calendar queries to identify missing meetings
echo -e "\n${BLUE}Running calendar queries...${NC}"

# Run with different time windows
echo -e "${YELLOW}Testing different time windows...${NC}"
for hours in 1 2 4 8 24; do
    echo -e "Testing $hours hour window..."
    java -Djava.util.logging.config.file=$LOGGING_PROPS \
         -Doutlookalerter.diagnostic.dir=$DIAG_DIR \
         -Doutlookalerter.time.window=$hours \
         -jar "$JAR_PATH" --debug --missing-meetings > "$DIAG_DIR/time-window-${hours}h.log" 2>&1
done

# Run with different calendars
echo -e "\n${YELLOW}Testing different calendars...${NC}"
java -Djava.util.logging.config.file=$LOGGING_PROPS \
     -Doutlookalerter.diagnostic.dir=$DIAG_DIR \
     -Doutlookalerter.all.calendars=true \
     -jar "$JAR_PATH" --debug --missing-meetings > "$DIAG_DIR/all-calendars.log" 2>&1

# Generate summary report
echo -e "\n${BLUE}Analyzing results...${NC}"
cat > "$DIAG_DIR/summary.txt" << EOL
OutlookAlerter Missing Meetings Analysis
======================================
Date: $(date)
System Timezone: $(date +%Z)

Summary of Findings:
------------------
EOL

# Add event counts from each test to summary
for log in "$DIAG_DIR"/*.log; do
    echo "Results from $(basename "$log"):" >> "$DIAG_DIR/summary.txt"
    grep "Found.*events" "$log" 2>/dev/null >> "$DIAG_DIR/summary.txt" || true
    echo "----------------------------------------" >> "$DIAG_DIR/summary.txt"
done

echo -e "\n${GREEN}Diagnostics complete!${NC}"
echo -e "${BLUE}Results are available in: $DIAG_DIR${NC}"
echo -e "${YELLOW}Please check $DIAG_DIR/summary.txt for analysis results${NC}"

# Create a simple Java program to perform calendar diagnostics
cat > "$SCRIPT_DIR/CalendarDiagnostics.java" << 'EOL'
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

public class CalendarDiagnostics {
    private static final String GRAPH_ENDPOINT = "https://graph.microsoft.com/v1.0";
    
    public static void main(String[] args) {
        try {
            System.out.println("===== Microsoft Graph API Calendar Diagnostics =====");
            
            // Get token from config file
            String token = getAccessToken();
            if (token == null || token.isEmpty()) {
                System.out.println("No access token found. Please run the application first to authenticate.");
                return;
            }
            
            // Get current time and boundaries
            ZonedDateTime now = ZonedDateTime.now();
            ZonedDateTime startOfDay = now.withHour(0).withMinute(0).withSecond(0);
            ZonedDateTime endOfTomorrow = now.plusDays(1).withHour(23).withMinute(59).withSecond(59);
            
            System.out.println("System timezone: " + ZoneId.systemDefault());
            System.out.println("Current time: " + now);
            System.out.println("Start time for query: " + startOfDay);
            System.out.println("End time for query: " + endOfTomorrow);
            System.out.println();
            
            // Test a variety of different query approaches
            testQueries(token, startOfDay, endOfTomorrow);
            
            // Compare query results
            compareQueryResults();
            
        } catch (Exception e) {
            System.out.println("Error during calendar diagnostics: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static String getAccessToken() {
        try {
            // Try to read from config file
            String homePath = System.getProperty("user.home");
            File configFile = new File(homePath + "/.outlookalerter/config.properties");
            
            if (!configFile.exists()) {
                System.out.println("Config file not found at: " + configFile.getAbsolutePath());
                return null;
            }
            
            Properties properties = new Properties();
            properties.load(new FileInputStream(configFile));
            
            return properties.getProperty("accessToken");
        } catch (Exception e) {
            System.out.println("Error reading config file: " + e.getMessage());
            return null;
        }
    }
    
    private static void testQueries(String token, ZonedDateTime startOfDay, ZonedDateTime endOfTomorrow) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            
            // Test different query approaches
            System.out.println("===== TESTING DIFFERENT QUERY APPROACHES =====");
            
            // Current approach used by OutlookAlerter
            executeQuery(client, token, createQuery1(startOfDay, endOfTomorrow), "Current OutlookAlerter approach");
            
            // Alternative approach using different time format
            executeQuery(client, token, createQuery2(startOfDay, endOfTomorrow), "Alternative time format approach");
            
            // Alternative approach using view parameter instead of filter
            executeQuery(client, token, createQuery3(), "Calendar view approach");
            
            // Primary calendar query - perhaps you have meetings in multiple calendars
            executeQuery(client, token, createQuery4(), "Primary calendar specific query");
            
            // Show all calendars
            executeQuery(client, token, createAllCalendarsQuery(), "List all available calendars");
            
        } catch (Exception e) {
            System.out.println("Error testing queries: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static String createQuery1(ZonedDateTime startOfDay, ZonedDateTime endOfTomorrow) throws Exception {
        // This mirrors our current approach in OutlookAlerter
        String baseUrl = GRAPH_ENDPOINT + "/me/calendar/events";
        String startTimeStr = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String endTimeStr = endOfTomorrow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        
        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        urlBuilder.append("?$select=id,subject,organizer,start,end,location,isOnlineMeeting");
        
        // Clean and encode filter parameter - similar to our current cleaning
        startTimeStr = startTimeStr.replaceAll("\\.[0-9]+-[0-9]+:[0-9]+", "Z");
        startTimeStr = startTimeStr.replaceAll("\\.[0-9]+\\+[0-9]+:[0-9]+", "Z");
        startTimeStr = startTimeStr.replaceAll("\\.[0-9]+Z", "Z");
        
        endTimeStr = endTimeStr.replaceAll("\\.[0-9]+-[0-9]+:[0-9]+", "Z");
        endTimeStr = endTimeStr.replaceAll("\\.[0-9]+\\+[0-9]+:[0-9]+", "Z");
        endTimeStr = endTimeStr.replaceAll("\\.[0-9]+Z", "Z");
        
        String filterParam = "start/dateTime ge '" + startTimeStr + "' and start/dateTime le '" + endTimeStr + "'";
        urlBuilder.append("&$filter=").append(URLEncoder.encode(filterParam, StandardCharsets.UTF_8));
        
        // Add orderby and maximum results
        urlBuilder.append("&$orderby=").append(URLEncoder.encode("start/dateTime asc", StandardCharsets.UTF_8));
        urlBuilder.append("&$top=50");
        
        return urlBuilder.toString();
    }
    
    private static String createQuery2(ZonedDateTime startOfDay, ZonedDateTime endOfTomorrow) throws Exception {
        // Alternative approach using just date format for filter
        String baseUrl = GRAPH_ENDPOINT + "/me/calendar/events";
        String startDateStr = startOfDay.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String endDateStr = endOfTomorrow.format(DateTimeFormatter.ISO_LOCAL_DATE);
        
        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        urlBuilder.append("?$select=id,subject,organizer,start,end,location,isOnlineMeeting");
        
        String filterParam = "start/dateTime ge '" + startDateStr + "' and start/dateTime le '" + endDateStr + "'";
        urlBuilder.append("&$filter=").append(URLEncoder.encode(filterParam, StandardCharsets.UTF_8));
        
        urlBuilder.append("&$orderby=").append(URLEncoder.encode("start/dateTime asc", StandardCharsets.UTF_8));
        urlBuilder.append("&$top=50");
        
        return urlBuilder.toString();
    }
    
    private static String createQuery3() throws Exception {
        // Using calendar view query which might catch more events
        String baseUrl = GRAPH_ENDPOINT + "/me/calendarView";
        
        // Get 10 days of calendar view to see if meetings are showing up at all
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime startOfDay = now.withHour(0).withMinute(0).withSecond(0);
        ZonedDateTime tenDaysLater = now.plusDays(10).withHour(23).withMinute(59).withSecond(59);
        
        String startTimeParam = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String endTimeParam = tenDaysLater.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        
        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        urlBuilder.append("?startDateTime=").append(URLEncoder.encode(startTimeParam, StandardCharsets.UTF_8));
        urlBuilder.append("&endDateTime=").append(URLEncoder.encode(endTimeParam, StandardCharsets.UTF_8));
        urlBuilder.append("&$select=id,subject,organizer,start,end,location,isOnlineMeeting");
        urlBuilder.append("&$orderby=").append(URLEncoder.encode("start/dateTime asc", StandardCharsets.UTF_8));
        urlBuilder.append("&$top=100");
        
        return urlBuilder.toString();
    }
    
    private static String createQuery4() throws Exception {
        // Query the primary calendar specifically
        String baseUrl = GRAPH_ENDPOINT + "/me/calendars";
        
        return baseUrl + "?$select=id,name,owner,canShare,canEdit&$top=50";
    }
    
    private static String createAllCalendarsQuery() throws Exception {
        // List all calendars available to the user
        return GRAPH_ENDPOINT + "/me/calendars?$select=id,name,owner,canShare,canEdit&$top=50";
    }
    
    private static void executeQuery(HttpClient client, String token, String url, String description) {
        try {
            System.out.println("\n----- " + description + " -----");
            System.out.println("URL: " + url);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                System.out.println("SUCCESS (200 OK)");
                
                String body = response.body();
                
                // Print summary of results (count of events)
                if (body.contains("\"value\":")) {
                    // Extract the values array to count items
                    int startIndex = body.indexOf("\"value\":");
                    int arrayStartIndex = body.indexOf('[', startIndex);
                    int arrayEndIndex = findMatchingCloseBracket(body, arrayStartIndex);
                    
                    if (arrayStartIndex > 0 && arrayEndIndex > arrayStartIndex) {
                        String valuesArray = body.substring(arrayStartIndex, arrayEndIndex + 1);
                        int eventCount = countEvents(valuesArray);
                        System.out.println("Found " + eventCount + " events/calendars in response");
                        
                        // For calendar lists, show calendar names
                        if (description.contains("calendar")) {
                            printCalendarNames(body);
                        } else {
                            // For events, print subject lines
                            printEventSubjects(body);
                        }
                    }
                } else {
                    System.out.println("Response doesn't contain a 'value' array");
                    System.out.println(body);
                }
            } else {
                System.out.println("ERROR: " + response.statusCode());
                System.out.println(response.body());
            }
        } catch (Exception e) {
            System.out.println("Error executing query: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static int findMatchingCloseBracket(String text, int openBracketIndex) {
        int level = 0;
        for (int i = openBracketIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') {
                level++;
            } else if (c == ']') {
                level--;
                if (level == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
    
    private static int countEvents(String valuesArray) {
        int count = 0;
        int index = 0;
        
        while (true) {
            index = valuesArray.indexOf("{", index + 1);
            if (index == -1) break;
            
            count++;
        }
        
        return count;
    }
    
    private static void printEventSubjects(String body) {
        Scanner scanner = new Scanner(body);
        System.out.println("\n===== COMPLETE EVENT LIST =====");
        int count = 0;
        
        // Store all event info for later comparison
        List<EventInfo> events = new ArrayList<>();
        
        // Save the raw JSON for debugging
        String jsonFilePath = System.getProperty("user.home") + "/OutlookAlerter_event_data_" + 
                              System.currentTimeMillis() + ".json";
        try {
            java.io.FileWriter fw = new java.io.FileWriter(jsonFilePath);
            fw.write(body);
            fw.close();
            System.out.println("Full JSON response saved to: " + jsonFilePath);
        } catch (Exception e) {
            System.out.println("Could not save JSON data: " + e.getMessage());
        }
        
        // First pass - collect all events to get proper count
        scanner = new Scanner(body);
        Map<String, EventInfo> eventsMap = new HashMap<>();
        
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (line.contains("\"subject\"")) {
                int startIndex = line.indexOf("\"subject\"") + 10;
                int valueStart = line.indexOf("\"", startIndex) + 1;
                int valueEnd = line.indexOf("\"", valueStart);
                
                if (valueStart > 0 && valueEnd > valueStart) {
                    // Extract the subject
                    String subject = line.substring(valueStart, valueEnd);
                    
                    // Get event details
                    String id = null;
                    String startTime = null;
                    String endTime = null;
                    String organizer = null;
                    String location = null;
                    boolean isOnlineMeeting = false;
                    
                    // Save scanner position
                    scanner.mark(100000); // Set a large read ahead limit
                    
                    // Look for additional event details in upcoming lines
                    for (int i = 0; i < 30 && scanner.hasNextLine(); i++) {
                        String nextLine = scanner.nextLine();
                        
                        if (nextLine.contains("\"id\"")) {
                            id = extractValueFromLine(nextLine, "id");
                        } else if (nextLine.contains("\"start\"")) {
                            // Found start object, next line should have dateTime
                            if (scanner.hasNextLine()) {
                                String dateTimeLine = scanner.nextLine();
                                if (dateTimeLine.contains("\"dateTime\"")) {
                                    startTime = extractValueFromLine(dateTimeLine, "dateTime");
                                }
                            }
                        } else if (nextLine.contains("\"end\"")) {
                            // Found end object, next line should have dateTime
                            if (scanner.hasNextLine()) {
                                String dateTimeLine = scanner.nextLine();
                                if (dateTimeLine.contains("\"dateTime\"")) {
                                    endTime = extractValueFromLine(dateTimeLine, "dateTime");
                                }
                            }
                        } else if (nextLine.contains("\"organizer\"")) {
                            // Found organizer object, look for emailAddress and name
                            for (int j = 0; j < 5 && scanner.hasNextLine(); j++) {
                                String orgLine = scanner.nextLine();
                                if (orgLine.contains("\"name\"")) {
                                    organizer = extractValueFromLine(orgLine, "name");
                                    break;
                                }
                            }
                        } else if (nextLine.contains("\"location\"")) {
                            // Found location object, look for displayName
                            for (int j = 0; j < 5 && scanner.hasNextLine(); j++) {
                                String locLine = scanner.nextLine();
                                if (locLine.contains("\"displayName\"")) {
                                    location = extractValueFromLine(locLine, "displayName");
                                    break;
                                }
                            }
                        } else if (nextLine.contains("\"isOnlineMeeting\"")) {
                            String value = extractValueFromLine(nextLine, "isOnlineMeeting");
                            isOnlineMeeting = "true".equalsIgnoreCase(value);
                        }
                        
                        // If we found a new event, we've gone too far
                        if (nextLine.contains("\"subject\"") && i > 0) {
                            break;
                        }
                    }
                    
                    // Reset to after the subject line
                    scanner.reset();
                    
                    // Create and store the event info
                    EventInfo event = new EventInfo(id, subject, startTime);
                    event.endTime = endTime;
                    event.organizer = organizer;
                    event.location = location;
                    event.isOnlineMeeting = isOnlineMeeting;
                    
                    if (id != null) {
                        eventsMap.put(id, event);
                    } else {
                        events.add(event);
                    }
                }
            }
        }
        
        scanner.close();
        
        // Add all events from the map
        events.addAll(eventsMap.values());
        
        // Sort events by start time if available
        events.sort((e1, e2) -> {
            if (e1.startTime == null && e2.startTime == null) return 0;
            if (e1.startTime == null) return 1;
            if (e2.startTime == null) return -1;
            return e1.startTime.compareTo(e2.startTime);
        });
        
        // Print all events with details
        System.out.println("Found " + events.size() + " events:");
        System.out.println("\n+------+------------------------+------------------------+------------------------+");
        System.out.println("| No.  | Subject                | Start Time             | Organizer              |");
        System.out.println("+------+------------------------+------------------------+------------------------+");
        
        for (int i = 0; i < events.size(); i++) {
            EventInfo event = events.get(i);
            
            // Format the strings to fit within the table columns
            String subjectDisplay = formatForTable(event.subject, 22);
            String startTimeDisplay = formatForTable(event.startTime, 22);
            String organizerDisplay = formatForTable(event.organizer, 22);
            
            System.out.println(String.format("| %-4d | %-22s | %-22s | %-22s |", 
                              i+1, subjectDisplay, startTimeDisplay, organizerDisplay));
        }
        
        System.out.println("+------+------------------------+------------------------+------------------------+");
        
        // Save the full list of events for later analysis
        allQueryEvents.add(events);
        
        System.out.println("\nTotal events found: " + events.size());
    }
    
    private static String extractValueFromLine(String line, String fieldName) {
        int startIndex = line.indexOf("\"" + fieldName + "\"") + fieldName.length() + 3;
        int valueStart = line.indexOf("\"", startIndex) + 1;
        int valueEnd = line.indexOf("\"", valueStart);
        
        if (valueStart > 0 && valueEnd > valueStart) {
            return line.substring(valueStart, valueEnd);
        }
        
        // For boolean values
        if (line.contains("\"" + fieldName + "\": true") || line.contains("\"" + fieldName + "\": false")) {
            int boolStart = line.indexOf("\"" + fieldName + "\"") + fieldName.length() + 3;
            int commaPos = line.indexOf(",", boolStart);
            int bracePos = line.indexOf("}", boolStart);
            int endPos = commaPos > 0 ? commaPos : bracePos;
            
            if (endPos > 0) {
                return line.substring(boolStart, endPos).trim();
            }
        }
        
        return null;
    }
    
    private static String formatForTable(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 3) + "...";
    }
    
    // Helper class to store event information
    private static class EventInfo {
        String id;
        String subject;
        String startTime;
        String endTime;
        String organizer;
        String location;
        boolean isOnlineMeeting;
        
        EventInfo(String id, String subject, String startTime) {
            this.id = id;
            this.subject = subject;
            this.startTime = startTime;
        }
    }
    
    // List to store events from each query for comparison
    private static List<List<EventInfo>> allQueryEvents = new ArrayList<>();
    
    private static String extractNearbyValue(Scanner scanner, String fieldName, int maxLines) {
        // Save scanner position
        int position = 0;
        if (scanner.hasNextLine()) {
            String currentLine = scanner.nextLine();
            position = scanner.findWithinHorizon("\\A", 0).length();
            
            // Look for the field in the current line
            if (currentLine.contains("\"" + fieldName + "\"")) {
                int startIndex = currentLine.indexOf("\"" + fieldName + "\"") + fieldName.length() + 3;
                int valueStart = currentLine.indexOf("\"", startIndex) + 1;
                int valueEnd = currentLine.indexOf("\"", valueStart);
                
                if (valueStart > 0 && valueEnd > valueStart) {
                    return currentLine.substring(valueStart, valueEnd);
                }
            }
        }
        
        return null;
    }
    
    private static String extractStartTime(Scanner scanner, int maxLines) {
        // Try to find start time info
        for (int i = 0; i < maxLines && scanner.hasNextLine(); i++) {
            String line = scanner.nextLine();
            if (line.contains("\"start\"")) {
                // Look for the dateTime field
                for (int j = 0; j < 3 && scanner.hasNextLine(); j++) {
                    String timeLine = scanner.nextLine();
                    if (timeLine.contains("\"dateTime\"")) {
                        int startIndex = timeLine.indexOf("\"dateTime\"") + 11;
                        int valueStart = timeLine.indexOf("\"", startIndex) + 1;
                        int valueEnd = timeLine.indexOf("\"", valueStart);
                        
                        if (valueStart > 0 && valueEnd > valueStart) {
                            return timeLine.substring(valueStart, valueEnd);
                        }
                    }
                }
                break;
            }
        }
        
        return null;
    }
    
    private static void printCalendarNames(String body) {
        Scanner scanner = new Scanner(body);
        System.out.println("\n===== COMPLETE CALENDAR LIST =====");
        
        List<CalendarInfo> calendars = new ArrayList<>();
        
        // Process calendar data
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (line.contains("\"name\"")) {
                int startIndex = line.indexOf("\"name\"") + 7;
                int valueStart = line.indexOf("\"", startIndex) + 1;
                int valueEnd = line.indexOf("\"", valueStart);
                
                if (valueStart > 0 && valueEnd > valueStart) {
                    String name = line.substring(valueStart, valueEnd);
                    
                    // Look for additional calendar details
                    String id = null;
                    String owner = null;
                    boolean canShare = false;
                    boolean canEdit = false;
                    
                    // Store the lines that follow in an array
                    List<String> followingLines = new ArrayList<>();
                    for (int i = 0; i < 15 && scanner.hasNextLine(); i++) {
                        followingLines.add(scanner.nextLine());
                    }
                    
                    // Search nearby lines for ID and other properties
                    for (String nextLine : followingLines) {
                        if (nextLine.contains("\"id\"")) {
                            id = extractValueFromLine(nextLine, "id");
                        } else if (nextLine.contains("\"owner\"")) {
                            // Found owner object but can't easily get details
                            owner = "Found";
                        } else if (nextLine.contains("\"canShare\"")) {
                            String value = extractValueFromLine(nextLine, "canShare");
                            canShare = "true".equalsIgnoreCase(value);
                        } else if (nextLine.contains("\"canEdit\"")) {
                            String value = extractValueFromLine(nextLine, "canEdit");
                            canEdit = "true".equalsIgnoreCase(value);
                        }
                    }
                    
                    // Create scanner for the remaining lines to continue parsing
                    String remainingText = String.join("\n", followingLines);
                    scanner = new Scanner(remainingText + (scanner.hasNext() ? "\n" + scanner.nextLine() : ""));
                    
                    // Create and store calendar info
                    CalendarInfo calendar = new CalendarInfo(id, name, owner);
                    calendar.canShare = canShare;
                    calendar.canEdit = canEdit;
                    calendars.add(calendar);
                }
            }
        }
        
        scanner.close();
        
        // Print complete calendar info in a table
        System.out.println("Found " + calendars.size() + " calendars:");
        System.out.println("\n+------+------------------------+------------------------+----------+");
        System.out.println("| No.  | Calendar Name          | Owner                  | Can Edit |");
        System.out.println("+------+------------------------+------------------------+----------+");
        
        for (int i = 0; i < calendars.size(); i++) {
            CalendarInfo calendar = calendars.get(i);
            
            // Format the strings to fit within the table columns
            String nameDisplay = formatForTable(calendar.name, 22);
            String ownerDisplay = formatForTable(calendar.owner, 22);
            
            System.out.println(String.format("| %-4d | %-22s | %-22s | %-8s |", 
                              i+1, nameDisplay, ownerDisplay, calendar.canEdit ? "Yes" : "No"));
        }
        
        System.out.println("+------+------------------------+------------------------+----------+");
        System.out.println("\nCalendar IDs (for reference):");
        for (CalendarInfo calendar : calendars) {
            System.out.println("  - " + calendar.name + ": " + calendar.id);
        }
        
        // Save calendar info for comparison
        allCalendars = calendars;
    }
    
    // Helper function to extract value from a JSON line
    private static String extractValueFromLine(String line, String key) {
        int startIndex = line.indexOf("\"" + key + "\"") + key.length() + 3;
        int valueStart = line.indexOf("\"", startIndex) + 1;
        int valueEnd = line.indexOf("\"", valueStart);
        
        if (valueStart > 0 && valueEnd > valueStart) {
            return line.substring(valueStart, valueEnd);
        }
        return null;
    }
    
    // Helper class to store calendar information
    private static class CalendarInfo {
        String id;
        String name;
        String owner;
        boolean canShare;
        boolean canEdit;
        
        CalendarInfo(String id, String name, String owner) {
            this.id = id;
            this.name = name;
            this.owner = owner;
        }
    }
    
    // Store calendars for reference
    private static List<CalendarInfo> allCalendars = new ArrayList<>();
    
    // After all queries are executed, compare the results
    private static void compareQueryResults() {
        if (allQueryEvents.size() < 2) {
            System.out.println("\n⚠️ Not enough query results collected to make a comparison");
            return;
        }
        
        System.out.println("\n\n========= DETAILED COMPARISON OF CALENDAR RETRIEVAL METHODS =========");
        
        // Compare query 1 (standard method) with query 3 (calendar view)
        if (allQueryEvents.size() >= 3) {
            List<EventInfo> standardEvents = allQueryEvents.get(0);
            List<EventInfo> calendarViewEvents = allQueryEvents.get(2);
            
            System.out.println("\n1️⃣ Comparison: Standard events endpoint vs Calendar view endpoint");
            System.out.println("------------------------------------------------------------");
            System.out.println("Standard endpoint events: " + standardEvents.size());
            System.out.println("Calendar view endpoint events: " + calendarViewEvents.size());
            
            // Find events that are only in calendar view
            List<EventInfo> onlyInCalendarView = findUniqueEvents(calendarViewEvents, standardEvents);
            if (!onlyInCalendarView.isEmpty()) {
                System.out.println("\n🔍 Found " + onlyInCalendarView.size() + " events in Calendar View that are missing from Standard endpoint:");
                System.out.println("\n+------+------------------------+------------------------+------------------------+");
                System.out.println("| No.  | Subject                | Start Time             | Location               |");
                System.out.println("+------+------------------------+------------------------+------------------------+");
                
                for (int i = 0; i < onlyInCalendarView.size(); i++) {
                    EventInfo event = onlyInCalendarView.get(i);
                    String subjectDisplay = formatForTable(event.subject, 22);
                    String startTimeDisplay = formatForTable(event.startTime, 22);
                    String locationDisplay = formatForTable(event.location, 22);
                    
                    System.out.println(String.format("| %-4d | %-22s | %-22s | %-22s |", 
                                     i+1, subjectDisplay, startTimeDisplay, locationDisplay));
                }
                
                System.out.println("+------+------------------------+------------------------+------------------------+");
                
                System.out.println("\n📝 DIAGNOSIS: The Calendar View endpoint is finding events that the standard endpoint misses.");
                System.out.println("This is often the case with recurring events or events from shared calendars.");
                System.out.println("RECOMMENDATION: Continue using both retrieval methods as implemented in OutlookAlerter.");
            } else {
                System.out.println("\n✅ No events found exclusively in Calendar View that are missing from Standard endpoint.");
            }
            
            // Find events that are only in standard view
            List<EventInfo> onlyInStandard = findUniqueEvents(standardEvents, calendarViewEvents);
            if (!onlyInStandard.isEmpty()) {
                System.out.println("\n⚠️ Found " + onlyInStandard.size() + " events in Standard endpoint that are missing from Calendar View:");
                System.out.println("\n+------+------------------------+------------------------+------------------------+");
                System.out.println("| No.  | Subject                | Start Time             | Location               |");
                System.out.println("+------+------------------------+------------------------+------------------------+");
                
                for (int i = 0; i < onlyInStandard.size(); i++) {
                    EventInfo event = onlyInStandard.get(i);
                    String subjectDisplay = formatForTable(event.subject, 22);
                    String startTimeDisplay = formatForTable(event.startTime, 22);
                    String locationDisplay = formatForTable(event.location, 22);
                    
                    System.out.println(String.format("| %-4d | %-22s | %-22s | %-22s |", 
                                     i+1, subjectDisplay, startTimeDisplay, locationDisplay));
                }
                
                System.out.println("+------+------------------------+------------------------+------------------------+");
                
                System.out.println("\n📝 DIAGNOSIS: The standard events endpoint is finding some events that calendar view misses.");
                System.out.println("RECOMMENDATION: Continue using both retrieval methods as implemented in OutlookAlerter.");
            } else {
                System.out.println("\n✅ No events found exclusively in Standard endpoint that are missing from Calendar View.");
            }
        }
        
        System.out.println("\n===== CALENDAR CONFIGURATION CHECK =====");
        System.out.println("Multiple calendars detected: " + (allCalendars.size() > 1 ? "Yes (" + allCalendars.size() + " calendars)" : "No"));
        if (allCalendars.size() > 1) {
            System.out.println("\n📝 You have multiple calendars. Some of your missing events might be in secondary calendars.");
            System.out.println("The OutlookAlerter application has been enhanced to check all your calendars for events.");
        }
        
        System.out.println("\n===== OTHER POTENTIAL ISSUES TO CHECK =====");
        System.out.println("1. Timezone Differences:");
        System.out.println("   • Current system timezone: " + ZoneId.systemDefault());
        System.out.println("   • Run the test-timezones.sh script to verify timezone handling");
        System.out.println("   • Try setting an explicit timezone using run-with-timezone.sh");
        
        System.out.println("\n2. Filter Settings:");
        System.out.println("   • The application filters out past events");
        System.out.println("   • Current date/time: " + ZonedDateTime.now());
        System.out.println("   • Events might be filtered out if their start time is before the current time");
        
        System.out.println("\n3. Event Status:");
        System.out.println("   • Check if any missing events were declined or cancelled");
        System.out.println("   • The application might filter out events based on your response status");
        
        System.out.println("\n======================================================");
    }
    
    private static List<EventInfo> findUniqueEvents(List<EventInfo> sourceList, List<EventInfo> compareList) {
        List<EventInfo> uniqueEvents = new ArrayList<>();
        
        // First try to match by ID
        Set<String> compareIds = new HashSet<>();
        for (EventInfo event : compareList) {
            if (event.id != null) {
                compareIds.add(event.id);
            }
        }
        
        // Then check for events with IDs not in the compare list
        for (EventInfo event : sourceList) {
            if (event.id != null && !compareIds.contains(event.id)) {
                uniqueEvents.add(event);
            }
        }
        
        // If we couldn't use IDs (they weren't extracted properly), fall back to subject+time matching
        if (uniqueEvents.isEmpty() && sourceList.size() != compareList.size()) {
            Set<String> compareSubjectTimes = new HashSet<>();
            for (EventInfo event : compareList) {
                String key = event.subject + "|" + (event.startTime != null ? event.startTime : "");
                compareSubjectTimes.add(key);
            }
            
            for (EventInfo event : sourceList) {
                String key = event.subject + "|" + (event.startTime != null ? event.startTime : "");
                if (!compareSubjectTimes.contains(key)) {
                    uniqueEvents.add(event);
                }
            }
        }
        
        return uniqueEvents;
    }
}
EOL

# Compile the diagnostics tool
javac -d "$SCRIPT_DIR" "$SCRIPT_DIR/CalendarDiagnostics.java"

# Run the diagnostics tool
java -cp "$SCRIPT_DIR" CalendarDiagnostics

# Clean up
rm "$SCRIPT_DIR/CalendarDiagnostics.java" "$SCRIPT_DIR/CalendarDiagnostics.class"

echo ""
echo "===== Calendar diagnostic test complete ====="
echo "This test helps identify why some meetings might not appear in the results."
echo "If you see meetings in the calendarView query but not in the regular events query,"
echo "consider using the calendarView approach in OutlookAlerter for more complete results."
