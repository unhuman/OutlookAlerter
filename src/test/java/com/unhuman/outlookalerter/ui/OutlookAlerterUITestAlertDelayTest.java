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
    @DisplayName("offers No Delay, 5s, 10s and 30s")
    void delayChoices() {
        int[] delays = OutlookAlerterUI.TEST_ALERT_DELAYS_SECONDS;
        assertArrayEquals(new int[]{0, 5, 10, 30}, delays);
        assertEquals(0, delays[0], "first choice must fire immediately");
        for (int i = 1; i < delays.length; i++) {
            assertTrue(delays[i] > delays[i - 1], "delays must increase");
        }
        assertTrue(delays[delays.length - 1] >= 10, "longest delay must allow time to arrange the desktop");
    }

    @Test
    @DisplayName("dropdown labels are self-describing")
    void delayLabels() {
        assertEquals("No Delay", OutlookAlerterUI.testAlertDelayLabel(0));
        assertEquals("5s Delay", OutlookAlerterUI.testAlertDelayLabel(5));
        assertEquals("10s Delay", OutlookAlerterUI.testAlertDelayLabel(10));
        assertEquals("30s Delay", OutlookAlerterUI.testAlertDelayLabel(30));
    }

    @Test
    @DisplayName("saved delay selects its dropdown entry")
    void savedDelaySelectsEntry() {
        int[] delays = OutlookAlerterUI.TEST_ALERT_DELAYS_SECONDS;
        for (int i = 0; i < delays.length; i++) {
            assertEquals(i, OutlookAlerterUI.testAlertDelayIndex(delays[i]));
        }
    }

    @Test
    @DisplayName("unknown saved delay falls back to 5s")
    void unknownSavedDelayFallsBack() {
        int fallback = OutlookAlerterUI.testAlertDelayIndex(7);
        assertEquals(5, OutlookAlerterUI.TEST_ALERT_DELAYS_SECONDS[fallback]);
        assertEquals(fallback, OutlookAlerterUI.testAlertDelayIndex(-1));
    }

    @Test
    @DisplayName("countdown status shows remaining seconds")
    void countdownStatus() {
        String status = OutlookAlerterUI.testAlertCountdownStatus(7);
        assertTrue(status.startsWith("Status: Test alert in 7s"), status);
    }
}
