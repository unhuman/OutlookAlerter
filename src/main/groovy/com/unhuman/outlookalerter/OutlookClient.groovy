package com.unhuman.outlookalerter

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.function.Supplier
import javax.swing.JOptionPane

// Additional imports for TokenEntryServer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.BindException
import java.util.Enumeration
import java.util.concurrent.Executors
import java.awt.Desktop
import java.net.URI

/**
 * Client for Microsoft Graph API to access Outlook calendar
 * with simplified authentication support
 */
@CompileStatic
class OutlookClient {
    // Graph API endpoints
    private static final String GRAPH_ENDPOINT = 'https://graph.microsoft.com/v1.0'
    private static final String SCOPE = 'offline_access https://graph.microsoft.com/Calendars.Read'
    // Server validation settings
    private static final long SERVER_VALIDATION_INTERVAL_MS = 15 * 60 * 1000; // 15 minutes

    // HTTP client for API requests
    private final HttpClient httpClient
    
    // Configuration manager
    private final ConfigManager configManager
    
    // Lock object to prevent multiple concurrent authentication attempts
    private final Object authLock = new Object()
    private volatile boolean isAuthenticating = false
    
    // Track if we've logged about timezone
    private boolean hasLoggedTimezone = false

    // Reference to OutlookAlerterUI for token dialog handling
    private final OutlookAlerterUI outlookAlerterUI;

    /**
     * Creates a new Outlook client with the given configuration
     */
    OutlookClient(ConfigManager configManager, OutlookAlerterUI outlookAlerterUI) {
        this.configManager = configManager;
        this.outlookAlerterUI = outlookAlerterUI;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Constructor for OutlookClient.
     * @param configManager The configuration manager instance.
     */
    OutlookClient(ConfigManager configManager) {
        this.configManager = configManager
        this.httpClient = HttpClient.newHttpClient()
    }
    
    /**
     * Authenticate with Microsoft Graph API
     * @return true if authentication was successful
     */
    boolean authenticate() {
        // Check if we have a valid token
        if (hasValidToken()) {
            println "Using existing valid token."
            return true
        }
        
        // Try to refresh token if we have one
        if (configManager.refreshToken) {
            println "Attempting to refresh token..."
            if (refreshToken()) {
                return true
            }
        }
        
        // Need to perform full authentication
        return performDirectAuthentication()
    }
    
    /**
     * Check if we have a valid token by validating with Microsoft's server
     * @return true if the token is valid according to the server
     */
    boolean hasValidToken() {
        String accessToken = configManager.accessToken

        // Quick check: Do we have a token at all?
        if (accessToken == null || accessToken.isEmpty()) {
            return false;
        }

        // Always perform server validation
        boolean isValid = validateTokenWithServer(accessToken);

        if (!isValid) {
            println "Token appears to be invalid according to Microsoft's server";
            return false;
        }

        // If we got here, the token is valid according to the server
        println "Token validated with server successfully.";
        return true;
    }
    
    /**
     * Check if we already have a valid token without refreshing or re-authenticating
     * This method is specifically designed for UI feedback purposes
     * @return true if the token is valid according to the server, false if re-authentication or refresh is needed
     */
    boolean isTokenAlreadyValid() {
        String accessToken = configManager.accessToken

        // Quick check: Do we have a token at all?
        if (accessToken == null || accessToken.isEmpty()) {
            return false;
        }

        // Validate with server
        return validateTokenWithServer(accessToken);
    }
    
    /**
     * Attempt to refresh the access token using the refresh token
     */
    private boolean refreshToken() {
        try {
            String tokenUrl = configManager.tokenEndpoint
            
            if (!tokenUrl) {
                println "Error: Token endpoint URL not configured"
                return false
            }
            
            Map<String, String> formData = [
                grant_type: 'refresh_token',
                refresh_token: configManager.refreshToken
            ]
            
            // Add client ID and secret if available
            if (configManager.clientId) {
                formData['client_id'] = configManager.clientId
                if (configManager.clientSecret) {
                    formData['client_secret'] = configManager.clientSecret
                }
            }
            
            // Add scope if needed
            formData['scope'] = SCOPE
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header('Content-Type', 'application/x-www-form-urlencoded')
                .header('Accept', 'application/json')
                .POST(ofFormData(formData))
                .build()
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() == 200) {
                Map json = new JsonSlurper().parseText(response.body()) as Map
                
                // Update tokens
                String accessToken = json['access_token'] as String
                String refreshToken = json['refresh_token'] as String
                
                configManager.updateTokens(accessToken, refreshToken)
                lastTokenValidationResult = TOKEN_REFRESHED
                println "Token refreshed successfully!"
                return true
            } else if (response.statusCode() == 401 || response.statusCode() == 400) {
                // If the refresh token is rejected or invalid, we need to re-authenticate
                println "Refresh token was rejected (${response.statusCode()}). Need to re-authenticate."
                // Fall through to perform direct authentication
                return performDirectAuthentication()
            } else {
                println "Failed to refresh token: ${response.statusCode()}"
                return false
            }
        } catch (Exception e) {
            println "Error refreshing token: ${e.message}"
            return false
        }
    }
    
    /**
     * Perform direct authentication via browser SSO or GUI token entry
     */
    protected boolean performDirectAuthentication() {
        synchronized(authLock) {
            if (isAuthenticating) {
                println "Authentication already in progress"
                return false
            }
            isAuthenticating = true
        }
        
        try {
            def tokens = getTokensFromUser()
            if (tokens == null || !tokens.accessToken) {
                println "No access token was provided."
                return false
            }
            
            println "Token received from user interface."
            
            // Redact token for logging (show only first 10 chars)
            String redactedToken = tokens.accessToken?.size() > 10 ? 
                tokens.accessToken.substring(0, 10) + "..." : 
                "(invalid token format)"
            println "Received token starting with: ${redactedToken}"
            
            String accessToken = null
            String refreshToken = null
            long expiryTime = 0
            
            while (true) {
                // Validate the received token with Microsoft's server before accepting it
                accessToken = tokens.accessToken
                
                println "Validating token with Microsoft's server..."
                boolean isValid = validateTokenWithServer(accessToken)

                if (!isValid) {
                    println "Token validation failed. The token appears to be invalid. Requesting a new token..."
                    JOptionPane.showMessageDialog(
                        null,
                        "The provided token was rejected by Microsoft's server. Please get a new token and try again.",
                        "Invalid Token",
                        JOptionPane.ERROR_MESSAGE
                    )
                    // Get new tokens from user by showing the dialog again
                    tokens = getTokensFromUser()
                    if (tokens == null) {
                        return false
                    }
                    // Continue the while loop to validate the new token
                    continue
                }
                
                // Token is valid, save all tokens
                refreshToken = tokens.refreshToken
                
                // Break the loop since we have a valid token
                break
            }
            
            configManager.updateTokens(accessToken, refreshToken)
            println "Authentication successful! Token validated and saved."
            return true
            
        } catch (Exception e) {
            println "Error during authentication: ${e.message}"
            e.printStackTrace()
            return false
        } finally {
            synchronized(authLock) {
                isAuthenticating = false
            }
        }
    }
    
