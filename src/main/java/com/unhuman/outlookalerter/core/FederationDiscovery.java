package com.unhuman.outlookalerter.core;

import com.unhuman.outlookalerter.util.LogCategory;
import com.unhuman.outlookalerter.util.LogManager;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Discovers the federation type for a given email address by querying Microsoft's
 * GetUserRealm API endpoint. This allows us to determine whether an email domain
 * uses Okta (or another external IdP) as a federated identity provider for
 * Microsoft 365, enabling SSO authentication without a separate Azure App Registration.
 *
 * <p>The flow:
 * <ol>
 *   <li>Call {@code https://login.microsoftonline.com/getuserrealm.srf?login={email}&json=1}</li>
 *   <li>Parse the {@code NameSpaceType} field — {@code "Federated"} means external IdP</li>
 *   <li>For federated domains, inspect {@code AuthURL} to detect Okta vs ADFS, etc.</li>
 *   <li>Return a {@link FederationResult} with type and the domain hint to use for MSAL</li>
 * </ol>
 */
public class FederationDiscovery {

    private static final String REALM_URL =
            "https://login.microsoftonline.com/getuserrealm.srf?login=%s&json=1";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Federation type detected for a given email domain. */
    public enum FederationType {
        /** Domain federates through Okta as its IdP. */
        OKTA,
        /** Domain federates through ADFS or another non-Okta IdP. */
        ADFS,
        /** Domain is managed directly by Azure AD (no external SSO). */
        MANAGED,
        /** Discovery failed or the result was ambiguous. */
        UNKNOWN
    }

    /**
     * Result of a federation discovery check.
     */
    public static class FederationResult {
        private final FederationType type;
        private final String domain;
        private final String authUrl;
        private final String errorMessage;

        public FederationResult(FederationType type, String domain, String authUrl) {
            this.type = type;
            this.domain = domain;
            this.authUrl = authUrl;
            this.errorMessage = null;
        }

        public FederationResult(String errorMessage) {
            this.type = FederationType.UNKNOWN;
            this.domain = null;
            this.authUrl = null;
            this.errorMessage = errorMessage;
        }

        /** The detected federation type. */
        public FederationType getType() { return type; }

        /**
         * The domain portion of the email (e.g. {@code "company.com"}).
         * Used as the MSAL {@code domainHint} parameter to route the browser
         * through the org's federated IdP instead of the Azure AD login page.
         */
        public String getDomain() { return domain; }

        /**
         * The federation {@code AuthURL} returned by Microsoft's realm endpoint
         * (e.g. {@code "https://company.okta.com/..."}).
         * Null for managed/unknown domains.
         */
        public String getAuthUrl() { return authUrl; }

        /** Non-null if discovery failed; contains the error description. */
        public String getErrorMessage() { return errorMessage; }

        /** True if this result indicates a successful Okta federation discovery. */
        public boolean isOkta() { return type == FederationType.OKTA; }

        /** True if this result indicates any federated (non-managed) domain. */
        public boolean isFederated() {
            return type == FederationType.OKTA || type == FederationType.ADFS;
        }

        @Override
        public String toString() {
            return "FederationResult{type=" + type + ", domain=" + domain + ", authUrl=" + authUrl + "}";
        }
    }

    /**
     * Discover the federation type for the given email address by querying
     * Microsoft's GetUserRealm endpoint.
     *
     * <p>This method performs an outbound HTTP GET and should be called on a
     * background thread, not the Swing EDT.
     *
     * @param email The user's full email address (e.g. {@code "user@company.com"})
     * @return A {@link FederationResult} — never null
     */
    public static FederationResult discoverFederation(String email) {
        // Auto-read the ignoreCertValidation flag from ConfigManager when available
        boolean ignoreCert = false;
        ConfigManager cm = ConfigManager.getInstance();
        if (cm != null) {
            ignoreCert = cm.getIgnoreCertValidation() || cm.getDefaultIgnoreCertValidation();
        }
        return discoverFederation(email, ignoreCert);
    }

    /**
     * Discover the federation type for the given email address.
     *
     * @param email              The user's full email address
     * @param ignoreCertValidation When {@code true}, SSL certificate errors are ignored
     *                           (needed in environments with corporate proxy SSL inspection).
     * @return A {@link FederationResult} — never null
     */
    public static FederationResult discoverFederation(String email, boolean ignoreCertValidation) {
        if (email == null || !email.contains("@")) {
            return new FederationResult("Invalid email address: must contain '@'");
        }

        String domain = email.substring(email.indexOf('@') + 1).trim().toLowerCase();
        if (domain.isEmpty()) {
            return new FederationResult("Invalid email address: domain part is empty");
        }

        String url = String.format(REALM_URL, encode(email));
        LogManager.getInstance().info(LogCategory.GENERAL,
                "FederationDiscovery: querying realm for domain '" + domain + "'"
                + (ignoreCertValidation ? " (SSL cert validation disabled)" : ""));

        try {
            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT);

            if (ignoreCertValidation) {
                SSLContext sslContext = createTrustAllSslContext();
                if (sslContext != null) {
                    clientBuilder.sslContext(sslContext);
                }
            }

            HttpClient client = clientBuilder.build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "OutlookAlerter/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String msg = "GetUserRealm returned HTTP " + response.statusCode();
                LogManager.getInstance().warn(LogCategory.GENERAL, "FederationDiscovery: " + msg);
                return new FederationResult(msg);
            }

            return parseRealmResponse(response.body(), domain);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new FederationResult("Federation discovery was interrupted");
        } catch (Exception e) {
            String msg = "Federation discovery failed: " + e.getMessage();
            LogManager.getInstance().error(LogCategory.GENERAL, "FederationDiscovery: " + msg);
            return new FederationResult(msg);
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    /**
     * Parse the JSON body from Microsoft's GetUserRealm endpoint.
     *
     * <p>Relevant fields:
     * <ul>
     *   <li>{@code NameSpaceType}: {@code "Federated"} | {@code "Managed"} | {@code "Unknown"}</li>
     *   <li>{@code federation_protocol}: {@code "SAML20"} | {@code "WsFed"} | etc.</li>
     *   <li>{@code AuthURL}: The IdP's SSO initiation URL (only present for Federated)</li>
     *   <li>{@code cloud_instance_name}: e.g. {@code "microsoftonline.com"}</li>
     * </ul>
     */
    static FederationResult parseRealmResponse(String json, String domain) {
        try {
            JSONObject obj = new JSONObject(json);
            String namespaceType = obj.optString("NameSpaceType", "Unknown");

            LogManager.getInstance().info(LogCategory.GENERAL,
                    "FederationDiscovery: NameSpaceType=" + namespaceType + " for domain=" + domain);

            if ("Managed".equalsIgnoreCase(namespaceType)) {
                return new FederationResult(FederationType.MANAGED, domain, null);
            }

            if ("Federated".equalsIgnoreCase(namespaceType)) {
                String authUrl = obj.optString("AuthURL", null);
                // Detect Okta by inspecting the AuthURL hostname
                if (authUrl != null && authUrl.contains("okta.com")) {
                    LogManager.getInstance().info(LogCategory.GENERAL,
                            "FederationDiscovery: Okta federation detected via AuthURL: " + authUrl);
                    return new FederationResult(FederationType.OKTA, domain, authUrl);
                }
                // Other federated IdP (ADFS, Ping, etc.)
                LogManager.getInstance().info(LogCategory.GENERAL,
                        "FederationDiscovery: Non-Okta federation detected, authUrl=" + authUrl);
                return new FederationResult(FederationType.ADFS, domain, authUrl);
            }

            // NameSpaceType == "Unknown" or anything else
            LogManager.getInstance().info(LogCategory.GENERAL,
                    "FederationDiscovery: Unknown namespace type '" + namespaceType + "' for domain=" + domain);
            return new FederationResult(FederationType.UNKNOWN, domain, null);

        } catch (Exception e) {
            String msg = "Failed to parse realm response: " + e.getMessage();
            LogManager.getInstance().error(LogCategory.GENERAL, "FederationDiscovery: " + msg);
            return new FederationResult(msg);
        }
    }

    /** Minimal URL encoding for the email parameter (handles @ and spaces). */
    private static String encode(String value) {
        return value.replace(" ", "%20")
                    .replace("@", "%40")
                    .replace("+", "%2B");
    }

    /**
     * Creates an SSLContext that trusts all certificates.
     * Used when the user has enabled "Ignore SSL certificate validation"
     * (e.g. in environments with corporate proxy SSL inspection).
     */
    private static SSLContext createTrustAllSslContext() {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL,
                    "FederationDiscovery: Failed to create trust-all SSLContext: " + e.getMessage());
            return null;
        }
    }
}
