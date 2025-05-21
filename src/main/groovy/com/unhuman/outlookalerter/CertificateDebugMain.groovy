package com.unhuman.outlookalerter

/**
 * Simple utility class to capture certificate debugging command-line arguments
 */
class CertificateDebugMain {
    
    /**
     * Main method for certificate debugging
     */
    static void main(String[] args) {
        println "OutlookAlerter Certificate Diagnostic Tool"
        println "Java version: ${System.getProperty('java.version')}"
        println "Java home: ${System.getProperty('java.home')}"
        
        // Run the certificate diagnostics
        CertificateDebugger.fullDiagnostics("graph.microsoft.com")
        
        // After diagnostics, try a test connection
        try {
            println "\nTesting connection to Microsoft Graph API..."
            URL url = new URL("https://graph.microsoft.com/v1.0/me")
            HttpURLConnection connection = (HttpURLConnection) url.openConnection()
            connection.setRequestMethod("GET")
            connection.setConnectTimeout(5000)
            connection.connect()
            println "Connection test result: ${connection.responseCode} ${connection.responseMessage}"
            connection.disconnect()
        } catch (Exception e) {
            println "Connection test failed: ${e.message}"
        }
        
        println "\nCertificate diagnostics complete"
        System.exit(0)
    }
}
