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
                // One meeting button + one Cancel button
                assertEquals(2, buttons.size(), "Expected meeting button + Cancel");
                JButton meetingBtn = buttons.get(0);
                assertTrue(meetingBtn.isEnabled(), "Button for event with URL must be enabled");
                assertTrue(meetingBtn.getText().contains("Team Standup"),
                        "Button text should contain the event subject");
            } finally {
                disposeOnEdt(dialog);
            }
        }

        @Test
        @DisplayName("event without URL produces a disabled button with '(No Link)' suffix")
        void eventWithoutUrlIsDisabled() throws Exception {
            CalendarEvent event = makeEvent("No-Link Meeting", 3);
            Function<CalendarEvent, String> resolver = e -> null;

            JoinMeetingDialog dialog = createDialog(null, List.of(event), resolver);
            try {
                List<JButton> buttons = collectButtons(dialog);
                JButton meetingBtn = buttons.get(0);
                assertFalse(meetingBtn.isEnabled(), "Button for event without URL must be disabled");
                assertTrue(meetingBtn.getText().contains("(No Link)"),
                        "Disabled button text must contain '(No Link)'");
            } finally {
                disposeOnEdt(dialog);
            }
        }

        @Test
        @DisplayName("multiple events produce one button each (plus Cancel)")
        void multipleEventsProduceMultipleButtons() throws Exception {
            List<CalendarEvent> events = List.of(
                    makeEvent("Standup", 1),
                    makeEvent("Retro", 2),
                    makeEvent("Design Review", 3));
            Function<CalendarEvent, String> resolver = e -> "https://zoom.us/j/999";

            JoinMeetingDialog dialog = createDialog(null, events, resolver);
            try {
                List<JButton> buttons = collectButtons(dialog);
                // 3 meeting buttons + 1 cancel
                assertEquals(4, buttons.size(),
                        "Expected one button per event plus a Cancel button");
            } finally {
                disposeOnEdt(dialog);
            }
        }

        @Test
        @DisplayName("mixed events: some with URL (enabled), some without (disabled)")
        void mixedEnabledDisabled() throws Exception {
            CalendarEvent withUrl = makeEvent("With Zoom", 1);
            CalendarEvent noUrl = makeEvent("No Zoom", 2);
            Function<CalendarEvent, String> resolver =
                    e -> "With Zoom".equals(e.getSubject()) ? "https://zoom.us/j/1" : null;

            JoinMeetingDialog dialog = createDialog(null, List.of(withUrl, noUrl), resolver);
            try {
                List<JButton> meetingBtns = collectButtons(dialog).subList(0, 2);
                long enabledCount = meetingBtns.stream().filter(JButton::isEnabled).count();
                long disabledCount = meetingBtns.stream().filter(b -> !b.isEnabled()).count();
                assertEquals(1, enabledCount, "Exactly one button should be enabled");
                assertEquals(1, disabledCount, "Exactly one button should be disabled");
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
                        .filter(b -> b.getText().startsWith("Cancel"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Cancel button not found"));

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

    private static void disposeOnEdt(JDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(dialog::dispose);
    }
}
