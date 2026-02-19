package com.unhuman.outlookalerter.core

import com.unhuman.outlookalerter.util.LogManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.function.Executable

import java.nio.file.Files

import static org.junit.jupiter.api.Assertions.*

class SingleInstanceManagerTest {

    private SingleInstanceManager manager
    private String originalHome
    private File tempDir

    @BeforeEach
    void setup() {
        LogManager.getInstance()
        // Redirect user.home so lock file goes to a temp directory
        originalHome = System.getProperty("user.home")
        tempDir = Files.createTempDirectory("sim-test").toFile()
        System.setProperty("user.home", tempDir.absolutePath)
        manager = new SingleInstanceManager()
    }

    @AfterEach
    void cleanup() {
        manager.releaseLock()
        System.setProperty("user.home", originalHome)
        tempDir.deleteDir()
    }

    // ───────── Lock Acquisition ─────────

    @Nested
    @DisplayName("tryAcquireLock()")
    class AcquireLock {

        @Test
        @DisplayName("first call acquires lock successfully")
        void firstAcquire() {
            assertTrue(manager.tryAcquireLock())
        }

        @Test
        @DisplayName("second call on same manager returns true (idempotent)")
        void idempotent() {
            assertTrue(manager.tryAcquireLock())
            assertTrue(manager.tryAcquireLock())
        }

        @Test
        @DisplayName("second manager cannot acquire lock while first holds it")
        void exclusiveLock() {
            assertTrue(manager.tryAcquireLock())

            SingleInstanceManager second = new SingleInstanceManager()
            assertFalse(second.tryAcquireLock())
        }
    }

    // ───────── Lock Release ─────────

    @Nested
    @DisplayName("releaseLock()")
    class ReleaseLock {

        @Test
        @DisplayName("release allows another manager to acquire")
        void releaseAndReacquire() {
            assertTrue(manager.tryAcquireLock())
            manager.releaseLock()

            SingleInstanceManager second = new SingleInstanceManager()
            try {
                assertTrue(second.tryAcquireLock())
            } finally {
                second.releaseLock()
            }
        }

        @Test
        @DisplayName("release without acquire does not throw")
        void releaseWithoutAcquire() {
            assertDoesNotThrow({ manager.releaseLock() } as Executable)
        }

        @Test
        @DisplayName("double release does not throw")
        void doubleRelease() {
            manager.tryAcquireLock()
            manager.releaseLock()
            assertDoesNotThrow({ manager.releaseLock() } as Executable)
        }
    }
}
