# Enhanced Graph API Retrieval Methods for OutlookAlerter

This document outlines the additional retrieval methods for Microsoft Graph API that can be used to improve calendar event retrieval in OutlookAlerter.

## Summary of Available Enhancements

The Microsoft Graph API provides several parameters and headers that can help improve calendar event retrieval accuracy, particularly with respect to timezones and handling recurring events.

### Key Graph API Features

1. **Prefer: outlook.timezone Header**
   - Allows specifying the timezone for event start and end times in the response
   - Example: `Prefer: outlook.timezone="Eastern Standard Time"`
   - Helps ensure consistent timezone interpretation of event times

2. **Calendar View Parameter**
   - Already implemented in OutlookAlerter as `getUpcomingEventsUsingCalendarView()`
   - More reliable for recurring events than standard event retrieval

3. **Multi-Calendar Retrieval**
   - Already implemented in OutlookAlerter as `getUpcomingEventsFromAllCalendars()`
   - Ensures events from all user calendars are included

4. **Delta Query Approach**
   - Allows getting only changes since last sync
   - Useful for caching and performance optimization
   - Provides a `@odata.deltaLink` that can be stored and used for future requests

5. **Outlook Body Content Type Preference**
   - Prefer header to specify the format of the event body
   - Example: `Prefer: outlook.body-content-type="text"`

6. **Combined Preference Headers**
   - Multiple Prefer headers can be combined for optimal results

## Implementation Details

### 1. Enhanced Method for Event Retrieval with Timezone Preference

This method adds the `Prefer: outlook.timezone` header to ensure consistent timezone handling:

```groovy
/**
 * Get upcoming calendar events with timezone preference
 * @param preferredTimezone The Microsoft Exchange timezone name 
 *        (e.g., "Eastern Standard Time", "Pacific Standard Time")
 * @return List of calendar events
 */
List<CalendarEvent> getUpcomingEventsWithTimezonePreference(String preferredTimezone) {
    try {
        // Ensure we have a valid token
        if (!hasValidToken() && !authenticate()) {
            throw new RuntimeException("Failed to authenticate with Outlook")
        }
        
        String accessToken = configManager.accessToken
        if (!isValidTokenFormat(accessToken)) {
            // Re-authentication logic...
            // isValidTokenFormat() accepts both JWT (3 dot-separated parts)
            // and MSAL compact/opaque tokens (no dots, ≥ 20 chars)
        }
        
        // Standard event endpoint
        String baseUrl = "${GRAPH_ENDPOINT}/me/calendar/events"
        
        // Time range filters
        ZonedDateTime startOfDay = ZonedDateTime.now().withHour(0).withMinute(0).withSecond(0)
        String startTime = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        String endTime = ZonedDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        
        // Create URL
        URI uri = createCalendarEventsUri(baseUrl, startTime, endTime)
        
        // Build request with timezone preference header
        HttpRequest request = HttpRequest.newBuilder()
            .uri(uri)
            .header("Authorization", "Bearer ${accessToken}")
            .header("Accept", "application/json")
            .header("Prefer", "outlook.timezone=\"${preferredTimezone}\"")
            .GET()
            .build()
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() == 200) {
            // Check if the preference was applied
            String appliedPreferences = response.headers().firstValue("Preference-Applied").orElse("")
            if (appliedPreferences.contains("outlook.timezone")) {
                println "Timezone preference was applied: ${appliedPreferences}"
            }
            
            return parseEventResponse(response.body())
        } else {
            // Error handling...
            return []
        }
    } catch (Exception e) {
        // Exception handling...
        return []
    }
}
```

### 2. Enhanced Calendar View Method with Timezone Preference

