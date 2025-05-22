package com.unhuman.outlookalerter

import javax.net.ssl.*
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal
import java.security.cert.Certificate
import java.util.jar.JarFile

/**
 * Tool for diagnosing SSL certificate issues - particularly useful for debugging 
 * PKIX path building failures in different runtime environments
 */
class CertificateDebugger {
    
    private static final String LOG_PREFIX = "CERT-DEBUG"
    private static final boolean VERBOSE = Boolean.parseBoolean(System.getProperty("outlookalerter.ssl.verbose", "false"))
    
    /**
     * Debug all aspects of SSL certificate validation in the current JVM
     */
    static void fullDiagnostics(String targetHost) {
        println "${LOG_PREFIX}: Running full certificate diagnostics..."
        println "${LOG_PREFIX}: Java version: ${System.getProperty('java.version')}"
        println "${LOG_PREFIX}: Java home: ${System.getProperty('java.home')}"
        println "${LOG_PREFIX}: Running mode: ${isRunningAsAppBundle() ? "App Bundle" : "JAR"}"
        
        // System truststore info
        debugSystemTruststore()
        
        // Custom truststore info if configured
        debugCustomTruststore()
        
        // Security properties
        debugSecurityProperties()
        
        // Try an actual connection to the target host
        if (targetHost) {
            testConnection(targetHost)
        }
    }
    
    /**
     * Determine if the application is running as an app bundle
     */
    static boolean isRunningAsAppBundle() {
        String appPath = new File(".").getAbsolutePath()
        return appPath.contains(".app/Contents") || 
               System.getProperty("java.class.path", "").contains(".app/Contents")
    }
    
    /**
     * Debug the system truststore that's shipped with Java
     */
    static void debugSystemTruststore() {
        try {
            println "\n${LOG_PREFIX}: === SYSTEM TRUSTSTORE INFORMATION ==="
            String javaHome = System.getProperty("java.home")
            File systemTruststoreFile = new File("${javaHome}/lib/security/cacerts")
            
            println "${LOG_PREFIX}: Default system truststore: ${systemTruststoreFile.absolutePath}"
            println "${LOG_PREFIX}: System truststore exists: ${systemTruststoreFile.exists()}"
            
            if (!systemTruststoreFile.exists()) {
                // Try alternate locations for different JVM distributions
                List<String> possibleLocations = [
                    "${javaHome}/jre/lib/security/cacerts",
                    "/Library/Java/JavaVirtualMachines/current/Contents/Home/lib/security/cacerts",
                    "/etc/ssl/certs/java/cacerts"
                ]
                
                for (String location : possibleLocations) {
                    File f = new File(location)
                    if (f.exists()) {
                        systemTruststoreFile = f
                        println "${LOG_PREFIX}: Found system truststore at alternate location: ${f.absolutePath}"
                        break
                    }
                }
            }
            
            if (systemTruststoreFile.exists()) {
                KeyStore systemStore = KeyStore.getInstance(KeyStore.getDefaultType())
                systemStore.load(new FileInputStream(systemTruststoreFile), "changeit".toCharArray())
                int certCount = systemStore.size()
                println "${LOG_PREFIX}: System truststore contains ${certCount} certificates"
                
                if (VERBOSE) {
                    int shown = 0
                    systemStore.aliases().asIterator().each { String alias ->
                        if (shown++ < 10) {  // Just show the first 10 to avoid overwhelming output
                            Certificate cert = systemStore.getCertificate(alias)
                            if (cert instanceof X509Certificate) {
                                X509Certificate x509 = (X509Certificate)cert
                                println "${LOG_PREFIX}:   Certificate: ${alias}"
                                println "${LOG_PREFIX}:     Subject: ${x509.getSubjectX500Principal().getName()}"
                                println "${LOG_PREFIX}:     Issuer: ${x509.getIssuerX500Principal().getName()}"
                            }
                        }
                    }
                    if (certCount > 10) {
                        println "${LOG_PREFIX}:   ... and ${certCount - 10} more certificates"
                    }
                }
            }
        } catch (Exception e) {
            println "${LOG_PREFIX}: Error examining system truststore: ${e.message}"
            if (VERBOSE) e.printStackTrace()
        }
    }
    
