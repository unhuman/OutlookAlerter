package com.unhuman.outlookalerter.core

import com.unhuman.outlookalerter.util.LogManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for OutlookClient focusing on testable non-HTTP components:
 * AuthenticationCancelledException and token validation constants.
 *
 * NOTE: Methods like getUpcomingEventsUsingCalendarView(), hasValidToken(),
 * executeRequestWithRetry() etc. make real HTTP calls to Microsoft Graph API
 * and are not suitable for unit testing without a mock HTTP server.
 */
class OutlookClientTest {

    private Path tempDir
    private ConfigManager configManager

    @BeforeEach
    void setup() {
        LogManager.getInstance()
        tempDir = Files.createTempDirectory("outlookclient-test")
        String configPath = new File(tempDir.toFile(), "test-config.properties").absolutePath
        configManager = new ConfigManager(configPath)
        configManager.loadConfiguration()
    }

    // ───────── AuthenticationCancelledException ─────────

    @Nested
    @DisplayName("AuthenticationCancelledException")
    class AuthCancelled {

        @Test
        @DisplayName("single-arg constructor sets message and default reason")
        void singleArgConstructor() {
            def ex = new OutlookClient.AuthenticationCancelledException("User cancelled")
            assertEquals("User cancelled", ex.message)
            assertEquals("user_cancelled", ex.reason)
        }

        @Test
        @DisplayName("two-arg constructor sets message and custom reason")
        void twoArgConstructor() {
            def ex = new OutlookClient.AuthenticationCancelledException("Validation failed", "validation_exhausted")
            assertEquals("Validation failed", ex.message)
            assertEquals("validation_exhausted", ex.reason)
        }

        @Test
        @DisplayName("is a RuntimeException")
        void isRuntimeException() {
            def ex = new OutlookClient.AuthenticationCancelledException("test")
            assertTrue(ex instanceof RuntimeException)
        }

        @Test
        @DisplayName("toString includes message and reason")
        void toStringFormat() {
            def ex = new OutlookClient.AuthenticationCancelledException("auth failed", "window_closed")
            String str = ex.toString()
            assertTrue(str.contains("auth failed"))
            assertTrue(str.contains("window_closed"))
        }

        @Test
        @DisplayName("can be caught as RuntimeException")
        void catchable() {
            assertThrows(RuntimeException.class, {
                throw new OutlookClient.AuthenticationCancelledException("cancelled")
            })
        }
    }

    // ───────── Token Validation Constants ─────────

    @Nested
    @DisplayName("token validation constants")
    class TokenConstants {

        @Test
        @DisplayName("all constants are non-null unique strings")
        void constantsAreUnique() {
            Set<String> constants = [
                OutlookClient.TOKEN_VALID_NO_ACTION,
                OutlookClient.TOKEN_VALID_AFTER_SERVER_VALIDATION,
                OutlookClient.TOKEN_REFRESHED,
                OutlookClient.TOKEN_NEW_AUTHENTICATION
            ] as Set

            assertEquals(4, constants.size(), "All token validation constants should be unique")
            constants.each { assertNotNull(it) }
        }

        @Test
        @DisplayName("TOKEN_VALID_NO_ACTION is default result")
        void defaultResult() {
            // A fresh OutlookClient should have TOKEN_VALID_NO_ACTION
            OutlookClient client = new OutlookClient(configManager)
            assertEquals(OutlookClient.TOKEN_VALID_NO_ACTION, client.lastTokenValidationResult)
        }
    }

    // ───────── Constructor ─────────

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("creates client without UI reference")
        void noUiConstructor() {
            OutlookClient client = new OutlookClient(configManager)
            assertNotNull(client)
        }

        @Test
        @DisplayName("creates client with null UI reference")
        void nullUiConstructor() {
            OutlookClient client = new OutlookClient(configManager, null)
            assertNotNull(client)
        }
    }

    // ───────── GRAPH_ENDPOINT ─────────

    @Nested
    @DisplayName("constants")
    class Constants {

        @Test
        @DisplayName("GRAPH_ENDPOINT points to Microsoft Graph v1.0")
        void graphEndpoint() {
            assertEquals("https://graph.microsoft.com/v1.0", OutlookClient.GRAPH_ENDPOINT)
        }

        @Test
        @DisplayName("REQUEST_TIMEOUT is 60 seconds")
        void requestTimeout() {
            assertEquals(java.time.Duration.ofSeconds(60), OutlookClient.REQUEST_TIMEOUT)
        }
    }
}
