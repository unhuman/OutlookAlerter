package com.unhuman.outlookalerter.core;

import com.unhuman.outlookalerter.util.LogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FederationDiscoveryTest {

    @BeforeEach
    void initLog() {
        LogManager.getInstance();
    }

    // ────────── parseRealmResponse ──────────

    @Nested
    @DisplayName("parseRealmResponse")
    class ParseRealmResponse {

        @Test
        @DisplayName("managed domain returns MANAGED type")
        void managedDomain() {
            String json = "{\"NameSpaceType\":\"Managed\",\"Login\":\"user@company.com\","
                    + "\"DomainName\":\"company.com\",\"FederationBrandName\":null}";
            FederationDiscovery.FederationResult result =
                    FederationDiscovery.parseRealmResponse(json, "company.com");

            assertEquals(FederationDiscovery.FederationType.MANAGED, result.getType());
            assertEquals("company.com", result.getDomain());
            assertNull(result.getAuthUrl());
            assertNull(result.getErrorMessage());
            assertFalse(result.isOkta());
            assertFalse(result.isFederated());
        }

        @Test
        @DisplayName("Okta-federated domain returns OKTA type with authUrl")
        void oktaFederatedDomain() {
            String oktaAuthUrl = "https://acme.okta.com/app/office365/exk8u9abc123/sso/saml";
            String json = "{\"NameSpaceType\":\"Federated\","
                    + "\"federation_protocol\":\"SAML20\","
                    + "\"AuthURL\":\"" + oktaAuthUrl + "\","
                    + "\"Login\":\"user@acme.com\"}";
            FederationDiscovery.FederationResult result =
                    FederationDiscovery.parseRealmResponse(json, "acme.com");

            assertEquals(FederationDiscovery.FederationType.OKTA, result.getType());
            assertEquals("acme.com", result.getDomain());
            assertEquals(oktaAuthUrl, result.getAuthUrl());
            assertNull(result.getErrorMessage());
            assertTrue(result.isOkta());
            assertTrue(result.isFederated());
        }

        @Test
        @DisplayName("ADFS-federated domain (non-Okta) returns ADFS type")
        void adfsFederatedDomain() {
            String adfsAuthUrl = "https://sts.acme.com/adfs/ls/?client-request-id=abc";
            String json = "{\"NameSpaceType\":\"Federated\","
                    + "\"federation_protocol\":\"WsFed\","
                    + "\"AuthURL\":\"" + adfsAuthUrl + "\","
                    + "\"Login\":\"user@acme.com\"}";
            FederationDiscovery.FederationResult result =
                    FederationDiscovery.parseRealmResponse(json, "acme.com");

            assertEquals(FederationDiscovery.FederationType.ADFS, result.getType());
            assertEquals("acme.com", result.getDomain());
            assertEquals(adfsAuthUrl, result.getAuthUrl());
            assertNull(result.getErrorMessage());
            assertFalse(result.isOkta());
            assertTrue(result.isFederated());
        }

        @Test
        @DisplayName("Unknown namespace type returns UNKNOWN type")
        void unknownNamespace() {
            String json = "{\"NameSpaceType\":\"Unknown\",\"Login\":\"user@nobody.com\"}";
            FederationDiscovery.FederationResult result =
                    FederationDiscovery.parseRealmResponse(json, "nobody.com");

            assertEquals(FederationDiscovery.FederationType.UNKNOWN, result.getType());
            assertEquals("nobody.com", result.getDomain());
            assertNull(result.getAuthUrl());
            assertFalse(result.isOkta());
            assertFalse(result.isFederated());
        }

        @Test
        @DisplayName("Federated domain without AuthURL returns ADFS (graceful null)")
        void federatedWithoutAuthUrl() {
            String json = "{\"NameSpaceType\":\"Federated\",\"federation_protocol\":\"SAML20\"}";
            FederationDiscovery.FederationResult result =
                    FederationDiscovery.parseRealmResponse(json, "mystery.com");

            assertEquals(FederationDiscovery.FederationType.ADFS, result.getType());
            assertNull(result.getAuthUrl());
            assertFalse(result.isOkta());
            assertTrue(result.isFederated());
        }

        @Test
        @DisplayName("malformed JSON returns UNKNOWN with error message")
        void malformedJson() {
            FederationDiscovery.FederationResult result =
                    FederationDiscovery.parseRealmResponse("not-json-at-all", "broken.com");

            assertEquals(FederationDiscovery.FederationType.UNKNOWN, result.getType());
            assertNotNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("case-insensitive NameSpaceType matching")
        void caseInsensitiveNamespaceType() {
            String json = "{\"NameSpaceType\":\"MANAGED\"}";
            FederationDiscovery.FederationResult result =
                    FederationDiscovery.parseRealmResponse(json, "upper.com");
            assertEquals(FederationDiscovery.FederationType.MANAGED, result.getType());

            String json2 = "{\"NameSpaceType\":\"federated\","
                    + "\"AuthURL\":\"https://corp.okta.com/sso\"}";
            FederationDiscovery.FederationResult result2 =
                    FederationDiscovery.parseRealmResponse(json2, "lower.com");
            assertEquals(FederationDiscovery.FederationType.OKTA, result2.getType());
        }
    }

    // ────────── discoverFederation input validation ──────────

    @Nested
    @DisplayName("discoverFederation input validation")
    class InputValidation {

        @Test
        @DisplayName("null email returns UNKNOWN with error")
        void nullEmail() {
            FederationDiscovery.FederationResult result = FederationDiscovery.discoverFederation(null);
            assertEquals(FederationDiscovery.FederationType.UNKNOWN, result.getType());
            assertNotNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("email without @ returns UNKNOWN with error")
        void emailWithoutAt() {
            FederationDiscovery.FederationResult result = FederationDiscovery.discoverFederation("notanemail");
            assertEquals(FederationDiscovery.FederationType.UNKNOWN, result.getType());
            assertNotNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("email with trailing @ returns UNKNOWN with error")
        void emailTrailingAt() {
            FederationDiscovery.FederationResult result = FederationDiscovery.discoverFederation("user@");
            assertEquals(FederationDiscovery.FederationType.UNKNOWN, result.getType());
            assertNotNull(result.getErrorMessage());
        }
    }
}
