#!/bin/bash

# Multi-calendar diagnostics script for OutlookAlerter
# This script helps diagnose if missing meetings are due to events in secondary calendars

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

echo "===== OutlookAlerter Multi-Calendar Diagnostics ====="
echo "Current date: $(date)"
echo "System timezone: $(date +%Z)"
echo ""
echo "This script will check if you have meetings in multiple calendars"
echo ""

# Create a simple Java program to perform multi-calendar diagnostics
cat > "$SCRIPT_DIR/MultiCalendarDiagnostics.java" << 'EOL'
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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
import groovy.json.JsonSlurper;

public class MultiCalendarDiagnostics {
    private static final String GRAPH_ENDPOINT = "https://graph.microsoft.com/v1.0";
    
    public static void main(String[] args) {
        try {
            System.out.println("===== Microsoft Multi-Calendar Diagnostics =====");
            
            // Get token from config file
            String token = getAccessToken();
            if (token == null || token.isEmpty()) {
                System.out.println("No access token found. Please run the application first to authenticate.");
                return;
            }
            
            // Get current time info
            ZonedDateTime now = ZonedDateTime.now();
            System.out.println("System timezone: " + ZoneId.systemDefault());
            System.out.println("Current time: " + now);
            System.out.println();
            
            // Create HTTP client
            HttpClient client = HttpClient.newHttpClient();
            
            // 1. First get all available calendars
            System.out.println("===== CHECKING AVAILABLE CALENDARS =====");
            List<Map<String, String>> calendars = getCalendars(client, token);
            
            if (calendars.isEmpty()) {
                System.out.println("No calendars found or could not retrieve calendars.");
                return;
            }
            
            System.out.println("\nFound " + calendars.size() + " calendars:");
            for (Map<String, String> calendar : calendars) {
                System.out.println("  - " + calendar.get("name") + 
                                   (calendar.get("owner") != null ? " (Owner: " + calendar.get("owner") + ")" : ""));
            }
            
            // 2. For each calendar, get upcoming events
            checkEventsInAllCalendars(client, token, calendars);
            
        } catch (Exception e) {
            System.out.println("Error during multi-calendar diagnostics: " + e.getMessage());
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
    
    private static List<Map<String, String>> getCalendars(HttpClient client, String token) {
        List<Map<String, String>> calendars = new ArrayList<>();
        
        try {
            // Create URL for calendars request
            String url = GRAPH_ENDPOINT + "/me/calendars?$select=id,name,owner,canShare,canEdit";
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                // Parse response
                JsonSlurper jsonSlurper = new JsonSlurper();
                Map<String, Object> parsed = (Map<String, Object>) jsonSlurper.parseText(response.body());
                List<Object> valueList = (List<Object>) parsed.get("value");
                
                // Process each calendar
                for (Object calendarObj : valueList) {
                    Map<String, Object> calendarData = (Map<String, Object>) calendarObj;
                    Map<String, String> calendar = new HashMap<>();
                    
                    calendar.put("id", (String) calendarData.get("id"));
                    calendar.put("name", (String) calendarData.get("name"));
                    
                    // Add owner info if available
                    if (calendarData.get("owner") != null) {
                        Map<String, Object> ownerMap = (Map<String, Object>) calendarData.get("owner");
                        if (ownerMap.get("user") != null) {
                            Map<String, Object> userMap = (Map<String, Object>) ownerMap.get("user");
                            calendar.put("owner", (String) userMap.get("displayName"));
                        }
                    }
                    
                    calendars.add(calendar);
                }
            } else {
                System.out.println("Error retrieving calendars: " + response.statusCode());
                System.out.println(response.body());
            }
        } catch (Exception e) {
            System.out.println("Error getting calendars: " + e.getMessage());
            e.printStackTrace();
        }
        
        return calendars;
    }
    
    private static void checkEventsInAllCalendars(HttpClient client, String token, List<Map<String, String>> calendars) {
        System.out.println("\n===== CHECKING EVENTS IN ALL CALENDARS =====");
        
        // Track all events by ID to find duplicates and unique events
        Map<String, CalendarEvent> allEventsById = new HashMap<>();
        Map<String, Set<String>> eventsByCalendar = new HashMap<>();
        
        // Set time range for event queries
        ZonedDateTime startOfDay = ZonedDateTime.now().withHour(0).withMinute(0).withSecond(0);
        ZonedDateTime endOfTomorrow = ZonedDateTime.now().plusDays(1).withHour(23).withMinute(59).withSecond(59);
        
        String startTimeParam = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String endTimeParam = endOfTomorrow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        
        // Track totals for summary
        int totalEventsAcrossAllCalendars = 0;
        int uniqueEventsAcrossAllCalendars = 0;
        
        try {
            // For each calendar, get events
            for (Map<String, String> calendar : calendars) {
                String calendarId = calendar.get("id");
                String calendarName = calendar.get("name");
                
                System.out.println("\nChecking events in calendar: " + calendarName);
                
                // Create URL for events request
                String url = GRAPH_ENDPOINT + "/me/calendars/" + calendarId + "/events";
                
                // Add parameters
                StringBuilder urlBuilder = new StringBuilder(url);
                urlBuilder.append("?$select=id,subject,organizer,start,end,location,isOnlineMeeting");
                
                // Clean time strings
                String startTime = startTimeParam.replaceAll("\\.[0-9]+-[0-9]+:[0-9]+", "Z")
                                              .replaceAll("\\.[0-9]+\\+[0-9]+:[0-9]+", "Z")
                                              .replaceAll("\\.[0-9]+Z", "Z");
                String endTime = endTimeParam.replaceAll("\\.[0-9]+-[0-9]+:[0-9]+", "Z")
                                          .replaceAll("\\.[0-9]+\\+[0-9]+:[0-9]+", "Z")
                                          .replaceAll("\\.[0-9]+Z", "Z");
                
                // Add filter for time range
                String filterParam = "start/dateTime ge '" + startTime + "' and start/dateTime le '" + endTime + "'";
                urlBuilder.append("&$filter=").append(URLEncoder.encode(filterParam, StandardCharsets.UTF_8));
                
                // Add ordering and limit
                urlBuilder.append("&$orderby=").append(URLEncoder.encode("start/dateTime asc", StandardCharsets.UTF_8));
                urlBuilder.append("&$top=50");
                
                url = urlBuilder.toString();
                System.out.println("URL: " + url);
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
                
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    // Parse event data
                    JsonSlurper jsonSlurper = new JsonSlurper();
                    Map<String, Object> parsed = (Map<String, Object>) jsonSlurper.parseText(response.body());
                    List<Object> valueList = (List<Object>) parsed.get("value");
                    
                    System.out.println("Found " + valueList.size() + " events in this calendar");
                    totalEventsAcrossAllCalendars += valueList.size();
                    
                    // Initialize set for this calendar
                    Set<String> eventsInThisCalendar = new HashSet<>();
                    eventsByCalendar.put(calendarName, eventsInThisCalendar);
                    
                    // Process each event
                    for (Object eventObj : valueList) {
                        Map<String, Object> eventData = (Map<String, Object>) eventObj;
                        String eventId = (String) eventData.get("id");
                        String subject = (String) eventData.get("subject");
                        
                        // Get start time
                        Map<String, Object> startMap = (Map<String, Object>) eventData.get("start");
                        String dateTimeStr = (String) startMap.get("dateTime");
                        String timeZone = (String) startMap.get("timeZone");
                        
                        // Create CalendarEvent object
                        CalendarEvent event = new CalendarEvent();
                        event.id = eventId;
                        event.subject = subject;
                        event.calendarName = calendarName;
                        event.startTimeStr = dateTimeStr;
                        
                        // Store event by ID and add to this calendar's set
                        allEventsById.put(eventId, event);
                        eventsInThisCalendar.add(eventId);
                        
                        System.out.println("  - " + subject + " (at " + dateTimeStr + " " + timeZone + ")");
                    }
                } else {
                    System.out.println("Error retrieving events for calendar " + calendarName + 
                                      ": " + response.statusCode());
                    System.out.println(response.body());
                }
            }
            
            // Count unique events
            uniqueEventsAcrossAllCalendars = allEventsById.size();
            
            // Print summary
            System.out.println("\n===== MULTI-CALENDAR SUMMARY =====");
            System.out.println("Total events across all calendars: " + totalEventsAcrossAllCalendars);
            System.out.println("Unique events across all calendars: " + uniqueEventsAcrossAllCalendars);
            
            if (totalEventsAcrossAllCalendars > uniqueEventsAcrossAllCalendars) {
                System.out.println("\nSome events appear in multiple calendars!");
                // Find duplicate events (appear in multiple calendars)
                findDuplicateEvents(allEventsById, eventsByCalendar);
            }
            
            // Find unique events per calendar (events that only appear in one calendar)
            findUniqueEventsPerCalendar(allEventsById, eventsByCalendar);
            
            // Provide guidance
            System.out.println("\n===== RECOMMENDATIONS =====");
            if (calendars.size() > 1) {
                System.out.println("✅ You have multiple calendars that may contain important events.");
                System.out.println("RECOMMENDATION: Continue to use the multi-calendar approach in OutlookAlerter");
                System.out.println("to ensure you don't miss any events from secondary calendars.");
            } else {
                System.out.println("ℹ️ You only have one calendar. Missing events are not due to multiple calendars.");
                System.out.println("Check other issues like timezone differences or filter settings.");
            }
        } catch (Exception e) {
            System.out.println("Error checking events in calendars: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void findDuplicateEvents(Map<String, CalendarEvent> allEvents, 
                                           Map<String, Set<String>> eventsByCalendar) {
        // Map to track which calendars contain each event
        Map<String, List<String>> calendarsByEventId = new HashMap<>();
        
        // Build the reverse mapping
        for (Map.Entry<String, Set<String>> entry : eventsByCalendar.entrySet()) {
            String calendarName = entry.getKey();
            Set<String> eventIds = entry.getValue();
            
            for (String eventId : eventIds) {
                if (!calendarsByEventId.containsKey(eventId)) {
                    calendarsByEventId.put(eventId, new ArrayList<>());
                }
                calendarsByEventId.get(eventId).add(calendarName);
            }
        }
        
        // Find events that appear in multiple calendars
        System.out.println("\nEvents appearing in multiple calendars:");
        boolean foundDuplicates = false;
        
        for (Map.Entry<String, List<String>> entry : calendarsByEventId.entrySet()) {
            String eventId = entry.getKey();
            List<String> calendarsWithEvent = entry.getValue();
            
            if (calendarsWithEvent.size() > 1) {
                foundDuplicates = true;
                CalendarEvent event = allEvents.get(eventId);
                System.out.println("  - " + event.subject + " appears in " + calendarsWithEvent.size() + " calendars:");
                for (String calendarName : calendarsWithEvent) {
                    System.out.println("      * " + calendarName);
                }
            }
        }
        
        if (!foundDuplicates) {
            System.out.println("  None found - all events are unique to their calendars");
        }
    }
    
    private static void findUniqueEventsPerCalendar(Map<String, CalendarEvent> allEvents,
                                                  Map<String, Set<String>> eventsByCalendar) {
        // For each calendar, find events that only appear in that calendar
        System.out.println("\nEvents that only appear in specific calendars:");
        boolean foundUniqueEvents = false;
        
        for (Map.Entry<String, Set<String>> entry : eventsByCalendar.entrySet()) {
            String calendarName = entry.getKey();
            Set<String> eventIds = entry.getValue();
            List<CalendarEvent> uniqueEvents = new ArrayList<>();
            
            // For each event in this calendar, check if it's in any other calendar
            for (String eventId : eventIds) {
                boolean isUnique = true;
                
                for (Map.Entry<String, Set<String>> otherEntry : eventsByCalendar.entrySet()) {
                    if (!otherEntry.getKey().equals(calendarName) && otherEntry.getValue().contains(eventId)) {
                        isUnique = false;
                        break;
                    }
                }
                
                if (isUnique) {
                    uniqueEvents.add(allEvents.get(eventId));
                }
            }
            
            // Print unique events for this calendar
            if (!uniqueEvents.isEmpty()) {
                foundUniqueEvents = true;
                System.out.println("\n  Calendar: " + calendarName + " has " + uniqueEvents.size() + 
                                  " unique events:");
                for (CalendarEvent event : uniqueEvents) {
                    System.out.println("    • " + event.subject + 
                                     (event.startTimeStr != null ? " (at " + event.startTimeStr + ")" : ""));
                }
            }
        }
        
        if (!foundUniqueEvents) {
            System.out.println("  No unique events found - all events appear in multiple calendars");
        }
    }
    
    // Simple class to hold calendar event info
    private static class CalendarEvent {
        String id;
        String subject;
        String calendarName;
        String startTimeStr;
    }
}
EOL

# Compile and run the diagnostics tool
javac -cp "$CLASSPATH" -d "$SCRIPT_DIR" "$SCRIPT_DIR/MultiCalendarDiagnostics.java"

# Run the diagnostics tool
java -cp "$CLASSPATH:$SCRIPT_DIR" MultiCalendarDiagnostics

# Clean up
rm "$SCRIPT_DIR/MultiCalendarDiagnostics.java" "$SCRIPT_DIR/MultiCalendarDiagnostics.class"

echo ""
echo "===== Multi-Calendar diagnostic test complete ====="
echo "This test helps identify if you have meetings across multiple calendars that"
echo "might be missed by only checking your primary calendar."
echo ""
echo "If this test showed events in multiple calendars, OutlookAlerter has been"
echo "enhanced to retrieve events from all your calendars, which should resolve"
echo "the issue of missing events."
