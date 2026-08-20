package com.unhuman.outlookalerter.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MacJava2DWakeGuardTest {

    @Test
    @DisplayName("registerWindow with null does not throw")
    void registerNullWindow() {
        MacJava2DWakeGuard guard = new MacJava2DWakeGuard();
        assertDoesNotThrow(() -> guard.registerWindow(null));
    }

    @Test
    @DisplayName("guardActive is false before any wake event")
    void guardInactiveInitially() {
        MacJava2DWakeGuard guard = new MacJava2DWakeGuard();
        assertFalse(guard.isGuardActive());
    }

    @Test
    @DisplayName("activateGuard does not throw in headless mode (no visible windows)")
    void activateGuardHeadless() {
        MacJava2DWakeGuard guard = new MacJava2DWakeGuard();
        assertDoesNotThrow(() -> guard.activateGuard());
    }

    @Test
    @DisplayName("GUARD_DURATION_MS is positive and at least 2 seconds")
    void guardDurationSanity() {
        assertTrue(MacJava2DWakeGuard.GUARD_DURATION_MS >= 2000,
            "Guard must be at least 2 seconds to cover CVDisplayLink stabilization");
    }
}
