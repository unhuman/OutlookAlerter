package com.unhuman.outlookalerter.core

import javax.net.ssl.*
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class SSLUtils {
    
    // Log detailed SSL information
    private static final boolean DEBUG_SSL = Boolean.parseBoolean(System.getProperty("outlookalerter.ssl.debug", "false"))
    
    /**
     * Initialize the SSL context with a custom truststore that includes the Netskope certificate
     * This allows the application to trust the Netskope certificate for SSL connections
     */
    static void initializeSSLContext() {
        try {
            println "Initializing custom SSL context..."
            
            // Check for system property first (highest priority)
            String trustStorePath = System.getProperty("javax.net.ssl.trustStore")
            if (trustStorePath != null) {
                File trustStoreFile = new File(trustStorePath)
                if (trustStoreFile.exists()) {
                    println "Using truststore from system property: ${trustStoreFile.absolutePath}"
                    loadTrustStoreFromStream(new FileInputStream(trustStoreFile))
                    return
                } else {
                    println "WARNING: Truststore specified in system property does not exist: ${trustStorePath}"
                    // Fall through to try other methods
                }
            }
            
            // Try to load from classpath or bundled resources
            InputStream bundledTruststore = SSLUtils.class.getResourceAsStream("/truststore.jks")
            
            if (bundledTruststore != null) {
                println "Found bundled truststore.jks in resources"
                loadTrustStoreFromStream(bundledTruststore)
                return
            }
            
            // Check possible locations for the truststore
            List<String> possibleLocations = [
                "Contents/Resources/truststore.jks",                  // App bundle - relative path
                System.getProperty("user.dir") + "/Contents/Resources/truststore.jks", // App bundle - using user.dir
                "/Applications/OutlookAlerter.app/Contents/Resources/truststore.jks", // Installed app
                new File(".").getAbsolutePath() + "/Contents/Resources/truststore.jks", // App bundle - absolute
                "target/OutlookAlerter.app/Contents/Resources/truststore.jks",  // App bundle - dev path
                "target/truststore/truststore.jks",                   // Maven build path
                "truststore.jks"                                      // Current directory
            ]
            
            // Get app directory for absolute paths
            String appDir = new File(".").getAbsolutePath()
            println "Current directory: ${appDir}"
            println "Checking truststore in these locations: ${possibleLocations.join('\n  ')}"
            
            File truststore = null
            for (String location : possibleLocations) {
                File testFile = new File(location)
                if (testFile.exists()) {
                    truststore = testFile
                    println "Found truststore at: ${truststore.absolutePath}"
                    break
                }
            }
            
            // If found, load the truststore
            if (truststore != null && truststore.exists()) {
                loadTrustStoreFromStream(new FileInputStream(truststore))
                return
            }
            
            // If no truststore found, create one dynamically including Netskope cert
            println "No existing truststore found, creating one dynamically"
            createDynamicTrustStore()
            
        } catch (Exception e) {
            println "Failed to initialize SSL context: ${e.message}"
            e.printStackTrace()
            println "Continuing with default SSL configuration"
        }
    }
    
    /**
     * Load truststore from an InputStream and initialize the SSL context
     */
    private static void loadTrustStoreFromStream(InputStream trustStoreStream) {
        try {
            KeyStore trustStore = KeyStore.getInstance("JKS")
            trustStore.load(trustStoreStream, "changeit".toCharArray())
            trustStoreStream.close()
            
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(trustStore)
            
            SSLContext sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, tmf.getTrustManagers(), null)
            
            // Set the default SSL context
            SSLContext.setDefault(sslContext)
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory())
            println "Successfully initialized SSL context from truststore"
        } catch (Exception e) {
            println "Error loading truststore: ${e.message}"
            e.printStackTrace()
            println "Will attempt to create dynamic truststore..."
            createDynamicTrustStore()
        }
    }
    
    /**
     * Create a dynamic truststore that includes both the default CA certs and the Netskope cert
     */
    private static void createDynamicTrustStore() {
        try {
            // Get the default truststore
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init((KeyStore)null)
            
            // Create a new writable truststore
            KeyStore dynamicTrustStore = KeyStore.getInstance(KeyStore.getDefaultType())
            dynamicTrustStore.load(null, "changeit".toCharArray())
            
            // Add all certificates from the default truststore
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    X509TrustManager x509tm = (X509TrustManager)tm
                    for (X509Certificate cert : x509tm.getAcceptedIssuers()) {
                        dynamicTrustStore.setCertificateEntry("default-" + cert.getSubjectDN().getName().hashCode(), cert)
                    }
                }
            }
            
            // Try to add the Netskope certificate
            File netskopeFile = new File("/Library/Application Support/Netskope/STAgent/download/nscacert_combined.pem")
            if (netskopeFile.exists()) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509")
                InputStream certStream = new FileInputStream(netskopeFile)
                
                int certCount = 0
                for (X509Certificate cert : cf.generateCertificates(certStream)) {
                    dynamicTrustStore.setCertificateEntry("netskope-cert-" + certCount, cert)
                    certCount++
                }
                certStream.close()
                println "Added ${certCount} Netskope certificates to dynamic truststore"
            } else {
                println "Netskope certificate file not found: ${netskopeFile.absolutePath}"
            }
            
            // Initialize the SSL context with our combined truststore
            TrustManagerFactory customTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            customTmf.init(dynamicTrustStore)
            
            SSLContext sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, customTmf.getTrustManagers(), null)
            
            // Set the default SSL context
            SSLContext.setDefault(sslContext)
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory())
            println "Successfully initialized dynamic SSL context with system and Netskope certificates"
        } catch (Exception e) {
            println "Error creating dynamic truststore: ${e.message}"
            e.printStackTrace()
        }
    }
    
    /**
     * Create an SSL debug utility method to print details of certificates and SSL context
     * This helps diagnose SSL certificate issues without compromising security
     */
    static void debugSSL() {
        if (!DEBUG_SSL) {
            return
        }
        
        try {
            println "=== SSL DEBUG INFORMATION ==="
            println "Java version: ${System.getProperty('java.version')}"
            println "Default truststore type: ${KeyStore.getDefaultType()}"
            println "Default SSL context protocol: ${SSLContext.getDefault().getProtocol()}"
            
            // Get the default trust managers
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init((KeyStore)null)
            
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    X509TrustManager x509tm = (X509TrustManager)tm
                    X509Certificate[] certs = x509tm.getAcceptedIssuers()
                    println "Default truststore has ${certs.length} accepted issuers"
                    
                    if (DEBUG_SSL) {
                        // Only print details if verbose SSL debug is enabled
                        for (int i = 0; i < Math.min(5, certs.length); i++) {
                            X509Certificate cert = certs[i]
                            println "Certificate ${i}: ${cert.getSubjectDN()}, Issued by: ${cert.getIssuerDN()}"
                        }
                        if (certs.length > 5) {
                            println "... and ${certs.length - 5} more certificates"
                        }
                    }
                }
            }
            println "=== END SSL DEBUG INFORMATION ==="
        } catch (Exception e) {
            println "Error getting SSL debug information: ${e.message}"
            e.printStackTrace()
        }
    }
    
    /**
     * Print certificate information from specific file for debugging
     */
    static void debugCertificate(File certFile) {
        if (!DEBUG_SSL) {
            return
        }
        
        try {
            if (certFile.exists()) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509")
                InputStream certStream = new FileInputStream(certFile)
                
                println "=== CERTIFICATE DEBUG: ${certFile.absolutePath} ==="
                int certCount = 0
                for (X509Certificate cert : cf.generateCertificates(certStream)) {
                    println "Certificate ${certCount++}:"
                    println "  Subject: ${cert.getSubjectDN()}"
                    println "  Issuer: ${cert.getIssuerDN()}"
                    println "  Not Before: ${cert.getNotBefore()}"
                    println "  Not After: ${cert.getNotAfter()}"
                }
                certStream.close()
                println "=== END CERTIFICATE DEBUG ==="
            } else {
                println "Certificate file not found: ${certFile.absolutePath}"
            }
        } catch (Exception e) {
            println "Error reading certificate: ${e.message}"
            e.printStackTrace()
        }
    }
}
