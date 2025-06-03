package com.unhuman.outlookalerter.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import groovy.transform.CompileStatic

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Simple HTTP server to handle OAuth redirects
 */
@CompileStatic
class OAuthRedirectServer {
    private final int port
    private HttpServer server
    private String authCode
    private String expectedState
    private CountDownLatch authCodeLatch = new CountDownLatch(1)
    
    /**
     * Creates a new redirect server on the specified port
     */
    OAuthRedirectServer(int port) {
        this.port = port
    }
    
    /**
     * Starts the server
     */
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0)
        server.createContext("/redirect", new RedirectHandler())
        server.setExecutor(null) // Use the default executor
        server.start()
        println "Redirect server started on port ${port}"
    }
    
    /**
     * Stops the server
     */
    void stop() {
        if (server != null) {
            server.stop(0)
            println "Redirect server stopped"
        }
    }
    
    /**
     * Waits for an authorization code to be received
     * @param state The expected state parameter (for CSRF protection)
     * @param timeoutSeconds Maximum time to wait in seconds
     * @return The authorization code, or null if timeout occurred or state was invalid
     */
    String waitForAuthCode(String state, int timeoutSeconds) {
        this.expectedState = state
        try {
            boolean received = authCodeLatch.await(timeoutSeconds, TimeUnit.SECONDS)
            return received ? authCode : null
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            return null
        }
    }
    
    /**
     * Handler for OAuth redirect requests
     */
    private class RedirectHandler implements HttpHandler {
        @Override
        void handle(HttpExchange exchange) throws IOException {
            String query = exchange.requestURI.query
            
            if (query != null) {
                // Parse query parameters
                Map<String, String> params = [:]
                query.split("&").each { String param ->
                    String[] pair = param.split("=", 2)
                    if (pair.length == 2) {
                        params[pair[0]] = URLDecoder.decode(pair[1], "UTF-8")
                    }
                }
                
                // Get authorization code and state
                String code = params["code"]
                String state = params["state"]
                
                if (code && state && state == expectedState) {
                    // Valid auth code with matching state
                    authCode = code
                    
                    // Send success response
                    String response = """
                    <html>
                    <head>
                        <title>Authentication Successful</title>
                        <style>
                            body { font-family: Arial, sans-serif; margin: 40px; text-align: center; }
                            h1 { color: #4CAF50; }
                        </style>
                    </head>
                    <body>
                        <h1>Authentication Successful!</h1>
                        <p>You have successfully authenticated. You can now close this window and return to the application.</p>
                    </body>
                    </html>
                    """
                    
                    exchange.responseHeaders.set("Content-Type", "text/html")
                    exchange.sendResponseHeaders(200, response.length())
                    OutputStream os = exchange.responseBody
                    os.write(response.getBytes())
                    os.close()
                    
                    // Signal that we got the code
                    authCodeLatch.countDown()
                } else if (params.containsKey("error")) {
                    // OAuth error response
                    String error = params["error"] ?: "unknown_error"
                    String errorDescription = params["error_description"] ?: "No description available"
                    
                    handleError(exchange, error, errorDescription)
                } else if (code && (!state || state != expectedState)) {
                    // State validation failed - potential CSRF attack
                    handleError(exchange, "invalid_state", "State validation failed. This could be a CSRF attack or your session expired.")
                } else {
                    // Unexpected response
                    handleError(exchange, "invalid_response", "The authorization server returned an invalid response.")
                }
            } else {
                // No query parameters
                handleError(exchange, "missing_parameters", "No query parameters found in the redirect.")
            }
        }
        
        /**
         * Handle error responses
         */
        private void handleError(HttpExchange exchange, String error, String description) throws IOException {
            String response = """
            <html>
            <head>
                <title>Authentication Failed</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 40px; text-align: center; }
                    h1 { color: #F44336; }
                    .error-code { font-family: monospace; background: #f1f1f1; padding: 10px; }
                </style>
            </head>
            <body>
                <h1>Authentication Failed</h1>
                <p>Error: ${error}</p>
                <p>${description}</p>
                <p>Please close this window and try again.</p>
            </body>
            </html>
            """
            
            exchange.responseHeaders.set("Content-Type", "text/html")
            exchange.sendResponseHeaders(400, response.length())
            OutputStream os = exchange.responseBody
            os.write(response.getBytes())
            os.close()
            
            println "Authentication error: ${error} - ${description}"
        }
    }
}