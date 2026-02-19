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
    private String flashColor = "#800000";
    private String flashTextColor = "#ffffff";
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
            try {
                FileInputStream fis = new FileInputStream(configFile);
                try {
                    properties.load(fis);
                } finally {
                    fis.close();
                }
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
        properties.setProperty("clientId", "");
        properties.setProperty("clientSecret", "");
        properties.setProperty("tenantId", "common");
        properties.setProperty("redirectUri", "http://localhost:8888/redirect");
        properties.setProperty("signInUrl", SimpleTokenDialog.DEFAULT_GRAPH_URL);
        properties.setProperty("tokenEndpoint", "");
        properties.setProperty("loginHint", "");
        properties.setProperty("preferredTimezone", "");
        properties.setProperty("alertMinutes", "1");
        properties.setProperty("defaultIgnoreCertValidation", "false");
        properties.setProperty("ignoreCertValidation", "false");
        properties.setProperty("flashColor", "#800000");
        properties.setProperty("flashTextColor", "#ffffff");
        properties.setProperty("flashOpacity", "1.0");
        properties.setProperty("flashDurationSeconds", "5");
        properties.setProperty("resyncIntervalMinutes", "240");
        properties.setProperty("alertBeepCount", "5");
        properties.setProperty("alertBeepAfterFlash", "false");
        try {
            FileOutputStream fos = new FileOutputStream(configFile);
            try {
                properties.store(fos, "Outlook Alerter Configuration");
            } finally {
                fos.close();
            }
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
        clientId = properties.getProperty("clientId");
        clientSecret = properties.getProperty("clientSecret");
        tenantId = properties.getProperty("tenantId", "common");
        redirectUri = properties.getProperty("redirectUri", "http://localhost:8888/redirect");
        signInUrl = properties.getProperty("signInUrl");
        tokenEndpoint = properties.getProperty("tokenEndpoint");
        loginHint = properties.getProperty("loginHint");
        preferredTimezone = properties.getProperty("preferredTimezone");
        try {
            alertMinutes = Integer.parseInt(properties.getProperty("alertMinutes", "1"));
        } catch (NumberFormatException e) {
            LogManager.getInstance().warn(LogCategory.GENERAL, "Invalid alertMinutes value, using default: " + e.getMessage());
        }
        defaultIgnoreCertValidation = Boolean.parseBoolean(properties.getProperty("defaultIgnoreCertValidation", "false"));
        ignoreCertValidation = Boolean.parseBoolean(properties.getProperty("ignoreCertValidation", "false"));
        flashColor = properties.getProperty("flashColor", "#800000");
        flashTextColor = properties.getProperty("flashTextColor", "#ffffff");
        try {
            flashOpacity = Double.parseDouble(properties.getProperty("flashOpacity", "1.0"));
        } catch (NumberFormatException e) {
            LogManager.getInstance().warn(LogCategory.GENERAL, "Invalid flashOpacity value, using default: " + e.getMessage());
        }
        try {
            flashDurationSeconds = Integer.parseInt(properties.getProperty("flashDurationSeconds", "5"));
        } catch (NumberFormatException e) {
            LogManager.getInstance().warn(LogCategory.GENERAL, "Invalid flashDurationSeconds value, using default: " + e.getMessage());
        }
        try {
            resyncIntervalMinutes = Integer.parseInt(properties.getProperty("resyncIntervalMinutes", "240"));
        } catch (NumberFormatException e) {
            LogManager.getInstance().warn(LogCategory.GENERAL, "Invalid resyncIntervalMinutes value, using default: " + e.getMessage());
        }
        try {
            alertBeepCount = Integer.parseInt(properties.getProperty("alertBeepCount", "5"));
        } catch (NumberFormatException e) {
            LogManager.getInstance().warn(LogCategory.GENERAL, "Invalid alertBeepCount value, using default: " + e.getMessage());
        }
        alertBeepAfterFlash = Boolean.parseBoolean(properties.getProperty("alertBeepAfterFlash", "false"));
        accessToken = properties.getProperty("accessToken");
        refreshToken = properties.getProperty("refreshToken");
    }

    public synchronized void saveConfiguration() {
        try {
            properties.setProperty("clientId", clientId != null ? clientId : "");
            properties.setProperty("clientSecret", clientSecret != null ? clientSecret : "");
            properties.setProperty("tenantId", tenantId != null ? tenantId : "common");
            properties.setProperty("redirectUri", redirectUri != null ? redirectUri : "http://localhost:8888/redirect");
            properties.setProperty("signInUrl", signInUrl != null ? signInUrl : "");
            properties.setProperty("tokenEndpoint", tokenEndpoint != null ? tokenEndpoint : "");
            properties.setProperty("loginHint", loginHint != null ? loginHint : "");
            properties.setProperty("preferredTimezone", preferredTimezone != null ? preferredTimezone : "");
            properties.setProperty("alertMinutes", String.valueOf(alertMinutes));
            properties.setProperty("defaultIgnoreCertValidation", String.valueOf(defaultIgnoreCertValidation));
            properties.setProperty("ignoreCertValidation", String.valueOf(ignoreCertValidation));
            properties.setProperty("flashColor", flashColor != null ? flashColor : "#800000");
            properties.setProperty("flashTextColor", flashTextColor != null ? flashTextColor : "#ffffff");
            properties.setProperty("flashOpacity", String.valueOf(flashOpacity));
            properties.setProperty("flashDurationSeconds", String.valueOf(flashDurationSeconds));
            properties.setProperty("resyncIntervalMinutes", String.valueOf(resyncIntervalMinutes));
            properties.setProperty("alertBeepCount", String.valueOf(alertBeepCount));
            properties.setProperty("alertBeepAfterFlash", String.valueOf(alertBeepAfterFlash));
            if (accessToken != null && !accessToken.isEmpty()) {
                properties.setProperty("accessToken", accessToken);
            }
            if (refreshToken != null && !refreshToken.isEmpty()) {
                properties.setProperty("refreshToken", refreshToken);
            }
            File configFile = new File(configFilePath);
            FileOutputStream fos = new FileOutputStream(configFile);
            try {
                properties.store(fos, "Outlook Alerter Configuration");
            } finally {
                fos.close();
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

    public String getClientId() { return clientId; }
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
    public void updateSignInUrl(String url) { this.signInUrl = url; saveConfiguration(); }
    public void updateFlashColor(String color) { this.flashColor = color; saveConfiguration(); }
    public void updateFlashTextColor(String color) { this.flashTextColor = color; saveConfiguration(); }
    public void updateFlashOpacity(double opacity) { this.flashOpacity = opacity; saveConfiguration(); }
    public void updateResyncIntervalMinutes(int minutes) { this.resyncIntervalMinutes = minutes; saveConfiguration(); }
    public void updateAlertBeepCount(int count) { this.alertBeepCount = count; saveConfiguration(); }
    public void updateAlertBeepAfterFlash(boolean enabled) { this.alertBeepAfterFlash = enabled; saveConfiguration(); }
}
