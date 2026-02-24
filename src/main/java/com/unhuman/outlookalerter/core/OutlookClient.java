package com.unhuman.outlookalerter.core;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.awt.TrayIcon;

import com.unhuman.outlookalerter.model.CalendarEvent;
import com.unhuman.outlookalerter.ui.SimpleTokenDialog;
import com.unhuman.outlookalerter.ui.OutlookAlerterUI;
import com.unhuman.outlookalerter.util.LogManager;
import com.unhuman.outlookalerter.util.LogCategory;
import com.unhuman.outlookalerter.util.ScreenFlasher;
import com.unhuman.outlookalerter.util.ScreenFlasherFactory;

/**
 * Client for Microsoft Graph API to access Outlook calendar
 * with simplified authentication support.
 *
 * Java conversion of OutlookClient.groovy — replaces groovy.json.JsonSlurper
 * with org.json.JSONObject / org.json.JSONArray.
 */
public class OutlookClient {
    // Graph API endpoints
    private static final String GRAPH_ENDPOINT = "https://graph.microsoft.com/v1.0";
    private static final String SCOPE = "offline_access https://graph.microsoft.com/Calendars.Read";

    // HTTP client for API requests
    private HttpClient httpClient;

    // Request timeout for individual HTTP calls (prevents indefinite hangs on read)
    private static final java.time.Duration REQUEST_TIMEOUT = java.time.Duration.ofSeconds(60);

    // Configuration manager
    private final ConfigManager configManager;

    // Lock object to prevent multiple concurrent authentication attempts
    private final Object authLock = new Object();
    private volatile boolean isAuthenticating = false;

    // Track if we've logged about timezone
    private boolean hasLoggedTimezone = false;

    // Reference to OutlookAlerterUI for token dialog handling
    private final OutlookAlerterUI outlookAlerterUI;

    // MSAL authentication provider for OAuth2 browser-based auth
    private final MsalAuthProvider msalAuthProvider;

    /**
     * Creates a new Outlook client with the given configuration and UI reference.
     */
    public OutlookClient(ConfigManager configManager, OutlookAlerterUI outlookAlerterUI) {
        this.configManager = configManager;
        this.outlookAlerterUI = outlookAlerterUI;
        this.msalAuthProvider = new MsalAuthProvider(configManager);
        this.httpClient = createHttpClient();
    }

    /**
     * Constructor for OutlookClient (no UI).
     * @param configManager The configuration manager instance.
     */
    public OutlookClient(ConfigManager configManager) {
        this.configManager = configManager;
        this.outlookAlerterUI = null;
        this.msalAuthProvider = new MsalAuthProvider(configManager);
        this.httpClient = createHttpClient();
    }

    /**
     * Get the MSAL authentication provider.
     * @return The MsalAuthProvider instance
     */
    public MsalAuthProvider getMsalAuthProvider() {
        return msalAuthProvider;
    }

    /**
     * Creates an HttpClient with or without certificate validation based on settings.
     */
    private HttpClient createHttpClient() {
        boolean ignoreCertValidation = configManager.getIgnoreCertValidation();
        LogManager.getInstance().info(LogCategory.DATA_FETCH,
                "Creating HTTP client with certificate validation: " + (ignoreCertValidation ? "disabled" : "enabled"));
        if (ignoreCertValidation) {
            return createHttpClientWithoutCertValidation();
        } else {
            return HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();
        }
    }

