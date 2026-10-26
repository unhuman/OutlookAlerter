package com.unhuman.outlookalerter.ui;

import com.unhuman.outlookalerter.model.CalendarEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JoinMeetingDialog.
 *
 * GUI construction requires a display; these tests are skipped in fully
 * headless CI environments where java.awt.headless=true.
 */
@DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
class JoinMeetingDialogTest {

    // ───────── Button rendering ─────────

    @Nested
    @DisplayName("Button rendering")
    class ButtonRenderingTests {

        @Test
        @DisplayName("event with URL produces an enabled button")
        void eventWithUrlIsEnabled() throws Exception {
            CalendarEvent event = makeEvent("Team Standup", 5);
            Function<CalendarEvent, String> resolver = e -> "https://zoom.us/j/123456";

            JoinMeetingDialog dialog = createDialog(null, List.of(event), resolver);
            try {
                List<JButton> buttons = collectButtons(dialog);
                // One meeting button + Snooze + Dismiss
                assertEquals(3, buttons.size(), "Expected meeting button + Snooze + Dismiss");
                JButton meetingBtn = buttons.get(0);
                assertTrue(meetingBtn.isEnabled(), "Button for event with URL must be enabled");
                assertTrue(isJoinable(meetingBtn), "Button for event with URL must open the link");
                assertTrue(meetingBtn.getText().contains("Team Standup"),
                        "Button text should contain the event subject");
            } finally {
                disposeOnEdt(dialog);
            }
        }

        @Test
        @DisplayName("event without URL produces an inert, legible button with '(No Link)' suffix")
        void eventWithoutUrlIsDisabled() throws Exception {
            CalendarEvent event = makeEvent("No-Link Meeting", 3);
            Function<CalendarEvent, String> resolver = e -> null;

            JoinMeetingDialog dialog = createDialog(null, List.of(event), resolver);
            try {
                List<JButton> buttons = collectButtons(dialog);
                JButton meetingBtn = buttons.get(0);
                // Never setEnabled(false): disabled buttons ignore the foreground colour and
                // become unreadable, so a no-link button stays enabled but has no action.
                assertTrue(meetingBtn.isEnabled(), "No-link button must stay enabled for legibility");
                assertFalse(isJoinable(meetingBtn), "Button for event without URL must have no action");
                assertTrue(meetingBtn.getFont().isItalic(), "No-link button is shown in italics");
                assertTrue(meetingBtn.getText().contains("(No Link)"),
                        "No-link button text must contain '(No Link)'");
            } finally {
                disposeOnEdt(dialog);
            }
        }

        @Test
        @DisplayName("multiple events produce one button each (plus Snooze and Dismiss)")
        void multipleEventsProduceMultipleButtons() throws Exception {
            List<CalendarEvent> events = List.of(
                    makeEvent("Standup", 1),
                    makeEvent("Retro", 2),
                    makeEvent("Design Review", 3));
            Function<CalendarEvent, String> resolver = e -> "https://zoom.us/j/999";

            JoinMeetingDialog dialog = createDialog(null, events, resolver);
            try {
                List<JButton> buttons = collectButtons(dialog);
                // 3 meeting buttons + Snooze + Dismiss
                assertEquals(5, buttons.size(),
                        "Expected one button per event plus Snooze and Dismiss buttons");
            } finally {
                disposeOnEdt(dialog);
            }
        }

        @Test
        @DisplayName("mixed events: some with URL (joinable), some without (inert)")
        void mixedEnabledDisabled() throws Exception {
            CalendarEvent withUrl = makeEvent("With Zoom", 1);
            CalendarEvent noUrl = makeEvent("No Zoom", 2);
            Function<CalendarEvent, String> resolver =
                    e -> "With Zoom".equals(e.getSubject()) ? "https://zoom.us/j/1" : null;

            JoinMeetingDialog dialog = createDialog(null, List.of(withUrl, noUrl), resolver);
            try {
                List<JButton> meetingBtns = collectButtons(dialog).subList(0, 2);
                long joinableCount = meetingBtns.stream().filter(JoinMeetingDialogTest::isJoinable).count();
                long inertCount = meetingBtns.stream().filter(b -> !isJoinable(b)).count();
                assertEquals(1, joinableCount, "Exactly one button should open a link");
                assertEquals(1, inertCount, "Exactly one button should be inert");
            } finally {
                disposeOnEdt(dialog);
            }
        }

