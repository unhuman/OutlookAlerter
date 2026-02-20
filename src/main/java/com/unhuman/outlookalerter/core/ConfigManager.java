package com.unhuman.outlookalerter.core;

import com.unhuman.outlookalerter.ui.SimpleTokenDialog;
import com.unhuman.outlookalerter.util.LogManager;
import com.unhuman.outlookalerter.util.LogCategory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Manages configuration settings and OAuth credentials for Outlook Alerter
 */
public class ConfigManager {
    private static volatile ConfigManager instance;

    public static synchronized ConfigManager getInstance() {
        return instance;
    }

    // ── Property key constants ────────────────────────────────────────────
    private static final String KEY_CLIENT_ID = "clientId";
    private static final String KEY_CLIENT_SECRET = "clientSecret";
    private static final String KEY_TENANT_ID = "tenantId";
    private static final String KEY_REDIRECT_URI = "redirectUri";
    private static final String KEY_SIGN_IN_URL = "signInUrl";
    private static final String KEY_TOKEN_ENDPOINT = "tokenEndpoint";
    private static final String KEY_LOGIN_HINT = "loginHint";
    private static final String KEY_PREFERRED_TIMEZONE = "preferredTimezone";
    private static final String KEY_ALERT_MINUTES = "alertMinutes";
    private static final String KEY_DEFAULT_IGNORE_CERT = "defaultIgnoreCertValidation";
    private static final String KEY_IGNORE_CERT = "ignoreCertValidation";
    private static final String KEY_FLASH_COLOR = "flashColor";
    private static final String KEY_FLASH_TEXT_COLOR = "flashTextColor";
    private static final String KEY_FLASH_OPACITY = "flashOpacity";
    private static final String KEY_FLASH_DURATION = "flashDurationSeconds";
    private static final String KEY_RESYNC_INTERVAL = "resyncIntervalMinutes";
    private static final String KEY_ALERT_BEEP_COUNT = "alertBeepCount";
    private static final String KEY_ALERT_BEEP_AFTER_FLASH = "alertBeepAfterFlash";
    private static final String KEY_ACCESS_TOKEN = "accessToken";
    private static final String KEY_REFRESH_TOKEN = "refreshToken";

    // ── Default value constants ───────────────────────────────────────────
    // User-registered multi-tenant Azure AD app. First-party Microsoft app IDs
    // cannot be used (AADSTS65002). Organizations that require admin consent for
    // third-party apps will need an IT admin to approve this app once.
    private static final String DEFAULT_CLIENT_ID = "ce88d638-d3c7-42fd-b46c-6b55e9decf12";
    private static final String DEFAULT_TENANT_ID = "common";
    private static final String DEFAULT_REDIRECT_URI = "http://localhost:8888/redirect";
    private static final String DEFAULT_FLASH_COLOR = "#800000";
    private static final String DEFAULT_FLASH_TEXT_COLOR = "#ffffff";
    private static final String DEFAULT_FLASH_OPACITY = "1.0";
    private static final String DEFAULT_ALERT_MINUTES = "1";
    private static final String DEFAULT_FLASH_DURATION = "5";
    private static final String DEFAULT_RESYNC_INTERVAL = "240";
    private static final String DEFAULT_ALERT_BEEP_COUNT = "5";
    private static final String DEFAULT_FALSE = "false";

    private String configFilePath;
    private Properties properties = new Properties();
    private String clientId;
    private String clientSecret;
    private String tenantId;
    private String redirectUri;
    private String accessToken;
    private String refreshToken;
    private String preferredTimezone;
    private int alertMinutes = 1;
    private boolean defaultIgnoreCertValidation = false;
    private boolean ignoreCertValidation;
    private String flashColor = DEFAULT_FLASH_COLOR;
    private String flashTextColor = DEFAULT_FLASH_TEXT_COLOR;
    private double flashOpacity = 1.0d;
    private int flashDurationSeconds = 5;
    private int resyncIntervalMinutes = 240;
    private int alertBeepCount = 5;
    private boolean alertBeepAfterFlash = false;
    private String signInUrl;
    private String tokenEndpoint;
    private String loginHint;

    public ConfigManager(String configFilePath) {
        this.configFilePath = configFilePath;
        synchronized (ConfigManager.class) {
            instance = this;
        }
        LogManager.getInstance().info(LogCategory.GENERAL, "ConfigManager initialized with path: " + configFilePath);
    }