    /**
     * Handle a 401 Unauthorized or 403 Forbidden response by trying to re-authenticate
     * This ensures expired tokens are automatically refreshed without user intervention when possible,
     * and prompts for new tokens when refresh fails
     * @param statusCode The HTTP status code (401 or 403) that triggered this handler
     * @return A new valid access token, or null if re-authentication failed
     */
    private String handleUnauthorizedResponse(int statusCode = 401) {
        String errorType = (statusCode == 401) ? "401 Unauthorized" : "403 Forbidden"
        println "Access token was rejected (${errorType}). Attempting to re-authenticate..."

        // Clear the existing token's expiry time to force validation
        configManager.updateTokens(configManager.accessToken, configManager.refreshToken);

        // Try to refresh the token first if we have one
        if (configManager.refreshToken && refreshToken()) {
            println "Successfully refreshed the token."
            return configManager.accessToken
        }

        // If refresh failed or no refresh token, try direct authentication
        if (performDirectAuthentication()) {
            println "Successfully re-authenticated."
            return configManager.accessToken
        }

        // If we got here, all authentication attempts failed
        return null
    }
    
    /**
     * Execute a request and handle 401 errors by re-authenticating and retrying once
     * @param request The HTTP request to execute
     * @param uri The URI for retry (needed if we need to rebuild the request)
     * @return Response from the server, either from first attempt or retry
     */
    /**
     * Execute a request and handle 401 Unauthorized or 403 Forbidden errors by re-authenticating and retrying once
     * @param request The HTTP request to execute
     * @param uri The URI for retry (needed if we need to rebuild the request)
     * @return Response from the server, either from first attempt or retry
     */
    private HttpResponse<String> executeRequestWithRetry(HttpRequest request, URI uri) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        // If we got a 401 Unauthorized or 403 Forbidden, try to re-authenticate and retry
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            String errorType = (response.statusCode() == 401) ? "401 Unauthorized" : "403 Forbidden";
            println "Received ${errorType} response. Attempting to re-authenticate..."

            // Pass the specific status code to the handler
            String newToken = handleUnauthorizedResponse(response.statusCode())