```groovy
/**
 * Get upcoming events using CalendarView with timezone preference 
 * @param preferredTimezone The Microsoft Exchange timezone name
 * @return List of calendar events
 */
List<CalendarEvent> getUpcomingEventsUsingCalendarViewWithTimezone(String preferredTimezone) {
    try {
        // Authentication checks...
        
        // Get events using calendar view API
        String baseUrl = "${GRAPH_ENDPOINT}/me/calendarView"
        
        // Time range parameters
        ZonedDateTime startOfDay = ZonedDateTime.now().withHour(0).withMinute(0).withSecond(0)
        ZonedDateTime endOfTomorrow = ZonedDateTime.now().plusDays(1).withHour(23).withMinute(59).withSecond(59)
        
        String startParam = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        String endParam = endOfTomorrow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        
        // Create URL for calendar view
        StringBuilder urlBuilder = new StringBuilder(baseUrl)
        urlBuilder.append("?startDateTime=").append(URLEncoder.encode(startParam, "UTF-8"))
        urlBuilder.append("&endDateTime=").append(URLEncoder.encode(endParam, "UTF-8"))
        urlBuilder.append("&\$select=id,subject,organizer,start,end,location,isOnlineMeeting,onlineMeeting,bodyPreview")
        urlBuilder.append("&\$orderby=").append(URLEncoder.encode("start/dateTime asc", "UTF-8"))
        urlBuilder.append("&\$top=50")
        
        String url = urlBuilder.toString()
        
        // Build request with timezone preference header
        HttpRequest request = HttpRequest.newBuilder()
            .uri(new URI(url))
            .header("Authorization", "Bearer ${accessToken}")
            .header("Accept", "application/json")
            .header("Prefer", "outlook.timezone=\"${preferredTimezone}\"")
            .GET()
            .build()
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() == 200) {
            // Check if the preference was applied...
            
            return parseEventResponse(response.body())
        } else {
            // Error handling...
            return []
        }
    } catch (Exception e) {
        // Exception handling...
        return []
    }
}
```

### 3. Delta Query Method for Efficient Updates

```groovy
/**
 * Get calendar events using delta query approach
 * This is useful for efficient syncing by only retrieving changes
 * @param deltaLink The delta link from a previous query or null for initial sync
 * @return Map containing events and the new deltaLink
 */
Map<String, Object> getEventsUsingDeltaQuery(String deltaLink) {
    try {
        // Authentication checks...
        
        // Use either provided delta link or initial URL
        String url = deltaLink ?: "${GRAPH_ENDPOINT}/me/calendarView/delta?\$select=id,subject,organizer,start,end,location,isOnlineMeeting"
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(new URI(url))
            .header("Authorization", "Bearer ${accessToken}")
            .header("Accept", "application/json")
            .header("Prefer", "outlook.timezone=\"${getMicrosoftTimezoneFormat(ZoneId.systemDefault())}\"")
            .GET()
            .build()
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() == 200) {
            List<CalendarEvent> events = parseEventResponse(response.body())
            
            // Extract new delta link for next sync
            String newDeltaLink = extractDeltaLink(response.body())
            
            return [events: events, deltaLink: newDeltaLink]
        } else {
            // Error handling...
            return [events: [], deltaLink: null]
        }
    } catch (Exception e) {
        // Exception handling...
        return [events: [], deltaLink: null]
    }
}

/**
 * Extract delta link from response
 */
private String extractDeltaLink(String responseBody) {
    try {
        Map parsed = new JsonSlurper().parseText(responseBody) as Map
        return parsed['@odata.deltaLink'] as String
    } catch (Exception e) {
        println "Error extracting delta link: ${e.message}"
        return null
    }
}
```

### 4. Convert Java ZoneId to Microsoft Exchange Timezone Format

```groovy
/**
 * Convert Java time zone ID to Microsoft Exchange format
 * @param zoneId The Java ZoneId
 * @return Microsoft Exchange timezone name
 */
private String getMicrosoftTimezoneFormat(ZoneId zoneId) {
    // Map of common Java timezone IDs to Microsoft Exchange format
    Map<String, String> zoneMapping = [
        'America/New_York': 'Eastern Standard Time',
        'America/Chicago': 'Central Standard Time',
        'America/Denver': 'Mountain Standard Time',
        'America/Los_Angeles': 'Pacific Standard Time',
        'America/Phoenix': 'US Mountain Standard Time',
        'America/Anchorage': 'Alaskan Standard Time',
        'America/Honolulu': 'Hawaiian Standard Time',
        'Europe/London': 'GMT Standard Time',
        'Europe/Paris': 'Central European Standard Time',
        'Europe/Berlin': 'Central European Standard Time',
        'Europe/Athens': 'Eastern European Standard Time',
        'Europe/Moscow': 'Russian Standard Time',
        'Asia/Tokyo': 'Tokyo Standard Time',
        'Asia/Singapore': 'Singapore Standard Time',
        'Asia/Shanghai': 'China Standard Time',
        'Asia/Hong_Kong': 'China Standard Time',
        'Australia/Sydney': 'AUS Eastern Standard Time',
        'Australia/Perth': 'W. Australia Standard Time',
        'Pacific/Auckland': 'New Zealand Standard Time'
    ]
    
    String zoneString = zoneId.toString()
    
    // Return the mapped zone or the original if no mapping exists
    return zoneMapping.getOrDefault(zoneString, zoneString)
}
```

