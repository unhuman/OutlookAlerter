package com.unhuman.outlookalerter.core;

import com.unhuman.outlookalerter.util.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OutlookClient focusing on testable non-HTTP components:
 * AuthenticationCancelledException and token validation constants.
 *
 * NOTE: Methods like getUpcomingEventsUsingCalendarView(), hasValidToken(),
 * executeRequestWithRetry() etc. make real HTTP calls to Microsoft Graph API
 * and are not suitable for unit testing without a mock HTTP server.
 */
class OutlookClientTest {

    private Path tempDir;
    private ConfigManager configManager;

    @BeforeEach
    void setup() throws IOException {
        LogManager.getInstance();
        tempDir = Files.createTempDirectory("outlookclient-test");
        String configPath = new File(tempDir.toFile(), "test-config.properties").getAbsolutePath();
        configManager = new ConfigManager(configPath);
        configManager.loadConfiguration();
    }

    // ───────── AuthenticationCancelledException ─────────

    @Nested
    @DisplayName("AuthenticationCancelledException")
    class AuthCancelled {

        @Test
        @DisplayName("single-arg constructor sets message and default reason")
        void singleArgConstructor() {
            OutlookClient.AuthenticationCancelledException ex =
                new OutlookClient.AuthenticationCancelledException("User cancelled");
            assertEquals("User cancelled", ex.getMessage());
            assertEquals("user_cancelled", ex.getReason());
        }

        @Test
        @DisplayName("two-arg constructor sets message and custom reason")
        void twoArgConstructor() {
            OutlookClient.AuthenticationCancelledException ex =
                new OutlookClient.AuthenticationCancelledException("Validation failed", "validation_exhausted");
            assertEquals("Validation failed", ex.getMessage());
            assertEquals("validation_exhausted", ex.getReason());
        }

        @Test
        @DisplayName("is a RuntimeException")
        void isRuntimeException() {
            OutlookClient.AuthenticationCancelledException ex =
                new OutlookClient.AuthenticationCancelledException("test");
            assertTrue(ex instanceof RuntimeException);
        }

        @Test
        @DisplayName("toString includes message and reason")
        void toStringFormat() {
            OutlookClient.AuthenticationCancelledException ex =
                new OutlookClient.AuthenticationCancelledException("auth failed", "window_closed");
            String str = ex.toString();
            assertTrue(str.contains("auth failed"));
            assertTrue(str.contains("window_closed"));
        }

        @Test
        @DisplayName("can be caught as RuntimeException")
        void catchable() {
            assertThrows(RuntimeException.class, () -> {
                throw new OutlookClient.AuthenticationCancelledException("cancelled");
            });
        }
    }

    // ───────── Token Validation Constants ─────────

    @Nested
    @DisplayName("token validation constants")
    class TokenConstants {

        private String getStaticField(String name) throws Exception {
            Field field = OutlookClient.class.getDeclaredField(name);
            field.setAccessible(true);
            return (String) field.get(null);
        }

        @Test
        @DisplayName("all constants are non-null unique strings")
        void constantsAreUnique() throws Exception {
            Set<String> constants = new HashSet<>(Arrays.asList(
                getStaticField("TOKEN_VALID_NO_ACTION"),
                getStaticField("TOKEN_VALID_AFTER_SERVER_VALIDATION"),
                getStaticField("TOKEN_REFRESHED"),
                getStaticField("TOKEN_NEW_AUTHENTICATION")
            ));

            assertEquals(4, constants.size(), "All token validation constants should be unique");
            for (String c : constants) {
                assertNotNull(c);
            }
        }

        @Test
        @DisplayName("TOKEN_VALID_NO_ACTION is default result")
        void defaultResult() throws Exception {
            // A fresh OutlookClient should have TOKEN_VALID_NO_ACTION
            OutlookClient client = new OutlookClient(configManager);
            assertEquals(getStaticField("TOKEN_VALID_NO_ACTION"), client.getLastTokenValidationResult());
        }
    }

    // ───────── Constructor ─────────

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("creates client without UI reference")
        void noUiConstructor() {
            OutlookClient client = new OutlookClient(configManager);
            assertNotNull(client);
        }

