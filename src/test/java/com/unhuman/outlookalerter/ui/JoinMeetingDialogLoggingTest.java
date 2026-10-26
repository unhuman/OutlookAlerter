package com.unhuman.outlookalerter.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless-safe tests for JoinMeetingDialog's dismissal logging helpers.
 * (JoinMeetingDialogTest needs a display; these do not.)
 */
class JoinMeetingDialogLoggingTest {

    private final JPanel source = new JPanel();

    @Test
    @DisplayName("keyboard input is identified with the key name (e.g. Enter on the default button)")
    void describeKeyboardInput() {
        KeyEvent enter = new KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_ENTER, '\n');
        assertEquals(" via keyboard (" + KeyEvent.getKeyText(KeyEvent.VK_ENTER) + ")",
                JoinMeetingDialog.describeInput(enter));
    }

    @Test
    @DisplayName("mouse input is identified")
    void describeMouseInput() {
        MouseEvent click = new MouseEvent(source, MouseEvent.MOUSE_RELEASED, 0L, 0, 5, 5, 1, false);
        assertEquals(" via mouse", JoinMeetingDialog.describeInput(click));
    }

    @Test
    @DisplayName("no input event (timer / programmatic) adds nothing")
    void describeNoInput() {
        assertEquals("", JoinMeetingDialog.describeInput(null));
        assertEquals("", JoinMeetingDialog.describeInput(new ActionEvent(source, ActionEvent.ACTION_PERFORMED, "x")));
    }

    @Test
    @DisplayName("close log names session, outcome, trigger, elapsed time and events")
    void formatCloseLog() {
        String line = JoinMeetingDialog.formatCloseLog("J7", "DISMISSED", "Dismiss button via keyboard (Enter)",
                2400L, "'Oktagon Standup!' @ 11:00 AM, 'Benchmarking Coordination' @ 11:00 AM");
        assertEquals("JoinMeetingDialog[J7]: DISMISSED by Dismiss button via keyboard (Enter) after 2.4s"
                + " — events: 'Oktagon Standup!' @ 11:00 AM, 'Benchmarking Coordination' @ 11:00 AM", line);
    }

    @Test
    @DisplayName("missing trigger is reported as unknown rather than null")
    void formatCloseLogUnknownTrigger() {
        String line = JoinMeetingDialog.formatCloseLog("J1", "CLOSED (no interaction recorded)", null, 0L, "(no events)");
        assertTrue(line.contains("by unknown trigger"), line);
        assertFalse(line.contains("null"), line);
    }
}
