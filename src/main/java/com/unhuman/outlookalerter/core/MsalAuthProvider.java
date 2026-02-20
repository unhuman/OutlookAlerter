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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
            PublicClientApplication app = getOrCreatePca();
            if (app == null) {
                return null;
            }

            String redirectUri = configManager.getRedirectUri();
            if (redirectUri == null || redirectUri.trim().isEmpty()) {
                redirectUri = "http://localhost:8888/redirect";
            }

            InteractiveRequestParameters.InteractiveRequestParametersBuilder builder =
                    InteractiveRequestParameters.builder(new URI(redirectUri))
                            .scopes(SCOPES);

            // Set login hint if available (pre-fills email field in browser)
            String loginHint = configManager.getLoginHint();
            if (loginHint != null && !loginHint.trim().isEmpty()) {
                builder.loginHint(loginHint);
            }

            InteractiveRequestParameters interactiveParams = builder.build();

            LogManager.getInstance().info(LogCategory.GENERAL,
                    "MSAL: Starting interactive token acquisition (browser will open)...");

            IAuthenticationResult result = app.acquireToken(interactiveParams).join();

            if (result != null && result.accessToken() != null && !result.accessToken().isEmpty()) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "MSAL: Interactive token acquisition successful for: " + result.account().username());
                return result.accessToken();
            }

            LogManager.getInstance().warn(LogCategory.GENERAL,
                    "MSAL: Interactive token acquisition returned no token");
            return null;

        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL,
                    "MSAL: Error during interactive token acquisition: " + e.getMessage(), e);
            return null;
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

            pca = PublicClientApplication.builder(clientId.trim())
                    .authority(authority)
                    .setTokenCacheAccessAspect(cacheAccessAspect)
                    .build();

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
