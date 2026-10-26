package com.unhuman.outlookalerter.util;

import com.unhuman.outlookalerter.util.MacWindowElevator.AlertWindowKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the native elevation path against real NSWindows.
 *
 * Disruptive: briefly shows a small window above all apps. Skipped in the default headless
 * build; run explicitly with {@code mvn package -Dheadless=false}.
 */
@EnabledOnOs(OS.MAC)
@DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
class MacWindowElevatorDisplayTest {

    @Test
    void elevatesTitledAlertWindowToStatusLevelOnAllSpaces() throws Exception {
        assertTrue(MacWindowElevator.isAvailable(), "native elevation should be available on a macOS display");

        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame(MacWindowElevator.FLASH_WINDOW_TITLE);
            frame.setUndecorated(true);
            frame.setBounds(20, 60, 160, 90);
            frame.setAlwaysOnTop(true);
            frame.setVisible(true);
            frameRef.set(frame);
        });
        try {
            // Let AppKit create and title the NSWindow before elevating
            Thread.sleep(500);
            MacWindowElevator.elevateAlertWindows(true);

            List<long[]> states = MacWindowElevator.queryAlertWindowState(AlertWindowKind.FLASH);
            assertFalse(states.isEmpty(), "the titled flash window should be found among NSApp windows");
            for (long[] state : states) {
                assertEquals(AlertWindowKind.FLASH.level, state[0], "window level");
                assertNotEquals(0L, state[1] & MacWindowElevator.CAN_JOIN_ALL_SPACES, "joins all Spaces");
                assertNotEquals(0L, state[1] & MacWindowElevator.FULL_SCREEN_AUXILIARY, "allowed over full-screen apps");
            }

            // The visibility report must run without error against real windows
            assertDoesNotThrow(() -> MacWindowElevator.reportAlertWindowVisibility("display test", "(test)", 0));
            Thread.sleep(300);
        } finally {
            SwingUtilities.invokeAndWait(() -> frameRef.get().dispose());
        }
    }
}
