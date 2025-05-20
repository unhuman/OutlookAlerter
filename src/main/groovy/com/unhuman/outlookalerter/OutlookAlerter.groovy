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
        
        // Parse command-line arguments
        String configPath = System.getProperty("user.home") + "/.outlookalerter/config.properties"
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