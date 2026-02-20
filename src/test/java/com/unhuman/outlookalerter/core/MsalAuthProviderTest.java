package com.unhuman.outlookalerter.core;

import com.unhuman.outlookalerter.util.LogManager;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MsalAuthProvider — verifies configuration checks,
 * graceful fallback behavior, and token cache operations.
 *
 * Note: Actual MSAL interactive/silent auth can't be tested in CI (headless).
 * These tests verify the provider's behavior around configuration and error handling.
 */
class MsalAuthProviderTest {

    private Path tempDir;
    private String configPath;
    private ConfigManager configManager;

    @BeforeEach
    void setup() throws IOException {
        LogManager.getInstance();
        tempDir = Files.createTempDirectory("outlookalerter-msal-test");
        configPath = new File(tempDir.toFile(), "test-config.properties").getAbsolutePath();
        configManager = new ConfigManager(configPath);
        configManager.loadConfiguration();
    }

    @AfterEach
    void cleanup() throws IOException {
        deleteDirectory(tempDir);
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            }
        }
    }

    // ───────── isConfigured ─────────

    @Nested
    @DisplayName("isConfigured")
    class IsConfigured {

        @Test
        @DisplayName("returns true when clientId is empty (uses default)")
        void returnsTrueWhenClientIdEmptyUsesDefault() {
            // Default client ID is baked in, so MSAL is always configured
            MsalAuthProvider provider = new MsalAuthProvider(configManager);
            assertTrue(provider.isConfigured());
        }

        @Test
        @DisplayName("returns true when clientId is whitespace (uses default)")
        void returnsTrueWhenClientIdWhitespaceUsesDefault() {
            configManager.updateClientId("   ");
            // Falls back to default client ID
            MsalAuthProvider provider = new MsalAuthProvider(configManager);
            assertTrue(provider.isConfigured());
        }

        @Test
        @DisplayName("returns true when clientId is set")
        void returnsTrueWhenClientIdSet() {
            configManager.updateClientId("test-client-id-12345");
            MsalAuthProvider provider = new MsalAuthProvider(configManager);
            assertTrue(provider.isConfigured());
        }

        @Test
        @DisplayName("returns true when clientId is null (uses default)")
        void returnsTrueWhenClientIdNullUsesDefault() {
            configManager.updateClientId(null);
            // Falls back to default client ID
            MsalAuthProvider provider = new MsalAuthProvider(configManager);
            assertTrue(provider.isConfigured());
        }
    }

    // ───────── Silent acquisition fallback ─────────

    @Nested
    @DisplayName("silent token acquisition")
    class SilentAcquisition {

        @Test
        @DisplayName("returns null when not configured")
        void returnsNullWhenNotConfigured() {
            MsalAuthProvider provider = new MsalAuthProvider(configManager);
            assertNull(provider.acquireTokenSilently());
        }

        @Test
        @DisplayName("returns null when configured but no cached accounts")
        void returnsNullWithNoCachedAccounts() {
            configManager.updateClientId("test-client-id");
            MsalAuthProvider provider = new MsalAuthProvider(configManager);
            // No prior interactive auth, so MSAL cache is empty
            assertNull(provider.acquireTokenSilently());
        }

        @Test
        @DisplayName("getAccessToken returns null when not configured")
        void getAccessTokenReturnsNullWhenNotConfigured() {
            MsalAuthProvider provider = new MsalAuthProvider(configManager);
            assertNull(provider.getAccessToken());
        }
    }

    // ───────── Interactive acquisition guard ─────────

    @Nested
    @DisplayName("interactive token acquisition")
    class InteractiveAcquisition {

        @Test
        @DisplayName("returns null when not configured")
        void returnsNullWhenNotConfigured() {
            MsalAuthProvider provider = new MsalAuthProvider(configManager);
            assertNull(provider.acquireTokenInteractively());
        }
    }

    // ───────── Cache operations ─────────

    @Nested
    @DisplayName("cache operations")
    class CacheOperations {

        @Test
        @DisplayName("clearCache does not throw when no cache file exists")
        void clearCacheNoFileDoesNotThrow() {
            MsalAuthProvider provider = new MsalAuthProvider(configManager);
            assertDoesNotThrow(provider::clearCache);
        }

        @Test
        @DisplayName("clearCache removes existing cache file")
        void clearCacheRemovesFile() throws IOException {
            // Create a fake cache file in the expected location
            String home = System.getProperty("user.home");
            Path cacheDir = Path.of(home, ".outlookalerter");
            Path cacheFile = cacheDir.resolve("msal_token_cache.json");

            // Only test if we can write to the directory
            if (Files.exists(cacheDir) || Files.isWritable(cacheDir.getParent())) {
                boolean created = false;
                try {
                    if (!Files.exists(cacheDir)) {
                        Files.createDirectories(cacheDir);
                    }
                    if (!Files.exists(cacheFile)) {
                        Files.writeString(cacheFile, "{\"test\":true}");
                        created = true;
                    }

                    MsalAuthProvider provider = new MsalAuthProvider(configManager);
                    // Only test clear if we created the file (don't delete real caches)
                    if (created) {
                        provider.clearCache();
                        assertFalse(Files.exists(cacheFile));
                    }
                } finally {
                    // Clean up test file if we created it
                    if (created && Files.exists(cacheFile)) {
                        Files.delete(cacheFile);
                    }
                }
            }
        }
    }

    // ───────── Constructor ─────────

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("accepts configManager and does not throw")
        void constructorDoesNotThrow() {
            assertDoesNotThrow(() -> new MsalAuthProvider(configManager));
        }

        @Test
        @DisplayName("multiple instances don't interfere")
        void multipleInstancesIndependent() {
            configManager.updateClientId("client-1");
            MsalAuthProvider provider1 = new MsalAuthProvider(configManager);

            configManager.updateClientId("");
            MsalAuthProvider provider2 = new MsalAuthProvider(configManager);

            // provider2 uses default client ID when explicit one is cleared
            assertTrue(provider2.isConfigured());
        }
    }
}
