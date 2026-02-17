package com.unhuman.outlookalerter.core

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.unhuman.outlookalerter.model.CalendarEvent
import com.unhuman.outlookalerter.ui.SimpleTokenDialog
import com.unhuman.outlookalerter.ui.OutlookAlerterUI
import com.unhuman.outlookalerter.util.LogManager
import com.unhuman.outlookalerter.util.LogCategory
import com.unhuman.outlookalerter.util.ScreenFlasher
import com.unhuman.outlookalerter.util.ScreenFlasherFactory
import javax.swing.JOptionPane
import java.net.URI

// Imports for SSL/TLS certificate handling
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate

/**
 * Client for Microsoft Graph API to access Outlook calendar
 * with simplified authentication support
 */
@CompileStatic
class OutlookClient {
    // Graph API endpoints
    private static final String GRAPH_ENDPOINT = 'https://graph.microsoft.com/v1.0'
    private static final String SCOPE = 'offline_access https://graph.microsoft.com/Calendars.Read'

    // HTTP client for API requests
    private HttpClient httpClient
    
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
        this.httpClient = createHttpClient();
    }

    /**
     * Constructor for OutlookClient.
     * @param configManager The configuration manager instance.
     */
    OutlookClient(ConfigManager configManager) {
        this.configManager = configManager
        this.httpClient = createHttpClient()
    }
    
    /**
     * Creates an HttpClient with or without certificate validation based on settings
     */
    private HttpClient createHttpClient() {
        boolean ignoreCertValidation = configManager.getIgnoreCertValidation()
        LogManager.getInstance().info(LogCategory.DATA_FETCH, "Creating HTTP client with certificate validation: " + (ignoreCertValidation ? "disabled" : "enabled")) 
        if (ignoreCertValidation) {
            return createHttpClientWithoutCertValidation();
        } else {
            return HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        }
    }
    
    /**
     * Creates an HttpClient that ignores certificate validation
     * 
     * WARNING: This method disables SSL certificate validation, which is a security risk.
     * It should only be used in environments where self-signed or invalid certificates are used,
     * and the user understands the security implications.
     * 
     * Security risks include:
     * - Vulnerability to man-in-the-middle attacks
     * - Inability to verify server identity
     * - Exposure of sensitive data to potential eavesdropping
     */
    private HttpClient createHttpClientWithoutCertValidation() {
        try {
            // Create a trust manager that does not validate certificate chains
            TrustManager[] trustAllCerts = [ 
                new X509TrustManager() {
                    void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0] }
                }
            ] as TrustManager[];

            // Install the all-trusting trust manager
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            
            // Create an HttpClient that uses the custom SSLContext
            return HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Error creating HttpClient without certificate validation: " + e.getMessage(), e);
            // Fall back to default HttpClient
            return HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        }
    }
    
    /**
     * Update the HTTP client based on current settings
     */
    void updateHttpClient() {
        LogManager.getInstance().info(LogCategory.GENERAL, "Updating HTTP client based on current settings")
        this.httpClient = createHttpClient();
    }
    
    /**
     * Force update of certificate validation setting and HTTP client
     * @param ignoreCertValidation Whether to ignore SSL certificate validation
     */
    void updateCertificateValidation(boolean ignoreCertValidation) {
        // Always update the setting and HTTP client regardless of current value
        // This ensures the value provided is always properly saved
        LogManager.getInstance().info(LogCategory.GENERAL, "Certificate validation setting set to: " + (ignoreCertValidation ? "disabled" : "enabled"))
        configManager.updateDefaultIgnoreCertValidation(ignoreCertValidation)
        updateHttpClient()
    }
    
    /**
     * Authenticate with Microsoft Graph API
     * @return true if authentication was successful
     */
    boolean authenticate() {
        // Check if we have a valid token
        if (hasValidToken()) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Using existing valid token.")
            return true
        }
        
        // Try to refresh token if we have one
        if (configManager.refreshToken) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Attempting to refresh token...")
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
            LogManager.getInstance().info(LogCategory.GENERAL, "Token appears to be invalid according to Microsoft's server");
            return false;
        }

        // If we got here, the token is valid according to the server
        LogManager.getInstance().info(LogCategory.GENERAL, "Token validated with server successfully.");
        return true;
    }
    
    /**
     * Check if we already have a valid token without refreshing or re-authenticating
     * This method is specifically designed for UI feedback purposes.
     * Delegates to hasValidToken() which performs the same server validation.
     * @return true if the token is valid according to the server, false if re-authentication or refresh is needed
     */
    boolean isTokenAlreadyValid() {
        return hasValidToken()
    }
    
    /**
     * Attempt to refresh the access token using the refresh token
     */
    private boolean refreshToken() {
        try {
            String tokenUrl = configManager.tokenEndpoint
            
            if (!tokenUrl) {
                LogManager.getInstance().error(LogCategory.GENERAL, "Error: Token endpoint URL not configured")
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
                
                configManager.updateTokens(accessToken, refreshToken, configManager.getIgnoreCertValidation())
                lastTokenValidationResult = TOKEN_REFRESHED
                LogManager.getInstance().info(LogCategory.GENERAL, "Token refreshed successfully!")
                return true
            } else if (response.statusCode() == 401 || response.statusCode() == 400) {
                // If the refresh token is rejected or invalid, we need to re-authenticate
                LogManager.getInstance().info(LogCategory.GENERAL, "Refresh token was rejected (${response.statusCode()}). Need to re-authenticate.")
                // Fall through to perform direct authentication
                return performDirectAuthentication()
            } else {
                LogManager.getInstance().error(LogCategory.GENERAL, "Failed to refresh token: ${response.statusCode()}")
                return false
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Error refreshing token: ${e.message}")
            return false
        }
    }
    
    /**
     * Perform direct authentication via browser SSO or GUI token entry
     */
    protected boolean performDirectAuthentication() {
        synchronized(authLock) {
            if (isAuthenticating) {
                LogManager.getInstance().info(LogCategory.GENERAL, "Authentication already in progress")
                return false
            }
            isAuthenticating = true
        }
        
        try {
            // Show the token dialog to the user
            def tokens = getTokensFromUser()
            // If user cancels or closes the dialog, exit immediately and do not retry or show extra popups
            if (tokens == null) {
                LogManager.getInstance().info(LogCategory.GENERAL, "Token dialog was cancelled by the user. Aborting authentication.")
                synchronized(authLock) { isAuthenticating = false }
                throw new AuthenticationCancelledException("Authentication was cancelled by the user.", "user_cancelled")
            }
            
            // Check if user provided a token
            if (tokens == null || !tokens.containsKey("accessToken") || tokens.accessToken == null || tokens.accessToken.isEmpty()) {
                LogManager.getInstance().info(LogCategory.GENERAL, "No access token was provided or dialog was cancelled.")
                return false
            }
            
            LogManager.getInstance().info(LogCategory.GENERAL, "Token received from user interface.")
            
            // Check if certificate validation setting was provided and update if needed
            boolean ignoreCertValidation = configManager.getIgnoreCertValidation()
            if (tokens.containsKey("ignoreCertValidation")) {
                LogManager.getInstance().info(LogCategory.GENERAL, "*** Certificate validation setting provided in tokens: " + tokens.ignoreCertValidation + " exists " + tokens.containsKey("ignoreCertValidation"))
                ignoreCertValidation = Boolean.valueOf(tokens.ignoreCertValidation)
                LogManager.getInstance().info(LogCategory.GENERAL, "*** Certificate validation setting from token dialog: " + ignoreCertValidation)

                boolean currentSetting = configManager.getIgnoreCertValidation()
                
                // Always log the certificate validation setting
                LogManager.getInstance().info(LogCategory.GENERAL, "Certificate validation setting from token dialog: " + 
                       (ignoreCertValidation ? "disabled" : "enabled") + 
                       " (current setting: " + (currentSetting ? "disabled" : "enabled") + ")")
                       
                boolean settingChanged = currentSetting != ignoreCertValidation
                
                if (settingChanged) {
                    LogManager.getInstance().info(LogCategory.GENERAL, "Certificate validation setting changed, updating HTTP client...")
                    configManager.updateIgnoreCertValidation(ignoreCertValidation)
                    updateHttpClient()
                } else {
                    LogManager.getInstance().info(LogCategory.GENERAL, "Certificate validation setting unchanged")
                }
            } else {
                LogManager.getInstance().info(LogCategory.GENERAL, "No certificate validation setting provided in tokens")
            }
            
            // Redact token for logging (show only first 10 chars)
            String redactedToken = tokens.accessToken?.size() > 10 ? 
                tokens.accessToken.substring(0, 10) + "..." : 
                "(invalid token format)"
            LogManager.getInstance().info(LogCategory.GENERAL, "Received token starting with: ${redactedToken}")
            
            String accessToken = null
            long expiryTime = 0
            
            int validationAttempts = 0
            final int MAX_VALIDATION_ATTEMPTS = 3
            while (validationAttempts < MAX_VALIDATION_ATTEMPTS) {
                validationAttempts++
                // Validate the received token with Microsoft's server before accepting it
                accessToken = tokens.accessToken
                
                LogManager.getInstance().info(LogCategory.GENERAL, "Validating token with Microsoft's server... (attempt ${validationAttempts}/${MAX_VALIDATION_ATTEMPTS})")
                boolean isValid = validateTokenWithServer(accessToken)

                if (!isValid) {
                    if (validationAttempts >= MAX_VALIDATION_ATTEMPTS) {
                        LogManager.getInstance().error(LogCategory.GENERAL, "Token validation failed after ${MAX_VALIDATION_ATTEMPTS} attempts.")
                        throw new AuthenticationCancelledException("Token validation failed after ${MAX_VALIDATION_ATTEMPTS} attempts", "validation_exhausted")
                    }
                    LogManager.getInstance().info(LogCategory.GENERAL, "Token validation failed. The token appears to be invalid. Requesting a new token...")
                    JOptionPane.showMessageDialog(
                        null,
                        "The provided token was rejected by Microsoft's server. Please get a new token and try again.",
                        "Invalid Token",
                        JOptionPane.ERROR_MESSAGE
                    )
                    // Get new tokens from user by showing the dialog again
                    tokens = getTokensFromUser()
                    if (tokens == null) {
                        LogManager.getInstance().info(LogCategory.GENERAL, "User canceled token dialog during validation retry")
                        throw new AuthenticationCancelledException("Authentication was cancelled during token validation", "validation_cancelled")
                    }
                    // Continue the while loop to validate the new token
                    continue
                }
                
                // Break the loop since we have a valid token
                break
            }
            
            // Refresh token is not collected anymore, pass null as refresh token
            configManager.updateTokens(accessToken, null, ignoreCertValidation)
            LogManager.getInstance().info(LogCategory.GENERAL, "Authentication successful! Token validated and saved.")
            return true
            
        } catch (AuthenticationCancelledException ace) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Authentication was cancelled by the user")
            // Note: isAuthenticating is released by the finally block below
            throw ace  // Rethrow to be handled by the caller
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Error during authentication: ${e.message}", e)
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
        LogManager.getInstance().info(LogCategory.GENERAL, "Access token was rejected (${errorType}). Attempting to re-authenticate...")

        // Clear the existing token's expiry time to force validation
        configManager.updateTokens(configManager.accessToken, configManager.refreshToken, configManager.getDefaultIgnoreCertValidation());

        // Try to refresh the token first if we have one
        if (configManager.refreshToken && refreshToken()) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Successfully refreshed the token.")
            return configManager.accessToken
        }

        // If refresh failed or no refresh token, try direct authentication
        if (performDirectAuthentication()) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Successfully re-authenticated.")
            return configManager.accessToken
        }

        // If we got here, all authentication attempts failed
        return null
    }
    
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
            LogManager.getInstance().info(LogCategory.GENERAL, "Received ${errorType} response. Attempting to re-authenticate...")

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
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "Retrying request with new token...")
                HttpResponse<String> retryResponse = httpClient.send(retryRequest, HttpResponse.BodyHandlers.ofString())

                if (retryResponse.statusCode() == 200) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "Request retry successful")
                    return retryResponse
                } else {
                    LogManager.getInstance().error(LogCategory.DATA_FETCH, "Request retry failed with status ${retryResponse.statusCode()}")
                    // Return the retry response even if it failed - the caller will handle the status code
                    return retryResponse
                }
            } else {
                LogManager.getInstance().error(LogCategory.DATA_FETCH, "Failed to obtain a new token for retry")
            }
        }

        return response
    }

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
            
            // Check if we have any token at all
            if (accessToken == null || accessToken.isEmpty()) {
                LogManager.getInstance().info(LogCategory.GENERAL, "No access token available. Showing token dialog...");
                try {
                    if (!performDirectAuthentication()) {
                        LogManager.getInstance().error(LogCategory.GENERAL, "No token provided after showing dialog. Cannot retrieve calendar events.");
                        throw new AuthenticationCancelledException(
                            "Authentication failed during calendar refresh.",
                            "validation_cancelled"
                        )
                    }
                    lastTokenValidationResult = TOKEN_NEW_AUTHENTICATION;
                } catch (AuthenticationCancelledException ace) {
                    // Rethrow any authentication cancellation
                    throw ace;
                }
            }                // First check if token format is valid
            else if (!isValidTokenFormat(accessToken)) {
                LogManager.getInstance().info(LogCategory.GENERAL, "Token format is invalid. Need to authenticate again.");
                if (!authenticate()) {
                    // Show direct authentication dialog if regular auth fails
                    LogManager.getInstance().info(LogCategory.GENERAL, "Re-authentication failed. Showing token dialog...");
                    try {
                        if (!performDirectAuthentication()) {
                            LogManager.getInstance().error(LogCategory.GENERAL, "User cancelled token dialog. Cannot retrieve calendar events.");
                            throw new AuthenticationCancelledException(
                                "Authentication was cancelled by the user.", 
                                "user_cancelled"
                            );
                        }
                    } catch (AuthenticationCancelledException ace) {
                        throw ace;
                    }
                }
                lastTokenValidationResult = TOKEN_NEW_AUTHENTICATION;
            } 
            // Then check if we have a valid token (by server validation)
            else if (!hasValidToken()) {
                // If we get here, the token is invalid according to the server and we need to authenticate
                LogManager.getInstance().info(LogCategory.GENERAL, "Token validation failed. Need to authenticate again.");
                if (!authenticate()) {
                    // Show direct authentication dialog if regular auth fails
                    LogManager.getInstance().info(LogCategory.GENERAL, "Re-authentication failed. Showing token dialog...");
                    try {
                        if (!performDirectAuthentication()) {
                            LogManager.getInstance().error(LogCategory.GENERAL, "User cancelled token dialog. Cannot retrieve calendar events.");
                            throw new AuthenticationCancelledException(
                                "Authentication was cancelled by the user.", 
                                "user_cancelled"
                            );
                        }
                    } catch (AuthenticationCancelledException ace) {
                        throw ace;
                    }
                }
                lastTokenValidationResult = TOKEN_NEW_AUTHENTICATION;
            } 
            else {
                // Token was validated with the server in hasValidToken()
                lastTokenValidationResult = TOKEN_VALID_AFTER_SERVER_VALIDATION;
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "Token was validated with server. Using validated token.");
                accessToken = configManager.accessToken;
            }
            
            // Make sure we use the latest token
            accessToken = configManager.accessToken;

            // Get events using calendar view API
            String baseUrl = "${GRAPH_ENDPOINT}/me/calendarView"

            // Calculate start and end time parameters
            ZonedDateTime startOfDay = ZonedDateTime.now()
            ZonedDateTime endOfTomorrow = ZonedDateTime.now().plusDays(1).withHour(23).withMinute(59).withSecond(59)

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
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "Requesting calendar events with calendar view URL: ${url}")
            
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
                LogManager.getInstance().error(LogCategory.DATA_FETCH, "Failed to retrieve events using calendar view (HTTP ${response.statusCode()})")
                return []
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Error retrieving calendar events using calendar view: ${e.message}", e)
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
            LogManager.getInstance().error(LogCategory.GENERAL, "Error validating token with server: ${e.message}")
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
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "Encoded URL: ${urlBuilder.toString()}")
            
            return new URI(urlBuilder.toString())
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Error creating URI: " + e.getMessage())
            
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
            
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "Using fallback simple date URL: ${urlBuilder.toString()}")
            
            return new URI(urlBuilder.toString())
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Error creating fallback URI: " + e.getMessage())
            
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
                if (organizerMap != null) {
                    Map emailAddressMap = organizerMap['emailAddress'] as Map
                    event.organizer = emailAddressMap != null ? emailAddressMap['name'] as String : null
                }
                
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
                        LogManager.getInstance().info(LogCategory.DATA_FETCH, "Warning: Could not parse start time: ${startTimeStr} ${startTimeZone}")
                        // Try to extract date part at minimum
                        event.startTime = ZonedDateTime.now() // Default to now as last resort
                    }
                    
                    if (event.endTime == null) {
                        LogManager.getInstance().info(LogCategory.DATA_FETCH, "Warning: Could not parse end time: ${endTimeStr} ${endTimeZone}")
                        // Default to start time plus 1 hour if we have a start time
                        event.endTime = event.startTime ? event.startTime.plusHours(1) : ZonedDateTime.now().plusHours(1)
                    }
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.DATA_FETCH, "Error parsing date/time for event ${event.subject}: ${e.message}")
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
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Error parsing event data: ${e.message}", e)
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
                        LogManager.getInstance().info(LogCategory.DATA_FETCH, "Invalid timezone: ${timeZone}, falling back to UTC. Error: ${e.message}")
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
                            LogManager.getInstance().info(LogCategory.DATA_FETCH, "Using preferred timezone from config: ${targetZoneId}")
                            hasLoggedTimezone = true
                        }
                    } catch (Exception e) {
                        LogManager.getInstance().info(LogCategory.DATA_FETCH, "Invalid preferred timezone in config: ${configManager.preferredTimezone}, falling back to system default")
                        targetZoneId = ZonedDateTime.now().getZone();
                    }
                } else {
                    targetZoneId = ZonedDateTime.now().getZone();
                }
                
                ZonedDateTime eventTimeInLocalZone = eventTimeInOriginalZone.withZoneSameInstant(targetZoneId)
                
                // println "Parsed to: ${eventTimeInLocalZone} (original zone: ${zoneId}, converted to: ${targetId})"
                
                return eventTimeInLocalZone
            } catch (Exception e) {
                exceptions.add(e)
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "Failed first parsing method: ${e.message}")
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
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "Failed to parse with direct timezone: ${dateTimeWithZone}, error: ${e.message}")
                    throw e; // Rethrow to try the next method
                }
            } catch (Exception e) {
                exceptions.add(e)
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "Failed second parsing method: ${e.message}")
            }
            
            // Last desperate attempt: just use the date part and a default time
            try {
                String datePart = dateTimeStr.split("T")[0]
                java.time.LocalDate localDate = java.time.LocalDate.parse(datePart)
                java.time.LocalDateTime localDateTime = localDate.atTime(0, 0) // Midnight
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "Falling back to date-only parsing with midnight time: ${localDate}")
                return localDateTime.atZone(ZoneId.systemDefault())
            } catch (Exception e) {
                exceptions.add(e)
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "Failed third parsing method: ${e.message}")
                throw e; // Let the outer catch block handle this
            }
        } catch (Exception e) {
            // Log all attempted parsing exceptions for debugging
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Failed to parse date/time: ${dateTimeStr} ${timeZone}")
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Attempts failed with: ${exceptions.collect { it.message }.join(", ")}")
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Final error: ${e.message}")
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
     * Get tokens from user through UI or dialog
     * @return Map containing tokens or null if cancelled
     */
    private Map<String, String> getTokensFromUser() {
        // Make sure we're not already showing a dialog
        int attempts = 0
        int maxAttempts = 3
        Map<String, String> resultTokens = null
        String signInUrl = configManager.getSignInUrl()

        try {
            // Keep trying until we get a valid token or max attempts is reached
            while ((resultTokens == null || !resultTokens.containsKey("accessToken") || 
                   resultTokens.accessToken == null || resultTokens.accessToken.isEmpty()) 
                  && attempts < maxAttempts) {
                attempts++
                LogManager.getInstance().info(LogCategory.GENERAL, "Token dialog attempt ${attempts} of ${maxAttempts}")
                
                // Fire all alert components (beep, banner, tray, flash) via the UI,
                // consistent with meeting alerts. Only on first attempt.
                if (attempts == 1) {
                    CalendarEvent tokenEvent = new CalendarEvent(
                        subject: "⚠️ TOKEN ENTRY REQUIRED ⚠️",
                        startTime: ZonedDateTime.now(),
                        endTime: ZonedDateTime.now().plusMinutes(1),
                        isOnlineMeeting: false,
                        organizer: "Token Entry",
                        responseStatus: "Flash",
                        calendarName: "Token Entry")
                    if (outlookAlerterUI != null) {
                        outlookAlerterUI.performFullAlert(
                            "⚠️ Token Entry Required - Please enter your OAuth tokens",
                            "Token Entry Required",
                            "Please enter your OAuth tokens in the dialog that will appear.",
                            [tokenEvent])
                    } else {
                        // Console mode fallback — flash only
                        Thread flashThread = new Thread(() -> {
                            try {
                                ScreenFlasher screenFlasher = ScreenFlasherFactory.createScreenFlasher()
                                screenFlasher.flash(tokenEvent)
                            } catch (Exception e) {
                                LogManager.getInstance().error(LogCategory.GENERAL, "[ERROR] Token alert flash error: " + e.getMessage())
                            }
                        }, "TokenAlertFlashThread")
                        flashThread.setDaemon(true)
                        flashThread.start()
                    }
                }

                // Handle both UI and console mode
                if (outlookAlerterUI != null) {
                    // UI mode
                    resultTokens = outlookAlerterUI.promptForTokens(signInUrl)
                } else {
                    // Console mode - show dialog directly
                    SimpleTokenDialog dialog = SimpleTokenDialog.getInstance(signInUrl)
                    dialog.show()
                    resultTokens = dialog.getTokens()
                }
                
                // Return null for cancelled dialog without additional messages
                if (resultTokens == null) {
                    LogManager.getInstance().info(LogCategory.GENERAL, "Token dialog was cancelled or closed. No tokens obtained.")
                    throw new AuthenticationCancelledException(
                        "Authentication was cancelled by the user.", 
                        "user_cancelled"
                    )
                }
                
                if (resultTokens != null && resultTokens.containsKey("accessToken") && 
                    resultTokens.accessToken != null && !resultTokens.accessToken.isEmpty()) {
                    // Handle certificate validation setting in console mode
                    if (resultTokens.containsKey("ignoreCertValidation")) {
                        try {
                            boolean ignoreCertValidation = Boolean.valueOf(resultTokens.get("ignoreCertValidation"))
                            LogManager.getInstance().info(LogCategory.GENERAL, "Certificate validation setting: " + 
                                          (ignoreCertValidation ? "disabled" : "enabled"))
                            ConfigManager.getInstance().updateIgnoreCertValidation(ignoreCertValidation)
                            updateHttpClient()
                        } catch (Exception e) {
                            LogManager.getInstance().error(LogCategory.GENERAL, "Error applying certificate validation setting: " + e.getMessage())
                        }
                    }
                    LogManager.getInstance().info(LogCategory.GENERAL, "Valid token received from user interface.")
                    return resultTokens
                }
            }
            
            // If we get here without valid tokens, return null
            LogManager.getInstance().info(LogCategory.GENERAL, "No valid token obtained after ${attempts} attempt(s).")
            return null
            
        } catch (AuthenticationCancelledException ace) {
            // Let the cancellation exception propagate up - the UI layer will handle any necessary notifications
            throw ace
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Error during token entry: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Exception thrown when authentication is cancelled by the user
     * This allows us to distinguish between authentication failures and user cancellations
     */
    static class AuthenticationCancelledException extends RuntimeException {
        private final String reason;
        
        AuthenticationCancelledException(String message) { 
            super(message)
            this.reason = "user_cancelled"
            println "AuthenticationCancelledException created: " + message
        }
        
        AuthenticationCancelledException(String message, String reason) {
            super(message)
            this.reason = reason
            println "AuthenticationCancelledException created: " + message + " (reason: " + reason + ")"
        }
        
        /**
         * Get the specific reason for the authentication cancellation
         * @return One of: "user_cancelled", "window_closed", "validation_failed", etc.
         */
        public String getReason() {
            return reason
        }

        @Override
        String toString() {
            return "AuthenticationCancelledException: " + getMessage() + " (reason: " + reason + ")"
        }
    }
}