    /**
     * Debug any custom truststores configured via system properties
     */
    static void debugCustomTruststore() {
        try {
            println "\n${LOG_PREFIX}: === CUSTOM TRUSTSTORE INFORMATION ==="
            
            String customTruststorePath = System.getProperty("javax.net.ssl.trustStore")
            String customTruststoreType = System.getProperty("javax.net.ssl.trustStoreType", KeyStore.getDefaultType())
            String customTruststorePassword = System.getProperty("javax.net.ssl.trustStorePassword", "changeit")
            
            println "${LOG_PREFIX}: Custom truststore path: ${customTruststorePath ?: "Not set"}"
            println "${LOG_PREFIX}: Custom truststore type: ${customTruststoreType}"
            
            if (customTruststorePath) {
                File trustStoreFile = new File(customTruststorePath)
                
                // Handle relative paths for app bundles
                if (!trustStoreFile.exists() && isRunningAsAppBundle() && !customTruststorePath.startsWith("/")) {
                    String appRoot = findAppBundleRoot()
                    if (appRoot) {
                        trustStoreFile = new File(appRoot, customTruststorePath)
                        println "${LOG_PREFIX}: Adjusted app bundle truststore path: ${trustStoreFile.absolutePath}"
                    }
                }
                
                println "${LOG_PREFIX}: Custom truststore exists: ${trustStoreFile.exists()}"
                
                if (trustStoreFile.exists()) {
                    KeyStore customStore = KeyStore.getInstance(customTruststoreType)
                    customStore.load(new FileInputStream(trustStoreFile), customTruststorePassword.toCharArray())
                    int certCount = customStore.size()
                    
                    println "${LOG_PREFIX}: Custom truststore contains ${certCount} certificates"
                    
                    // Important: get all aliases to check if Netskope certificate is included
                    boolean hasNetskopeCert = false
                    customStore.aliases().asIterator().each { String alias ->
                        if (alias.toLowerCase().contains("netskope") || alias.toLowerCase().contains("nscacert")) {
                            hasNetskopeCert = true
                        }
                    }
                    
                    println "${LOG_PREFIX}: Contains Netskope certificate: ${hasNetskopeCert}"
                    
                    if (VERBOSE) {
                        customStore.aliases().asIterator().each { String alias ->
                            Certificate cert = customStore.getCertificate(alias)
                            if (cert instanceof X509Certificate) {
                                X509Certificate x509 = (X509Certificate)cert
                                println "${LOG_PREFIX}:   Certificate: ${alias}"
                                println "${LOG_PREFIX}:     Subject: ${x509.getSubjectX500Principal().getName()}"
                                println "${LOG_PREFIX}:     Issuer: ${x509.getIssuerX500Principal().getName()}"
                            }
                        }
                    }
                }
            } else {
                println "${LOG_PREFIX}: No custom truststore configured"
            }
        } catch (Exception e) {
            println "${LOG_PREFIX}: Error examining custom truststore: ${e.message}"
            if (VERBOSE) e.printStackTrace()
        }
    }
    
    /**
     * Find the root directory of the app bundle if running as an app
     */
    static String findAppBundleRoot() {
        String path = new File(".").getAbsolutePath()
        int index = path.indexOf(".app/Contents")
        if (index > 0) {
            return path.substring(0, index + ".app/Contents".length())
        }
        return null
    }
    
