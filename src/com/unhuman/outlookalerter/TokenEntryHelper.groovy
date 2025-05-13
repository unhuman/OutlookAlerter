package com.unhuman.outlookalerter

import groovy.transform.CompileStatic

import java.awt.Desktop
import java.io.IOException
import java.net.URI

/**
 * Enhanced version of TokenEntryServer that provides a direct browser launch for the token form
 * This is a more reliable way to show the token entry form on different platforms
 */
@CompileStatic
class TokenEntryHelper {
    /**
     * Launch the token entry form directly in a browser
     * Uses the Desktop API which is more reliable than Runtime.exec
     */
    static void launchTokenEntryForm(String baseUrl, int port) {
        try {
            // Check if Desktop is supported
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                System.out.println("Desktop browsing not supported. Please manually visit: http://localhost:" + port)
                return
            }
            
            // Use Desktop API to open browser (more reliable than Runtime.exec)
            URI tokenFormUri = new URI("http://localhost:" + port)
            System.out.println("Opening token entry form at: " + tokenFormUri)
            Desktop.getDesktop().browse(tokenFormUri)
            
            // Also print manual instructions
            System.out.println("If the browser doesn't open automatically, please manually navigate to: http://localhost:" + port)
        } catch (Exception e) {
            System.err.println("Failed to open browser for token entry: " + e.getMessage())
            System.out.println("Please manually open http://localhost:" + port + " in your browser")
        }
    }
    
    /**
     * Launch the Okta sign-in page in browser
     */
    static void launchSignInPage(String signInUrl) {
        try {
            // Check if Desktop is supported
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                System.out.println("Desktop browsing not supported. Please manually visit: " + signInUrl)
                return
            }
            
            // Use Desktop API to open browser
            URI signInUri = new URI(signInUrl)
            System.out.println("Opening sign-in page at: " + signInUri)
            Desktop.getDesktop().browse(signInUri)
        } catch (Exception e) {
            System.err.println("Failed to open browser for sign-in: " + e.getMessage())
            System.out.println("Please manually open " + signInUrl + " in your browser")
        }
    }
}