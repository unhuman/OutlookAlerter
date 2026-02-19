package com.unhuman.outlookalerter.core

import com.unhuman.outlookalerter.util.LogManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

class ConfigManagerTest {

    private Path tempDir
    private String configPath
    private ConfigManager configManager

    @BeforeEach
    void setup() {
        // Ensure LogManager is initialized
        LogManager.getInstance()

        tempDir = Files.createTempDirectory("outlookalerter-test")
        configPath = new File(tempDir.toFile(), "test-config.properties").absolutePath
        configManager = new ConfigManager(configPath)
    }

    @AfterEach
    void cleanup() {
        // Clean up temp files
        tempDir.toFile().deleteDir()
    }

    // ───────── Singleton ─────────

    @Nested
    @DisplayName("singleton")
    class SingletonBehavior {

        @Test
        @DisplayName("constructor sets singleton instance")
        void constructorSetsSingleton() {
            assertSame(configManager, ConfigManager.getInstance())
        }

        @Test
        @DisplayName("new constructor replaces singleton")
        void newConstructorReplacesSingleton() {
            String otherPath = new File(tempDir.toFile(), "other-config.properties").absolutePath
            ConfigManager other = new ConfigManager(otherPath)
            assertSame(other, ConfigManager.getInstance())
        }
    }

    // ───────── Default Configuration ─────────

    @Nested
    @DisplayName("default configuration")
    class DefaultConfig {

        @Test
        @DisplayName("loadConfiguration creates default config file if none exists")
        void createsDefault() {
            assertFalse(new File(configPath).exists())
            configManager.loadConfiguration()
            assertTrue(new File(configPath).exists())
        }

        @Test
        @DisplayName("defaults have correct values")
        void defaultValues() {
            configManager.loadConfiguration()

            assertEquals(1, configManager.alertMinutes)
            assertFalse(configManager.defaultIgnoreCertValidation)
            assertFalse(configManager.ignoreCertValidation)
            assertEquals("#800000", configManager.flashColor)
            assertEquals("#ffffff", configManager.flashTextColor)
            assertEquals(1.0d, configManager.flashOpacity, 0.001)
            assertEquals(5, configManager.flashDurationSeconds)
            assertEquals(240, configManager.resyncIntervalMinutes)
            assertEquals(5, configManager.alertBeepCount)
            assertFalse(configManager.alertBeepAfterFlash)
            assertEquals("common", configManager.tenantId)
        }

        @Test
        @DisplayName("signInUrl returns default Graph URL when empty")
        void defaultSignInUrl() {
            configManager.loadConfiguration()
            // signInUrl defaults to SimpleTokenDialog.DEFAULT_GRAPH_URL
            assertNotNull(configManager.signInUrl)
            assertTrue(configManager.signInUrl.contains("microsoft.com"))
        }
    }

    // ───────── Load/Save Roundtrip ─────────

    @Nested
    @DisplayName("load/save roundtrip")
    class LoadSaveRoundtrip {

        @Test
        @DisplayName("saved configuration is loadable")
        void saveAndReload() {
            configManager.loadConfiguration()
            configManager.updateAlertMinutes(3)
            configManager.updateFlashColor("#FF0000")
            configManager.updateFlashDurationSeconds(10)
            configManager.updateResyncIntervalMinutes(60)

            // Create new ConfigManager pointing at same file
            ConfigManager reloaded = new ConfigManager(configPath)
            reloaded.loadConfiguration()

            assertEquals(3, reloaded.alertMinutes)
            assertEquals("#FF0000", reloaded.flashColor)
            assertEquals(10, reloaded.flashDurationSeconds)
            assertEquals(60, reloaded.resyncIntervalMinutes)
        }

        @Test
        @DisplayName("updateTokens persists tokens and cert setting")
        void updateTokens() {
            configManager.loadConfiguration()
            configManager.updateTokens("access123", "refresh456", true)

            ConfigManager reloaded = new ConfigManager(configPath)
            reloaded.loadConfiguration()

            assertEquals("access123", reloaded.accessToken)
            assertEquals("refresh456", reloaded.refreshToken)
            assertTrue(reloaded.ignoreCertValidation)
        }
    }

    // ───────── Individual Update Methods ─────────

    @Nested
    @DisplayName("update methods")
    class UpdateMethods {

        @BeforeEach
        void load() {
            configManager.loadConfiguration()
        }

        @Test
        @DisplayName("updatePreferredTimezone")
        void updateTimezone() {
            configManager.updatePreferredTimezone("America/New_York")
            assertEquals("America/New_York", configManager.preferredTimezone)
            // Verify persisted
            ConfigManager reloaded = new ConfigManager(configPath)
            reloaded.loadConfiguration()
            assertEquals("America/New_York", reloaded.preferredTimezone)
        }

        @Test
        @DisplayName("updateAlertMinutes")
        void updateAlertMinutes() {
            configManager.updateAlertMinutes(5)
            assertEquals(5, configManager.alertMinutes)
        }

        @Test
        @DisplayName("updateDefaultIgnoreCertValidation")
        void updateDefaultCert() {
            configManager.updateDefaultIgnoreCertValidation(true)
            assertTrue(configManager.defaultIgnoreCertValidation)
        }

        @Test
        @DisplayName("updateIgnoreCertValidation")
        void updateCert() {
            configManager.updateIgnoreCertValidation(true)
            assertTrue(configManager.ignoreCertValidation)
        }

