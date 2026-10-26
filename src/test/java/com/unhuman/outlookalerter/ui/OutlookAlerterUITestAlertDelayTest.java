package com.unhuman.outlookalerter.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless tests for the Test Alerts delay options.
 * (OutlookAlerterUIAlertTest is excluded from the default build; these run in it.)
 */
class OutlookAlerterUITestAlertDelayTest {

    @Test
    @DisplayName("offers an immediate option first, then increasing delays")
    void delayChoices() {
        int[] delays = OutlookAlerterUI.TEST_ALERT_DELAYS_SECONDS;
        assertEquals(0, delays[0], "first choice must fire immediately");
        for (int i = 1; i < delays.length; i++) {
            assertTrue(delays[i] > delays[i - 1], "delays must increase");
        }
        assertTrue(delays[delays.length - 1] >= 10, "longest delay must allow time to arrange the desktop");
    }

    @Test
    @DisplayName("menu labels describe each choice")
    void menuLabels() {
        assertEquals("Test now", OutlookAlerterUI.testAlertMenuLabel(0));
        assertEquals("Test in 10 seconds", OutlookAlerterUI.testAlertMenuLabel(10));
    }

    @Test
    @DisplayName("countdown status shows remaining seconds")
    void countdownStatus() {
        String status = OutlookAlerterUI.testAlertCountdownStatus(7);
        assertTrue(status.startsWith("Status: Test alert in 7s"), status);
    }
}