            if (newToken != null) {
                // Build a new request with the new token
                HttpRequest retryRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", "Bearer ${newToken}")
                    .header("Accept", "application/json")
                    .GET()
                    .build()

                // Execute the retry request
                println "Retrying request with new token..."
                HttpResponse<String> retryResponse = httpClient.send(retryRequest, HttpResponse.BodyHandlers.ofString())

                if (retryResponse.statusCode() == 200) {
                    println "Request retry successful"
                    return retryResponse
                } else {
                    println "Request retry failed with status ${retryResponse.statusCode()}"
                    // Return the retry response even if it failed - the caller will handle the status code
                    return retryResponse
                }
            } else {
                println "Failed to obtain a new token for retry"
            }
        }

        return response
    }
    
    /**
     * Execute a request and automatically retry with a refreshed token if it returns 401 Unauthorized or 403 Forbidden
     * @param requestSupplier A supplier function that creates and executes the HTTP request
     * @return The response from the request, either the original response or the retried response
     */
    private HttpResponse executeRequestWithRetry(Supplier<HttpResponse> requestSupplier) {
        // Execute the original request
        HttpResponse response = requestSupplier.get()

        // If we get a 401 Unauthorized or 403 Forbidden, try to refresh the token and retry once
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            String errorType = (response.statusCode() == 401) ? "401 Unauthorized" : "403 Forbidden"
            println "Received ${errorType}. Attempting to refresh token and retry the request..."

            // Try to get a new token, passing the specific status code
            String newToken = handleUnauthorizedResponse(response.statusCode())
            if (newToken == null) {
                println "Failed to get a new token. Cannot retry the request."
                return response
            }

            // Retry the request with the new token
            HttpResponse retryResponse = requestSupplier.get()
            if (retryResponse.statusCode() == 200) {
                println "Request retry successful"
                return retryResponse
            } else {
                println "Request retry failed with status ${retryResponse.statusCode()}"
                // Return the retry response even if it failed - the caller will handle the status code
                return retryResponse
            }
        }

        // For any other status code, just return the original response
        return response
    }

    /**
     * Get upcoming calendar events
     * @return List of calendar events
     */
    List<CalendarEvent> getUpcomingEvents() {
        try {
            // Ensure we have a valid token
            String accessToken = configManager.accessToken

            // First check the format without making server requests
            boolean validFormat = isValidTokenFormat(accessToken);

            // If we don't have a valid token or the token is expired according to our records
            if (!validFormat || !hasValidToken()) {
                // If the token format is valid but may be expired, validate with server before prompting
                if (validFormat && accessToken) {
                    boolean isValid = validateTokenWithServer(accessToken);                
                    if (isValid) {
                        // Token is valid according to server validation
                        configManager.updateTokens(accessToken, configManager.refreshToken);
                        println "Token validated with server successfully."
                    } else if (!authenticate()) {
                        throw new RuntimeException("Failed to authenticate with Outlook")
                    }
                    accessToken = configManager.accessToken;
                } else {
                    // No valid token at all - authenticate from scratch
                    if (!authenticate()) {
                        throw new RuntimeException("Failed to authenticate with Outlook")
                    }
                    accessToken = configManager.accessToken;
                }
            }
            if (!isValidTokenFormat(accessToken)) {
                println """
                WARNING: The access token does not appear to be properly formatted.
                A valid JWT token should have 3 parts separated by dots (e.g., xxxxx.yyyyy.zzzzz).
                Your token may not be a valid Microsoft Graph API token.
                
                Current token format check: ${accessToken ? "Token length: ${accessToken.length()}, Contains dots: ${accessToken.contains('.')}" : "Token is null"}
                
                The application will try to continue, but you may need to re-authenticate.
                """
                // Try to force re-authentication
                if (performDirectAuthentication()) {
                    // Re-get the token after successful authentication
                    accessToken = configManager.accessToken
                    if (!isValidTokenFormat(accessToken)) {
                        throw new RuntimeException("Failed to obtain a valid JWT token even after re-authentication")
                    }
                } else {
                    throw new RuntimeException("Failed to re-authenticate to obtain a valid token")
                }
            }
            
            // Get events from the API
            String baseUrl = "${GRAPH_ENDPOINT}/me/calendar/events"
            
            // Calculate start and end time filters (from earlier today to 1 day ahead)
            // Start from now to the end of today
            ZonedDateTime startOfDay = ZonedDateTime.now()
            String startTime = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            
            // Look ahead to end of day
            String endTime = ZonedDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            
            // println "Start time (raw): ${startTime} (now)"
            // println "End time (raw): ${endTime} (end of day)"
            
            // Create a properly encoded URL using URI builder pattern
            URI uri = createCalendarEventsUri(baseUrl, startTime, endTime)
            String url = uri.toString()
            
            println "Requesting calendar events with URL: ${url}"
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Bearer ${accessToken}")
                .header("Accept", "application/json")
                .GET()
                .build()
            
            HttpResponse<String> response = executeRequestWithRetry(request, uri)
            
            if (response.statusCode() == 200) {
                return parseEventResponse(response.body())
            } else {
                println "Failed to retrieve events (HTTP ${response.statusCode()}): ${response.body()}"
                return []
            }
        } catch (Exception e) {
            println "Error retrieving calendar events: ${e.message}"
            println "Error type: ${e.class.name}"
            if (e.cause) {
                println "Caused by: ${e.cause.message}"
            }
            e.printStackTrace() // Print the full stack trace for detailed debugging
            
            // More helpful user message
            if (e instanceof java.net.URISyntaxException || 
                e.message?.contains("Illegal character") || 
                (e.cause instanceof java.net.URISyntaxException)) {
                println """
                ===========================================
                URL ENCODING ERROR DETECTED
                ===========================================
                The Microsoft Graph API URL contains characters that need to be properly encoded.
                This is often caused by special characters in date/time strings.
                
                Start time: ${ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}
                End time: ${ZonedDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}
                ===========================================
                """
            }
            
            return []
        }
    }

    /**
     * Get upcoming events using CalendarView (alternative approach)
     * This method can sometimes capture events that are not returned by the regular events endpoint,
     * particularly recurring events or events in different calendars
     * @return List of calendar events
     */
    /**
     * Token validation result enum as string constants
     */
    static final String TOKEN_VALID_NO_ACTION = "TOKEN_VALID_NO_ACTION"
    static final String TOKEN_VALID_AFTER_SERVER_VALIDATION = "TOKEN_VALID_AFTER_SERVER_VALIDATION" 
    static final String TOKEN_REFRESHED = "TOKEN_REFRESHED"
    static final String TOKEN_NEW_AUTHENTICATION = "TOKEN_NEW_AUTHENTICATION"
    
    // Track the last token validation result for UI feedback
    private String lastTokenValidationResult = TOKEN_VALID_NO_ACTION
    
    /**
     * Get the result of the last token validation operation
     * @return One of the TOKEN_* constants
     */
    String getLastTokenValidationResult() {
        return lastTokenValidationResult
    }
    
    List<CalendarEvent> getUpcomingEventsUsingCalendarView() {
        try {
            // Reset the validation result
            lastTokenValidationResult = TOKEN_VALID_NO_ACTION
            
            // Get current token
            String accessToken = configManager.accessToken

            // First check if token format is valid
            if (!isValidTokenFormat(accessToken)) {
                println "Token format is invalid. Need to authenticate again.";
                if (!authenticate()) {
                    throw new RuntimeException("Failed to re-authenticate to obtain a valid token");
                }
                lastTokenValidationResult = TOKEN_NEW_AUTHENTICATION;
            } 
            // Then check if we have a valid token (by server validation)
            else if (!hasValidToken()) {
                // If we get here, the token is invalid according to the server and we need to authenticate
                println "Token validation failed. Need to authenticate again.";
                if (!authenticate()) {
                    throw new RuntimeException("Failed to re-authenticate to obtain a valid token");
                }
                lastTokenValidationResult = TOKEN_NEW_AUTHENTICATION;
            } 
            else {
                // Token was validated with the server in hasValidToken()
                lastTokenValidationResult = TOKEN_VALID_AFTER_SERVER_VALIDATION;
                println "Token was validated with server. Using validated token.";
                accessToken = configManager.accessToken;
            }
            
            // Make sure we use the latest token
            accessToken = configManager.accessToken;

            // Get events using calendar view API
            String baseUrl = "${GRAPH_ENDPOINT}/me/calendarView"

            // Calculate start and end time parameters
            ZonedDateTime startOfDay = ZonedDateTime.now()
            ZonedDateTime endOfTomorrow = ZonedDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0)

            String startParam = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            String endParam = endOfTomorrow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

            // System.out.println "Start time (raw): ${startParam} (now) + formatted to ISO ${URLEncoder.encode(startParam, "UTF-8")}"
 
            // Create URL for calendar view
            StringBuilder urlBuilder = new StringBuilder(baseUrl)
            urlBuilder.append("?startDateTime=").append(URLEncoder.encode(startParam, "UTF-8"))
            urlBuilder.append("&endDateTime=").append(URLEncoder.encode(endParam, "UTF-8"))
            urlBuilder.append("&\$select=id,subject,organizer,start,end,location,isOnlineMeeting,onlineMeeting,bodyPreview")
            urlBuilder.append("&\$orderby=").append(URLEncoder.encode("start/dateTime asc", "UTF-8"))
            urlBuilder.append("&\$top=50")
            
            String url = urlBuilder.toString()
            println "Requesting calendar events with calendar view URL: ${url}"
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Authorization", "Bearer ${accessToken}")
                .header("Accept", "application/json")
                .GET()
                .build()
            
            HttpResponse<String> response = executeRequestWithRetry(request, new URI(url))
            
            if (response.statusCode() == 200) {
                List<CalendarEvent> events = parseEventResponse(response.body())
                return events
            } else {
                println "Failed to retrieve events using calendar view (HTTP ${response.statusCode()})"
                return []
            }
        } catch (Exception e) {
            println "Error retrieving calendar events using calendar view: ${e.message}"
            e.printStackTrace()
            return []
        }
    }
    
    /**
     * Get all calendars available to the user
     * @return List of calendar IDs and names
     */
    List<Map<String, String>> getAvailableCalendars() {
        try {
            // Get current token
            String accessToken = configManager.accessToken

            // If token appears invalid, try to validate with server before prompting
            if (!hasValidToken() || !isValidTokenFormat(accessToken)) {
                // If the token format is valid but possibly expired, check with server
                if (isValidTokenFormat(accessToken)) {
                    boolean isValid = validateTokenWithServer(accessToken);

                    if (isValid) {
                        // Token is valid according to server
                        configManager.updateTokens(accessToken, configManager.refreshToken);
                        println "Token validated with server successfully."
                    } else if (!authenticate()) {
                        throw new RuntimeException("Failed to re-authenticate to obtain a valid token")
                    }
                } else if (!authenticate()) {
                    throw new RuntimeException("Failed to re-authenticate to obtain a valid token")
                }
                accessToken = configManager.accessToken;
            }

            // Get all calendars
            String url = "${GRAPH_ENDPOINT}/me/calendars?\$select=id,name,owner,canShare,canEdit"

            println "Requesting available calendars with URL: ${url}"

            HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Authorization", "Bearer ${accessToken}")
                .header("Accept", "application/json")
                .GET()
                .build()

            HttpResponse<String> response = executeRequestWithRetry(request, new URI(url))

            if (response.statusCode() == 200) {
                Map parsed = new JsonSlurper().parseText(response.body()) as Map
                List valueList = parsed['value'] as List
                
                List<Map<String, String>> calendars = []
                
                valueList.each { Object calendarData ->
                    Map calendarMap = calendarData as Map
                    Map<String, String> calendar = [:]
                    calendar.id = calendarMap['id'] as String
                    calendar.name = calendarMap['name'] as String
                    
                    // Add owner info if available
                    if (calendarMap['owner']) {
                        Map ownerMap = calendarMap['owner'] as Map
                        if (ownerMap['user']) {
                            Map userMap = ownerMap['user'] as Map
                            calendar.owner = userMap['displayName'] as String
                        }
                    }
                    
                    calendars.add(calendar)
                }
                
                return calendars
            } else {
                println "Failed to retrieve calendars (HTTP ${response.statusCode()})"
                return []
            }
        } catch (Exception e) {
            println "Error retrieving calendars: ${e.message}"
            e.printStackTrace()
            return []
        }
    }
    
    /**
     * Get upcoming events from a specific calendar
     * @param calendarId The ID of the calendar to get events from
     * @return List of calendar events
     */
    List<CalendarEvent> getUpcomingEventsFromCalendar(String calendarId) {
        try {
            if (calendarId == null || calendarId.isEmpty()) {
                println "No calendar ID provided, using primary calendar"
                return getUpcomingEvents()
            }
            
            // Get current token
            String accessToken = configManager.accessToken

            // If token appears invalid, try to validate with server before prompting
            if (!hasValidToken() || !isValidTokenFormat(accessToken)) {
                // If the token format is valid but possibly expired, check with server
                if (isValidTokenFormat(accessToken)) {
                    boolean isValid = validateTokenWithServer(accessToken);

                    if (isValid) {
                        // Token is valid according to server validation
                        configManager.updateTokens(accessToken, configManager.refreshToken);
                        println "Token validated with server successfully."
                    } else if (!authenticate()) {
                        throw new RuntimeException("Failed to re-authenticate to obtain a valid token")
                    }
                } else if (!authenticate()) {
                    throw new RuntimeException("Failed to re-authenticate to obtain a valid token")
                }
                accessToken = configManager.accessToken;
            }
            
            // Get events from specific calendar
            String baseUrl = "${GRAPH_ENDPOINT}/me/calendars/${calendarId}/events"

            ZonedDateTime startOfDay = ZonedDateTime.now()
            String startTime = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            
            // Look ahead to end of day
            String endTime = ZonedDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            
            println "Getting events from calendar ID: ${calendarId}"
            println "Start time: ${startTime} (now)"
            println "End time: ${endTime} (end of day)"
            
            // Create a properly encoded URL
            URI uri = createCalendarEventsUri(baseUrl, startTime, endTime)
            String url = uri.toString()
            
            println "Requesting calendar events with URL: ${url}"
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Bearer ${accessToken}")
                .header("Accept", "application/json")
                .GET()
                .build()
            
            HttpResponse<String> response = executeRequestWithRetry(request, uri)
            
            if (response.statusCode() == 200) {
                List<CalendarEvent> events = parseEventResponse(response.body())
                return events
            } else {
                println "Failed to retrieve events from calendar (HTTP ${response.statusCode()})"
                return []
            }
        } catch (Exception e) {
            println "Error retrieving events from calendar: ${e.message}"
            e.printStackTrace()
            return []
        }
    }
    
    /**
     * Get upcoming events from all available calendars
     * This can help ensure we don't miss any events that might be in different calendars
     * @return List of calendar events from all calendars
     */
    List<CalendarEvent> getUpcomingEventsFromAllCalendars() {
        try {
            List<Map<String, String>> calendars = getAvailableCalendars()
            if (calendars.isEmpty()) {
                println "No calendars found, using primary calendar"
                return getUpcomingEvents()
            }
            
            List<CalendarEvent> allEvents = []
            
            // Get events from each calendar
            calendars.each { calendar ->
                println "Getting events from calendar: ${calendar.name}"
                List<CalendarEvent> eventsFromCalendar = getUpcomingEventsFromCalendar(calendar.id)
                // Tag events with calendar name
                eventsFromCalendar.each { event ->
                    event.calendarName = calendar.name
                }
                allEvents.addAll(eventsFromCalendar)
            }
            
            println "Found ${allEvents.size()} total events across ${calendars.size()} calendars"
            
            return allEvents
        } catch (Exception e) {
            println "Error retrieving events from all calendars: ${e.message}"
            e.printStackTrace()
            return []
        }
    }
    
    /**
     * Validates that a token has the basic JWT format (three parts separated by dots)
     */
    private boolean isValidTokenFormat(String token) {
        if (token == null || token.isEmpty()) {
            return false
        }
        
        // A valid JWT token consists of three parts separated by dots
        // Header.Payload.Signature
        String[] parts = token.split("\\.")
        return parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty() && !parts[2].isEmpty()
    }
    
    /**
     * Validates token by making a lightweight request to Microsoft Graph API
     * @return true if the token is valid according to Microsoft's server
     */
    private boolean validateTokenWithServer(String token) {
        if (!isValidTokenFormat(token)) {
            return false
        }

        try {
            // Make a lightweight request to validate the token
            // Using /me endpoint which is a minimal call
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("${GRAPH_ENDPOINT}/me"))
                .header("Authorization", "Bearer ${token}")
                .header("Accept", "application/json")
                .GET()
                .build()

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            // If we get a 200 OK, the token is valid
            return response.statusCode() == 200
        } catch (Exception e) {
            println "Error validating token with server: ${e.message}"
            return false
        }
    }

    /**
     * Create a properly encoded URI for calendar events
     */
    private URI createCalendarEventsUri(String baseUrl, String startTime, String endTime) throws URISyntaxException {
        try {
            // Clean the date times to ensure they're in a format the API accepts
            startTime = cleanDateTimeForFilter(startTime)
            endTime = cleanDateTimeForFilter(endTime)
            
            // Create URL with encoded components manually
            StringBuilder urlBuilder = new StringBuilder(baseUrl)
            urlBuilder.append("?\$select=id,subject,organizer,start,end,location,isOnlineMeeting,onlineMeeting,bodyPreview,responseStatus")
            
            // Properly encode the filter parameter
            String filterParam = "start/dateTime ge '" + startTime + "' and start/dateTime le '" + endTime + "'"
            urlBuilder.append("&\$filter=").append(URLEncoder.encode(filterParam, "UTF-8"))
            
            // Add orderby
            urlBuilder.append("&\$orderby=").append(URLEncoder.encode("start/dateTime asc", "UTF-8"))
            
            // Log the encoded URL
            println "Encoded URL: ${urlBuilder.toString()}"
            
            return new URI(urlBuilder.toString())
        } catch (Exception e) {
            println "Error creating URI: " + e.getMessage()
            
            // Fall back to safer approach with less precise filtering
            return createSimpleDateFilterUri(baseUrl)
        }
    }
    
    /**
     * Clean date time string for use in filter
     */
    private String cleanDateTimeForFilter(String dateTime) {
        // Remove any timezone indicators that might cause problems
        dateTime = dateTime.replaceAll("\\.[0-9]+-[0-9]+:[0-9]+", "Z")
        dateTime = dateTime.replaceAll("\\.[0-9]+\\+[0-9]+:[0-9]+", "Z")
        dateTime = dateTime.replaceAll("\\.[0-9]+Z", "Z")
        
        // If it still has problematic characters, simplify even further
        if (dateTime.contains(" ") || dateTime.contains("'") || dateTime.contains(":")) {
            // Just use the date part (YYYY-MM-DD)
            try {
                ZonedDateTime dt = ZonedDateTime.parse(dateTime)
                return dt.format(DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (Exception e) {
                // If parsing fails, extract just the date part as a string
                int tIndex = dateTime.indexOf('T')
                if (tIndex > 0) {
                    return dateTime.substring(0, tIndex)
                } else {
                    return dateTime
                }
            }
        }
        
        return dateTime
    }
    
    /**
     * Fall back to a simpler date filter that's less likely to have encoding issues
     */
    private URI createSimpleDateFilterUri(String baseUrl) {
        try {
            // Use a simpler date format without time components
            // Start from now
            String today = ZonedDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            // Look to end of day
            String tomorrow = ZonedDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0).format(DateTimeFormatter.ISO_LOCAL_DATE)
            
            StringBuilder urlBuilder = new StringBuilder(baseUrl)
            urlBuilder.append("?\$select=id,subject,organizer,start,end,location,isOnlineMeeting,onlineMeeting,bodyPreview")
            
            // Encode filter parameter
            String filterParam = "start/dateTime ge '" + today + "' and start/dateTime le '" + tomorrow + "'"
            urlBuilder.append("&\$filter=").append(URLEncoder.encode(filterParam, "UTF-8"))
            
            // Add orderby
            urlBuilder.append("&\$orderby=").append(URLEncoder.encode("start/dateTime asc", "UTF-8"))
            
            // Increase the maximum number of events returned
            urlBuilder.append("&\$top=50")
            
            println "Using fallback simple date URL: ${urlBuilder.toString()}"
            
            return new URI(urlBuilder.toString())
        } catch (Exception e) {
            println "Error creating fallback URI: " + e.getMessage()
            
            // Ultimate fallback - just get recent calendar events with limited date filtering
            try {
                return new URI(baseUrl + "?\$select=id,subject,organizer,start,end,location,isOnlineMeeting,onlineMeeting,bodyPreview,responseStatus&\$top=50&\$orderby=start/dateTime asc")
            } catch (URISyntaxException e2) {
                throw new RuntimeException("Failed to create even a basic URI: " + e2.getMessage())
            }
        }
    }
    
    /**
     * Parse the JSON response from the Graph API into CalendarEvent objects
     */
    private List<CalendarEvent> parseEventResponse(String json) {
        List<CalendarEvent> events = []
        
        try {
            Map parsed = new JsonSlurper().parseText(json) as Map
            List valueList = parsed['value'] as List
            
            valueList.each { Object eventData ->
                Map eventMap = eventData as Map
                CalendarEvent event = new CalendarEvent()
                event.id = eventMap['id'] as String
                event.subject = eventMap['subject'] as String
                
                Map organizerMap = eventMap['organizer'] as Map
                Map emailAddressMap = organizerMap['emailAddress'] as Map
                event.organizer = emailAddressMap['name'] as String
                
                if (eventMap['location']) {
                    Map locationMap = eventMap['location'] as Map
                    event.location = locationMap['displayName'] as String
                }
                
                // Parse date times with improved handling for different formats
                try {
                    Map startMap = eventMap['start'] as Map
                    Map endMap = eventMap['end'] as Map
                    
                    // Get the date and timezone components
                    String startTimeStr = startMap['dateTime'] as String
                    String startTimeZone = startMap['timeZone'] as String
                    String endTimeStr = endMap['dateTime'] as String
                    String endTimeZone = endMap['timeZone'] as String
                    
                    // println "\nEvent datetime info for '${event.subject}':"
                    // println "  Raw start: ${startTimeStr}, timezone: ${startTimeZone}"
                    // println "  Raw end: ${endTimeStr}, timezone: ${endTimeZone}"
                    
                    // Handle various timezone formats
                    event.startTime = parseDateTime(startTimeStr, startTimeZone)
                    event.endTime = parseDateTime(endTimeStr, endTimeZone)
                    
                    // // Log parsed times
                    // println "  Parsed start: ${event.startTime}"
                    // println "  Parsed end: ${event.endTime}"
                    // println "  Current time: ${ZonedDateTime.now()}"
                    // println "  Minutes to start: ${event.getMinutesToStart()}"
                    
                    // Fall back to local timezone if parsing fails
                    if (event.startTime == null) {
                        println "Warning: Could not parse start time: ${startTimeStr} ${startTimeZone}"
                        // Try to extract date part at minimum
                        event.startTime = ZonedDateTime.now() // Default to now as last resort
                    }
                    
                    if (event.endTime == null) {
                        println "Warning: Could not parse end time: ${endTimeStr} ${endTimeZone}"
                        // Default to start time plus 1 hour if we have a start time
                        event.endTime = event.startTime ? event.startTime.plusHours(1) : ZonedDateTime.now().plusHours(1)
                    }
                } catch (Exception e) {
                    println "Error parsing date/time for event ${event.subject}: ${e.message}"
                    // Set default times so the event can still be displayed
                    event.startTime = ZonedDateTime.now()
                    event.endTime = ZonedDateTime.now().plusHours(1)
                }
                
                // Online meeting info
                event.isOnlineMeeting = eventMap['isOnlineMeeting'] as boolean
                if (event.isOnlineMeeting && eventMap['onlineMeeting']) {
                    Map onlineMeetingMap = eventMap['onlineMeeting'] as Map
                    event.onlineMeetingUrl = onlineMeetingMap['joinUrl'] as String
                }
                
                event.bodyPreview = eventMap['bodyPreview'] as String
                
                // Extract responseStatus if available
                if (eventMap['responseStatus']) {
                    Map responseStatusMap = eventMap['responseStatus'] as Map
                    event.responseStatus = responseStatusMap['response'] as String
                    // println "  Event response status: ${event.responseStatus}"
                }
                
                events.add(event)
            }
        } catch (Exception e) {
            println "Error parsing event data: ${e.message}"
            e.printStackTrace() // Add stack trace for better debugging
        }
        
        return events
    }
    
    /**
     * Helper method to parse date/time with various timezone formats
     * This improved version properly handles Microsoft Graph API's timezone information
     * and ensures events are displayed in the correct local time
     */
    private ZonedDateTime parseDateTime(String dateTimeStr, String timeZone) {
        // Make multiple attempts with different formats
        List<Exception> exceptions = []
        
        try {
            // Debug info to help diagnose timezone issues
            // println "Parsing datetime: ${dateTimeStr} with timezone: ${timeZone}"
            
            // First attempt: Parse as a LocalDateTime and then apply the timezone
            // This is the most reliable method for Graph API data
            try {
                // Format: 2025-05-14T17:00:00.0000000
                java.time.LocalDateTime localDateTime = java.time.LocalDateTime.parse(
                    dateTimeStr.replaceAll("\\.\\d+", ""), // Clean up fractional seconds
                    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
                )
                
                // Apply timezone properly
                ZoneId zoneId;
                if (timeZone == "UTC") {
                    zoneId = ZoneId.of("UTC")
                } else {
                    try {
                        zoneId = ZoneId.of(timeZone)
                    } catch (Exception e) {
                        // If the timezone string isn't a valid ZoneId, log and use UTC
                        println "Invalid timezone: ${timeZone}, falling back to UTC. Error: ${e.message}"
                        zoneId = ZoneId.of("UTC")
                    }
                }
                
                // First create the datetime in the specified timezone
                ZonedDateTime eventTimeInOriginalZone = localDateTime.atZone(zoneId)
                
                // Then convert to the user's preferred timezone or system default
                ZoneId targetZoneId;
                if (configManager.preferredTimezone && !configManager.preferredTimezone.isEmpty()) {
                    try {
                        targetZoneId = ZoneId.of(configManager.preferredTimezone);
                        if (!hasLoggedTimezone) {
                            println "Using preferred timezone from config: ${targetZoneId}"
                            hasLoggedTimezone = true
                        }
                    } catch (Exception e) {
                        println "Invalid preferred timezone in config: ${configManager.preferredTimezone}, falling back to system default"
                        targetZoneId = ZonedDateTime.now().getZone();
                    }
                } else {
                    targetZoneId = ZonedDateTime.now().getZone();
                }
                
                ZonedDateTime eventTimeInLocalZone = eventTimeInOriginalZone.withZoneSameInstant(targetZoneId)
                
                // println "Parsed to: ${eventTimeInLocalZone} (original zone: ${zoneId}, converted to: ${targetZoneId})"
                
                return eventTimeInLocalZone
            } catch (Exception e) {
                exceptions.add(e)
                println "Failed first parsing method: ${e.message}"
            }
            
            // Second attempt: Try with direct string concatenation
            try {
                // Microsoft Graph API format: 2025-05-14T17:00:00.0000000
                String cleanedDateTime = dateTimeStr.replaceAll("\\.\\d+", "") // Remove fractional seconds
                
                // Handle various timezone formats
                String dateTimeWithZone;
                if (timeZone == "UTC") {
                    dateTimeWithZone = cleanedDateTime + "Z" // Append Z for UTC/Zulu time
                } else {
                    // Try to construct a valid ISO-8601 datetime with timezone
                    try {
                        // This will work for timezone IDs like "America/New_York"
                        dateTimeWithZone = cleanedDateTime + "[" + timeZone + "]"
                    } catch (Exception e) {
                        // Fall back to UTC if there's any issue
                        dateTimeWithZone = cleanedDateTime + "Z"
                    }
                }
                
                try {
                    // Try to parse the combined string directly
                    ZonedDateTime zonedDateTime = ZonedDateTime.parse(dateTimeWithZone);
                    // Convert to local timezone
                    return zonedDateTime.withZoneSameInstant(ZonedDateTime.now().getZone());
                } catch (Exception e) {
                    println "Failed to parse with direct timezone: ${dateTimeWithZone}, error: ${e.message}"
                    throw e; // Rethrow to try the next method
                }
            } catch (Exception e) {
                exceptions.add(e)
                println "Failed second parsing method: ${e.message}"
            }
            
            // Last desperate attempt: just use the date part and a default time
            try {
                String datePart = dateTimeStr.split("T")[0]
                java.time.LocalDate localDate = java.time.LocalDate.parse(datePart)
                java.time.LocalDateTime localDateTime = localDate.atTime(0, 0) // Midnight
                println "Falling back to date-only parsing with midnight time: ${localDate}"
                return localDateTime.atZone(ZoneId.systemDefault())
            } catch (Exception e) {
                exceptions.add(e)
                println "Failed third parsing method: ${e.message}"
                throw e; // Let the outer catch block handle this
            }
        } catch (Exception e) {
            // Log all attempted parsing exceptions for debugging
            println "Failed to parse date/time: ${dateTimeStr} ${timeZone}"
            println "Attempts failed with: ${exceptions.collect { it.message }.join(", ")}"
            println "Final error: ${e.message}"
            return null
        }
    }
    
    /**
     * Helper to create form data for POST requests
     */
    private static HttpRequest.BodyPublisher ofFormData(Map<String, String> data) {
        StringBuilder builder = new StringBuilder()
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (builder.length() > 0) {
                builder.append("&")
            }
            builder.append(URLEncoder.encode(entry.key, "UTF-8"))
            builder.append("=")
            builder.append(URLEncoder.encode(entry.value, "UTF-8"))
        }
        return HttpRequest.BodyPublishers.ofString(builder.toString())
    }
    
    /**
     * Open a URL in the default browser
     */
    private static void openBrowser(String url) {
        String os = System.getProperty("os.name").toLowerCase()
        Runtime rt = Runtime.getRuntime()
        
        try {
            println "Attempting to open browser for URL: ${url}"
            
            if (os.contains("mac")) {
                println "Detected macOS, using 'open' command"
                Process process = rt.exec(["open", url] as String[])
                int exitCode = process.waitFor()
                println "Browser open command completed with exit code: ${exitCode}"
            } else if (os.contains("windows")) {
                println "Detected Windows, using 'start' command"
                rt.exec(["cmd", "/c", "start", url] as String[])
            } else {
                // Assume Linux or Unix
                println "Detected Linux/Unix, trying common browsers"
                String[] browsers = ["firefox", "google-chrome", "chromium-browser", "mozilla", "konqueror", "netscape", "opera", "epiphany"]
                String browser = null
                
                for (String b : browsers) {
                    if (browser == null && rt.exec(["which", b] as String[]).waitFor() == 0) {
                        browser = b
                    }
                }
                
                if (browser != null) {
                    rt.exec([browser, url] as String[])
                } else {
                    throw new Exception("No web browser found")
                }
            }
        } catch (Exception e) {
            println "Failed to open browser: ${e.message}"
            println "Please manually open this URL in your browser: ${url}"
            
            // Print full stack trace for debugging
            e.printStackTrace()
        }
    }
    
    /**
     * Simple HTTP server that shows a form for token entry
     */
    private static class TokenEntryServer {
        private final int port
        private HttpServer server
        private Map<String, String> tokens
        private CountDownLatch tokenLatch = new CountDownLatch(1)
        
        TokenEntryServer(int port) {
            this.port = port
        }
        
        void start() throws IOException {
            try {
                // First try a more reliable binding approach with explicit InetSocketAddress
                println "Attempting to start token entry server on port ${port}..."
                
                // Find an available local IP address
                InetSocketAddress address = findLocalAddress()
                
                // Create and start the server
                server = HttpServer.create(address, 0)
                server.createContext("/", new TokenEntryHandler())
                server.setExecutor(Executors.newSingleThreadExecutor())
                server.start()
                
                println "Token entry server started at http://${address.hostName}:${port}/"
                println "Please open http://localhost:${port}/ in your browser"
            } catch (BindException e) {
                println "Cannot bind to port ${port}. It may be in use by another application."
                throw e
            } catch (IOException e) {
                println "Error starting token entry server: ${e.message}"
                throw e
            }
        }
        
        /**
         * Find a suitable local address to bind to
         */
        private InetSocketAddress findLocalAddress() throws IOException {
            // First try localhost/loopback
            return new InetSocketAddress("localhost", port)
        }
        
        void stop() {
            if (server != null) {
                server.stop(1) // 1 second delay
                println "Token entry server stopped"
            }
        }
        
        Map<String, String> waitForTokens(int timeoutSeconds) {
            try {
                boolean received = tokenLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                return received ? tokens : null
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        
        private class TokenEntryHandler implements HttpHandler {
            @Override
            void handle(HttpExchange exchange) throws IOException {
                String method = exchange.requestMethod
                
                if (method == "GET") {
                    // Show token entry form
                    String form = """
                    <html>
                    <head>
                        <title>Enter OAuth Tokens</title>
                        <style>
                            body { font-family: Arial, sans-serif; margin: 40px; line-height: 1.6; }
                            h1 { color: #333; }
                            form { margin-top: 20px; }
                            label { display: block; margin-top: 10px; font-weight: bold; }
                            textarea, input[type="text"] { width: 100%; padding: 8px; margin-top: 5px; }
                            textarea { height: 100px; }
                            button { background-color: #4CAF50; color: white; padding: 10px 15px; 
                                     border: none; cursor: pointer; margin-top: 20px; }
                            .note { background-color: #f8f8f8; padding: 10px; border-left: 4px solid #ccc; margin-bottom: 20px; }
                            .warning { background-color: #fff3cd; padding: 10px; border-left: 4px solid #ffc107; margin-bottom: 20px; }
                            .steps { background-color: #e8f4f8; padding: 15px; border-radius: 5px; margin-bottom: 20px; }
                            .token-example { font-family: monospace; background: #f5f5f5; padding: 5px; font-size: 0.9em; }
                            .method { background-color: #e9f7ef; padding: 15px; border-radius: 5px; margin-bottom: 10px; }
                            .image-container { margin: 15px 0; text-align: center; }
                            .image-container img { max-width: 100%; border: 1px solid #ddd; }
                        </style>
                    </head>
                    <body>
                        <h1>Enter OAuth Tokens</h1>
                        
                        <div class="warning">
                            <strong>Important:</strong> A valid access token for Microsoft Graph API must be in JWT format, which looks like:
                            <div class="token-example">eyJ0eXAiOi...a-lot-of-characters-here...1nNCjM</div>
                            <p>It should contain two periods (.) that divide it into three parts.</p>
                        </div>
                        
                        <div class="steps">
                            <h3>Multiple Ways to Get Your Token:</h3>
                            
                            <div class="method">
                                <h4>Method 1: Direct from Okta or Microsoft Login Page</h4>
                                <ol>
                                    <li>After signing in through Okta or Microsoft login page, you'll be redirected to Office/Outlook web app</li>
                                    <li>In that page, open your browser's developer tools:
                                        <ul>
                                            <li>Chrome/Edge: Right-click → Inspect, or press F12</li>
                                            <li>Safari: Enable developer menu in Safari → Preferences → Advanced, then use Safari → Develop → Show Web Inspector</li>
                                            <li>Firefox: Right-click → Inspect, or press F12</li>
                                        </ul>
                                    </li>
                                    <li>In Developer Tools, go to the "Application" tab (Chrome/Edge) or "Storage" tab (Firefox)</li>
                                    <li>Look for "Local Storage" or "Session Storage" in the left sidebar</li>
                                    <li>Click on the domain (usually outlook.office.com or similar)</li>
                                    <li>Search for keys containing "token", "auth", or "access"</li>
                                    <li>The access token is often stored under keys like "accessToken", "authToken", or within a JSON object</li>
                                </ol>
                            </div>
                            
                            <div class="method">
                                <h4>Method 2: From Network Requests (If Method 1 Doesn't Work)</h4>
                                <ol>
                                    <li>After signing in, in Developer Tools, go to the "Network" tab</li>
                                    <li>Filter requests by typing "graph" in the filter box to show only requests to Microsoft Graph API</li>
                                    <li>If you don't see any Graph API requests, try performing an action in Outlook that might trigger a request (e.g., click on your calendar)</li>
                                    <li>Click on any request to graph.microsoft.com</li>
                                    <li>Look in the "Headers" tab for:
                                        <ul>
                                            <li>The "Authorization" header (the token comes after the word "Bearer" if present)</li>
                                            <li>If you don't see "Bearer" in the Authorization header, the token might be in a different format</li>
                                            <li>Alternatively, check "Request Payload" or "Form Data" sections for token-related fields</li>
                                        </ul>
                                    </li>
                                </ol>
                            </div>
                            
                            <div class="method">
                                <h4>Method 3: Using Microsoft Graph Explorer (Easiest Option)</h4>
                                <ol>
                                    <li>Open <a href="https://developer.microsoft.com/en-us/graph/graph-explorer" target="_blank">Microsoft Graph Explorer</a></li>
                                    <li>Click "Sign In" in the top right and authenticate with your Microsoft account</li>
                                    <li>After signing in, click on your profile picture/icon in the top right</li>
                                    <li>In the dropdown menu, look for "Access Token" or a related option</li>
                                    <li>Copy the displayed token - this is a valid token you can use directly</li>
                                </ol>
                            </div>
                        </div>
                        
                        <div class="note">
                            <p><strong>Tip:</strong> If you're having trouble finding the token, try using the Microsoft Graph Explorer method as it's the most straightforward way to get a valid access token.</p>
                            <p>Access tokens for Microsoft services typically start with "eyJ" and are several hundred characters long.</p>
                        </div>
                        
                        <form method="post">
                            <label for="accessToken">Access Token (must be in JWT format with two periods):</label>
                            <textarea id="accessToken" name="accessToken" required 
                                placeholder="eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIsImtpZCI6Im..."></textarea>
                            
                            <label for="refreshToken">Refresh Token (if available):</label>
                            <textarea id="refreshToken" name="refreshToken" placeholder="Optional: Refresh token allows the app to get new access tokens without re-authentication"></textarea>
                            

                            
                            <button type="submit">Submit</button>
                        </form>
                        
                        <script>
                            // Simple validation to check if token has JWT format
                            document.querySelector('form').addEventListener('submit', function(e) {
                                var token = document.getElementById('accessToken').value.trim();
                                if (!token.includes('.') || token.split('.').length !== 3) {
                                    e.preventDefault();
                                    alert('The access token appears to be invalid. A JWT token should contain exactly two periods (.) dividing it into three parts.');
                                }
                            });
                        </script>
                    </body>
                    </html>
                    """
                    
                    // Fix for connection closing too soon:
                    // 1. First set headers
                    exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                    exchange.responseHeaders.set("Connection", "close") // Explicitly close after completion
                    
                    // 2. Get bytes BEFORE sending headers so we know the content length
                    byte[] responseBytes = form.getBytes("UTF-8")
                    
                    // 3. Send response headers with correct content length
                    exchange.sendResponseHeaders(200, responseBytes.length)
                    
                    // 4. Write response body and ensure proper cleanup
                    OutputStream os = null
                    try {
                        os = exchange.getResponseBody()
                        os.write(responseBytes)
                        os.flush()
                    } finally {
                        if (os != null) {
                            try { os.close() } catch (IOException e) { /* ignore */ }
                        }
                    }
                } else if (method == "POST") {
                    // Process token submission
                    InputStreamReader isr = null
                    BufferedReader br = null
                    String formData = null
                    
                    try {
                        isr = new InputStreamReader(exchange.requestBody, "UTF-8")
                        br = new BufferedReader(isr)
                        formData = br.readLine()
                    } finally {
                        if (br != null) try { br.close() } catch (IOException e) { /* ignore */ }
                        if (isr != null) try { isr.close() } catch (IOException e) { /* ignore */ }
                    }
                    
                    // Parse form data
                    Map<String, String> formParams = [:]
                    if (formData != null) {
                        formData.split("&").each { String param ->
                            String[] pair = param.split("=", 2)
                            if (pair.length == 2) {
                                formParams[pair[0]] = URLDecoder.decode(pair[1], "UTF-8")
                            }
                        }
                    }
                    
                    // Store tokens
                    tokens = [
                        accessToken: formParams["accessToken"],
                        refreshToken: formParams["refreshToken"]
                    ]
                    
                    // Send success response
                    String response = """
                    <html>
                    <head>
                        <title>Tokens Submitted</title>
                        <style>
                            body { font-family: Arial, sans-serif; margin: 40px; text-align: center; }
                            h1 { color: #4CAF50; }
                        </style>
                    </head>
                    <body>
                        <h1>Tokens Submitted Successfully</h1>
                        <p>You can now close this window and return to the application.</p>
                    </body>
                    </html>
                    """
                    
                    // Same improved response handling as the GET case
                    exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                    exchange.responseHeaders.set("Connection", "close")
                    
                    byte[] responseBytes = response.getBytes("UTF-8")
                    exchange.sendResponseHeaders(200, responseBytes.length)
                    
                    OutputStream os = null
                    try {
                        os = exchange.getResponseBody()
                        os.write(responseBytes)
                        os.flush()
                    } finally {
                        if (os != null) {
                            try { os.close() } catch (IOException e) { /* ignore */ }
                        }
                    }
                    
                    // Signal that we got the tokens
                    tokenLatch.countDown()
                } else {
                    // Method not supported
                    String errorMsg = "Method not allowed: " + method;
                    exchange.sendResponseHeaders(405, errorMsg.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(errorMsg.getBytes());
                    os.close();
                }
            }
        }
    }

    private Map<String, String> getTokensFromUser() {
        try {
            // The sign-in URL (either Okta SSO URL or direct Microsoft login)
            String signInUrl = configManager.signInUrl

            if (!signInUrl) {
                println """
                ERROR: No sign-in URL configured. You need to set up either:

                1. For Okta SSO: Configure the 'signInUrl' property with your organization's Okta SSO URL
                   Example: https://your-company.okta.com/home/office365/0oa1b2c3d4/aln5b6c7d8

                2. For direct Microsoft authentication: Register your own application in Azure Portal
                   and configure the clientId, clientSecret, and redirectUri properties
                """
                return null
            }

            println "Starting direct authentication flow with sign-in URL: ${signInUrl}"

            Map<String, String> tokens = null
            while (tokens == null || !tokens.containsKey("accessToken") || tokens.accessToken == null || tokens.accessToken.isEmpty()) {
                tokens = outlookAlerterUI.promptForTokens(signInUrl)

                if (tokens == null) {
                    println "Authentication timed out or was canceled by the user."
                    return null
                }

                if (!tokens.containsKey("accessToken") || tokens.accessToken == null || tokens.accessToken.isEmpty()) {
                    println "No access token was provided. Prompting user again."
                }
            }

            println "Token received from user interface."
            return tokens

        } catch (Exception e) {
            println "Error during token entry: ${e.message}"
            e.printStackTrace()
            return null
        }
    }
}