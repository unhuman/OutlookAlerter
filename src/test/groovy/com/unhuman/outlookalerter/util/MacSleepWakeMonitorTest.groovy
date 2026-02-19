package com.unhuman.outlookalerter.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.function.Executable

import static org.junit.jupiter.api.Assertions.*

class MacSleepWakeMonitorTest {

    private MacSleepWakeMonitor monitor

    @BeforeEach
    void setup() {
        monitor = MacSleepWakeMonitor.getInstance()
        monitor.stopMonitoring()
    }

    @AfterEach
    void teardown() {
        monitor.stopMonitoring()
    }

    // ───────── Singleton ─────────

    @Nested
    @DisplayName("singleton")
    class Singleton {

        @Test
        @DisplayName("getInstance returns same instance")
        void sameInstance() {
            assertSame(MacSleepWakeMonitor.getInstance(), MacSleepWakeMonitor.getInstance())
        }

        @Test
        @DisplayName("is never null")
        void notNull() {
            assertNotNull(MacSleepWakeMonitor.getInstance())
        }
    }

    // ───────── Monitoring Lifecycle ─────────

    @Nested
    @DisplayName("monitoring lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("startMonitoring does not throw")
        void startDoesNotThrow() {
            assertDoesNotThrow({ monitor.startMonitoring() } as Executable)
        }

        @Test
        @DisplayName("stopMonitoring does not throw when not monitoring")
        void stopWhenNotMonitoring() {
            assertDoesNotThrow({ monitor.stopMonitoring() } as Executable)
        }

        @Test
        @DisplayName("double start does not throw")
        void doubleStart() {
            monitor.startMonitoring()
            assertDoesNotThrow({ monitor.startMonitoring() } as Executable)
        }

        @Test
        @DisplayName("start after stop works")
        void startAfterStop() {
            monitor.startMonitoring()
            monitor.stopMonitoring()
            assertDoesNotThrow({ monitor.startMonitoring() } as Executable)
        }
    }

    // ───────── Wake Listeners ─────────

    @Nested
    @DisplayName("wake listeners")
    class WakeListeners {

        @Test
        @DisplayName("addWakeListener with null does not throw")
        void nullListener() {
            assertDoesNotThrow({ monitor.addWakeListener(null) } as Executable)
        }

        @Test
        @DisplayName("addWakeListener accepts a runnable")
        void addListener() {
            assertDoesNotThrow({
                monitor.addWakeListener({ /* no-op */ } as Runnable)
            } as Executable)
        }
    }

    // ───────── getTimeSinceWake ─────────

    @Nested
    @DisplayName("getTimeSinceWake()")
    class TimeSinceWake {

        @Test
        @DisplayName("returns non-negative value")
        void nonNegative() {
            assertTrue(monitor.getTimeSinceWake() >= 0)
        }
    }
}