    /**
     * Debug security properties that might affect certificate validation
     */
    static void debugSecurityProperties() {
        println "\n${LOG_PREFIX}: === SECURITY PROPERTIES ==="
        try {
            println "${LOG_PREFIX}: Default SSLContext protocol: ${SSLContext.getDefault().getProtocol()}"
            println "${LOG_PREFIX}: Default SecureRandom algorithm: ${java.security.SecureRandom.getInstance(java.security.SecureRandom.getDefaultAlgorithm()).getAlgorithm()}"
            println "${LOG_PREFIX}: Certificate path validation algorithm: ${System.getProperty("java.security.cert.revocationcheck", "Not set")}"
            
            // Check SSL-related system properties
            [
                "javax.net.debug",
                "javax.net.ssl.keyStore",
                "javax.net.ssl.keyStorePassword",
                "javax.net.ssl.keyStoreType",
                "javax.net.ssl.trustStoreProvider",
                "javax.net.ssl.trustStoreType",
                "https.protocols",
                "jdk.tls.client.protocols",
                "com.sun.net.ssl.checkRevocation",
                "java.security.debug"
            ].each { prop ->
                String value = System.getProperty(prop)
                println "${LOG_PREFIX}: ${prop}: ${value ?: "Not set"}"
            }
            
            // Debug enabled cipher suites
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault()
            println "${LOG_PREFIX}: Enabled cipher suites: ${factory.getSupportedCipherSuites().length} available"
            if (VERBOSE) {
                factory.getSupportedCipherSuites().each { suite ->
                    println "${LOG_PREFIX}:   ${suite}"
                }
            }
            
            // Check if we have the Netskope certificate in memory
            checkForNetskopeCertInMemory()
            
        } catch (Exception e) {
            println "${LOG_PREFIX}: Error getting security properties: ${e.message}"
            if (VERBOSE) e.printStackTrace()
        }
    }
    
    /**
     * Check if Netskope certificate is loaded in memory through various means
     */
    static void checkForNetskopeCertInMemory() {
        try {
            // Try to locate and load the Netskope certificate
            File netskopeFile = new File("/Library/Application Support/Netskope/STAgent/download/nscacert_combined.pem")
            if (!netskopeFile.exists()) {
                netskopeFile = new File("/Applications/Netskope Client.app/Contents/Resources/nscacert.pem")
            }
            
            if (netskopeFile.exists()) {
                println "${LOG_PREFIX}: Found Netskope certificate file: ${netskopeFile.absolutePath}"
                
                CertificateFactory cf = CertificateFactory.getInstance("X.509")
                Collection<? extends Certificate> certs = cf.generateCertificates(new FileInputStream(netskopeFile))
                
                println "${LOG_PREFIX}: Netskope certificate file contains ${certs.size()} certificates"
                
                if (VERBOSE && certs.size() > 0) {
                    int i = 0
                    for (Certificate cert : certs) {
                        if (cert instanceof X509Certificate) {
                            X509Certificate x509 = (X509Certificate)cert
                            println "${LOG_PREFIX}:   Certificate ${++i}:"
                            println "${LOG_PREFIX}:     Subject: ${x509.getSubjectX500Principal().getName()}"
                            println "${LOG_PREFIX}:     Issuer: ${x509.getIssuerX500Principal().getName()}"
                            println "${LOG_PREFIX}:     Valid from: ${x509.getNotBefore()} to ${x509.getNotAfter()}"
                            println "${LOG_PREFIX}:     SerialNumber: ${x509.getSerialNumber()}"
                        }
                    }
                }
            } else {
                println "${LOG_PREFIX}: Could not find Netskope certificate file"
            }
        } catch (Exception e) {
            println "${LOG_PREFIX}: Error checking for Netskope certificate: ${e.message}"
            if (VERBOSE) e.printStackTrace()
        }
    }
    
