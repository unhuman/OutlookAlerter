#!/bin/bash

# Implementation of alternative calendar retrieval methods using additional Microsoft Graph API parameters
# This script focuses on the Prefer: outlook.timezone header and other advanced techniques

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

# Create a Java program to test enhanced retrieval methods
cat > "$SCRIPT_DIR/EnhancedCalendarRetrieval.java" << 'EOL'
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
import groovy.json.JsonSlurper;

public class EnhancedCalendarRetrieval {
    private static final String GRAPH_ENDPOINT = "https://graph.microsoft.com/v1.0";
    private static final JsonSlurper jsonSlurper = new JsonSlurper();
    
    public static void main(String[] args) {
        try {
            System.out.println("===== Enhanced Microsoft Graph API Calendar Methods =====");
            
            // Get token from config file
            String token = getAccessToken();
            if (token == null || token.isEmpty()) {
                System.out.println("No access token found. Please run the application first to authenticate.");
                return;
            }
            
            // Get local timezone for testing
            ZoneId localZone = ZoneId.systemDefault();
            String localTimezoneName = getMicrosoftTimezoneFormat(localZone);
            
            System.out.println("System timezone: " + localZone);
            System.out.println("Microsoft timezone format: " + localTimezoneName);
            System.out.println("Current time: " + ZonedDateTime.now());
            
            // Create HTTP client
            HttpClient client = HttpClient.newHttpClient();
            
            // Test standard method (baseline)
            System.out.println("\n===== TESTING BASELINE METHOD =====");
            executeStandardQuery(client, token, null);
            
            // Test with Prefer: outlook.timezone header
            System.out.println("\n===== TESTING WITH PREFER: OUTLOOK.TIMEZONE HEADER =====");
            executeStandardQuery(client, token, localTimezoneName);
            
            // Test view approach with outlook.timezone header
            System.out.println("\n===== TESTING CALENDAR VIEW WITH PREFER: OUTLOOK.TIMEZONE HEADER =====");
            executeCalendarViewQuery(client, token, localTimezoneName);
            
            // Test with specific timeZoneProperty parameter
            System.out.println("\n===== TESTING WITH $timeZone PARAMETER =====");
            executeQueryWithTimezoneParameter(client, token, localTimezoneName);
            
            // Test getting events with specific preference headers
            System.out.println("\n===== TESTING WITH ADDITIONAL PREFERENCE HEADERS =====");
            executeQueryWithPreferenceHeaders(client, token);
            
            // Test specific delta token approach
            System.out.println("\n===== TESTING DELTA QUERY APPROACH =====");
            executeDeltaQuery(client, token);
            
            // Test multi-calendar merged approach
            System.out.println("\n===== TESTING MULTI-CALENDAR MERGED VIEW =====");
            executeMultiCalendarMergedView(client, token, localTimezoneName);
            
        } catch (Exception e) {
            System.out.println("Error during enhanced calendar retrieval: " + e.getMessage());
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
    
    private static String getMicrosoftTimezoneFormat(ZoneId zoneId) {
        // Convert Java time zone IDs to Microsoft Exchange time zone format
        // This is a partial mapping of common zones
        Map<String, String> zoneMapping = new HashMap<>();
        zoneMapping.put("America/New_York", "Eastern Standard Time");
        zoneMapping.put("America/Chicago", "Central Standard Time");
        zoneMapping.put("America/Denver", "Mountain Standard Time");
        zoneMapping.put("America/Los_Angeles", "Pacific Standard Time");
        zoneMapping.put("America/Phoenix", "US Mountain Standard Time");
        zoneMapping.put("America/Anchorage", "Alaskan Standard Time");
        zoneMapping.put("America/Honolulu", "Hawaiian Standard Time");
        zoneMapping.put("America/Toronto", "Eastern Standard Time");
        zoneMapping.put("America/Vancouver", "Pacific Standard Time");
        zoneMapping.put("Europe/London", "GMT Standard Time");
        zoneMapping.put("Europe/Paris", "Central European Standard Time");
        zoneMapping.put("Europe/Berlin", "Central European Standard Time");
        zoneMapping.put("Europe/Athens", "Eastern European Standard Time");
        zoneMapping.put("Europe/Moscow", "Russian Standard Time");
        zoneMapping.put("Asia/Tokyo", "Tokyo Standard Time");
        zoneMapping.put("Asia/Singapore", "Singapore Standard Time");
        zoneMapping.put("Asia/Shanghai", "China Standard Time");
        zoneMapping.put("Asia/Hong_Kong", "China Standard Time");
        zoneMapping.put("Australia/Sydney", "AUS Eastern Standard Time");
        zoneMapping.put("Australia/Perth", "W. Australia Standard Time");
        zoneMapping.put("Pacific/Auckland", "New Zealand Standard Time");
        
        String zoneString = zoneId.toString();
        
        // Return the mapped zone or the original if no mapping exists
        return zoneMapping.getOrDefault(zoneString, zoneString);
    }
    
    private static void executeStandardQuery(HttpClient client, String token, String timezonePref) {
        try {
            ZonedDateTime startOfDay = ZonedDateTime.now().withHour(0).withMinute(0).withSecond(0);
            ZonedDateTime endOfTomorrow = ZonedDateTime.now().plusDays(1).withHour(23).withMinute(59).withSecond(59);
            
            String startTimeStr = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String endTimeStr = endOfTomorrow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            
            // Create URL for standard events query
            String baseUrl = GRAPH_ENDPOINT + "/me/calendar/events";
            StringBuilder urlBuilder = new StringBuilder(baseUrl);
            urlBuilder.append("?$select=id,subject,organizer,start,end,location,isOnlineMeeting");
            
            String filterParam = "start/dateTime ge '" + startTimeStr + "' and start/dateTime le '" + endTimeStr + "'";
            urlBuilder.append("&$filter=").append(URLEncoder.encode(filterParam, StandardCharsets.UTF_8));
            
            urlBuilder.append("&$orderby=").append(URLEncoder.encode("start/dateTime asc", StandardCharsets.UTF_8));
            urlBuilder.append("&$top=50");
            
            String url = urlBuilder.toString();
            System.out.println("URL: " + url);
            
            // Create request builder
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
            
            // Add timezone preference header if specified
            if (timezonePref != null) {
                System.out.println("Adding Prefer: outlook.timezone=\"" + timezonePref + "\" header");
                requestBuilder.header("Prefer", "outlook.timezone=\"" + timezonePref + "\"");
            }
            
            HttpRequest request = requestBuilder.GET().build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                System.out.println("SUCCESS (200 OK)");
                
                String body = response.body();
                printEventSummary(body, timezonePref != null ? "Standard query with timezone preference" : "Standard query");
                
                // Check if timezone preference was applied
                String appliedPreferences = response.headers().firstValue("Preference-Applied").orElse("");
                if (!appliedPreferences.isEmpty()) {
                    System.out.println("Server applied preferences: " + appliedPreferences);
                }
            } else {
                System.out.println("ERROR: " + response.statusCode());
                System.out.println(response.body());
            }
        } catch (Exception e) {
            System.out.println("Error executing standard query: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void executeCalendarViewQuery(HttpClient client, String token, String timezonePref) {
        try {
            ZonedDateTime startOfDay = ZonedDateTime.now().withHour(0).withMinute(0).withSecond(0);
            ZonedDateTime endOfTomorrow = ZonedDateTime.now().plusDays(1).withHour(23).withMinute(59).withSecond(59);
            
            String startTimeStr = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String endTimeStr = endOfTomorrow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            
            // Create URL for calendar view query
            String baseUrl = GRAPH_ENDPOINT + "/me/calendarView";
            StringBuilder urlBuilder = new StringBuilder(baseUrl);
            urlBuilder.append("?startDateTime=").append(URLEncoder.encode(startTimeStr, StandardCharsets.UTF_8));
            urlBuilder.append("&endDateTime=").append(URLEncoder.encode(endTimeStr, StandardCharsets.UTF_8));
            urlBuilder.append("&$select=id,subject,organizer,start,end,location,isOnlineMeeting");
            urlBuilder.append("&$orderby=").append(URLEncoder.encode("start/dateTime asc", StandardCharsets.UTF_8));
            urlBuilder.append("&$top=50");
            
            String url = urlBuilder.toString();
            System.out.println("URL: " + url);
            
            // Create request builder
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
            
            // Add timezone preference header if specified
            if (timezonePref != null) {
                System.out.println("Adding Prefer: outlook.timezone=\"" + timezonePref + "\" header");
                requestBuilder.header("Prefer", "outlook.timezone=\"" + timezonePref + "\"");
            }
            
            HttpRequest request = requestBuilder.GET().build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                System.out.println("SUCCESS (200 OK)");
                
                String body = response.body();
                printEventSummary(body, "Calendar view query" + (timezonePref != null ? " with timezone preference" : ""));
                
                // Check if timezone preference was applied
                String appliedPreferences = response.headers().firstValue("Preference-Applied").orElse("");
                if (!appliedPreferences.isEmpty()) {
                    System.out.println("Server applied preferences: " + appliedPreferences);
                }
            } else {
                System.out.println("ERROR: " + response.statusCode());
                System.out.println(response.body());
            }
        } catch (Exception e) {
            System.out.println("Error executing calendar view query: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void executeQueryWithTimezoneParameter(HttpClient client, String token, String timezoneName) {
        try {
            // This approach uses the $timeZone parameter directly in the URL
            // See https://learn.microsoft.com/en-us/graph/api/calendar-list-calendarview?view=graph-rest-1.0
            
            ZonedDateTime startOfDay = ZonedDateTime.now().withHour(0).withMinute(0).withSecond(0);
            ZonedDateTime endOfTomorrow = ZonedDateTime.now().plusDays(1).withHour(23).withMinute(59).withSecond(59);
            
            String startTimeStr = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String endTimeStr = endOfTomorrow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            
            // Create URL with $timeZone parameter
            String baseUrl = GRAPH_ENDPOINT + "/me/calendar/events";
            StringBuilder urlBuilder = new StringBuilder(baseUrl);
            urlBuilder.append("?$select=id,subject,organizer,start,end,location,isOnlineMeeting");
            
            String filterParam = "start/dateTime ge '" + startTimeStr + "' and start/dateTime le '" + endTimeStr + "'";
            urlBuilder.append("&$filter=").append(URLEncoder.encode(filterParam, StandardCharsets.UTF_8));
            
            urlBuilder.append("&$timeZone=").append(URLEncoder.encode(timezoneName, StandardCharsets.UTF_8));
            urlBuilder.append("&$orderby=").append(URLEncoder.encode("start/dateTime asc", StandardCharsets.UTF_8));
            urlBuilder.append("&$top=50");
            
            String url = urlBuilder.toString();
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
                printEventSummary(body, "Query with $timeZone parameter");
            } else {
                System.out.println("ERROR: " + response.statusCode());
                System.out.println(response.body());
            }
        } catch (Exception e) {
            System.out.println("Error executing query with timezone parameter: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void executeQueryWithPreferenceHeaders(HttpClient client, String token) {
        try {
            ZonedDateTime startOfDay = ZonedDateTime.now().withHour(0).withMinute(0).withSecond(0);
            ZonedDateTime endOfTomorrow = ZonedDateTime.now().plusDays(1).withHour(23).withMinute(59).withSecond(59);
            
            String startTimeStr = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String endTimeStr = endOfTomorrow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            
            // Create URL for standard events query
            String baseUrl = GRAPH_ENDPOINT + "/me/calendar/events";
            StringBuilder urlBuilder = new StringBuilder(baseUrl);
            urlBuilder.append("?$select=id,subject,organizer,start,end,location,isOnlineMeeting,body");
            
            String filterParam = "start/dateTime ge '" + startTimeStr + "' and start/dateTime le '" + endTimeStr + "'";
            urlBuilder.append("&$filter=").append(URLEncoder.encode(filterParam, StandardCharsets.UTF_8));
            
            urlBuilder.append("&$orderby=").append(URLEncoder.encode("start/dateTime asc", StandardCharsets.UTF_8));
            urlBuilder.append("&$top=50");
            
            String url = urlBuilder.toString();
            System.out.println("URL: " + url);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                // Multiple preference headers combined
                .header("Prefer", "outlook.timezone=\"Eastern Standard Time\"")
                .header("Prefer", "outlook.body-content-type=\"text\"")
                .GET()
                .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                System.out.println("SUCCESS (200 OK)");
                
                String body = response.body();
                printEventSummary(body, "Query with multiple preference headers");
                
                // Check if body content preference was applied
                if (body.contains("\"contentType\":\"text\"")) {
                    System.out.println("Body content-type preference was applied (returned as text)");
                } else {
                    System.out.println("Body content-type preference was NOT applied");
                }
                
                // Check if timezone preference was applied
                String appliedPreferences = response.headers().firstValue("Preference-Applied").orElse("");
                if (!appliedPreferences.isEmpty()) {
                    System.out.println("Server applied preferences: " + appliedPreferences);
                }
            } else {
                System.out.println("ERROR: " + response.statusCode());
                System.out.println(response.body());
            }
        } catch (Exception e) {
            System.out.println("Error executing query with preference headers: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void executeDeltaQuery(HttpClient client, String token) {
        try {
            // This approach uses delta queries to get changes since last sync
            // Useful for caching results and just getting updates
            // See https://learn.microsoft.com/en-us/graph/delta-query-overview
            
            // Create URL for delta query
            String url = GRAPH_ENDPOINT + "/me/calendarView/delta?$select=id,subject,organizer,start,end,location,isOnlineMeeting";
            System.out.println("URL: " + url);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("Prefer", "outlook.timezone=\"Eastern Standard Time\"")
                .GET()
                .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                System.out.println("SUCCESS (200 OK)");
                
                String body = response.body();
                printEventSummary(body, "Delta query for changes");
                
                // Extract deltaLink for future queries
                String deltaLink = extractDeltaLink(body);
                if (deltaLink != null) {
                    System.out.println("\nDelta Link for future sync: " + deltaLink);
                    System.out.println("Store this link to only get changes in future requests");
                }
            } else {
                System.out.println("ERROR: " + response.statusCode());
                System.out.println(response.body());
            }
        } catch (Exception e) {
            System.out.println("Error executing delta query: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void executeMultiCalendarMergedView(HttpClient client, String token, String timezonePref) {
        try {
            // First, get all calendars
            System.out.println("Fetching all calendars...");
            
            String calendarUrl = GRAPH_ENDPOINT + "/me/calendars?$select=id,name,owner";
            HttpRequest calendarRequest = HttpRequest.newBuilder()
                .uri(new URI(calendarUrl))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();
            
            HttpResponse<String> calendarResponse = client.send(calendarRequest, HttpResponse.BodyHandlers.ofString());
            
            if (calendarResponse.statusCode() != 200) {
                System.out.println("Failed to retrieve calendars: " + calendarResponse.statusCode());
                return;
            }
            
            // Parse all calendars
            Map<String, Object> calendarData = (Map<String, Object>) jsonSlurper.parseText(calendarResponse.body());
            List<Map<String, Object>> calendars = (List<Map<String, Object>>) calendarData.get("value");
            
            System.out.println("Found " + calendars.size() + " calendars");
            
            // Time range for view
            ZonedDateTime startOfDay = ZonedDateTime.now().withHour(0).withMinute(0).withSecond(0);
            ZonedDateTime endOfTomorrow = ZonedDateTime.now().plusDays(1).withHour(23).withMinute(59).withSecond(59);
            
            String startTimeStr = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String endTimeStr = endOfTomorrow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            
            // Create URL for calendar group
            StringBuilder urlBuilder = new StringBuilder(GRAPH_ENDPOINT);
            urlBuilder.append("/me/calendars/calendarView");
            urlBuilder.append("?startDateTime=").append(URLEncoder.encode(startTimeStr, StandardCharsets.UTF_8));
            urlBuilder.append("&endDateTime=").append(URLEncoder.encode(endTimeStr, StandardCharsets.UTF_8));
            urlBuilder.append("&$select=id,subject,organizer,start,end,location,isOnlineMeeting");
            
            String url = urlBuilder.toString();
            System.out.println("URL: " + url);
            
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
            
            if (timezonePref != null) {
                System.out.println("Adding Prefer: outlook.timezone=\"" + timezonePref + "\" header");
                requestBuilder.header("Prefer", "outlook.timezone=\"" + timezonePref + "\"");
            }
            
            HttpRequest request = requestBuilder.GET().build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                System.out.println("SUCCESS (200 OK)");
                
                String body = response.body();
                printEventSummary(body, "Multi-calendar merged view");
            } else {
                System.out.println("ERROR: " + response.statusCode());
                System.out.println(response.body());
            }
        } catch (Exception e) {
            System.out.println("Error executing multi-calendar merged view: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void printEventSummary(String body, String queryType) {
        try {
            Map<String, Object> data = (Map<String, Object>) jsonSlurper.parseText(body);
            List<Map<String, Object>> events = (List<Map<String, Object>>) data.get("value");
            
            System.out.println("\n===== " + queryType.toUpperCase() + " =====");
            
            if (events == null || events.isEmpty()) {
                System.out.println("No events found");
                return;
            }
            
            System.out.println("Found " + events.size() + " events:");
            System.out.println("+-----+-------------------------+-------------------------+-------------------------+");
            System.out.println("| No. | Subject                 | Start Time              | Timezone Info           |");
            System.out.println("+-----+-------------------------+-------------------------+-------------------------+");
            
            int count = 1;
            for (Map<String, Object> event : events) {
                String subject = (String) event.get("subject");
                Map<String, Object> start = (Map<String, Object>) event.get("start");
                String dateTime = (String) start.get("dateTime");
                String timeZone = (String) start.get("timeZone");
                
                // Format for display
                String subjectDisplay = formatForTable(subject, 23);
                String dateTimeDisplay = formatForTable(dateTime, 23);
                String timezoneDisplay = formatForTable(timeZone, 23);
                
                System.out.println(String.format("| %-3d | %-23s | %-23s | %-23s |", 
                                 count++, subjectDisplay, dateTimeDisplay, timezoneDisplay));
            }
            
            System.out.println("+-----+-------------------------+-------------------------+-------------------------+");
            
            // Save response to file for detailed examination
            String filename = "enhanced_" + queryType.replaceAll("\\s+", "_").toLowerCase() + ".json";
            saveToFile(body, filename);
            System.out.println("Complete response saved to: " + filename);
            
        } catch (Exception e) {
            System.out.println("Error parsing event data: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static String formatForTable(String text, int maxLength) {
        if (text == null) return "N/A";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }
    
    private static String extractDeltaLink(String body) {
        try {
            Map<String, Object> data = (Map<String, Object>) jsonSlurper.parseText(body);
            return (String) data.get("@odata.deltaLink");
        } catch (Exception e) {
            System.out.println("Error extracting delta link: " + e.getMessage());
            return null;
        }
    }
    
    private static void saveToFile(String content, String filename) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(filename);
            fw.write(content);
            fw.close();
        } catch (Exception e) {
            System.out.println("Error saving to file: " + e.getMessage());
        }
    }
}
EOL

# Compile the Java program
echo "Compiling the enhanced calendar retrieval program..."
javac -cp "$CLASSPATH" "$SCRIPT_DIR/EnhancedCalendarRetrieval.java"

# Run the program
echo "Running the enhanced calendar retrieval program..."
java -cp "$CLASSPATH:$SCRIPT_DIR" EnhancedCalendarRetrieval

# Clean up temporary files
rm -f "$SCRIPT_DIR/EnhancedCalendarRetrieval.java" "$SCRIPT_DIR/EnhancedCalendarRetrieval.class"

echo "Done!"
