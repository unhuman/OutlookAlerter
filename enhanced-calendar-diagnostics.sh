#!/bin/bash

# Enhanced Calendar Diagnostics Script for OutlookAlerter
# This script helps diagnose why some meetings might not appear in the results

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

echo "===== Enhanced OutlookAlerter Calendar Diagnostics ====="
echo "Current date: $(date)"
echo "System timezone: $(date +%Z)"
echo ""
echo "This script will help diagnose why some meetings might not appear in the results"
echo ""

# Create a simple Java program to perform calendar diagnostics
cat > "$SCRIPT_DIR/EnhancedCalendarDiagnostics.java" << 'EOL'
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
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

public class EnhancedCalendarDiagnostics {
    private static final String GRAPH_ENDPOINT = "https://graph.microsoft.com/v1.0";
    private static List<List<EventInfo>> allQueryEvents = new ArrayList<>();
    private static List<CalendarInfo> allCalendars = new ArrayList<>();
    
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
                
                // Process results
                if (description.contains("calendar")) {
                    printCalendarNames(body);
                } else {
                    printEventSubjects(body);
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
    
    private static void printEventSubjects(String body) {
        System.out.println("\n===== COMPLETE EVENT LIST =====");
        
        // Parse events from the response
        List<EventInfo> events = parseEventsFromJson(body);
        
        // Save the raw JSON for debugging
        saveJsonToFile(body, "events");
        
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
        
        // Save the events for later comparison
        allQueryEvents.add(events);
        
        System.out.println("\nTotal events found: " + events.size());
    }
    
    private static void saveJsonToFile(String jsonData, String prefix) {
        String jsonFilePath = System.getProperty("user.home") + "/OutlookAlerter_" + prefix + "_" + 
                             System.currentTimeMillis() + ".json";
        try {
            java.io.FileWriter fw = new java.io.FileWriter(jsonFilePath);
            fw.write(jsonData);
            fw.close();
            System.out.println("Full JSON response saved to: " + jsonFilePath);
        } catch (Exception e) {
            System.out.println("Could not save JSON data: " + e.getMessage());
        }
    }
    
    private static List<EventInfo> parseEventsFromJson(String body) {
        List<EventInfo> events = new ArrayList<>();
        Map<String, EventInfo> eventsMap = new HashMap<>();
        
        // Simple JSON parsing using string operations
        // Look for event blocks and extract data
        int index = 0;
        while (true) {
            int eventStart = body.indexOf("{", index);
            if (eventStart == -1) break;
            
            // Find the matching closing brace
            int braceLevel = 1;
            int eventEnd = eventStart + 1;
            while (braceLevel > 0 && eventEnd < body.length()) {
                char c = body.charAt(eventEnd);
                if (c == '{') braceLevel++;
                else if (c == '}') braceLevel--;
                eventEnd++;
            }
            
            if (braceLevel == 0) {
                // Found a complete JSON object, extract the event data
                String eventJson = body.substring(eventStart, eventEnd);
                EventInfo event = extractEventInfo(eventJson);
                if (event != null && event.id != null) {
                    if (!eventsMap.containsKey(event.id)) {
                        eventsMap.put(event.id, event);
                        events.add(event);
                    }
                }
            }
            
            index = eventEnd;
        }
        
        return events;
    }
    
    private static EventInfo extractEventInfo(String eventJson) {
        String id = extractValueFromJson(eventJson, "id");
        String subject = extractValueFromJson(eventJson, "subject");
        
        if (id == null || subject == null) {
            return null;
        }
        
        // Extract start time from nested structure
        String startTime = null;
        int startIndex = eventJson.indexOf("\"start\"");
        if (startIndex != -1) {
            int startObjEnd = findObjectEndIndex(eventJson, startIndex);
            if (startObjEnd != -1) {
                String startObj = eventJson.substring(startIndex, startObjEnd);
                startTime = extractValueFromJson(startObj, "dateTime");
            }
        }
        
        // Extract end time from nested structure
        String endTime = null;
        int endIndex = eventJson.indexOf("\"end\"");
        if (endIndex != -1) {
            int endObjEnd = findObjectEndIndex(eventJson, endIndex);
            if (endObjEnd != -1) {
                String endObj = eventJson.substring(endIndex, endObjEnd);
                endTime = extractValueFromJson(endObj, "dateTime");
            }
        }
        
        // Extract organizer from nested structure
        String organizer = null;
        int organizerIndex = eventJson.indexOf("\"organizer\"");
        if (organizerIndex != -1) {
            int organizerObjEnd = findObjectEndIndex(eventJson, organizerIndex);
            if (organizerObjEnd != -1) {
                String organizerObj = eventJson.substring(organizerIndex, organizerObjEnd);
                // Find emailAddress in organizer
                int emailAddrIndex = organizerObj.indexOf("\"emailAddress\"");
                if (emailAddrIndex != -1) {
                    int emailAddrObjEnd = findObjectEndIndex(organizerObj, emailAddrIndex);
                    if (emailAddrObjEnd != -1) {
                        String emailAddrObj = organizerObj.substring(emailAddrIndex, emailAddrObjEnd);
                        organizer = extractValueFromJson(emailAddrObj, "name");
                    }
                }
            }
        }
        
        // Extract location from nested structure
        String location = null;
        int locationIndex = eventJson.indexOf("\"location\"");
        if (locationIndex != -1) {
            int locationObjEnd = findObjectEndIndex(eventJson, locationIndex);
            if (locationObjEnd != -1) {
                String locationObj = eventJson.substring(locationIndex, locationObjEnd);
                location = extractValueFromJson(locationObj, "displayName");
            }
        }
        
        // Extract isOnlineMeeting boolean
        String onlineMeetingStr = extractValueFromJson(eventJson, "isOnlineMeeting");
        boolean isOnlineMeeting = "true".equalsIgnoreCase(onlineMeetingStr);
        
        // Create the event object
        EventInfo event = new EventInfo(id, subject, startTime);
        event.endTime = endTime;
        event.organizer = organizer;
        event.location = location;
        event.isOnlineMeeting = isOnlineMeeting;
        
        return event;
    }
    
    private static int findObjectEndIndex(String json, int startIndex) {
        int braceStart = json.indexOf("{", startIndex);
        if (braceStart == -1) return -1;
        
        int braceLevel = 1;
        int pos = braceStart + 1;
        while (braceLevel > 0 && pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '{') braceLevel++;
            else if (c == '}') braceLevel--;
            pos++;
        }
        
        return braceLevel == 0 ? pos : -1;
    }
    
