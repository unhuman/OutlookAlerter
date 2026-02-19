package com.unhuman.outlookalerter.core;

import com.unhuman.outlookalerter.util.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {

    private Path tempDir;
    private String configPath;
    private ConfigManager configManager;

    @BeforeEach
    void setup() throws IOException {
        // Ensure LogManager is initialized
        LogManager.getInstance();

        tempDir = Files.createTempDirectory("outlookalerter-test");
        configPath = new File(tempDir.toFile(), "test-config.properties").getAbsolutePath();
        configManager = new ConfigManager(configPath);
    }

    @AfterEach
    void cleanup() throws IOException {
        // Clean up temp files
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

    // ───────── Singleton ─────────

    @Nested
    @DisplayName("singleton")
    class SingletonBehavior {

        @Test
        @DisplayName("constructor sets singleton instance")
        void constructorSetsSingleton() {
            assertSame(configManager, ConfigManager.getInstance());
        }

        @Test
        @DisplayName("new constructor replaces singleton")
        void newConstructorReplacesSingleton() {
            String otherPath = new File(tempDir.toFile(), "other-config.properties").getAbsolutePath();
            ConfigManager other = new ConfigManager(otherPath);
            assertSame(other, ConfigManager.getInstance());
        }
    }

    // ───────── Default Configuration ─────────

    @Nested
    @DisplayName("default configuration")
    class DefaultConfig {

        @Test
        @DisplayName("loadConfiguration creates default config file if none exists")
        void createsDefault() {
            assertFalse(new File(configPath).exists());
            configManager.loadConfiguration();
            assertTrue(new File(configPath).exists());
        }

        @Test
        @DisplayName("defaults have correct values")
        void defaultValues() {
            configManager.loadConfiguration();

            assertEquals(1, configManager.getAlertMinutes());
            assertFalse(configManager.getDefaultIgnoreCertValidation());
            assertFalse(configManager.getIgnoreCertValidation());
            assertEquals("#800000", configManager.getFlashColor());
            assertEquals("#ffffff", configManager.getFlashTextColor());
            assertEquals(1.0d, configManager.getFlashOpacity(), 0.001);
            assertEquals(5, configManager.getFlashDurationSeconds());
            assertEquals(240, configManager.getResyncIntervalMinutes());
            assertEquals(5, configManager.getAlertBeepCount());
            assertFalse(configManager.getAlertBeepAfterFlash());
            assertEquals("common", configManager.getTenantId());
        }

        @Test
        @DisplayName("signInUrl returns default Graph URL when empty")
        void defaultSignInUrl() {
            configManager.loadConfiguration();
            // signInUrl defaults to SimpleTokenDialog.DEFAULT_GRAPH_URL
            assertNotNull(configManager.getSignInUrl());
            assertTrue(configManager.getSignInUrl().contains("microsoft.com"));
        }
    }

    // ───────── Load/Save Roundtrip ─────────

    @Nested
    @DisplayName("load/save roundtrip")
    class LoadSaveRoundtrip {

        @Test
        @DisplayName("saved configuration is loadable")
        void saveAndReload() {
            configManager.loadConfiguration();
            configManager.updateAlertMinutes(3);
            configManager.updateFlashColor("#FF0000");
            configManager.updateFlashDurationSeconds(10);
            configManager.updateResyncIntervalMinutes(60);

            // Create new ConfigManager pointing at same file
            ConfigManager reloaded = new ConfigManager(configPath);
            reloaded.loadConfiguration();

            assertEquals(3, reloaded.getAlertMinutes());
            assertEquals("#FF0000", reloaded.getFlashColor());
            assertEquals(10, reloaded.getFlashDurationSeconds());
            assertEquals(60, reloaded.getResyncIntervalMinutes());
        }

        @Test
        @DisplayName("updateTokens persists tokens and cert setting")
        void updateTokens() {
            configManager.loadConfiguration();
            configManager.updateTokens("access123", "refresh456", true);

            ConfigManager reloaded = new ConfigManager(configPath);
            reloaded.loadConfiguration();

            assertEquals("access123", reloaded.getAccessToken());
            assertEquals("refresh456", reloaded.getRefreshToken());
            assertTrue(reloaded.getIgnoreCertValidation());
        }
    }

    // ───────── Individual Update Methods ─────────

    @Nested
    @DisplayName("update methods")
    class UpdateMethods {

        @BeforeEach
        void load() {
            configManager.loadConfiguration();
        }

        @Test
        @DisplayName("updatePreferredTimezone")
        void updateTimezone() {
            configManager.updatePreferredTimezone("America/New_York");
            assertEquals("America/New_York", configManager.getPreferredTimezone());
            // Verify persisted
            ConfigManager reloaded = new ConfigManager(configPath);
            reloaded.loadConfiguration();
            assertEquals("America/New_York", reloaded.getPreferredTimezone());
        }

        @Test
        @DisplayName("updateAlertMinutes")
        void updateAlertMinutes() {
            configManager.updateAlertMinutes(5);
            assertEquals(5, configManager.getAlertMinutes());
        }

        @Test
        @DisplayName("updateDefaultIgnoreCertValidation")
        void updateDefaultCert() {
            configManager.updateDefaultIgnoreCertValidation(true);
            assertTrue(configManager.getDefaultIgnoreCertValidation());
        }

        @Test
        @DisplayName("updateIgnoreCertValidation")
        void updateCert() {
            configManager.updateIgnoreCertValidation(true);
            assertTrue(configManager.getIgnoreCertValidation());
        }

        @Test
        @DisplayName("updateFlashDurationSeconds")
        void updateFlashDuration() {
            configManager.updateFlashDurationSeconds(15);
            assertEquals(15, configManager.getFlashDurationSeconds());
        }

        @Test
        @DisplayName("updateSignInUrl")
        void updateSignInUrl() {
            configManager.updateSignInUrl("https://okta.example.com");
            assertEquals("https://okta.example.com", configManager.getSignInUrl());
        }

        @Test
        @DisplayName("updateFlashColor")
        void updateFlashColor() {
            configManager.updateFlashColor("#00FF00");
            assertEquals("#00FF00", configManager.getFlashColor());
        }

        @Test
        @DisplayName("updateFlashTextColor")
        void updateFlashTextColor() {
            configManager.updateFlashTextColor("#000000");
            assertEquals("#000000", configManager.getFlashTextColor());
        }

        @Test
        @DisplayName("updateFlashOpacity")
        void updateFlashOpacity() {
            configManager.updateFlashOpacity(0.75d);
            assertEquals(0.75d, configManager.getFlashOpacity(), 0.001);
        }

        @Test
        @DisplayName("updateResyncIntervalMinutes")
        void updateResync() {
            configManager.updateResyncIntervalMinutes(120);
            assertEquals(120, configManager.getResyncIntervalMinutes());
        }

        @Test
        @DisplayName("updateAlertBeepCount")
        void updateBeepCount() {
            configManager.updateAlertBeepCount(10);
            assertEquals(10, configManager.getAlertBeepCount());
        }

        @Test
        @DisplayName("updateAlertBeepAfterFlash")
        void updateBeepAfterFlash() {
            configManager.updateAlertBeepAfterFlash(true);
            assertTrue(configManager.getAlertBeepAfterFlash());
        }
    }

    // ───────── Existing Config Loading ─────────

    @Nested
    @DisplayName("loading existing config")
    class LoadExisting {

        @Test
        @DisplayName("loads existing config file with custom values")
        void loadCustom() throws IOException {
            // Write a config file manually
            Properties props = new Properties();
            props.setProperty("alertMinutes", "7");
            props.setProperty("flashColor", "#ABCDEF");
            props.setProperty("resyncIntervalMinutes", "30");
            props.setProperty("flashDurationSeconds", "20");
            props.setProperty("flashOpacity", "0.5");
            props.setProperty("alertBeepCount", "3");
            props.setProperty("alertBeepAfterFlash", "true");
            props.setProperty("ignoreCertValidation", "true");
            props.setProperty("signInUrl", "https://custom.sso.com");

            try (FileOutputStream out = new FileOutputStream(configPath)) {
                props.store(out, "Test config");
            }

            configManager.loadConfiguration();

            assertEquals(7, configManager.getAlertMinutes());
            assertEquals("#ABCDEF", configManager.getFlashColor());
            assertEquals(30, configManager.getResyncIntervalMinutes());
            assertEquals(20, configManager.getFlashDurationSeconds());
            assertEquals(0.5d, configManager.getFlashOpacity(), 0.001);
            assertEquals(3, configManager.getAlertBeepCount());
            assertTrue(configManager.getAlertBeepAfterFlash());
            assertTrue(configManager.getIgnoreCertValidation());
            assertEquals("https://custom.sso.com", configManager.getSignInUrl());
        }

        @Test
        @DisplayName("handles malformed numeric values gracefully (uses defaults)")
        void malformedValues() throws IOException {
            Properties props = new Properties();
            props.setProperty("alertMinutes", "not-a-number");
            props.setProperty("flashOpacity", "bad-opacity");
            props.setProperty("flashDurationSeconds", "NaN");
            props.setProperty("resyncIntervalMinutes", "xyz");
            props.setProperty("alertBeepCount", "abc");

            try (FileOutputStream out = new FileOutputStream(configPath)) {
                props.store(out, "Bad config");
            }

            // Should not throw — uses defaults for bad values
            configManager.loadConfiguration();

            assertEquals(1, configManager.getAlertMinutes());  // default
            assertEquals(1.0d, configManager.getFlashOpacity(), 0.001);  // default
            assertEquals(5, configManager.getFlashDurationSeconds());  // default
            assertEquals(240, configManager.getResyncIntervalMinutes());  // default
            assertEquals(5, configManager.getAlertBeepCount());  // default
        }

        @Test
        @DisplayName("creates parent directories if they don't exist")
        void createsParentDirs() {
            String nestedPath = new File(tempDir.toFile(), "a/b/c/config.properties").getAbsolutePath();
            ConfigManager nested = new ConfigManager(nestedPath);
            nested.loadConfiguration();

            assertTrue(new File(nestedPath).exists());
        }
    }

    // ───────── Getters ─────────

    @Nested
    @DisplayName("getters")
    class Getters {

        @BeforeEach
        void load() {
            configManager.loadConfiguration();
        }

        @Test
        @DisplayName("OAuth getters return loaded values")
        void oauthGetters() {
            // Defaults should be empty strings or nulls
            assertNotNull(configManager.getTenantId());
            assertNotNull(configManager.getRedirectUri());
        }

        @Test
        @DisplayName("signInUrl getter returns default when empty")
        void signInUrlDefault() {
            // After loading defaults, signInUrl should be the default Graph URL
            String url = configManager.getSignInUrl();
            assertNotNull(url);
            assertFalse(url.trim().isEmpty());
        }
    }
}
