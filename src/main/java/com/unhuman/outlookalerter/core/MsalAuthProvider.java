package com.unhuman.outlookalerter.core;

import com.microsoft.aad.msal4j.*;
import com.unhuman.outlookalerter.util.LogManager;
import com.unhuman.outlookalerter.util.LogCategory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Provides Microsoft Authentication Library (MSAL4J) based OAuth2 authentication
 * for the OutlookAlerter application. Handles:
 * <ul>
 *   <li>Interactive browser-based authentication (Authorization Code + PKCE)</li>
 *   <li>Silent token acquisition using cached refresh tokens</li>
 *   <li>Persistent token cache for reducing re-authentication frequency</li>
 * </ul>
 *
 * This is a "Public Client" application — no client secret is required.
 * MSAL4J opens the system browser for login and captures the redirect on a local port.
 */
public class MsalAuthProvider {

    private static final Set<String> SCOPES;
    static {
        Set<String> s = new HashSet<>();
        s.add("https://graph.microsoft.com/Calendars.Read");
        s.add("https://graph.microsoft.com/User.Read");
        s.add("offline_access");
        SCOPES = Collections.unmodifiableSet(s);
    }

    private final ConfigManager configManager;
    private volatile PublicClientApplication pca;
    private final String tokenCachePath;
    private volatile CompletableFuture<IAuthenticationResult> pendingInteractiveAuth;

    /** Timeout in seconds for waiting for the browser redirect. */
    private static final int INTERACTIVE_TIMEOUT_SECONDS = 120;

    /**
     * Creates a new MsalAuthProvider.
     *
     * @param configManager The configuration manager containing clientId, tenantId, redirectUri, etc.
     */
    public MsalAuthProvider(ConfigManager configManager) {
        this.configManager = configManager;

        // Determine cache path: ~/.outlookalerter/msal_token_cache.json
        String configFilePath = getConfigDir();
        this.tokenCachePath = configFilePath + "/msal_token_cache.json";
    }

    /**
     * Returns true if MSAL authentication is configured (clientId is non-empty).
     */
    public boolean isConfigured() {
        String clientId = configManager.getClientId();
        return clientId != null && !clientId.trim().isEmpty();
    }

    /**
     * Attempt to acquire a token silently from the MSAL cache (using refresh tokens).
     *
     * @return The access token string, or null if silent acquisition failed.
     */
    public String acquireTokenSilently() {
        if (!isConfigured()) {
            return null;
        }

        try {
            PublicClientApplication app = getOrCreatePca();
            if (app == null) {
                return null;
            }

            // Get cached accounts
            Set<IAccount> accounts = app.getAccounts().join();
            if (accounts == null || accounts.isEmpty()) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "MSAL: No cached accounts found for silent acquisition");
                return null;
            }

            // Use the first cached account
            IAccount account = accounts.iterator().next();
            LogManager.getInstance().info(LogCategory.GENERAL,
                    "MSAL: Attempting silent token acquisition for account: " + account.username());

            SilentParameters silentParams = SilentParameters.builder(SCOPES, account).build();

            IAuthenticationResult result = app.acquireTokenSilently(silentParams).join();