    public void loadConfiguration() {
        File configFile = new File(configFilePath);
        File configDir = configFile.getParentFile();
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                properties.load(fis);
                loadPropertiesFromConfig();
                LogManager.getInstance().info(LogCategory.GENERAL, "Configuration loaded from " + configFilePath);
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.GENERAL, "Error loading configuration: " + e.getMessage());
                createDefaultConfig(configFile);
            }
        } else {
            createDefaultConfig(configFile);
        }
    }

    private void createDefaultConfig(File configFile) {
        LogManager.getInstance().info(LogCategory.GENERAL, "Creating default configuration at " + configFile.getAbsolutePath());
        properties.setProperty(KEY_CLIENT_ID, DEFAULT_CLIENT_ID);
        properties.setProperty(KEY_CLIENT_SECRET, "");
        properties.setProperty(KEY_TENANT_ID, DEFAULT_TENANT_ID);
        properties.setProperty(KEY_REDIRECT_URI, DEFAULT_REDIRECT_URI);
        properties.setProperty(KEY_SIGN_IN_URL, SimpleTokenDialog.DEFAULT_GRAPH_URL);
        properties.setProperty(KEY_TOKEN_ENDPOINT, "");
        properties.setProperty(KEY_LOGIN_HINT, "");
        properties.setProperty(KEY_PREFERRED_TIMEZONE, "");
        properties.setProperty(KEY_ALERT_MINUTES, DEFAULT_ALERT_MINUTES);
        properties.setProperty(KEY_DEFAULT_IGNORE_CERT, DEFAULT_FALSE);
        properties.setProperty(KEY_IGNORE_CERT, DEFAULT_FALSE);
        properties.setProperty(KEY_FLASH_COLOR, DEFAULT_FLASH_COLOR);
        properties.setProperty(KEY_FLASH_TEXT_COLOR, DEFAULT_FLASH_TEXT_COLOR);
        properties.setProperty(KEY_FLASH_OPACITY, DEFAULT_FLASH_OPACITY);
        properties.setProperty(KEY_FLASH_DURATION, DEFAULT_FLASH_DURATION);
        properties.setProperty(KEY_RESYNC_INTERVAL, DEFAULT_RESYNC_INTERVAL);
        properties.setProperty(KEY_ALERT_BEEP_COUNT, DEFAULT_ALERT_BEEP_COUNT);
        properties.setProperty(KEY_ALERT_BEEP_AFTER_FLASH, DEFAULT_FALSE);
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            properties.store(fos, "Outlook Alerter Configuration");
            LogManager.getInstance().info(LogCategory.GENERAL,
                "Configuration file created at " + configFile.getAbsolutePath() + "\n\n" +
                "For Okta SSO authentication, edit the configuration file with these values:\n\n" +
                "1. signInUrl - Your organization's Okta SSO URL for Microsoft 365 (ask your IT department)\n" +
                "   Default: " + SimpleTokenDialog.DEFAULT_GRAPH_URL + " (Microsoft Graph developer site)\n" +
                "   Example: https://your-company.okta.com/home/office365/0oa1b2c3d4/aln5b6c7d8\n\n" +
                "2. loginHint - Your email address (optional but recommended)\n" +
                "   Example: your.name@company.com\n\n" +
                "3. preferredTimezone - Your preferred timezone for displaying events (optional)\n" +
                "   Example: America/New_York, Europe/London, Asia/Tokyo\n" +
                "   Leave empty to use system default timezone\n\n" +
                "If your organization supports OAuth flows directly, you can also configure:\n" +
                "- clientId - Your application's client ID (if available)\n" +
                "- clientSecret - Your application's client secret (if available)\n" +
                "- tokenEndpoint - Token endpoint URL for refreshing tokens\n\n" +
                "After configuration, the application will open a browser for you to sign in through Okta,\n" +
                "then guide you through copying the access token to complete the authentication.");
            loadPropertiesFromConfig();
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Error creating default configuration: " + e.getMessage());
        }
    }

    private void loadPropertiesFromConfig() {
        clientId = properties.getProperty(KEY_CLIENT_ID);
        clientSecret = properties.getProperty(KEY_CLIENT_SECRET);
        tenantId = properties.getProperty(KEY_TENANT_ID, DEFAULT_TENANT_ID);
        redirectUri = properties.getProperty(KEY_REDIRECT_URI, DEFAULT_REDIRECT_URI);
        signInUrl = properties.getProperty(KEY_SIGN_IN_URL);
        tokenEndpoint = properties.getProperty(KEY_TOKEN_ENDPOINT);
        loginHint = properties.getProperty(KEY_LOGIN_HINT);
        preferredTimezone = properties.getProperty(KEY_PREFERRED_TIMEZONE);
        try {
            alertMinutes = Integer.parseInt(properties.getProperty(KEY_ALERT_MINUTES, DEFAULT_ALERT_MINUTES));
        } catch (NumberFormatException e) {
            LogManager.getInstance().warn(LogCategory.GENERAL, "Invalid alertMinutes value, using default: " + e.getMessage());
        }
        defaultIgnoreCertValidation = Boolean.parseBoolean(properties.getProperty(KEY_DEFAULT_IGNORE_CERT, DEFAULT_FALSE));
        ignoreCertValidation = Boolean.parseBoolean(properties.getProperty(KEY_IGNORE_CERT, DEFAULT_FALSE));
        flashColor = properties.getProperty(KEY_FLASH_COLOR, DEFAULT_FLASH_COLOR);
        flashTextColor = properties.getProperty(KEY_FLASH_TEXT_COLOR, DEFAULT_FLASH_TEXT_COLOR);
        try {
            flashOpacity = Double.parseDouble(properties.getProperty(KEY_FLASH_OPACITY, DEFAULT_FLASH_OPACITY));
        } catch (NumberFormatException e) {
            LogManager.getInstance().warn(LogCategory.GENERAL, "Invalid flashOpacity value, using default: " + e.getMessage());
        }
        try {
            flashDurationSeconds = Integer.parseInt(properties.getProperty(KEY_FLASH_DURATION, DEFAULT_FLASH_DURATION));
        } catch (NumberFormatException e) {
            LogManager.getInstance().warn(LogCategory.GENERAL, "Invalid flashDurationSeconds value, using default: " + e.getMessage());
        }
        try {
            resyncIntervalMinutes = Integer.parseInt(properties.getProperty(KEY_RESYNC_INTERVAL, DEFAULT_RESYNC_INTERVAL));
        } catch (NumberFormatException e) {
            LogManager.getInstance().warn(LogCategory.GENERAL, "Invalid resyncIntervalMinutes value, using default: " + e.getMessage());
        }
        try {
            alertBeepCount = Integer.parseInt(properties.getProperty(KEY_ALERT_BEEP_COUNT, DEFAULT_ALERT_BEEP_COUNT));
        } catch (NumberFormatException e) {
            LogManager.getInstance().warn(LogCategory.GENERAL, "Invalid alertBeepCount value, using default: " + e.getMessage());
        }
        alertBeepAfterFlash = Boolean.parseBoolean(properties.getProperty(KEY_ALERT_BEEP_AFTER_FLASH, DEFAULT_FALSE));
        accessToken = properties.getProperty(KEY_ACCESS_TOKEN);
        refreshToken = properties.getProperty(KEY_REFRESH_TOKEN);
    }

    public synchronized void saveConfiguration() {
        try {
            properties.setProperty(KEY_CLIENT_ID, clientId != null ? clientId : "");
            properties.setProperty(KEY_CLIENT_SECRET, clientSecret != null ? clientSecret : "");
            properties.setProperty(KEY_TENANT_ID, tenantId != null ? tenantId : DEFAULT_TENANT_ID);
            properties.setProperty(KEY_REDIRECT_URI, redirectUri != null ? redirectUri : DEFAULT_REDIRECT_URI);
            properties.setProperty(KEY_SIGN_IN_URL, signInUrl != null ? signInUrl : "");
            properties.setProperty(KEY_TOKEN_ENDPOINT, tokenEndpoint != null ? tokenEndpoint : "");
            properties.setProperty(KEY_LOGIN_HINT, loginHint != null ? loginHint : "");
            properties.setProperty(KEY_PREFERRED_TIMEZONE, preferredTimezone != null ? preferredTimezone : "");
            properties.setProperty(KEY_ALERT_MINUTES, String.valueOf(alertMinutes));
            properties.setProperty(KEY_DEFAULT_IGNORE_CERT, String.valueOf(defaultIgnoreCertValidation));
            properties.setProperty(KEY_IGNORE_CERT, String.valueOf(ignoreCertValidation));
            properties.setProperty(KEY_FLASH_COLOR, flashColor != null ? flashColor : DEFAULT_FLASH_COLOR);
            properties.setProperty(KEY_FLASH_TEXT_COLOR, flashTextColor != null ? flashTextColor : DEFAULT_FLASH_TEXT_COLOR);
            properties.setProperty(KEY_FLASH_OPACITY, String.valueOf(flashOpacity));
            properties.setProperty(KEY_FLASH_DURATION, String.valueOf(flashDurationSeconds));
            properties.setProperty(KEY_RESYNC_INTERVAL, String.valueOf(resyncIntervalMinutes));
            properties.setProperty(KEY_ALERT_BEEP_COUNT, String.valueOf(alertBeepCount));
            properties.setProperty(KEY_ALERT_BEEP_AFTER_FLASH, String.valueOf(alertBeepAfterFlash));
            if (accessToken != null && !accessToken.isEmpty()) {
                properties.setProperty(KEY_ACCESS_TOKEN, accessToken);
            }
            if (refreshToken != null && !refreshToken.isEmpty()) {
                properties.setProperty(KEY_REFRESH_TOKEN, refreshToken);
            }
            File configFile = new File(configFilePath);
            try (FileOutputStream fos = new FileOutputStream(configFile)) {
                properties.store(fos, "Outlook Alerter Configuration");
            }
            LogManager.getInstance().info(LogCategory.GENERAL, "Configuration saved to " + configFilePath);
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Error saving configuration: " + e.getMessage());
        }
    }

    public void updateTokens(String accessToken, String refreshToken, boolean ignoreCertValidation) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.ignoreCertValidation = ignoreCertValidation;
        saveConfiguration();
    }

    public String getClientId() {
        return (clientId != null && !clientId.trim().isEmpty()) ? clientId : DEFAULT_CLIENT_ID;
    }
    public String getClientSecret() { return clientSecret; }
    public String getTenantId() { return tenantId; }
    public String getRedirectUri() { return redirectUri; }
    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }

    public String getSignInUrl() {
        return signInUrl != null && !signInUrl.trim().isEmpty() ? signInUrl : SimpleTokenDialog.DEFAULT_GRAPH_URL;
    }
    public String getTokenEndpoint() { return tokenEndpoint; }
    public String getLoginHint() { return loginHint; }
    public String getPreferredTimezone() { return preferredTimezone; }
    public int getAlertMinutes() { return alertMinutes; }
    public boolean getDefaultIgnoreCertValidation() { return defaultIgnoreCertValidation; }
    public boolean getIgnoreCertValidation() { return ignoreCertValidation; }
    public String getFlashColor() { return flashColor; }
    public String getFlashTextColor() { return flashTextColor; }
    public double getFlashOpacity() { return flashOpacity; }
    public int getFlashDurationSeconds() { return flashDurationSeconds; }
    public int getResyncIntervalMinutes() { return resyncIntervalMinutes; }
    public int getAlertBeepCount() { return alertBeepCount; }
    public boolean getAlertBeepAfterFlash() { return alertBeepAfterFlash; }

    public void updatePreferredTimezone(String timezone) { this.preferredTimezone = timezone; saveConfiguration(); }
    public void updateAlertMinutes(int minutes) { this.alertMinutes = minutes; saveConfiguration(); }
    public void updateDefaultIgnoreCertValidation(boolean ignore) { this.defaultIgnoreCertValidation = ignore; saveConfiguration(); }
    public void updateIgnoreCertValidation(boolean ignore) { this.ignoreCertValidation = ignore; saveConfiguration(); }
    public void updateFlashDurationSeconds(int seconds) { this.flashDurationSeconds = seconds; saveConfiguration(); }
    public void updateClientId(String clientId) { this.clientId = clientId; saveConfiguration(); }
    public void updateTenantId(String tenantId) { this.tenantId = tenantId; saveConfiguration(); }
    public void updateSignInUrl(String url) { this.signInUrl = url; saveConfiguration(); }
    public void updateFlashColor(String color) { this.flashColor = color; saveConfiguration(); }
    public void updateFlashTextColor(String color) { this.flashTextColor = color; saveConfiguration(); }
    public void updateFlashOpacity(double opacity) { this.flashOpacity = opacity; saveConfiguration(); }
    public void updateResyncIntervalMinutes(int minutes) { this.resyncIntervalMinutes = minutes; saveConfiguration(); }
    public void updateAlertBeepCount(int count) { this.alertBeepCount = count; saveConfiguration(); }
    public void updateAlertBeepAfterFlash(boolean enabled) { this.alertBeepAfterFlash = enabled; saveConfiguration(); }
}
