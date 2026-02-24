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
    @DisplayName("GRAPH_CLIENT_IDS")
    class GraphClientIds {

        @Test
        @DisplayName("contains exactly 3 client IDs")
        void containsThreeIds() {
            assertEquals(3, MsalAuthProvider.GRAPH_CLIENT_IDS.length);
        }

        @Test
        @DisplayName("first entry is Graph PowerShell SDK ID")
        void firstIsGraphPowerShell() {
            assertEquals("14d82eec-204b-4c2f-b7e8-296a70dab67e", MsalAuthProvider.GRAPH_CLIENT_IDS[0]);
        }

        @Test
        @DisplayName("second entry is Graph Explorer ID")
        void secondIsGraphExplorer() {
            assertEquals("de8bc8b5-d9f9-48b1-a8ad-b748da725064", MsalAuthProvider.GRAPH_CLIENT_IDS[1]);
        }

        @Test
        @DisplayName("last entry is Azure CLI (WELL_KNOWN_OFFICE_CLIENT_ID)")
        void lastIsAzureCli() {
            assertEquals(MsalAuthProvider.WELL_KNOWN_OFFICE_CLIENT_ID, MsalAuthProvider.GRAPH_CLIENT_IDS[2]);
        }

        @Test
        @DisplayName("WELL_KNOWN_OFFICE_CLIENT_ID is Azure CLI ID")
        void wellKnownOfficeClientId() {
            assertEquals("04b07795-8ddb-461a-bbee-02f9e1bf7b46", MsalAuthProvider.WELL_KNOWN_OFFICE_CLIENT_ID);
        }

        @Test
        @DisplayName("all IDs are non-null and non-empty UUID format")
        void allIdsAreValidUuids() {
            for (String id : MsalAuthProvider.GRAPH_CLIENT_IDS) {
                assertNotNull(id);
                assertFalse(id.isBlank());
                assertTrue(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                        "Client ID should be UUID format: " + id);
            }
        }

        @Test
        @DisplayName("no duplicate IDs in the list")
        void noDuplicates() {
            long distinct = java.util.Arrays.stream(MsalAuthProvider.GRAPH_CLIENT_IDS).distinct().count();
            assertEquals(MsalAuthProvider.GRAPH_CLIENT_IDS.length, distinct);
        }
    }

    // ───────── isConfigured ─────────

    @Nested
    @DisplayName("isConfigured")
    class IsConfigured {

        @Test
        @DisplayName("returns false when clientId is empty")
        void returnsFalseWhenClientIdEmpty() {
            // No default fallback - MSAL is not configured without explicit clientId
            MsalAuthProvider provider = new MsalAuthProvider(configManager);
            assertFalse(provider.isConfigured());
        }

        @Test
        @DisplayName("returns false when clientId is whitespace")
        void returnsFalseWhenClientIdWhitespace() {
            configManager.updateClientId("   ");
            // No default fallback
            MsalAuthProvider provider = new MsalAuthProvider(configManager);
            assertFalse(provider.isConfigured());
        }

        @Test
        @DisplayName("returns true when clientId is set")
        void returnsTrueWhenClientIdSet() {
            configManager.updateClientId("test-client-id-12345");
            MsalAuthProvider provider = new MsalAuthProvider(configManager);
            assertTrue(provider.isConfigured());
        }

        @Test
        @DisplayName("returns false when clientId is null")
        void returnsFalseWhenClientIdNull() {
            configManager.updateClientId(null);
            // No default fallback
            MsalAuthProvider provider = new MsalAuthProvider(configManager);
            assertFalse(provider.isConfigured());
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
            assertTrue(provider1.isConfigured());

            configManager.updateClientId("");
            MsalAuthProvider provider2 = new MsalAuthProvider(configManager);

            // No default fallback - MSAL is not configured when clientId cleared
            assertFalse(provider2.isConfigured());
        }
    }
}
