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
import java.util.function.Consumer;
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

    /**
     * Azure CLI first-party client ID.
     * Same client ID used by the {@code az} CLI and many open-source Graph tools.
     */
    public static final String WELL_KNOWN_OFFICE_CLIENT_ID = "04b07795-8ddb-461a-bbee-02f9e1bf7b46";

    /**
     * Ordered list of well-known first-party client IDs to try for Device Code Flow.
     * Each is a public client pre-registered by Microsoft. Some tenants block certain
     * IDs with AADSTS65002 ("consent between first-party app and resource not configured");
     * we try each in turn until one works.
     *
     * <ol>
     *   <li>Microsoft Graph PowerShell SDK</li>
     *   <li>Microsoft Graph Explorer</li>
     *   <li>Azure CLI</li>
     * </ol>
     */
    public static final String[] GRAPH_CLIENT_IDS = {
        "14d82eec-204b-4c2f-b7e8-296a70dab67e",  // Graph PowerShell SDK
        "de8bc8b5-d9f9-48b1-a8ad-b748da725064",  // Graph Explorer
        WELL_KNOWN_OFFICE_CLIENT_ID,              // Azure CLI (fallback)
    };

    private final ConfigManager configManager;
    private volatile PublicClientApplication pca;
    private final String tokenCachePath;
    private volatile CompletableFuture<IAuthenticationResult> pendingInteractiveAuth;

    /** Timeout in seconds for waiting for the browser redirect. */
    private static final int INTERACTIVE_TIMEOUT_SECONDS = 120;
    /** Device Code Flow polls for up to 10 minutes — much longer than a browser redirect. */
    private static final int DEVICE_CODE_TIMEOUT_SECONDS = 600;

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
        return acquireTokenInteractively(null, null);
    }

    /**
     * Acquire a token interactively with an optional domain hint for federated SSO (e.g. Okta).
     *
     * <p>When {@code domainHint} is non-null, Azure AD skips the home-realm-discovery page and
     * routes the browser directly to the org's federated IdP (Okta, ADFS, etc.).  This is the
     * mechanism that allows SSO login without requiring a separate IT-approved Azure App
     * Registration — the existing federation trust between the org's Okta tenant and Azure AD
     * is used transparently.
     *
     * @param domainHint       The UPN suffix / email domain (e.g. {@code "company.com"}).
     *                         Pass {@code null} for standard Azure AD login without a domain hint.
     * @param loginHintOverride Override email to pre-fill in the browser.  When {@code null},
     *                         falls back to the value from {@link ConfigManager#getLoginHint()}.
     * @return The access token string, or null if interactive acquisition failed.
     */
    public String acquireTokenInteractively(String domainHint, String loginHintOverride) {
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
                    configManager.getClientId().substring(0, 8) + "..., redirectUri=" + redirectUri
                    + (domainHint != null ? ", domainHint=" + domainHint : ""));

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

            // Apply domain hint if provided — routes browser to federated IdP (Okta, ADFS, etc.)
            if (domainHint != null && !domainHint.trim().isEmpty()) {
                builder.domainHint(domainHint);
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "MSAL: Using domain hint for federated SSO: " + domainHint);
            }

            // Set login hint: prefer the override (e.g. from Okta flow), fall back to config
            String loginHint = (loginHintOverride != null && !loginHintOverride.trim().isEmpty())
                    ? loginHintOverride
                    : configManager.getLoginHint();
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
     * Acquire a token using Device Code Flow with a specific client ID.
     * No redirect URI is needed — MSAL4J polls Azure AD while the user visits
     * {@code https://microsoft.com/devicelogin} and enters the displayed code.
     * This is the same flow used by {@code az login}.
     *
     * @param clientId           The client ID to use (e.g. {@link #WELL_KNOWN_OFFICE_CLIENT_ID})
     * @param deviceCodeConsumer Callback invoked with the {@link DeviceCode} as soon as Azure
     *                           AD issues it; use this to display the code/URL to the user
     * @param progressConsumer   Optional callback invoked with progress messages (for status label);
     *                           may be null
     * @return The access token, or throws on failure
     */
    public String acquireTokenDeviceCode(String clientId, Consumer<DeviceCode> deviceCodeConsumer,
                                         Consumer<String> progressConsumer) {
        CompletableFuture<IAuthenticationResult> cancellable = new CompletableFuture<>();
        pendingInteractiveAuth = cancellable;

        // Write progress to both the in-memory log AND a file for diagnostics
        java.util.function.BiConsumer<String, Throwable> debugLog = (msg, ex) -> {
            String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()) + " " + msg;
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "MSAL-DCF: " + msg);
            try {
                String dir = getConfigDir();
                java.nio.file.Path logFile = java.nio.file.Paths.get(dir, "device-code-debug.log");
                java.nio.file.Files.writeString(logFile,
                        line + (ex != null ? " | " + ex : "") + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
        };

        try {
            String tenantId = configManager.getTenantId();
            if (tenantId == null || tenantId.trim().isEmpty()) {
                tenantId = "common";
            }
            String authority = "https://login.microsoftonline.com/" + tenantId;

            debugLog.accept("authority=" + authority + ", clientId=" + clientId, null);
            if (progressConsumer != null) progressConsumer.accept("Connecting to Microsoft...");

            String oktaCachePath = getConfigDir() + "/msal_okta_cache.json";
            TokenCacheAccessAspect cacheAspect = new TokenCacheAccessAspect(oktaCachePath);

            PublicClientApplication.Builder pcaBuilder = PublicClientApplication.builder(clientId)
                    .authority(authority)
                    .setTokenCacheAccessAspect(cacheAspect)
                    .connectTimeoutForDefaultHttpClient(15000)
                    .readTimeoutForDefaultHttpClient(15000);

            boolean ignoreCert = configManager.getIgnoreCertValidation();
            debugLog.accept("ignoreCertValidation=" + ignoreCert, null);
            if (ignoreCert) {
                SSLSocketFactory sf = createTrustAllSslSocketFactory();
                if (sf != null) {
                    pcaBuilder.sslSocketFactory(sf);
                    debugLog.accept("trust-all SSL set", null);
                }
            }

            if (cancellable.isCancelled()) {
                throw new java.util.concurrent.CancellationException("Cancelled");
            }

            debugLog.accept("building PCA...", null);
            PublicClientApplication tempPca = pcaBuilder.build();
            debugLog.accept("PCA built OK", null);

            if (cancellable.isCancelled()) {
                throw new java.util.concurrent.CancellationException("Cancelled");
            }

            if (progressConsumer != null) progressConsumer.accept("Requesting device code...");
            debugLog.accept("requesting device code...", null);

            DeviceCodeFlowParameters params = DeviceCodeFlowParameters
                    .builder(SCOPES, (DeviceCode dc) -> {
                        debugLog.accept("device code callback fired: code=" + dc.userCode()
                                + " url=" + dc.verificationUri(), null);
                        deviceCodeConsumer.accept(dc);
                    })
                    .build();

            CompletableFuture<IAuthenticationResult> msalFuture = tempPca.acquireToken(params);
            debugLog.accept("acquireToken returned future", null);

            msalFuture.whenComplete((res, ex) -> {
                if (cancellable.isCancelled()) return;
                if (ex != null) {
                    debugLog.accept("msalFuture completed exceptionally: " + ex.getMessage(), ex);
                    cancellable.completeExceptionally(ex);
                } else {
                    debugLog.accept("msalFuture completed with result", null);
                    cancellable.complete(res);
                }
            });
            cancellable.whenComplete((res, ex) -> {
                if (cancellable.isCancelled()) msalFuture.cancel(true);
            });

            debugLog.accept("waiting on cancellable.get(" + DEVICE_CODE_TIMEOUT_SECONDS + "s)...", null);
            IAuthenticationResult result = cancellable.get(DEVICE_CODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            pendingInteractiveAuth = null;
            if (result == null || result.accessToken() == null) {
                throw new RuntimeException("Device code flow returned no access token.");
            }
            debugLog.accept("token acquired!", null);
            return result.accessToken();

        } catch (java.util.concurrent.CancellationException e) {
            debugLog.accept("cancelled", null);
            pendingInteractiveAuth = null;
            throw e;
        } catch (InterruptedException e) {
            debugLog.accept("interrupted", null);
            pendingInteractiveAuth = null;
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            pendingInteractiveAuth = null;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            debugLog.accept("ExecutionException: " + msg, cause);
            throw new RuntimeException(msg, cause);
        } catch (java.util.concurrent.TimeoutException e) {
            pendingInteractiveAuth = null;
            cancellable.cancel(true);
            debugLog.accept("timed out after " + DEVICE_CODE_TIMEOUT_SECONDS + "s", null);
            throw new RuntimeException("Sign-in timed out after " + DEVICE_CODE_TIMEOUT_SECONDS + " seconds.");
        } catch (Exception e) {
            pendingInteractiveAuth = null;
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            debugLog.accept("Exception: " + msg, cause);
            throw new RuntimeException(msg, cause);
        }
    }

    /**
     * Acquire a token interactively using a specific client ID, without disturbing the
     * main PCA or token cache.  Used for the Okta SSO path which needs a pre-approved
     * first-party client ID ({@link #WELL_KNOWN_OFFICE_CLIENT_ID}) to bypass the
     * tenant admin-consent gate for unverified apps.
     *
     * <p>A fresh, temporary {@link PublicClientApplication} is created for this call
     * using its own isolated cache file ({@code msal_okta_cache.json}).
     *
     * @param clientId         The client ID to use (e.g. {@link #WELL_KNOWN_OFFICE_CLIENT_ID})
     * @param domainHint       UPN suffix to route the browser to the federated IdP (Okta)
     * @param loginHintOverride Email to pre-fill; null falls back to ConfigManager loginHint
     * @return The access token, or null on failure
     */
    public String acquireTokenWithClientId(String clientId, String domainHint, String loginHintOverride) {
        try {
            // The Azure CLI app (and similar first-party apps) have "http://localhost" (no port)
            // registered as their redirect URI in Azure AD.  Using a port (e.g. :8888) causes
            // Azure AD to return an error in the redirect, which MSAL4J reports as "no
            // authorization code".  MSAL4J handles the portless loopback URI by binding its
            // local callback server to any free port and advertising only "http://localhost"
            // to Azure AD, which accepts any loopback port per RFC 8252.
            String redirectUri = "http://localhost";

            String tenantId = configManager.getTenantId();
            if (tenantId == null || tenantId.trim().isEmpty()) {
                tenantId = "common";
            }
            String authority = "https://login.microsoftonline.com/" + tenantId;

            // Separate cache so it doesn't collide with the regular MSAL cache
            String oktaCachePath = getConfigDir() + "/msal_okta_cache.json";
            TokenCacheAccessAspect cacheAspect = new TokenCacheAccessAspect(oktaCachePath);

            PublicClientApplication.Builder pcaBuilder = PublicClientApplication.builder(clientId)
                    .authority(authority)
                    .setTokenCacheAccessAspect(cacheAspect);

            if (configManager.getIgnoreCertValidation()) {
                SSLSocketFactory sf = createTrustAllSslSocketFactory();
                if (sf != null) {
                    pcaBuilder.sslSocketFactory(sf);
                }
            }

            PublicClientApplication tempPca = pcaBuilder.build();

            LogManager.getInstance().info(LogCategory.GENERAL,
                    "MSAL(Okta): Starting interactive auth — clientId=" + clientId.substring(0, 8)
                    + "..., domainHint=" + domainHint + ", redirectUri=" + redirectUri);

            // Try silent first (re-uses cached Okta SSO session)
            Set<IAccount> accounts = tempPca.getAccounts().join();
            if (!accounts.isEmpty()) {
                try {
                    IAccount account = accounts.iterator().next();
                    SilentParameters silentParams = SilentParameters.builder(SCOPES, account).build();
                    IAuthenticationResult silent = tempPca.acquireTokenSilently(silentParams).join();
                    if (silent != null && silent.accessToken() != null && !silent.accessToken().isEmpty()) {
                        LogManager.getInstance().info(LogCategory.GENERAL,
                                "MSAL(Okta): Silent re-use of cached session successful");
                        return silent.accessToken();
                    }
                } catch (Exception ignored) { /* fall through to interactive */ }
            }

            SystemBrowserOptions browserOptions = SystemBrowserOptions.builder()
                    .htmlMessageSuccess("<html><body><h2>Okta SSO Successful!</h2>"
                            + "<p>You have been signed in. You can close this tab.</p></body></html>")
                    .htmlMessageError("<html><body><h2>Sign-in Failed</h2>"
                            + "<p>Please return to Outlook Alerter and try again.</p>"
                            + "<p>Check the application log viewer for the specific Azure AD error code.</p>"
                            + "</body></html>")
                    .build();

            InteractiveRequestParameters.InteractiveRequestParametersBuilder builder =
                    InteractiveRequestParameters.builder(new URI(redirectUri))
                            .scopes(SCOPES)
                            .systemBrowserOptions(browserOptions)
                            .httpPollingTimeoutInSeconds(INTERACTIVE_TIMEOUT_SECONDS);

            // Do NOT set domainHint here — it can trigger conditional-access errors when combined
            // with first-party app IDs in some tenants.  Azure AD's Home Realm Discovery (HRD)
            // will automatically route to Okta when loginHint is set to a federated email address,
            // making domainHint redundant and potentially harmful.

            String loginHint = (loginHintOverride != null && !loginHintOverride.trim().isEmpty())
                    ? loginHintOverride : configManager.getLoginHint();
            if (loginHint != null && !loginHint.trim().isEmpty()) {
                builder.loginHint(loginHint);
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "MSAL(Okta): Using loginHint for Home Realm Discovery: " + loginHint
                        + " — Azure AD will route to federated IdP automatically");
            }

            CompletableFuture<IAuthenticationResult> future = tempPca.acquireToken(builder.build());
            // Track so cancel works
            pendingInteractiveAuth = future;

            IAuthenticationResult result = future.get(INTERACTIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            pendingInteractiveAuth = null;

            if (result != null && result.accessToken() != null && !result.accessToken().isEmpty()) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "MSAL(Okta): Interactive auth successful for: " + result.account().username());
                return result.accessToken();
            }
            return null;

        } catch (TimeoutException te) {
            cancelPendingAuth();
            throw new RuntimeException("Sign-in timed out after " + INTERACTIVE_TIMEOUT_SECONDS + " seconds.");
        } catch (java.util.concurrent.CancellationException ce) {
            cancelPendingAuth();
            return null;
        } catch (Exception e) {
            // Walk the cause chain collecting all messages — Azure AD error codes (AADSTS...) are
            // often buried inside MsalServiceException wrapped by CompletionException
            StringBuilder fullMsg = new StringBuilder();
            Throwable cause = e;
            Throwable rootCause = e;
            while (cause != null) {
                if (cause.getMessage() != null && !cause.getMessage().isEmpty()) {
                    if (fullMsg.length() > 0) fullMsg.append(" | ");
                    fullMsg.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
                }
                rootCause = cause;
                cause = (cause.getCause() != null && cause.getCause() != cause) ? cause.getCause() : null;
            }
            String displayMsg = rootCause.getMessage() != null ? rootCause.getMessage() : e.getClass().getSimpleName();
            LogManager.getInstance().error(LogCategory.GENERAL,
                    "MSAL(Okta): Error during interactive auth — full chain: " + fullMsg, e);
            cancelPendingAuth();
            throw new RuntimeException(displayMsg, rootCause);
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

        private final String cachePath;

        /** Use the instance-level token cache path (default MSAL flow). */
        TokenCacheAccessAspect() {
            this.cachePath = tokenCachePath;
        }

        /** Use an explicit cache path (Okta SSO flow uses a separate cache file). */
        TokenCacheAccessAspect(String cachePath) {
            this.cachePath = cachePath;
        }

        @Override
        public void beforeCacheAccess(ITokenCacheAccessContext context) {
            try {
                Path path = Paths.get(cachePath);
                if (Files.exists(path)) {
                    String data = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
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
                    Path path = Paths.get(cachePath);

                    // Ensure directory exists
                    Path cacheDir = path.getParent();
                    if (cacheDir != null && !Files.exists(cacheDir)) {
                        Files.createDirectories(cacheDir);
                    }

                    String data = context.tokenCache().serialize();
                    Files.write(path, data.getBytes(StandardCharsets.UTF_8));

                    // Set file permissions to owner-only on POSIX systems
                    try {
                        Set<PosixFilePermission> perms = new HashSet<>();
                        perms.add(PosixFilePermission.OWNER_READ);
                        perms.add(PosixFilePermission.OWNER_WRITE);
                        Files.setPosixFilePermissions(path, perms);
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
