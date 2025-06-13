package com.unhuman.outlookalerter.core

import groovy.transform.CompileStatic
import com.unhuman.outlookalerter.ui.SimpleTokenDialog

/**
 * Manages configuration settings and OAuth credentials for Outlook Alerter
 */
@CompileStatic
class ConfigManager {
    // Singleton instance
    private static ConfigManager instance
    
    /**
     * Gets the singleton instance of ConfigManager
     * @return The ConfigManager instance or null if not initialized
     */
    static ConfigManager getInstance() {
        return instance
    }
    
    // Configuration properties
    private String configFilePath
    private Properties properties = new Properties()
    
    // OAuth related properties
    private String clientId
    private String clientSecret
    private String tenantId
    private String redirectUri
    private String accessToken
    private String refreshToken
    
    // Application-specific properties
    private String preferredTimezone
    private int alertMinutes = 1 // Default to 1 minute
    private boolean ignoreCertValidation = false // Default to validating certificates
    private String flashColor = "#800000" // Default dark red
    private String flashTextColor = "#ffffff" // Default white text
    private double flashOpacity = 1.0d // Default 100%
    private int resyncIntervalMinutes = 240; // Default to 4 hours

    // SSO related properties
    private String signInUrl
    private String tokenEndpoint
    private String loginHint
    
    /**
     * Creates a new ConfigManager with the specified config file path
     */
    ConfigManager(String configFilePath) {
        this.configFilePath = configFilePath
        instance = this  // Set this instance as the singleton
        println "ConfigManager initialized with path: ${configFilePath}"
    }
    
    /**
     * Loads configuration from the config file
     * Creates default config if the file doesn't exist
     */
    void loadConfiguration() {
        File configFile = new File(configFilePath)
        
        // Create directories if they don't exist
        File configDir = configFile.getParentFile()
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        
        // If config file exists, load it
        if (configFile.exists()) {
            try {
                properties.load(new FileInputStream(configFile))
                loadPropertiesFromConfig()
                println "Configuration loaded from ${configFilePath}"
            } catch (Exception e) {
                println "Error loading configuration: ${e.message}"
                createDefaultConfig(configFile)
            }
        } else {
            // Create default config
            createDefaultConfig(configFile)
        }
    }
    
    /**
     * Creates default configuration file
     */
    private void createDefaultConfig(File configFile) {
        println "Creating default configuration at ${configFile.absolutePath}"
        
        // Set default properties
        properties.setProperty("clientId", "")
        properties.setProperty("clientSecret", "")
        properties.setProperty("tenantId", "common")
        properties.setProperty("redirectUri", "http://localhost:8888/redirect")
        
        // SSO related properties - default to Microsoft Graph URL
        properties.setProperty("signInUrl", SimpleTokenDialog.DEFAULT_GRAPH_URL)
        properties.setProperty("tokenEndpoint", "")
        properties.setProperty("loginHint", "")
        
        // Application-specific properties
        properties.setProperty("preferredTimezone", "")
        properties.setProperty("alertMinutes", "1")
        properties.setProperty("ignoreCertValidation", "false")
        properties.setProperty("flashColor", "#800000")
        properties.setProperty("flashTextColor", "#ffffff")
        properties.setProperty("flashOpacity", "1.0")
        
        // Save to file
        try {
            properties.store(new FileOutputStream(configFile), "Outlook Alerter Configuration")
            
            println """
            Configuration file created at ${configFile.absolutePath}
            
            For Okta SSO authentication, edit the configuration file with these values:
            
            1. signInUrl - Your organization's Okta SSO URL for Microsoft 365 (ask your IT department)
               Default: ${SimpleTokenDialog.DEFAULT_GRAPH_URL} (Microsoft Graph developer site)
               Example: https://your-company.okta.com/home/office365/0oa1b2c3d4/aln5b6c7d8
            
            2. loginHint - Your email address (optional but recommended)
               Example: your.name@company.com
            
            3. preferredTimezone - Your preferred timezone for displaying events (optional)
               Example: America/New_York, Europe/London, Asia/Tokyo
               Leave empty to use system default timezone
            
            If your organization supports OAuth flows directly, you can also configure:
            - clientId - Your application's client ID (if available)
            - clientSecret - Your application's client secret (if available)
            - tokenEndpoint - Token endpoint URL for refreshing tokens
            
            After configuration, the application will open a browser for you to sign in through Okta,
            then guide you through copying the access token to complete the authentication.
            """
            
            loadPropertiesFromConfig()
        } catch (Exception e) {
            println "Error creating default configuration: ${e.message}"
        }
    }
    