    private static String extractValueFromJson(String json, String key) {
        String keyPattern = "\"" + key + "\"";
        int keyIndex = json.indexOf(keyPattern);
        if (keyIndex == -1) return null;
        
        // Skip to the colon and any whitespace
        int colonIndex = json.indexOf(":", keyIndex + keyPattern.length());
        if (colonIndex == -1) return null;
        
        // Find the start of the value
        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart >= json.length()) return null;
        
        // Check the type of value
        char firstChar = json.charAt(valueStart);
        
        if (firstChar == '"') {
            // String value
            int valueEnd = findStringEndIndex(json, valueStart + 1);
            if (valueEnd == -1) return null;
            return json.substring(valueStart + 1, valueEnd);
        } else if (firstChar == 't' || firstChar == 'f') {
            // Boolean value
            if (json.substring(valueStart).startsWith("true")) return "true";
            if (json.substring(valueStart).startsWith("false")) return "false";
        } else if (firstChar == '{') {
            // Object value - return null as we need special handling for objects
            return null;
        } else if (Character.isDigit(firstChar) || firstChar == '-') {
            // Number value
            int valueEnd = valueStart;
            while (valueEnd < json.length() && 
                  (Character.isDigit(json.charAt(valueEnd)) || 
                   json.charAt(valueEnd) == '.' || 
                   json.charAt(valueEnd) == '-' ||
                   json.charAt(valueEnd) == 'e' ||
                   json.charAt(valueEnd) == 'E' ||
                   json.charAt(valueEnd) == '+')) {
                valueEnd++;
            }
            return json.substring(valueStart, valueEnd);
        }
        
        return null;
    }
    
    private static int findStringEndIndex(String json, int start) {
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '"' && json.charAt(i-1) != '\\') {
                return i;
            }
        }
        return -1;
    }
    
    private static void printCalendarNames(String body) {
        System.out.println("\n===== COMPLETE CALENDAR LIST =====");
        
        // Save the calendar JSON for debugging
        saveJsonToFile(body, "calendars");
        
        // Extract calendar information
        List<CalendarInfo> calendars = parseCalendarsFromJson(body);
        
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
    
    private static List<CalendarInfo> parseCalendarsFromJson(String body) {
        List<CalendarInfo> calendars = new ArrayList<>();
        
        // Look for calendar blocks and extract data
        int index = 0;
        while (true) {
            int calendarStart = body.indexOf("{", index);
            if (calendarStart == -1) break;
            
            // Find the matching closing brace
            int braceLevel = 1;
            int calendarEnd = calendarStart + 1;
            while (braceLevel > 0 && calendarEnd < body.length()) {
                char c = body.charAt(calendarEnd);
                if (c == '{') braceLevel++;
                else if (c == '}') braceLevel--;
                calendarEnd++;
            }
            
            if (braceLevel == 0) {
                // Found a complete JSON object, extract the calendar data
                String calendarJson = body.substring(calendarStart, calendarEnd);
                
                String id = extractValueFromJson(calendarJson, "id");
                String name = extractValueFromJson(calendarJson, "name");
                
                if (id != null && name != null) {
                    // Extract owner information
                    String owner = "Unknown";
                    int ownerIndex = calendarJson.indexOf("\"owner\"");
                    if (ownerIndex != -1) {
                        owner = "Has Owner";
                    }
                    
                    // Extract can share/edit flags
                    String canShareStr = extractValueFromJson(calendarJson, "canShare");
                    String canEditStr = extractValueFromJson(calendarJson, "canEdit");
                    
                    boolean canShare = "true".equalsIgnoreCase(canShareStr);
                    boolean canEdit = "true".equalsIgnoreCase(canEditStr);
                    
                    CalendarInfo calendar = new CalendarInfo(id, name, owner);
                    calendar.canShare = canShare;
                    calendar.canEdit = canEdit;
                    calendars.add(calendar);
                }
            }
            
            index = calendarEnd;
        }
        
        return calendars;
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
        System.out.println("   • Run the test-calendar-events.sh script to verify timezone handling");
        
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
javac -d "$SCRIPT_DIR" "$SCRIPT_DIR/EnhancedCalendarDiagnostics.java"

# Run the diagnostics tool
java -cp "$SCRIPT_DIR" EnhancedCalendarDiagnostics

# Clean up
rm "$SCRIPT_DIR/EnhancedCalendarDiagnostics.java" "$SCRIPT_DIR/EnhancedCalendarDiagnostics.class"

echo ""
echo "===== Enhanced Calendar diagnostic test complete ====="
echo "This test helps identify why some meetings might not appear in the results."
echo "If you see meetings in the calendarView query but not in the regular events query,"
echo "consider using the calendarView approach in OutlookAlerter for more complete results."
echo ""
echo "For more detailed testing, run the test-calendar-events.sh script to examine"
echo "all event retrieval methods and see which events are being found by each method."
