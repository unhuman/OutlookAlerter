package com.unhuman.outlookalerter.util;

import com.unhuman.outlookalerter.util.MacWindowElevator.AlertWindowKind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MacWindowElevator's pure logic. These run in the default headless build.
 * The native path is covered by MacWindowElevatorDisplayTest (requires -Dheadless=false).
 */
class MacWindowElevatorTest {

    // ───────── Title classification ─────────

    @Nested
    @DisplayName("classifyTitle()")
    class ClassifyTitleTests {

        @Test
        @DisplayName("flash, banner and dialog titles map to their kinds")
        void knownTitles() {
            assertEquals(AlertWindowKind.FLASH, MacWindowElevator.classifyTitle(MacWindowElevator.FLASH_WINDOW_TITLE));
            assertEquals(AlertWindowKind.BANNER, MacWindowElevator.classifyTitle(MacWindowElevator.BANNER_WINDOW_TITLE));
            assertEquals(AlertWindowKind.DIALOG, MacWindowElevator.classifyTitle(MacWindowElevator.DIALOG_WINDOW_TITLE));
        }

        @Test
        @DisplayName("other windows (main window, settings, null title) are not alert windows")
        void unknownTitles() {
            assertNull(MacWindowElevator.classifyTitle(null));
            assertNull(MacWindowElevator.classifyTitle(""));
            assertNull(MacWindowElevator.classifyTitle("Outlook Alerter"));
            assertNull(MacWindowElevator.classifyTitle("Settings"));
        }

        @Test
        @DisplayName("titles are distinct so kinds cannot be confused")
        void titlesDistinct() {
            assertNotEquals(MacWindowElevator.FLASH_WINDOW_TITLE, MacWindowElevator.DIALOG_WINDOW_TITLE);
            assertNotEquals(MacWindowElevator.FLASH_WINDOW_TITLE, MacWindowElevator.BANNER_WINDOW_TITLE);
            assertNotEquals(MacWindowElevator.BANNER_WINDOW_TITLE, MacWindowElevator.DIALOG_WINDOW_TITLE);
        }
    }

    // ───────── Window levels ─────────

    @Nested
    @DisplayName("Window levels")
    class LevelTests {

        @Test
        @DisplayName("z-order is flash < banner < dialog")
        void zOrder() {
            assertTrue(AlertWindowKind.FLASH.level < AlertWindowKind.BANNER.level);
            assertTrue(AlertWindowKind.BANNER.level < AlertWindowKind.DIALOG.level);
        }

        @Test
        @DisplayName("all levels are above floating (3), Dock (20) and main menu (24)")
        void aboveSystemLevels() {
            for (AlertWindowKind kind : AlertWindowKind.values()) {
                assertTrue(kind.level > 24, kind + " level must be above the main menu level");
            }
        }
    }

    // ───────── Collection behavior ─────────

    @Nested
    @DisplayName("elevatedCollectionBehavior()")
    class CollectionBehaviorTests {

        @Test
        @DisplayName("adds can-join-all-spaces and full-screen-auxiliary")
        void addsRequiredBits() {
            long result = MacWindowElevator.elevatedCollectionBehavior(0L);
            assertNotEquals(0L, result & MacWindowElevator.CAN_JOIN_ALL_SPACES);
            assertNotEquals(0L, result & MacWindowElevator.FULL_SCREEN_AUXILIARY);
        }

        @Test
        @DisplayName("clears mutually exclusive bits")
        void clearsExclusiveBits() {
            long current = MacWindowElevator.MOVE_TO_ACTIVE_SPACE
                | MacWindowElevator.FULL_SCREEN_PRIMARY
                | MacWindowElevator.FULL_SCREEN_NONE;
            long result = MacWindowElevator.elevatedCollectionBehavior(current);
            assertEquals(0L, result & MacWindowElevator.MOVE_TO_ACTIVE_SPACE);
            assertEquals(0L, result & MacWindowElevator.FULL_SCREEN_PRIMARY);
            assertEquals(0L, result & MacWindowElevator.FULL_SCREEN_NONE);
        }

        @Test
        @DisplayName("preserves unrelated bits")
        void preservesOtherBits() {
            long unrelated = 1L << 4; // NSWindowCollectionBehaviorStationary
            long result = MacWindowElevator.elevatedCollectionBehavior(unrelated);
            assertNotEquals(0L, result & unrelated);
        }

        @Test
        @DisplayName("is idempotent")
        void idempotent() {
            long once = MacWindowElevator.elevatedCollectionBehavior(MacWindowElevator.MOVE_TO_ACTIVE_SPACE);
            assertEquals(once, MacWindowElevator.elevatedCollectionBehavior(once));
        }
    }

    // ───────── Visibility ─────────

    @Nested
    @DisplayName("Visibility reporting")
    class VisibilityTests {

        @Test
        @DisplayName("visible only when on active Space and occlusion-visible bit set")
        void isUserVisible() {
            long visible = MacWindowElevator.OCCLUSION_STATE_VISIBLE;
            assertTrue(MacWindowElevator.isUserVisible(true, visible));
            assertFalse(MacWindowElevator.isUserVisible(false, visible), "other Space is not visible");
            assertFalse(MacWindowElevator.isUserVisible(true, 0L), "fully occluded is not visible");
        }

        @Test
        @DisplayName("report flags ALERT NOT VISIBLE when no window is visible")
        void reportNotVisible() {
            String report = MacWindowElevator.formatVisibilityReport("flash", "'Standup' @ 11:00 AM",
                false, "Slack", 2, 0, "flash(level=3 activeSpace=false unoccluded=false)");
            assertTrue(report.contains("ALERT NOT VISIBLE"), report);
            assertTrue(report.contains("appActive=false"), report);
            assertTrue(report.contains("frontmostApp=Slack"), report);
            assertTrue(report.contains("'Standup' @ 11:00 AM"), "report must name the alerted events: " + report);
        }

        @Test
        @DisplayName("report shows visible count when some windows are visible")
        void reportVisible() {
            String report = MacWindowElevator.formatVisibilityReport("join dialog J1", "'Standup' @ 11:00 AM",
                true, "OutlookAlerter", 2, 1, "");
            assertTrue(report.contains("visible (1/2"), report);
            assertTrue(report.contains("join dialog J1"), report);
            assertFalse(report.contains("NOT VISIBLE"), report);
        }

        @Test
        @DisplayName("report flags when no alert windows exist")
        void reportNoWindows() {
            String report = MacWindowElevator.formatVisibilityReport("flash", "(no events)",
                true, "OutlookAlerter", 0, 0, "");
            assertTrue(report.contains("NO ALERT WINDOWS ON SCREEN"), report);
        }
    }

    // ───────── Headless safety ─────────

    @Nested
    @DisplayName("Headless safety")
    @EnabledIfSystemProperty(named = "java.awt.headless", matches = "true")
    class HeadlessTests {

        @Test
        @DisplayName("unavailable in headless mode")
        void unavailableHeadless() {
            assertFalse(MacWindowElevator.isAvailable());
        }

        @Test
        @DisplayName("public entry points are no-ops in headless mode")
        void noOpsHeadless() {
            assertDoesNotThrow(() -> MacWindowElevator.elevateAlertWindows(true));
            assertDoesNotThrow(() -> MacWindowElevator.reportAlertWindowVisibility("flash", "(no events)", 0));
        }
    }
}