    /**
     * Loads properties from the config file into member variables
     */
    private void loadPropertiesFromConfig() {
        clientId = properties.getProperty("clientId")
        clientSecret = properties.getProperty("clientSecret")
        tenantId = properties.getProperty("tenantId", "common")
        redirectUri = properties.getProperty("redirectUri", "http://localhost:8888/redirect")
        
        // SSO related properties
        signInUrl = properties.getProperty("signInUrl")
        tokenEndpoint = properties.getProperty("tokenEndpoint")
        loginHint = properties.getProperty("loginHint")
        
        // Application-specific properties
        preferredTimezone = properties.getProperty("preferredTimezone")
        alertMinutes = Integer.parseInt(properties.getProperty("alertMinutes", "1"))
        ignoreCertValidation = Boolean.parseBoolean(properties.getProperty("ignoreCertValidation", "false"))
        flashColor = properties.getProperty("flashColor", "#800000")
        flashTextColor = properties.getProperty("flashTextColor", "#ffffff")
        flashOpacity = Double.parseDouble(properties.getProperty("flashOpacity", "1.0"))
        resyncIntervalMinutes = Integer.parseInt(properties.getProperty("resyncIntervalMinutes", "240"))
        
        accessToken = properties.getProperty("accessToken")
        refreshToken = properties.getProperty("refreshToken")
    }
    
    /**
     * Saves the current configuration to the config file
     */
    void saveConfiguration() {
        try {
            // Update properties with current values
            properties.setProperty("clientId", clientId ?: "")
            properties.setProperty("clientSecret", clientSecret ?: "")
            properties.setProperty("tenantId", tenantId ?: "common")
            properties.setProperty("redirectUri", redirectUri ?: "http://localhost:8888/redirect")
            
            // SSO related properties
            properties.setProperty("signInUrl", signInUrl ?: "")
            properties.setProperty("tokenEndpoint", tokenEndpoint ?: "")
            properties.setProperty("loginHint", loginHint ?: "")
            
            // Application-specific properties
            properties.setProperty("preferredTimezone", preferredTimezone ?: "")
            properties.setProperty("alertMinutes", String.valueOf(alertMinutes))
            properties.setProperty("ignoreCertValidation", String.valueOf(ignoreCertValidation))
            properties.setProperty("flashColor", flashColor ?: "#800000")
            properties.setProperty("flashTextColor", flashTextColor ?: "#ffffff")
            properties.setProperty("flashOpacity", String.valueOf(flashOpacity))
            properties.setProperty("resyncIntervalMinutes", String.valueOf(resyncIntervalMinutes))
            
            if (accessToken) {
                properties.setProperty("accessToken", accessToken)
            }
            
            if (refreshToken) {
                properties.setProperty("refreshToken", refreshToken)
            }
            
            // Save to file
            File configFile = new File(configFilePath)
            properties.store(new FileOutputStream(configFile), "Outlook Alerter Configuration")
            println "Configuration saved to ${configFilePath}"
        } catch (Exception e) {
            println "Error saving configuration: ${e.message}"
        }
    }
    
    /**
     * Updates OAuth tokens and saves the configuration
     */
    void updateTokens(String accessToken, String refreshToken) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        saveConfiguration()
    }
    
    // Getters
    String getClientId() { return clientId }
    String getClientSecret() { return clientSecret }
    String getTenantId() { return tenantId }
    String getRedirectUri() { return redirectUri }
    String getAccessToken() { return accessToken }
    String getRefreshToken() { return refreshToken }
    
    // SSO specific getters
    String getSignInUrl() { 
        // Return default URL if no signInUrl is configured
        return signInUrl && !signInUrl.trim().isEmpty() ? signInUrl : SimpleTokenDialog.DEFAULT_GRAPH_URL 
    }
    String getTokenEndpoint() { return tokenEndpoint }
    String getLoginHint() { return loginHint }
    
    // Application-specific getters
    String getPreferredTimezone() { return preferredTimezone }
    int getAlertMinutes() { return alertMinutes }
    boolean getIgnoreCertValidation() { return ignoreCertValidation }
    String getFlashColor() { return flashColor }
    String getFlashTextColor() { return flashTextColor }
    double getFlashOpacity() { return flashOpacity }
    int getResyncIntervalMinutes() { return resyncIntervalMinutes; }
    
    /**
     * Updates the preferred timezone setting
     */
    void updatePreferredTimezone(String timezone) {
        this.preferredTimezone = timezone
        saveConfiguration()
    }
    
    /**
     * Updates the alert minutes setting
     */
    void updateAlertMinutes(int minutes) {
        this.alertMinutes = minutes
        saveConfiguration()
    }

    /**
     * Updates the certificate validation setting
     */
    void updateIgnoreCertValidation(boolean ignore) {
        this.ignoreCertValidation = ignore
        saveConfiguration()
    }
    
    /**
     * Updates the sign-in URL setting
     */
    void updateSignInUrl(String url) {
        this.signInUrl = url
        saveConfiguration()
    }
    
    /**
     * Updates the flash color setting
     */
    void updateFlashColor(String color) {
        this.flashColor = color
        saveConfiguration()
    }
    
    /**
     * Updates the flash text color setting
     */
    void updateFlashTextColor(String color) {
        this.flashTextColor = color
        saveConfiguration()
    }
    
    /**
     * Updates the flash opacity setting
     */
    void updateFlashOpacity(double opacity) {
        this.flashOpacity = opacity
        saveConfiguration()
    }

    /**
     * Updates the re-sync interval setting
     */
    void updateResyncIntervalMinutes(int minutes) {
        this.resyncIntervalMinutes = minutes;
        saveConfiguration();
    }
}