            if (result != null && result.accessToken() != null && !result.accessToken().isEmpty()) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "MSAL: Silent token acquisition successful");
                return result.accessToken();
            }

            LogManager.getInstance().info(LogCategory.GENERAL,
                    "MSAL: Silent token acquisition returned no token");
            return null;

        } catch (Exception e) {
            // MsalInteractionRequiredException means we need interactive auth
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof MsalInteractionRequiredException) {
                    LogManager.getInstance().info(LogCategory.GENERAL,
                            "MSAL: Silent acquisition failed — interaction required");
                    return null;
                }
                cause = cause.getCause();
            }
            LogManager.getInstance().error(LogCategory.GENERAL,
                    "MSAL: Error during silent token acquisition: " + e.getMessage());
            return null;
        }
    }

    /**
     * Acquire a token interactively by opening the system browser for Microsoft login.
     * MSAL4J will open the browser and listen on the configured redirect URI for the callback.
     *
     * @return The access token string, or null if interactive acquisition failed.
     */
    public String acquireTokenInteractively() {
        if (!isConfigured()) {
            LogManager.getInstance().warn(LogCategory.GENERAL,
                    "MSAL: Cannot acquire token interactively — clientId not configured");
            return null;
        }

        try {
            // Force PCA re-creation if clientId was recently configured
            PublicClientApplication app = getOrCreatePca();
            if (app == null) {
                LogManager.getInstance().error(LogCategory.GENERAL,
                        "MSAL: Failed to create PublicClientApplication — aborting interactive auth");
                return null;
            }

            String redirectUri = configManager.getRedirectUri();
            if (redirectUri == null || redirectUri.trim().isEmpty()) {
                redirectUri = "http://localhost:8888";
            }

            LogManager.getInstance().info(LogCategory.GENERAL,
                    "MSAL: Preparing interactive auth — clientId=" +
                    configManager.getClientId().substring(0, 8) + "..., redirectUri=" + redirectUri);

            // Configure browser feedback pages
            SystemBrowserOptions browserOptions = SystemBrowserOptions.builder()
                    .htmlMessageSuccess("<html><body><h2>Authentication Successful!</h2>"
                            + "<p>You have been signed in to Outlook Alerter.</p>"
                            + "<p>You can close this browser tab and return to the application.</p></body></html>")
                    .htmlMessageError("<html><body><h2>Authentication Failed</h2>"
                            + "<p>Something went wrong during sign-in. Please return to Outlook Alerter and try again.</p>"
                            + "<p>If your organization requires admin approval, contact your IT administrator.</p></body></html>")
                    .build();

            InteractiveRequestParameters.InteractiveRequestParametersBuilder builder =
                    InteractiveRequestParameters.builder(new URI(redirectUri))
                            .scopes(SCOPES)
                            .systemBrowserOptions(browserOptions)
                            .httpPollingTimeoutInSeconds(INTERACTIVE_TIMEOUT_SECONDS);

            // Set login hint if available (pre-fills email field in browser)
            String loginHint = configManager.getLoginHint();
            if (loginHint != null && !loginHint.trim().isEmpty()) {
                builder.loginHint(loginHint);
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "MSAL: Using login hint: " + loginHint);
            }

            InteractiveRequestParameters interactiveParams = builder.build();

            LogManager.getInstance().info(LogCategory.GENERAL,
                    "MSAL: Starting interactive token acquisition — local server will listen on " +
                    redirectUri + " (timeout: " + INTERACTIVE_TIMEOUT_SECONDS + "s, browser will open)...");

            pendingInteractiveAuth = app.acquireToken(interactiveParams);

            IAuthenticationResult result = pendingInteractiveAuth.get(
                    INTERACTIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            pendingInteractiveAuth = null;

            if (result != null && result.accessToken() != null && !result.accessToken().isEmpty()) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "MSAL: Interactive token acquisition successful for: " + result.account().username());
                return result.accessToken();
            }

            LogManager.getInstance().warn(LogCategory.GENERAL,
                    "MSAL: Interactive token acquisition returned no token");
            return null;

        } catch (TimeoutException te) {
            LogManager.getInstance().warn(LogCategory.GENERAL,
                    "MSAL: Interactive auth timed out after " + INTERACTIVE_TIMEOUT_SECONDS +
                    "s — browser redirect was not received. " +
                    "Your organization may require admin consent for this application.");
            cancelPendingAuth();
            throw new RuntimeException("Sign-in timed out after " + INTERACTIVE_TIMEOUT_SECONDS +
                    " seconds. The browser redirect was not received.");
        } catch (java.util.concurrent.CancellationException ce) {
            LogManager.getInstance().info(LogCategory.GENERAL,
                    "MSAL: Interactive auth was cancelled by user");
            cancelPendingAuth();
            return null;  // cancelled — SimpleTokenDialog handles this via its own cancelled flag
        } catch (Exception e) {
            // Unwrap CompletionException to get the real cause
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            String errorDetail = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            LogManager.getInstance().error(LogCategory.GENERAL,
                    "MSAL: Error during interactive token acquisition: " + errorDetail, e);
            cancelPendingAuth();
            // Re-throw so SimpleTokenDialog can display the actual error
            throw new RuntimeException(cause.getMessage() != null ? cause.getMessage() : errorDetail, cause);
        }
    }

    /**
     * Get an access token — tries silent first, returns null if silent fails.
     * Does NOT trigger interactive auth automatically.
     *
     * @return The access token string, or null if no token available silently.
     */
    public String getAccessToken() {
        return acquireTokenSilently();
    }

    /**
     * Cancel any pending interactive authentication.
     * Called when the user clicks cancel in the token dialog or on timeout.
     */
    public void cancelPendingAuth() {
        CompletableFuture<IAuthenticationResult> pending = pendingInteractiveAuth;
        if (pending != null) {
            pending.cancel(true);
            pendingInteractiveAuth = null;
            LogManager.getInstance().info(LogCategory.GENERAL, "MSAL: Cancelled pending interactive auth");
        }
        // Force PCA re-creation on next attempt (cleans up any lingering HTTP server)
        pca = null;
    }

    /**
     * Clear the MSAL token cache. Useful when user wants to sign out or re-authenticate.
     */
    public void clearCache() {
        try {
            Path cachePath = Paths.get(tokenCachePath);
            if (Files.exists(cachePath)) {
                Files.delete(cachePath);
                LogManager.getInstance().info(LogCategory.GENERAL, "MSAL: Token cache cleared");
            }
            // Force re-creation of PCA on next use
            pca = null;
        } catch (IOException e) {
            LogManager.getInstance().error(LogCategory.GENERAL,
                    "MSAL: Error clearing token cache: " + e.getMessage());
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    /**
     * Get or create the PublicClientApplication (lazily initialized).
     */
    private synchronized PublicClientApplication getOrCreatePca() {
        if (pca != null) {
            return pca;
        }

        try {
            String clientId = configManager.getClientId();
            if (clientId == null || clientId.trim().isEmpty()) {
                return null;
            }

            String tenantId = configManager.getTenantId();
            if (tenantId == null || tenantId.trim().isEmpty()) {
                tenantId = "common";
            }

            String authority = "https://login.microsoftonline.com/" + tenantId;

            // Create cache accessor for persistence
            TokenCacheAccessAspect cacheAccessAspect = new TokenCacheAccessAspect();

            PublicClientApplication.Builder pcaBuilder = PublicClientApplication.builder(clientId.trim())
                    .authority(authority)
                    .setTokenCacheAccessAspect(cacheAccessAspect);

            // If cert validation is disabled, configure MSAL to use a trust-all SSLSocketFactory
            if (configManager.getIgnoreCertValidation()) {
                SSLSocketFactory sslSocketFactory = createTrustAllSslSocketFactory();
                if (sslSocketFactory != null) {
                    pcaBuilder.sslSocketFactory(sslSocketFactory);
                    LogManager.getInstance().info(LogCategory.GENERAL,
                            "MSAL: SSL certificate validation disabled for token exchange");
                }
            }

            pca = pcaBuilder.build();

            LogManager.getInstance().info(LogCategory.GENERAL,
                    "MSAL: PublicClientApplication created with authority: " + authority);

            return pca;

        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL,
                    "MSAL: Error creating PublicClientApplication: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Derive the config directory from ConfigManager's known config path.
     */
    private String getConfigDir() {
        // The config file is at ~/.outlookalerter/config.properties
        // We want ~/.outlookalerter/
        String home = System.getProperty("user.home");
        return home + "/.outlookalerter";
    }

    /**
     * Creates an SSLSocketFactory that trusts all certificates.
     * Used when ignoreCertValidation is enabled (e.g., corporate proxy with SSL interception).
     */
    private SSLSocketFactory createTrustAllSslSocketFactory() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL,
                    "MSAL: Error creating trust-all SSLSocketFactory: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Token cache persistence implementation.
     * Reads/writes the MSAL token cache to a JSON file on disk.
     */
    private class TokenCacheAccessAspect implements ITokenCacheAccessAspect {

        @Override
        public void beforeCacheAccess(ITokenCacheAccessContext context) {
            try {
                Path cachePath = Paths.get(tokenCachePath);
                if (Files.exists(cachePath)) {
                    String data = new String(Files.readAllBytes(cachePath), StandardCharsets.UTF_8);
                    context.tokenCache().deserialize(data);
                }
            } catch (IOException e) {
                LogManager.getInstance().error(LogCategory.GENERAL,
                        "MSAL: Error reading token cache: " + e.getMessage());
            }
        }

        @Override
        public void afterCacheAccess(ITokenCacheAccessContext context) {
            if (context.hasCacheChanged()) {
                try {
                    Path cachePath = Paths.get(tokenCachePath);

                    // Ensure directory exists
                    Path cacheDir = cachePath.getParent();
                    if (cacheDir != null && !Files.exists(cacheDir)) {
                        Files.createDirectories(cacheDir);
                    }

                    String data = context.tokenCache().serialize();
                    Files.write(cachePath, data.getBytes(StandardCharsets.UTF_8));

                    // Set file permissions to owner-only on POSIX systems
                    try {
                        Set<PosixFilePermission> perms = new HashSet<>();
                        perms.add(PosixFilePermission.OWNER_READ);
                        perms.add(PosixFilePermission.OWNER_WRITE);
                        Files.setPosixFilePermissions(cachePath, perms);
                    } catch (UnsupportedOperationException e) {
                        // Not a POSIX filesystem (e.g., Windows) — skip
                    }

                } catch (IOException e) {
                    LogManager.getInstance().error(LogCategory.GENERAL,
                            "MSAL: Error writing token cache: " + e.getMessage());
                }
            }
        }
    }
}