        @Test
        @DisplayName("button label contains 'now' for meetings with start <= 0 minutes away")
        void buttonLabelNowForCurrentMeeting() throws Exception {
            CalendarEvent event = makeEvent("Running Meeting", -1); // already started
            Function<CalendarEvent, String> resolver = e -> "https://zoom.us/j/1";

            JoinMeetingDialog dialog = createDialog(null, List.of(event), resolver);
            try {
                List<JButton> buttons = collectButtons(dialog);
                JButton btn = buttons.get(0);
                assertTrue(btn.getText().contains("now"),
                        "Meeting already started or starting: label should say 'now'");
            } finally {
                disposeOnEdt(dialog);
            }
        }

        @Test
        @DisplayName("button label contains 'in Nm' for future meeting")
        void buttonLabelMinutesForFutureMeeting() throws Exception {
            CalendarEvent event = makeEvent("Future Meeting", 7);
            Function<CalendarEvent, String> resolver = e -> "https://zoom.us/j/1";

            JoinMeetingDialog dialog = createDialog(null, List.of(event), resolver);
            try {
                List<JButton> buttons = collectButtons(dialog);
                JButton btn = buttons.get(0);
                // getMinutesToStart() uses floor division so label is "in 7m" or similar
                assertTrue(btn.getText().matches(".*\\d+m.*"),
                        "Future meeting label should contain minute count");
            } finally {
                disposeOnEdt(dialog);
            }
        }
    }

    // ───────── Physical location extraction ─────────

    @Nested
    @DisplayName("extractPhysicalLocation()")
    class PhysicalLocationTests {

        @Test
        @DisplayName("null location returns null")
        void nullLocationReturnsNull() {
            CalendarEvent e = makeEvent("Meeting", 5);
            assertNull(JoinMeetingDialog.extractPhysicalLocation(e));
        }

        @Test
        @DisplayName("blank location returns null")
        void blankLocationReturnsNull() {
            CalendarEvent e = makeEvent("Meeting", 5);
            e.setLocation("   ");
            assertNull(JoinMeetingDialog.extractPhysicalLocation(e));
        }

        @Test
        @DisplayName("URL-only location returns null")
        void urlOnlyLocationReturnsNull() {
            CalendarEvent e = makeEvent("Meeting", 5);
            e.setLocation("https://zoom.us/j/12345");
            assertNull(JoinMeetingDialog.extractPhysicalLocation(e));
        }

        @Test
        @DisplayName("plain room name is returned as-is")
        void plainRoomReturned() {
            CalendarEvent e = makeEvent("Meeting", 5);
            e.setLocation("Conference Room A");
            assertEquals("Conference Room A", JoinMeetingDialog.extractPhysicalLocation(e));
        }

        @Test
        @DisplayName("composite location strips URL and returns room")
        void compositeLocationStripsUrl() {
            CalendarEvent e = makeEvent("Meeting", 5);
            e.setLocation("https://zoom.us/j/123; Conference Room B");
            assertEquals("Conference Room B", JoinMeetingDialog.extractPhysicalLocation(e));
        }

        @Test
        @DisplayName("tilde-delimited rooms are split and joined")
        void tildeDelimitedRoomsJoined() {
            CalendarEvent e = makeEvent("Meeting", 5);
            e.setLocation("~ Room A~ Room B");
            assertEquals("Room A, Room B", JoinMeetingDialog.extractPhysicalLocation(e));
        }
    }

    // ───────── Button location subtitle ─────────

    @Nested
    @DisplayName("Button location subtitle")
    class ButtonLocationSubtitleTests {

        @Test
        @DisplayName("button text contains location when event has a physical location")
        void buttonContainsLocationWhenPresent() throws Exception {
            CalendarEvent event = makeEvent("Team Standup", 5);
            event.setLocation("Conference Room A");
            Function<CalendarEvent, String> resolver = e -> "https://zoom.us/j/1";

            JoinMeetingDialog dialog = createDialog(null, List.of(event), resolver);
            try {
                List<JButton> buttons = collectButtons(dialog);
                JButton meetingBtn = buttons.get(0);
                assertTrue(meetingBtn.getText().contains("Conference Room A"),
                        "Button text should contain the physical location");
            } finally {
                disposeOnEdt(dialog);
            }
        }

        @Test
        @DisplayName("button text does not contain URL-only location")
        void buttonDoesNotContainUrlLocation() throws Exception {
            CalendarEvent event = makeEvent("Zoom Only", 5);
            event.setLocation("https://zoom.us/j/99999");
            Function<CalendarEvent, String> resolver = e -> "https://zoom.us/j/99999";

            JoinMeetingDialog dialog = createDialog(null, List.of(event), resolver);
            try {
                List<JButton> buttons = collectButtons(dialog);
                JButton meetingBtn = buttons.get(0);
                assertFalse(meetingBtn.getText().contains("https://"),
                        "Button text should not contain a URL from the location field");
            } finally {
                disposeOnEdt(dialog);
            }
        }
    }