        @Test
        @DisplayName("creates client with null UI reference")
        void nullUiConstructor() {
            OutlookClient client = new OutlookClient(configManager, null);
            assertNotNull(client);
        }
    }

    // ───────── GRAPH_ENDPOINT ─────────

    @Nested
    @DisplayName("constants")
    class Constants {

        @Test
        @DisplayName("GRAPH_ENDPOINT points to Microsoft Graph v1.0")
        void graphEndpoint() throws Exception {
            Field field = OutlookClient.class.getDeclaredField("GRAPH_ENDPOINT");
            field.setAccessible(true);
            assertEquals("https://graph.microsoft.com/v1.0", field.get(null));
        }

        @Test
        @DisplayName("REQUEST_TIMEOUT is 60 seconds")
        void requestTimeout() throws Exception {
            Field field = OutlookClient.class.getDeclaredField("REQUEST_TIMEOUT");
            field.setAccessible(true);
            assertEquals(Duration.ofSeconds(60), field.get(null));
        }
    }

    // ───────── Token Format Validation ─────────

    @Nested
    @DisplayName("isValidTokenFormat")
    class TokenFormatValidation {

        private boolean invokeIsValidTokenFormat(String token) throws Exception {
            OutlookClient client = new OutlookClient(configManager);
            Method method = OutlookClient.class.getDeclaredMethod("isValidTokenFormat", String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(client, token);
        }

        @Test
        @DisplayName("rejects null token")
        void rejectsNull() throws Exception {
            assertFalse(invokeIsValidTokenFormat(null));
        }

        @Test
        @DisplayName("rejects empty token")
        void rejectsEmpty() throws Exception {
            assertFalse(invokeIsValidTokenFormat(""));
        }

        @Test
        @DisplayName("rejects whitespace-only token")
        void rejectsWhitespace() throws Exception {
            assertFalse(invokeIsValidTokenFormat("   "));
        }

        @Test
        @DisplayName("rejects very short token")
        void rejectsShortToken() throws Exception {
            assertFalse(invokeIsValidTokenFormat("abc"));
        }

        @Test
        @DisplayName("accepts standard JWT token (3 dot-separated parts)")
        void acceptsJwtToken() throws Exception {
            assertTrue(invokeIsValidTokenFormat("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signature123"));
        }

        @Test
        @DisplayName("accepts MSAL compact/opaque token (no dots, starts with Ew)")
        void acceptsMsalCompactToken() throws Exception {
            // Simulating the format observed in real MSAL v2 tokens
            assertTrue(invokeIsValidTokenFormat("EwBIBMl6BAAUu4TQbLz1234567890abcdefghijklmnop"));
        }

        @Test
        @DisplayName("accepts long opaque token without dots")
        void acceptsLongOpaqueToken() throws Exception {
            String token = "a".repeat(1000);
            assertTrue(invokeIsValidTokenFormat(token));
        }

        @Test
        @DisplayName("rejects JWT with empty parts")
        void rejectsJwtWithEmptyParts() throws Exception {
            assertFalse(invokeIsValidTokenFormat("header..signature"));
        }
    }

    // ───────── attemptSilentTokenRefresh ─────────

    @Nested
    @DisplayName("attemptSilentTokenRefresh")
    class AttemptSilentTokenRefresh {

        @Test
        @DisplayName("method exists and is public")
        void methodExists() throws Exception {
            Method method = OutlookClient.class.getDeclaredMethod("attemptSilentTokenRefresh");
            assertNotNull(method);
            assertTrue(java.lang.reflect.Modifier.isPublic(method.getModifiers()));
        }

        @Test
        @DisplayName("returns false when no tokens or caches available")
        void returnsFalseWithNoConfig() {
            OutlookClient client = new OutlookClient(configManager);
            // With a fresh config (no MSAL cache, no Okta cache, no refresh token),
            // silent refresh should return false without crashing
            assertFalse(client.attemptSilentTokenRefresh());
        }

        @Test
        @DisplayName("does not throw when called with empty configuration")
        void doesNotThrowWithEmptyConfig() {
            OutlookClient client = new OutlookClient(configManager);
            assertDoesNotThrow(() -> client.attemptSilentTokenRefresh());
        }
    }
}