    /**
     * Test an actual HTTPS connection to verify certificate chain
     */
    static void testConnection(String host) {
        if (!host) {
            println "${LOG_PREFIX}: No host provided for connection test"
            return
        }
        
        println "\n${LOG_PREFIX}: === TESTING CONNECTION TO ${host} ==="
        try {
            URL url = new URL("https://${host}")
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection()
            
            // Enable certificate chain logging
            connection.setSSLSocketFactory(new DebugSSLSocketFactory(
                (SSLSocketFactory) SSLSocketFactory.getDefault()
            ))
            
            // Configure timeouts
            connection.setConnectTimeout(10000)
            connection.setReadTimeout(10000)
            
            // Disable hostname verification temporarily for debugging
            if (Boolean.parseBoolean(System.getProperty("outlookalerter.ssl.disableHostnameVerification", "false"))) {
                connection.setHostnameVerifier(new HostnameVerifier() {
                    boolean verify(String hostname, SSLSession session) {
                        println "${LOG_PREFIX}: Hostname verification disabled for debugging: ${hostname}"
                        return true
                    }
                })
            }
            
            // Make the connection
            connection.connect()
            
            // Get server certificate chain for manual verification
            println "${LOG_PREFIX}: Connection established successfully!"
            Certificate[] serverCerts = connection.getServerCertificates()
            println "${LOG_PREFIX}: Server presented ${serverCerts.length} certificates"
            
            if (VERBOSE) {
                for (int i = 0; i < serverCerts.length; i++) {
                    Certificate cert = serverCerts[i]
                    if (cert instanceof X509Certificate) {
                        X509Certificate x509 = (X509Certificate)cert
                        println "${LOG_PREFIX}:   Certificate ${i}:"
                        println "${LOG_PREFIX}:     Subject: ${x509.getSubjectX500Principal().getName()}"
                        println "${LOG_PREFIX}:     Issuer: ${x509.getIssuerX500Principal().getName()}"
                    }
                }
            }
            
            // Close the connection
            connection.disconnect()
            
        } catch (javax.net.ssl.SSLHandshakeException e) {
            println "${LOG_PREFIX}: SSL Handshake failed: ${e.message}"
            Throwable cause = e
            while (cause != null) {
                println "${LOG_PREFIX}:   Caused by: ${cause.class.name}: ${cause.message}"
                cause = cause.getCause()
            }
            if (VERBOSE) e.printStackTrace()
        } catch (Exception e) {
            println "${LOG_PREFIX}: Connection test failed: ${e.message}"
            if (VERBOSE) e.printStackTrace()
        }
    }
    
    /**
     * A custom SSLSocketFactory wrapper that logs certificate information for debugging
     */
    static class DebugSSLSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate
        
        DebugSSLSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate
        }
        
        @Override
        Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            SSLSocket sslSocket = (SSLSocket) delegate.createSocket(socket, host, port, autoClose)
            println "${LOG_PREFIX}: Creating SSL socket for ${host}:${port}"
            setupHooks(sslSocket)
            return sslSocket
        }
        
        private void setupHooks(SSLSocket socket) {
            socket.addHandshakeCompletedListener(new HandshakeCompletedListener() {
                @Override
                void handshakeCompleted(HandshakeCompletedEvent event) {
                    try {
                        println "${LOG_PREFIX}: Handshake completed for ${event.socket.inetAddress.hostAddress}"
                        println "${LOG_PREFIX}: Cipher suite: ${event.cipherSuite}"
                        println "${LOG_PREFIX}: Protocol: ${event.session.protocol}"
                        
                        Certificate[] peerCerts = event.session.peerCertificates
                        println "${LOG_PREFIX}: Peer presented ${peerCerts.length} certificates"
                        
                        if (VERBOSE) {
                            for (int i = 0; i < peerCerts.length; i++) {
                                Certificate cert = peerCerts[i]
                                if (cert instanceof X509Certificate) {
                                    X509Certificate x509 = (X509Certificate)cert
                                    println "${LOG_PREFIX}:   Certificate ${i}:"
                                    println "${LOG_PREFIX}:     Subject: ${x509.getSubjectX500Principal().getName()}"
                                    println "${LOG_PREFIX}:     Issuer: ${x509.getIssuerX500Principal().getName()}"
                                }
                            }
                        }
                    } catch (Exception e) {
                        println "${LOG_PREFIX}: Error in handshake listener: ${e.message}"
                        if (VERBOSE) e.printStackTrace()
                    }
                }
            })
        }
        
        @Override String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites() }
        @Override String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites() }
        @Override Socket createSocket(String host, int port) throws IOException { return delegate.createSocket(host, port) }
        @Override Socket createSocket(InetAddress host, int port) throws IOException { return delegate.createSocket(host, port) }
        @Override Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException { return delegate.createSocket(host, port, localHost, localPort) }
        @Override Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException { return delegate.createSocket(address, port, localAddress, localPort) }
    }
}