    // ───────── URL resolver interaction ─────────

    @Nested
    @DisplayName("URL resolver interaction")
    class UrlResolverTests {

        @Test
        @DisplayName("URL resolver is called for each event")
        void resolverCalledForEachEvent() throws Exception {
            CalendarEvent e1 = makeEvent("A", 1);
            CalendarEvent e2 = makeEvent("B", 2);
            List<CalendarEvent> resolved = new ArrayList<>();
            Function<CalendarEvent, String> trackingResolver = e -> {
                resolved.add(e);
                return "https://zoom.us/j/1";
            };

            JoinMeetingDialog dialog = createDialog(null, List.of(e1, e2), trackingResolver);
            try {
                assertEquals(2, resolved.size(), "Resolver should be invoked once per event");
            } finally {
                disposeOnEdt(dialog);
            }
        }

        @Test
        @DisplayName("Cancel button disposes dialog without invoking URL resolver")
        void cancelDisposesWithoutOpeningUrl() throws Exception {
            AtomicBoolean urlOpened = new AtomicBoolean(false);
            CalendarEvent event = makeEvent("Meeting", 2);
            Function<CalendarEvent, String> resolver = e -> {
                urlOpened.set(true); // resolver is called during dialog construction, not on cancel
                return null;         // return null so no URL button is enabled
            };

            JoinMeetingDialog dialog = createDialog(null, List.of(event), resolver);
            try {
                // Find the Cancel button and click it
                List<JButton> buttons = collectButtons(dialog);
                JButton cancelBtn = buttons.stream()
                        .filter(b -> b.getText().startsWith("Dismiss"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Dismiss button not found"));

                // Clicking Cancel should dispose the dialog
                AtomicBoolean disposed = new AtomicBoolean(false);
                dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosed(java.awt.event.WindowEvent e) {
                        disposed.set(true);
                    }
                });

                SwingUtilities.invokeAndWait(cancelBtn::doClick);
                // Give disposal a moment
                Thread.sleep(100);

                assertTrue(disposed.get(), "Dialog should be disposed after Cancel click");
            } finally {
                if (dialog.isDisplayable()) disposeOnEdt(dialog);
            }
        }
    }

    // ───────── Static show() factory ─────────

    @Nested
    @DisplayName("show() factory method")
    class ShowFactoryTests {

        @Test
        @DisplayName("show() with null events does nothing")
        void showWithNullEventsIsNoOp() {
            assertDoesNotThrow(() -> {
                // show() on EDT with null — must not throw
                SwingUtilities.invokeAndWait(() ->
                        JoinMeetingDialog.show(null, null, e -> null));
            });
        }

        @Test
        @DisplayName("show() with empty events does nothing")
        void showWithEmptyEventsIsNoOp() {
            assertDoesNotThrow(() -> {
                SwingUtilities.invokeAndWait(() ->
                        JoinMeetingDialog.show(null, List.of(), e -> null));
            });
        }
    }

    // ───────── Helpers ─────────

    private static CalendarEvent makeEvent(String subject, int minutesFromNow) {
        CalendarEvent e = new CalendarEvent();
        e.setSubject(subject);
        e.setStartTime(ZonedDateTime.now().plusMinutes(minutesFromNow));
        e.setEndTime(ZonedDateTime.now().plusMinutes(minutesFromNow + 30));
        return e;
    }

    /** Creates a dialog on the EDT and returns it (not yet visible). */
    private static JoinMeetingDialog createDialog(java.awt.Window parent,
            List<CalendarEvent> events,
            Function<CalendarEvent, String> resolver) throws Exception {
        AtomicReference<JoinMeetingDialog> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                ref.set(new JoinMeetingDialog(parent, events, resolver)));
        return ref.get();
    }

    /** Collects all JButtons from the dialog's content pane, in order. */
    private static List<JButton> collectButtons(JDialog dialog) {
        List<JButton> buttons = new ArrayList<>();
        collectButtons((JComponent) dialog.getContentPane(), buttons);
        return buttons;
    }

    private static void collectButtons(JComponent parent, List<JButton> out) {
        for (java.awt.Component c : parent.getComponents()) {
            if (c instanceof JButton) {
                out.add((JButton) c);
            } else if (c instanceof JComponent) {
                collectButtons((JComponent) c, out);
            }
        }
    }

    /** True if clicking the button does something (only meetings with a join URL get a listener). */
    private static boolean isJoinable(JButton button) {
        return button.getActionListeners().length > 0;
    }

    private static void disposeOnEdt(JDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(dialog::dispose);
    }
}