    /**
     * Creates an HttpClient that ignores certificate validation.
     *
     * WARNING: This method disables SSL certificate validation, which is a security risk.
     */
    private HttpClient createHttpClientWithoutCertValidation() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL,
                    "Error creating HttpClient without certificate validation: " + e.getMessage(), e);
            return HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();
        }
    }

    /**
     * Update the HTTP client based on current settings.
     */
    public void updateHttpClient() {
        LogManager.getInstance().info(LogCategory.GENERAL, "Updating HTTP client based on current settings");
        this.httpClient = createHttpClient();
    }

    /**
     * Force update of certificate validation setting and HTTP client.
     * @param ignoreCertValidation Whether to ignore SSL certificate validation
     */
    public void updateCertificateValidation(boolean ignoreCertValidation) {
        LogManager.getInstance().info(LogCategory.GENERAL,
                "Certificate validation setting set to: " + (ignoreCertValidation ? "disabled" : "enabled"));
        configManager.updateDefaultIgnoreCertValidation(ignoreCertValidation);
        updateHttpClient();
    }

    /**
     * Authenticate with Microsoft Graph API.
     * Prefers MSAL OAuth (silent refresh) when configured, then falls back
     * to legacy refresh token, and finally to manual token entry.
     * @return true if authentication was successful
     */
    public boolean authenticate() {
        if (hasValidToken()) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Using existing valid token.");
            return true;
        }

        // Try MSAL silent refresh first (if configured)
        if (msalAuthProvider.isConfigured()) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Attempting MSAL silent token acquisition...");
            String msalToken = msalAuthProvider.acquireTokenSilently();
            if (msalToken != null) {
                configManager.updateTokens(msalToken, null, configManager.getIgnoreCertValidation());
                LogManager.getInstance().info(LogCategory.GENERAL, "MSAL silent token acquisition successful.");
                return true;
            }
        }

        // Try silent refresh from Device Code Flow (Okta) cache
        {
            LogManager.getInstance().info(LogCategory.GENERAL, "Attempting Okta cache silent token refresh...");
            String oktaToken = msalAuthProvider.acquireTokenSilentlyFromOktaCache();
            if (oktaToken != null) {
                configManager.updateTokens(oktaToken, null, configManager.getIgnoreCertValidation());
                LogManager.getInstance().info(LogCategory.GENERAL, "Okta cache silent token refresh successful.");
                return true;
            }
        }

        // Fall back to legacy refresh token
        String refreshTokenValue = configManager.getRefreshToken();
        if (refreshTokenValue != null && !refreshTokenValue.isEmpty()) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Attempting to refresh token...");
            if (refreshToken()) {
                return true;
            }
        }

        return performDirectAuthentication();
    }

    /**
     * Check if we have a valid token by validating with Microsoft's server.
     * @return true if the token is valid according to the server
     */
    public boolean hasValidToken() {
        String accessToken = configManager.getAccessToken();

        if (accessToken == null || accessToken.isEmpty()) {
            return false;
        }

        boolean isValid = validateTokenWithServer(accessToken);

        if (!isValid) {
            LogManager.getInstance().info(LogCategory.GENERAL,
                    "Token appears to be invalid according to Microsoft's server");
            return false;
        }

        LogManager.getInstance().info(LogCategory.GENERAL, "Token validated with server successfully.");
        return true;
    }

    /**
     * Attempt to silently refresh the token without any user interaction.
     * Tries MSAL silent, Okta DCF cache silent, and legacy refresh token.
     * Does NOT fall back to interactive authentication.
     * @return true if a silent refresh succeeded and we now have a valid token
     */
    public boolean attemptSilentTokenRefresh() {
        // Try MSAL silent refresh first (if configured)
        if (msalAuthProvider.isConfigured()) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Attempting MSAL silent token acquisition...");
            String msalToken = msalAuthProvider.acquireTokenSilently();
            if (msalToken != null) {
                configManager.updateTokens(msalToken, null, configManager.getIgnoreCertValidation());
                LogManager.getInstance().info(LogCategory.GENERAL, "MSAL silent token acquisition successful.");
                return true;
            }
        }

        // Try silent refresh from Device Code Flow (Okta) cache
        {
            LogManager.getInstance().info(LogCategory.GENERAL, "Attempting Okta cache silent token refresh...");
            String oktaToken = msalAuthProvider.acquireTokenSilentlyFromOktaCache();
            if (oktaToken != null) {
                configManager.updateTokens(oktaToken, null, configManager.getIgnoreCertValidation());
                LogManager.getInstance().info(LogCategory.GENERAL, "Okta cache silent token refresh successful.");
                return true;
            }
        }

        // Fall back to legacy refresh token
        String refreshTokenValue = configManager.getRefreshToken();
        if (refreshTokenValue != null && !refreshTokenValue.isEmpty()) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Attempting legacy refresh token...");
            if (refreshToken()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if we already have a valid token without refreshing or re-authenticating.
     * Delegates to hasValidToken() which performs the same server validation.
     * @return true if the token is valid according to the server
     */
    public boolean isTokenAlreadyValid() {
        return hasValidToken();
    }

    /**
     * Attempt to refresh the access token using the refresh token.
     */
    private boolean refreshToken() {
        try {
            String tokenUrl = configManager.getTokenEndpoint();

            if (tokenUrl == null || tokenUrl.isEmpty()) {
                LogManager.getInstance().error(LogCategory.GENERAL, "Error: Token endpoint URL not configured");
                return false;
            }

            Map<String, String> formData = new HashMap<>();
            formData.put("grant_type", "refresh_token");
            formData.put("refresh_token", configManager.getRefreshToken());

            String clientId = configManager.getClientId();
            if (clientId != null && !clientId.isEmpty()) {
                formData.put("client_id", clientId);
                String clientSecret = configManager.getClientSecret();
                if (clientSecret != null && !clientSecret.isEmpty()) {
                    formData.put("client_secret", clientSecret);
                }
            }

            formData.put("scope", SCOPE);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(ofFormData(formData))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject json = new JSONObject(response.body());

                String accessToken = json.optString("access_token", null);
                String refreshTokenValue = json.optString("refresh_token", null);

                configManager.updateTokens(accessToken, refreshTokenValue, configManager.getIgnoreCertValidation());
                lastTokenValidationResult = TOKEN_REFRESHED;
                LogManager.getInstance().info(LogCategory.GENERAL, "Token refreshed successfully!");
                return true;
            } else if (response.statusCode() == 401 || response.statusCode() == 400) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "Refresh token was rejected (" + response.statusCode() + "). Need to re-authenticate.");
                return performDirectAuthentication();
            } else {
                LogManager.getInstance().error(LogCategory.GENERAL,
                        "Failed to refresh token: " + response.statusCode());
                return false;
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Error refreshing token: " + e.getMessage());
            return false;
        }
    }

    /**
     * Perform direct authentication via browser SSO or GUI token entry.
     */
    protected boolean performDirectAuthentication() {
        synchronized (authLock) {
            if (isAuthenticating) {
                LogManager.getInstance().info(LogCategory.GENERAL, "Authentication already in progress");
                return false;
            }
            isAuthenticating = true;
        }

        try {
            Map<String, String> tokens = getTokensFromUser();
            if (tokens == null) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "Token dialog was cancelled by the user. Aborting authentication.");
                synchronized (authLock) { isAuthenticating = false; }
                throw new AuthenticationCancelledException("Authentication was cancelled by the user.", "user_cancelled");
            }

            if (!tokens.containsKey("accessToken") || tokens.get("accessToken") == null || tokens.get("accessToken").isEmpty()) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "No access token was provided or dialog was cancelled.");
                return false;
            }

            LogManager.getInstance().info(LogCategory.GENERAL, "Token received from user interface.");

            boolean ignoreCertValidation = configManager.getIgnoreCertValidation();
            if (tokens.containsKey("ignoreCertValidation")) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "*** Certificate validation setting provided in tokens: " + tokens.get("ignoreCertValidation")
                                + " exists " + tokens.containsKey("ignoreCertValidation"));
                ignoreCertValidation = Boolean.valueOf(tokens.get("ignoreCertValidation"));
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "*** Certificate validation setting from token dialog: " + ignoreCertValidation);

                boolean currentSetting = configManager.getIgnoreCertValidation();

                LogManager.getInstance().info(LogCategory.GENERAL,
                        "Certificate validation setting from token dialog: "
                                + (ignoreCertValidation ? "disabled" : "enabled")
                                + " (current setting: " + (currentSetting ? "disabled" : "enabled") + ")");

                boolean settingChanged = currentSetting != ignoreCertValidation;

                if (settingChanged) {
                    LogManager.getInstance().info(LogCategory.GENERAL,
                            "Certificate validation setting changed, updating HTTP client...");
                    configManager.updateIgnoreCertValidation(ignoreCertValidation);
                    updateHttpClient();
                } else {
                    LogManager.getInstance().info(LogCategory.GENERAL, "Certificate validation setting unchanged");
                }
            } else {
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "No certificate validation setting provided in tokens");
            }

            String accessTokenValue = tokens.get("accessToken");
            String redactedToken = (accessTokenValue != null && accessTokenValue.length() > 10)
                    ? accessTokenValue.substring(0, 10) + "..."
                    : "(invalid token format)";
            LogManager.getInstance().info(LogCategory.GENERAL, "Received token starting with: " + redactedToken);

            String accessToken = null;

            int validationAttempts = 0;
            final int MAX_VALIDATION_ATTEMPTS = 3;
            Map<String, String> currentTokens = tokens;
            while (validationAttempts < MAX_VALIDATION_ATTEMPTS) {
                validationAttempts++;
                accessToken = currentTokens.get("accessToken");

                LogManager.getInstance().info(LogCategory.GENERAL,
                        "Validating token with Microsoft's server... (attempt " + validationAttempts + "/" + MAX_VALIDATION_ATTEMPTS + ")");
                boolean isValid = validateTokenWithServer(accessToken);

                if (!isValid) {
                    if (validationAttempts >= MAX_VALIDATION_ATTEMPTS) {
                        LogManager.getInstance().error(LogCategory.GENERAL,
                                "Token validation failed after " + MAX_VALIDATION_ATTEMPTS + " attempts.");
                        throw new AuthenticationCancelledException(
                                "Token validation failed after " + MAX_VALIDATION_ATTEMPTS + " attempts",
                                "validation_exhausted");
                    }
                    LogManager.getInstance().warn(LogCategory.GENERAL,
                            "Token validation failed (attempt " + validationAttempts
                                    + "). The token appears to be invalid. Requesting a new token...");
                    if (outlookAlerterUI != null) {
                        outlookAlerterUI.showTrayNotification(
                                "Invalid Token",
                                "The provided token was rejected by Microsoft's server. Please get a new token.",
                                TrayIcon.MessageType.ERROR);
                    }
                    currentTokens = getTokensFromUser();
                    if (currentTokens == null) {
                        LogManager.getInstance().info(LogCategory.GENERAL,
                                "User canceled token dialog during validation retry");
                        throw new AuthenticationCancelledException(
                                "Authentication was cancelled during token validation", "validation_cancelled");
                    }
                    continue;
                }

                break;
            }

            configManager.updateTokens(accessToken, null, ignoreCertValidation);
            LogManager.getInstance().info(LogCategory.GENERAL,
                    "Authentication successful! Token validated and saved.");
            return true;

        } catch (AuthenticationCancelledException ace) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Authentication was cancelled by the user");
            throw ace;
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL,
                    "Error during authentication: " + e.getMessage(), e);
            return false;
        } finally {
            synchronized (authLock) {
                isAuthenticating = false;
            }
        }
    }

    /**
     * Handle a 401 Unauthorized or 403 Forbidden response by trying to re-authenticate.
     * Tries MSAL silent refresh first, then legacy refresh, then manual auth.
     * @param statusCode The HTTP status code (401 or 403) that triggered this handler
     * @return A new valid access token, or null if re-authentication failed
     */
    private String handleUnauthorizedResponse(int statusCode) {
        String errorType = (statusCode == 401) ? "401 Unauthorized" : "403 Forbidden";
        LogManager.getInstance().info(LogCategory.GENERAL,
                "Access token was rejected (" + errorType + "). Attempting to re-authenticate...");

        configManager.updateTokens(configManager.getAccessToken(), configManager.getRefreshToken(),
                configManager.getDefaultIgnoreCertValidation());

        // Try MSAL silent refresh first
        if (msalAuthProvider.isConfigured()) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Attempting MSAL silent token refresh after " + errorType + "...");
            String msalToken = msalAuthProvider.acquireTokenSilently();
            if (msalToken != null) {
                configManager.updateTokens(msalToken, null, configManager.getIgnoreCertValidation());
                LogManager.getInstance().info(LogCategory.GENERAL, "MSAL silent refresh successful after " + errorType);
                return msalToken;
            }
        }

        // Try silent refresh from Device Code Flow (Okta) cache
        {
            LogManager.getInstance().info(LogCategory.GENERAL, "Attempting Okta cache silent token refresh after " + errorType + "...");
            String oktaToken = msalAuthProvider.acquireTokenSilentlyFromOktaCache();
            if (oktaToken != null) {
                configManager.updateTokens(oktaToken, null, configManager.getIgnoreCertValidation());
                LogManager.getInstance().info(LogCategory.GENERAL, "Okta cache silent refresh successful after " + errorType);
                return oktaToken;
            }
        }

        // Fall back to legacy refresh
        String refreshTokenValue = configManager.getRefreshToken();
        if (refreshTokenValue != null && !refreshTokenValue.isEmpty() && refreshToken()) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Successfully refreshed the token.");
            return configManager.getAccessToken();
        }

        if (performDirectAuthentication()) {
            LogManager.getInstance().info(LogCategory.GENERAL, "Successfully re-authenticated.");
            return configManager.getAccessToken();
        }

        return null;
    }

    /**
     * Overload without statusCode — defaults to 401.
     */
    private String handleUnauthorizedResponse() {
        return handleUnauthorizedResponse(401);
    }

    /**
     * Execute a request and handle 401/403 errors by re-authenticating and retrying once.
     */
    private HttpResponse<String> executeRequestWithRetry(HttpRequest request, URI uri)
            throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            String errorType = (response.statusCode() == 401) ? "401 Unauthorized" : "403 Forbidden";
            LogManager.getInstance().info(LogCategory.GENERAL,
                    "Received " + errorType + " response. Attempting to re-authenticate...");

            String newToken = handleUnauthorizedResponse(response.statusCode());

            if (newToken != null) {
                HttpRequest retryRequest = HttpRequest.newBuilder()
                        .uri(uri)
                        .header("Authorization", "Bearer " + newToken)
                        .header("Accept", "application/json")
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build();

                LogManager.getInstance().info(LogCategory.DATA_FETCH, "Retrying request with new token...");
                HttpResponse<String> retryResponse = httpClient.send(retryRequest, HttpResponse.BodyHandlers.ofString());

                if (retryResponse.statusCode() == 200) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "Request retry successful");
                    return retryResponse;
                } else {
                    LogManager.getInstance().error(LogCategory.DATA_FETCH,
                            "Request retry failed with status " + retryResponse.statusCode());
                    return retryResponse;
                }
            } else {
                LogManager.getInstance().error(LogCategory.DATA_FETCH, "Failed to obtain a new token for retry");
            }
        }

        return response;
    }

    // ── Token validation result constants ─────────────────────────────────
    public static final String TOKEN_VALID_NO_ACTION = "TOKEN_VALID_NO_ACTION";
    public static final String TOKEN_VALID_AFTER_SERVER_VALIDATION = "TOKEN_VALID_AFTER_SERVER_VALIDATION";
    public static final String TOKEN_REFRESHED = "TOKEN_REFRESHED";
    public static final String TOKEN_NEW_AUTHENTICATION = "TOKEN_NEW_AUTHENTICATION";

    private String lastTokenValidationResult = TOKEN_VALID_NO_ACTION;

    /**
     * Get the result of the last token validation operation.
     * @return One of the TOKEN_* constants
     */
    public String getLastTokenValidationResult() {
        return lastTokenValidationResult;
    }

    // ── Calendar event retrieval ──────────────────────────────────────────

    /**
     * Retrieve upcoming calendar events using the calendarView endpoint.
     */
    public List<CalendarEvent> getUpcomingEventsUsingCalendarView() {
        try {
            lastTokenValidationResult = TOKEN_VALID_NO_ACTION;
            String accessToken = configManager.getAccessToken();

            if (accessToken == null || accessToken.isEmpty()) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "No access token available. Showing token dialog...");
                try {
                    if (!performDirectAuthentication()) {
                        LogManager.getInstance().error(LogCategory.GENERAL,
                                "No token provided after showing dialog. Cannot retrieve calendar events.");
                        throw new AuthenticationCancelledException(
                                "Authentication failed during calendar refresh.", "validation_cancelled");
                    }
                    lastTokenValidationResult = TOKEN_NEW_AUTHENTICATION;
                } catch (AuthenticationCancelledException ace) {
                    throw ace;
                }
            } else if (!isValidTokenFormat(accessToken)) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "Token format is invalid. Need to authenticate again.");
                if (!authenticate()) {
                    LogManager.getInstance().info(LogCategory.GENERAL,
                            "Re-authentication failed. Showing token dialog...");
                    try {
                        if (!performDirectAuthentication()) {
                            LogManager.getInstance().error(LogCategory.GENERAL,
                                    "User cancelled token dialog. Cannot retrieve calendar events.");
                            throw new AuthenticationCancelledException(
                                    "Authentication was cancelled by the user.", "user_cancelled");
                        }
                    } catch (AuthenticationCancelledException ace) {
                        throw ace;
                    }
                }
                lastTokenValidationResult = TOKEN_NEW_AUTHENTICATION;
            } else if (!hasValidToken()) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "Token validation failed. Need to authenticate again.");
                if (!authenticate()) {
                    LogManager.getInstance().info(LogCategory.GENERAL,
                            "Re-authentication failed. Showing token dialog...");
                    try {
                        if (!performDirectAuthentication()) {
                            LogManager.getInstance().error(LogCategory.GENERAL,
                                    "User cancelled token dialog. Cannot retrieve calendar events.");
                            throw new AuthenticationCancelledException(
                                    "Authentication was cancelled by the user.", "user_cancelled");
                        }
                    } catch (AuthenticationCancelledException ace) {
                        throw ace;
                    }
                }
                lastTokenValidationResult = TOKEN_NEW_AUTHENTICATION;
            } else {
                lastTokenValidationResult = TOKEN_VALID_AFTER_SERVER_VALIDATION;
                LogManager.getInstance().info(LogCategory.DATA_FETCH,
                        "Token was validated with server. Using validated token.");
                accessToken = configManager.getAccessToken();
            }

            // Make sure we use the latest token
            accessToken = configManager.getAccessToken();

            String baseUrl = GRAPH_ENDPOINT + "/me/calendarView";

            ZonedDateTime startOfDay = ZonedDateTime.now();
            ZonedDateTime endOfTomorrow = ZonedDateTime.now().plusDays(1)
                    .withHour(23).withMinute(59).withSecond(59);

            String startParam = startOfDay.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String endParam = endOfTomorrow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            StringBuilder urlBuilder = new StringBuilder(baseUrl);
            urlBuilder.append("?startDateTime=").append(URLEncoder.encode(startParam, "UTF-8"));
            urlBuilder.append("&endDateTime=").append(URLEncoder.encode(endParam, "UTF-8"));
            urlBuilder.append("&$select=id,subject,organizer,start,end,location,isOnlineMeeting,onlineMeeting,bodyPreview,body");
            urlBuilder.append("&$top=50");

            String url = urlBuilder.toString();
            LogManager.getInstance().info(LogCategory.DATA_FETCH,
                    "Requesting calendar events with calendar view URL: " + url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = executeRequestWithRetry(request, new URI(url));

            if (response.statusCode() == 200) {
                List<CalendarEvent> events = parseEventResponse(response.body());
                return events;
            } else {
                LogManager.getInstance().error(LogCategory.DATA_FETCH,
                        "Failed to retrieve events using calendar view (HTTP " + response.statusCode() + ")");
                return new ArrayList<>();
            }
        } catch (AuthenticationCancelledException ace) {
            throw ace;
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH,
                    "Error retrieving calendar events using calendar view: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    // ── Token format validation ───────────────────────────────────────────

    /**
     * Validates that a token has a plausible format.
     * Accepts both traditional JWT tokens (three dot-separated parts) and
     * MSAL compact/opaque tokens (e.g. Microsoft Graph v2 tokens that start
     * with "Ew..." and contain no dots).
     */
    private boolean isValidTokenFormat(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        // JWT format: header.payload.signature
        String[] parts = token.split("\\.");
        if (parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty() && !parts[2].isEmpty()) {
            return true;
        }
        // MSAL compact/opaque format: non-empty string of reasonable length
        return token.length() >= 20;
    }

    /**
     * Validates token by making a lightweight request to Microsoft Graph API.
     * Accepts any non-empty token string (JWT or MSAL compact/opaque format).
     * @return true if the token is valid according to Microsoft's server
     */
    private boolean validateTokenWithServer(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GRAPH_ENDPOINT + "/me"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL,
                    "Error validating token with server: " + e.getMessage());
            return false;
        }
    }

    // ── URI helpers ───────────────────────────────────────────────────────

    /**
     * Create a properly encoded URI for calendar events.
     */
    private URI createCalendarEventsUri(String baseUrl, String startTime, String endTime) throws URISyntaxException {
        try {
            startTime = cleanDateTimeForFilter(startTime);
            endTime = cleanDateTimeForFilter(endTime);

            StringBuilder urlBuilder = new StringBuilder(baseUrl);
            urlBuilder.append("?$select=id,subject,organizer,start,end,location,isOnlineMeeting,onlineMeeting,bodyPreview,body,responseStatus");

            String filterParam = "start/dateTime ge '" + startTime + "' and start/dateTime le '" + endTime + "'";
            urlBuilder.append("&$filter=").append(URLEncoder.encode(filterParam, "UTF-8"));
            urlBuilder.append("&$orderby=").append(URLEncoder.encode("start/dateTime asc", "UTF-8"));

            LogManager.getInstance().info(LogCategory.DATA_FETCH, "Encoded URL: " + urlBuilder.toString());

            return new URI(urlBuilder.toString());
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Error creating URI: " + e.getMessage());
            return createSimpleDateFilterUri(baseUrl);
        }
    }

    /**
     * Clean date time string for use in filter.
     */
    private String cleanDateTimeForFilter(String dateTime) {
        dateTime = dateTime.replaceAll("\\.[0-9]+-[0-9]+:[0-9]+", "Z");
        dateTime = dateTime.replaceAll("\\.[0-9]+\\+[0-9]+:[0-9]+", "Z");
        dateTime = dateTime.replaceAll("\\.[0-9]+Z", "Z");

        if (dateTime.contains(" ") || dateTime.contains("'") || dateTime.contains(":")) {
            try {
                ZonedDateTime dt = ZonedDateTime.parse(dateTime);
                return dt.format(DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception e) {
                int tIndex = dateTime.indexOf('T');
                if (tIndex > 0) {
                    return dateTime.substring(0, tIndex);
                } else {
                    return dateTime;
                }
            }
        }

        return dateTime;
    }

    /**
     * Fall back to a simpler date filter that's less likely to have encoding issues.
     */
    private URI createSimpleDateFilterUri(String baseUrl) {
        try {
            String today = ZonedDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            String tomorrow = ZonedDateTime.now().plusDays(1)
                    .withHour(0).withMinute(0).withSecond(0)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);

            StringBuilder urlBuilder = new StringBuilder(baseUrl);
            urlBuilder.append("?$select=id,subject,organizer,start,end,location,isOnlineMeeting,onlineMeeting,bodyPreview,body");

            String filterParam = "start/dateTime ge '" + today + "' and start/dateTime le '" + tomorrow + "'";
            urlBuilder.append("&$filter=").append(URLEncoder.encode(filterParam, "UTF-8"));
            urlBuilder.append("&$orderby=").append(URLEncoder.encode("start/dateTime asc", "UTF-8"));
            urlBuilder.append("&$top=50");

            LogManager.getInstance().info(LogCategory.DATA_FETCH,
                    "Using fallback simple date URL: " + urlBuilder.toString());

            return new URI(urlBuilder.toString());
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH,
                    "Error creating fallback URI: " + e.getMessage());
            try {
                return new URI(baseUrl
                        + "?$select=id,subject,organizer,start,end,location,isOnlineMeeting,onlineMeeting,bodyPreview,body,responseStatus&$top=50&$orderby=start/dateTime asc");
            } catch (URISyntaxException e2) {
                throw new RuntimeException("Failed to create even a basic URI: " + e2.getMessage());
            }
        }
    }

    // ── JSON response parsing (org.json) ──────────────────────────────────

    /**
     * Parse the JSON response from the Graph API into CalendarEvent objects.
     * Uses org.json.JSONObject / JSONArray instead of Groovy's JsonSlurper.
     */
    private List<CalendarEvent> parseEventResponse(String json) {
        List<CalendarEvent> events = new ArrayList<>();

        try {
            JSONObject parsed = new JSONObject(json);
            JSONArray valueList = parsed.getJSONArray("value");

            for (int i = 0; i < valueList.length(); i++) {
                JSONObject eventObj = valueList.getJSONObject(i);
                CalendarEvent event = new CalendarEvent();
                event.setId(eventObj.optString("id", null));
                event.setSubject(eventObj.optString("subject", null));

                JSONObject organizerObj = eventObj.optJSONObject("organizer");
                if (organizerObj != null) {
                    JSONObject emailAddressObj = organizerObj.optJSONObject("emailAddress");
                    event.setOrganizer(emailAddressObj != null ? emailAddressObj.optString("name", null) : null);
                }

                JSONObject locationObj = eventObj.optJSONObject("location");
                if (locationObj != null) {
                    event.setLocation(locationObj.optString("displayName", null));
                }

                // Parse date times
                try {
                    JSONObject startObj = eventObj.getJSONObject("start");
                    JSONObject endObj = eventObj.getJSONObject("end");

                    String startTimeStr = startObj.getString("dateTime");
                    String startTimeZone = startObj.getString("timeZone");
                    String endTimeStr = endObj.getString("dateTime");
                    String endTimeZone = endObj.getString("timeZone");

                    event.setStartTime(parseDateTime(startTimeStr, startTimeZone));
                    event.setEndTime(parseDateTime(endTimeStr, endTimeZone));

                    if (event.getStartTime() == null) {
                        LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                "Warning: Could not parse start time: " + startTimeStr + " " + startTimeZone);
                        event.setStartTime(ZonedDateTime.now());
                    }

                    if (event.getEndTime() == null) {
                        LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                "Warning: Could not parse end time: " + endTimeStr + " " + endTimeZone);
                        event.setEndTime(event.getStartTime() != null
                                ? event.getStartTime().plusHours(1)
                                : ZonedDateTime.now().plusHours(1));
                    }
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.DATA_FETCH,
                            "Error parsing date/time for event " + event.getSubject() + ": " + e.getMessage());
                    event.setStartTime(ZonedDateTime.now());
                    event.setEndTime(ZonedDateTime.now().plusHours(1));
                }

                event.setIsOnlineMeeting(eventObj.optBoolean("isOnlineMeeting", false));
                // Always attempt to get the joinUrl regardless of isOnlineMeeting flag
                // (manually-scheduled Zoom meetings may have isOnlineMeeting=false but still have a joinUrl)
                JSONObject onlineMeetingObj = eventObj.optJSONObject("onlineMeeting");
                if (onlineMeetingObj != null) {
                    event.setOnlineMeetingUrl(onlineMeetingObj.optString("joinUrl", null));
                }

                event.setBodyPreview(eventObj.optString("bodyPreview", null));

                // Store full body HTML for meeting URL extraction
                JSONObject bodyObj = eventObj.optJSONObject("body");
                if (bodyObj != null) {
                    event.setBodyHtml(bodyObj.optString("content", null));
                }

                JSONObject responseStatusObj = eventObj.optJSONObject("responseStatus");
                if (responseStatusObj != null) {
                    event.setResponseStatus(responseStatusObj.optString("response", null));
                }

                event.setCancelledByOrganizer(eventObj.optBoolean("isCancelled", false));

                // Skip cancelled/canceled events entirely — they must not appear anywhere in the UI
                if (event.isCancelled()) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH,
                            "Skipping cancelled event: " + event.getSubject());
                    continue;
                }

                events.add(event);
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH,
                    "Error parsing event data: " + e.getMessage(), e);
        }

        return events;
    }

    // ── Date / time parsing ───────────────────────────────────────────────

    /**
     * Helper method to parse date/time with various timezone formats.
     */
    private ZonedDateTime parseDateTime(String dateTimeStr, String timeZone) {
        List<Exception> exceptions = new ArrayList<>();

        try {
            // First attempt: parse as LocalDateTime then apply timezone
            try {
                LocalDateTime localDateTime = LocalDateTime.parse(
                        dateTimeStr.replaceAll("\\.\\d+", ""),
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME);

                ZoneId zoneId;
                if ("UTC".equals(timeZone)) {
                    zoneId = ZoneId.of("UTC");
                } else {
                    try {
                        zoneId = ZoneId.of(timeZone);
                    } catch (Exception e) {
                        LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                "Invalid timezone: " + timeZone + ", falling back to UTC. Error: " + e.getMessage());
                        zoneId = ZoneId.of("UTC");
                    }
                }

                ZonedDateTime eventTimeInOriginalZone = localDateTime.atZone(zoneId);

                ZoneId targetZoneId;
                String prefTz = configManager.getPreferredTimezone();
                if (prefTz != null && !prefTz.isEmpty()) {
                    try {
                        targetZoneId = ZoneId.of(prefTz);
                        if (!hasLoggedTimezone) {
                            LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                    "Using preferred timezone from config: " + targetZoneId);
                            hasLoggedTimezone = true;
                        }
                    } catch (Exception e) {
                        LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                "Invalid preferred timezone in config: " + prefTz + ", falling back to system default");
                        targetZoneId = ZonedDateTime.now().getZone();
                    }
                } else {
                    targetZoneId = ZonedDateTime.now().getZone();
                }

                ZonedDateTime eventTimeInLocalZone = eventTimeInOriginalZone.withZoneSameInstant(targetZoneId);
                return eventTimeInLocalZone;
            } catch (Exception e) {
                exceptions.add(e);
                LogManager.getInstance().info(LogCategory.DATA_FETCH,
                        "Failed first parsing method: " + e.getMessage());
            }

            // Second attempt: direct string concatenation
            try {
                String cleanedDateTime = dateTimeStr.replaceAll("\\.\\d+", "");

                String dateTimeWithZone;
                if ("UTC".equals(timeZone)) {
                    dateTimeWithZone = cleanedDateTime + "Z";
                } else {
                    try {
                        dateTimeWithZone = cleanedDateTime + "[" + timeZone + "]";
                    } catch (Exception e) {
                        dateTimeWithZone = cleanedDateTime + "Z";
                    }
                }

                try {
                    ZonedDateTime zonedDateTime = ZonedDateTime.parse(dateTimeWithZone);
                    return zonedDateTime.withZoneSameInstant(ZonedDateTime.now().getZone());
                } catch (Exception e) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH,
                            "Failed to parse with direct timezone: " + dateTimeWithZone + ", error: " + e.getMessage());
                    throw e;
                }
            } catch (Exception e) {
                exceptions.add(e);
                LogManager.getInstance().info(LogCategory.DATA_FETCH,
                        "Failed second parsing method: " + e.getMessage());
            }

            // Third attempt: date-only with midnight
            try {
                String datePart = dateTimeStr.split("T")[0];
                LocalDate localDate = LocalDate.parse(datePart);
                LocalDateTime localDateTime = localDate.atTime(0, 0);
                LogManager.getInstance().info(LogCategory.DATA_FETCH,
                        "Falling back to date-only parsing with midnight time: " + localDate);
                return localDateTime.atZone(ZoneId.systemDefault());
            } catch (Exception e) {
                exceptions.add(e);
                LogManager.getInstance().info(LogCategory.DATA_FETCH,
                        "Failed third parsing method: " + e.getMessage());
                throw e;
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH,
                    "Failed to parse date/time: " + dateTimeStr + " " + timeZone);
            LogManager.getInstance().error(LogCategory.DATA_FETCH,
                    "Attempts failed with: "
                            + exceptions.stream().map(Exception::getMessage).collect(Collectors.joining(", ")));
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Final error: " + e.getMessage());
            return null;
        }
    }

    // ── Form data helper ──────────────────────────────────────────────────

    /**
     * Helper to create form data for POST requests.
     */
    private static HttpRequest.BodyPublisher ofFormData(Map<String, String> data) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (builder.length() > 0) {
                builder.append("&");
            }
            builder.append(URLEncoder.encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8));
            builder.append("=");
            builder.append(URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return HttpRequest.BodyPublishers.ofString(builder.toString());
    }

    // ── Token dialog ──────────────────────────────────────────────────────

    /**
     * Get tokens from user through UI or dialog.
     * If MSAL is configured, the token dialog's "Sign In with Browser" button
     * will handle interactive browser auth — we do NOT auto-launch it here so
     * the user sees the alert + token dialog first.
     * @return Map containing tokens or null if cancelled
     */
    private Map<String, String> getTokensFromUser() {
        int attempts = 0;
        int maxAttempts = 3;
        Map<String, String> resultTokens = null;
        String signInUrl = configManager.getSignInUrl();

        try {
            while ((resultTokens == null || !resultTokens.containsKey("accessToken")
                    || resultTokens.get("accessToken") == null || resultTokens.get("accessToken").isEmpty())
                    && attempts < maxAttempts) {
                attempts++;
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "Token dialog attempt " + attempts + " of " + maxAttempts);

                // Fire alert on first attempt
                if (attempts == 1) {
                    CalendarEvent tokenEvent = new CalendarEvent();
                    tokenEvent.setSubject("\u26A0\uFE0F TOKEN ENTRY REQUIRED \u26A0\uFE0F");
                    tokenEvent.setStartTime(ZonedDateTime.now());
                    tokenEvent.setEndTime(ZonedDateTime.now().plusMinutes(1));
                    tokenEvent.setIsOnlineMeeting(false);
                    tokenEvent.setOrganizer("Token Entry");
                    tokenEvent.setResponseStatus("Flash");
                    tokenEvent.setCalendarName("Token Entry");

                    if (outlookAlerterUI != null) {
                        outlookAlerterUI.performFullAlert(
                                "\u26A0\uFE0F Token Entry Required - Please enter your OAuth tokens",
                                "Token Entry Required",
                                "Please enter your OAuth tokens in the dialog that will appear.",
                                Arrays.asList(tokenEvent));
                    } else {
                        Thread flashThread = new Thread(() -> {
                            try {
                                ScreenFlasher screenFlasher = ScreenFlasherFactory.createScreenFlasher();
                                screenFlasher.flash(tokenEvent);
                            } catch (Exception e) {
                                LogManager.getInstance().error(LogCategory.GENERAL,
                                        "[ERROR] Token alert flash error: " + e.getMessage());
                            }
                        }, "TokenAlertFlashThread");
                        flashThread.setDaemon(true);
                        flashThread.start();
                    }
                }

                if (outlookAlerterUI != null) {
                    resultTokens = outlookAlerterUI.promptForTokens(signInUrl, msalAuthProvider);
                } else {
                    SimpleTokenDialog dialog = SimpleTokenDialog.getInstance(signInUrl, msalAuthProvider);
                    dialog.show();
                    resultTokens = dialog.getTokens();
                }

                if (resultTokens == null) {
                    LogManager.getInstance().info(LogCategory.GENERAL,
                            "Token dialog was cancelled or closed. No tokens obtained.");
                    throw new AuthenticationCancelledException(
                            "Authentication was cancelled by the user.", "user_cancelled");
                }

                if (resultTokens.containsKey("accessToken")
                        && resultTokens.get("accessToken") != null
                        && !resultTokens.get("accessToken").isEmpty()) {
                    if (resultTokens.containsKey("ignoreCertValidation")) {
                        try {
                            boolean ignoreCertValidation = Boolean.valueOf(resultTokens.get("ignoreCertValidation"));
                            LogManager.getInstance().info(LogCategory.GENERAL,
                                    "Certificate validation setting: "
                                            + (ignoreCertValidation ? "disabled" : "enabled"));
                            ConfigManager.getInstance().updateIgnoreCertValidation(ignoreCertValidation);
                            updateHttpClient();
                        } catch (Exception e) {
                            LogManager.getInstance().error(LogCategory.GENERAL,
                                    "Error applying certificate validation setting: " + e.getMessage());
                        }
                    }
                    LogManager.getInstance().info(LogCategory.GENERAL,
                            "Valid token received from user interface.");
                    return resultTokens;
                }
            }

            LogManager.getInstance().info(LogCategory.GENERAL,
                    "No valid token obtained after " + attempts + " attempt(s).");
            return null;

        } catch (AuthenticationCancelledException ace) {
            throw ace;
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL,
                    "Error during token entry: " + e.getMessage(), e);
            return null;
        }
    }

    // ── Inner exception class ─────────────────────────────────────────────

    /**
     * Exception thrown when authentication is cancelled by the user.
     */
    public static class AuthenticationCancelledException extends RuntimeException {
        private final String reason;

        public AuthenticationCancelledException(String message) {
            super(message);
            this.reason = "user_cancelled";
            System.out.println("AuthenticationCancelledException created: " + message);
        }

        public AuthenticationCancelledException(String message, String reason) {
            super(message);
            this.reason = reason;
            System.out.println("AuthenticationCancelledException created: " + message + " (reason: " + reason + ")");
        }

        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return "AuthenticationCancelledException: " + getMessage() + " (reason: " + reason + ")";
        }
    }
}