### 5. Comprehensive Event Retrieval Method (Recommended Implementation)

```groovy
/**
 * Get upcoming events using all available methods and enhancements
 * This method combines multiple retrieval approaches to maximize reliability
 * @return Combined list of calendar events
 */
List<CalendarEvent> getUpcomingEventsComprehensive() {
    List<CalendarEvent> allEvents = []
    Map<String, CalendarEvent> eventMap = [:]
    
    try {
        // 1. Try standard retrieval with timezone preference
        String localTimezoneName = getMicrosoftTimezoneFormat(ZoneId.systemDefault())
        List<CalendarEvent> standardEvents = getUpcomingEventsWithTimezonePreference(localTimezoneName)
        standardEvents.each { event -> 
            eventMap[event.id] = event
            event.retrievalMethod = "standard"
        }
        
        // 2. Try calendar view retrieval with timezone preference
        List<CalendarEvent> calendarViewEvents = getUpcomingEventsUsingCalendarViewWithTimezone(localTimezoneName)
        calendarViewEvents.each { event ->
            if (!eventMap.containsKey(event.id)) {
                event.retrievalMethod = "calendarView"
                eventMap[event.id] = event
            }
        }
        
        // 3. Try multi-calendar retrieval
        List<CalendarEvent> multiCalendarEvents = getUpcomingEventsFromAllCalendars()
        multiCalendarEvents.each { event ->
            if (!eventMap.containsKey(event.id)) {
                event.retrievalMethod = "multiCalendar"
                eventMap[event.id] = event
            }
        }
        
        // Convert map to list
        allEvents = new ArrayList<>(eventMap.values())
        
        println "Comprehensive event retrieval found ${allEvents.size()} unique events:"
        println " - Standard method: ${standardEvents.size()} events"
        println " - Calendar view method: ${calendarViewEvents.size()} events"
        println " - Multi-calendar method: ${multiCalendarEvents.size()} events"
        
    } catch (Exception e) {
        println "Error during comprehensive event retrieval: ${e.message}"
        e.printStackTrace()
    }
    
    return allEvents
}
```

## Implementation Notes

1. **OutlookClient.groovy** should be updated with these enhanced methods.

2. **CalendarEvent.groovy** should be updated to include a `retrievalMethod` field to track which method found the event:
   ```groovy
   String retrievalMethod  // Which method retrieved this event (standard, calendarView, multiCalendar)
   ```

3. **OutlookAlerter.groovy** should be updated to use the comprehensive retrieval method:
   ```groovy
   List<CalendarEvent> events = outlookClient.getUpcomingEventsComprehensive()
   ```

4. **Converting Timezones**: The Microsoft Graph API uses a different timezone format than Java's ZoneId. A mapping function is provided to convert between them.

5. **Error Handling**: Each method includes robust error handling to ensure the application continues functioning even if one retrieval method fails.

6. **Performance Considerations**: The delta query approach can significantly improve performance by only retrieving changes, but requires storing the delta link between runs.

## Recommended Testing Approach

1. Use the `test-enhanced-retrieval.sh` script to validate the different retrieval methods.

2. Update the `test-calendar-events.sh` script to include the new methods.

3. Create comprehensive test cases covering:
   - Events in different timezones
   - Recurring events
   - Events from multiple calendars
   - All-day events
   - Events with varying attendee configurations

## References

- [Microsoft Graph API Calendar Documentation](https://learn.microsoft.com/en-us/graph/api/resources/calendar?view=graph-rest-1.0)
- [Calendar View API](https://learn.microsoft.com/en-us/graph/api/calendar-list-calendarview?view=graph-rest-1.0)
- [Prefer Headers in Microsoft Graph](https://learn.microsoft.com/en-us/graph/api/user-list-events?view=graph-rest-1.0#support-various-time-zones)
- [Delta Query Support](https://learn.microsoft.com/en-us/graph/delta-query-overview)