        @Test
        @DisplayName("updateFlashDurationSeconds")
        void updateFlashDuration() {
            configManager.updateFlashDurationSeconds(15)
            assertEquals(15, configManager.flashDurationSeconds)
        }

        @Test
        @DisplayName("updateSignInUrl")
        void updateSignInUrl() {
            configManager.updateSignInUrl("https://okta.example.com")
            assertEquals("https://okta.example.com", configManager.signInUrl)
        }

        @Test
        @DisplayName("updateFlashColor")
        void updateFlashColor() {
            configManager.updateFlashColor("#00FF00")
            assertEquals("#00FF00", configManager.flashColor)
        }

        @Test
        @DisplayName("updateFlashTextColor")
        void updateFlashTextColor() {
            configManager.updateFlashTextColor("#000000")
            assertEquals("#000000", configManager.flashTextColor)
        }

        @Test
        @DisplayName("updateFlashOpacity")
        void updateFlashOpacity() {
            configManager.updateFlashOpacity(0.75d)
            assertEquals(0.75d, configManager.flashOpacity, 0.001)
        }

        @Test
        @DisplayName("updateResyncIntervalMinutes")
        void updateResync() {
            configManager.updateResyncIntervalMinutes(120)
            assertEquals(120, configManager.resyncIntervalMinutes)
        }

        @Test
        @DisplayName("updateAlertBeepCount")
        void updateBeepCount() {
            configManager.updateAlertBeepCount(10)
            assertEquals(10, configManager.alertBeepCount)
        }

        @Test
        @DisplayName("updateAlertBeepAfterFlash")
        void updateBeepAfterFlash() {
            configManager.updateAlertBeepAfterFlash(true)
            assertTrue(configManager.alertBeepAfterFlash)
        }
    }

    // ───────── Existing Config Loading ─────────

    @Nested
    @DisplayName("loading existing config")
    class LoadExisting {

        @Test
        @DisplayName("loads existing config file with custom values")
        void loadCustom() {
            // Write a config file manually
            Properties props = new Properties()
            props.setProperty("alertMinutes", "7")
            props.setProperty("flashColor", "#ABCDEF")
            props.setProperty("resyncIntervalMinutes", "30")
            props.setProperty("flashDurationSeconds", "20")
            props.setProperty("flashOpacity", "0.5")
            props.setProperty("alertBeepCount", "3")
            props.setProperty("alertBeepAfterFlash", "true")
            props.setProperty("ignoreCertValidation", "true")
            props.setProperty("signInUrl", "https://custom.sso.com")

            new File(configPath).withOutputStream { out ->
                props.store(out, "Test config")
            }

            configManager.loadConfiguration()

            assertEquals(7, configManager.alertMinutes)
            assertEquals("#ABCDEF", configManager.flashColor)
            assertEquals(30, configManager.resyncIntervalMinutes)
            assertEquals(20, configManager.flashDurationSeconds)
            assertEquals(0.5d, configManager.flashOpacity, 0.001)
            assertEquals(3, configManager.alertBeepCount)
            assertTrue(configManager.alertBeepAfterFlash)
            assertTrue(configManager.ignoreCertValidation)
            assertEquals("https://custom.sso.com", configManager.signInUrl)
        }

        @Test
        @DisplayName("handles malformed numeric values gracefully (uses defaults)")
        void malformedValues() {
            Properties props = new Properties()
            props.setProperty("alertMinutes", "not-a-number")
            props.setProperty("flashOpacity", "bad-opacity")
            props.setProperty("flashDurationSeconds", "NaN")
            props.setProperty("resyncIntervalMinutes", "xyz")
            props.setProperty("alertBeepCount", "abc")

            new File(configPath).withOutputStream { out ->
                props.store(out, "Bad config")
            }

            // Should not throw — uses defaults for bad values
            configManager.loadConfiguration()

            assertEquals(1, configManager.alertMinutes)  // default
            assertEquals(1.0d, configManager.flashOpacity, 0.001)  // default
            assertEquals(5, configManager.flashDurationSeconds)  // default
            assertEquals(240, configManager.resyncIntervalMinutes)  // default
            assertEquals(5, configManager.alertBeepCount)  // default
        }

        @Test
        @DisplayName("creates parent directories if they don't exist")
        void createsParentDirs() {
            String nestedPath = new File(tempDir.toFile(), "a/b/c/config.properties").absolutePath
            ConfigManager nested = new ConfigManager(nestedPath)
            nested.loadConfiguration()

            assertTrue(new File(nestedPath).exists())
        }
    }

    // ───────── Getters ─────────

    @Nested
    @DisplayName("getters")
    class Getters {

        @BeforeEach
        void load() {
            configManager.loadConfiguration()
        }

        @Test
        @DisplayName("OAuth getters return loaded values")
        void oauthGetters() {
            // Defaults should be empty strings or nulls
            assertNotNull(configManager.tenantId)
            assertNotNull(configManager.redirectUri)
        }

        @Test
        @DisplayName("signInUrl getter returns default when empty")
        void signInUrlDefault() {
            // After loading defaults, signInUrl should be the default Graph URL
            String url = configManager.signInUrl
            assertNotNull(url)
            assertFalse(url.trim().isEmpty())
        }
    }
}
