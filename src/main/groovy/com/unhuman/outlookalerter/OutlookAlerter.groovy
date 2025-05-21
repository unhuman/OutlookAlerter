package com.unhuman.outlookalerter

import groovy.transform.CompileStatic

import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.JOptionPane
import java.security.KeyStore
import java.io.FileInputStream

/**
 * Main application class for Outlook Alerter
 * Monitors Outlook calendar events and flashes screen when meetings are about to start
 */
@CompileStatic
class OutlookAlerter {
    // Debugging
    private static boolean DEBUG_MODE = false
    
    /**
     * Main entry point
     */
    static void main(String[] args) {
        // Check for certificate debug mode
        if (args.contains("--cert-debug") || args.contains("-cd")) {
            println "Certificate debug mode detected"
            CertificateDebugMain.main(args)
            return
        }
    
        // First check if another instance is running
        SingleInstanceManager instanceManager = new SingleInstanceManager()
        if (!instanceManager.tryAcquireLock()) {
            String message = "Another instance of Outlook Alerter is already running."
            println message
            
            // Show error dialog if not in console mode
            if (!args.contains("--console")) {
                JOptionPane.showMessageDialog(
                    null,
                    message,
                    "Outlook Alerter - Already Running",
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
            
            System.exit(1)
            return
        }
        
        // Set truststore properties
        System.setProperty("javax.net.ssl.trustStore", "${System.getProperty('user.dir')}/OutlookAlerter.app/Contents/Resources/truststore.jks")
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit")
        
        // Parse command-line arguments
        String configPath = System.getProperty("user.home") + "/.outlookalerter/config.properties"
        println "Resolved user.home: ${System.getProperty("user.home")}";
        println "Using config path: ${configPath}";
        boolean consoleMode = false
        String timezoneOverride = null
        
        for (int i = 0; i < args.length; i++) {
            if (args[i] == "--config" && i < args.length - 1) {
                configPath = args[i + 1]
                i++
            } else if (args[i] == "--console") {
                consoleMode = true
            } else if (args[i] == "--debug") {
                DEBUG_MODE = true
                println "Debug mode enabled - detailed logging will be displayed"
            } else if (args[i] == "--timezone" && i < args.length - 1) {
                timezoneOverride = args[i + 1]
                i++
                println "Timezone override: ${timezoneOverride}"
            } else if (args[i] == "--help") {
                printUsage()
                return
            }
        }
        
        if (DEBUG_MODE) {
            // Log environment variables
            System.getenv().each { key, value -> println "$key=$value" }

            // Log working directory
            println "Working Directory: ${System.getProperty('user.dir')}"

            // Log Java system properties
            System.getProperties().each { key, value -> println "$key=$value" }

            // Verify access to the config file
            File configFile = new File(configPath)
            println "Config file exists: ${configFile.exists()}"
            println "Config file path: ${configFile.absolutePath}"

            // Verify network connectivity
            try {
                URL url = new URL("https://login.microsoftonline.com")
                HttpURLConnection connection = (HttpURLConnection) url.openConnection()
                connection.connect()
                println "Connection successful: ${connection.responseCode}"
            } catch (Exception e) {
                println "Connection failed: ${e.message}"
            }

            // List certificates in the truststore
            println "Listing certificates in the truststore..."
            try {
                String trustStorePath = System.getProperty("javax.net.ssl.trustStore", System.getProperty("java.home") + "/lib/security/cacerts")
                String trustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword", "changeit")
                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
                trustStore.load(new FileInputStream(trustStorePath), trustStorePassword.toCharArray())

                trustStore.aliases().each { alias ->
                    println "Alias: $alias"
                    println "Certificate: ${trustStore.getCertificate(alias as String)}"
                }
            } catch (Exception e) {
                println "Error listing truststore certificates: ${e.message}"
                e.printStackTrace()
            }
        }
        
        if (consoleMode) {
            // Run in console mode (legacy)
            OutlookAlerterConsole app = new OutlookAlerterConsole(configPath)
            
            // Apply timezone override if specified
            if (timezoneOverride) {
                app.setTimezone(timezoneOverride)
            }
            
            app.start(false)
        } else {
            // Run in GUI mode
            try {
                // Set system look and feel
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            } catch (Exception e) {
                println "Could not set system look and feel: ${e.message}"
            }
            
            // Start the UI application
            SwingUtilities.invokeLater({
                OutlookAlerterUI app = new OutlookAlerterUI(configPath)
                
                // Apply timezone override if specified
                if (timezoneOverride) {
                    app.setTimezone(timezoneOverride)
                }
                
                app.start()
            } as Runnable)
        }
    }
    
    /**
     * Print usage information
     */
    private static void printUsage() {
        println """
        Outlook Alerter - Monitor Outlook calendar events and alert before meetings
        
        Usage: java -jar outlook-alerter.jar [options]
        
        Options:
          --config <path>   Path to configuration file (default: ~/.outlookalerter/config.properties)
          --console         Run in console mode (no GUI)
          --debug           Enable debug mode with detailed logging
          --timezone <zone> Override the timezone for displaying events (e.g., America/New_York)
          --help            Show this help message
        
        Description:
          This tool connects to your Outlook/Microsoft 365 calendar using OAuth authentication
          and monitors for upcoming meetings. When a meeting is about to start (within 1 minute),
          it flashes the screen to alert you.
        """
    }
}