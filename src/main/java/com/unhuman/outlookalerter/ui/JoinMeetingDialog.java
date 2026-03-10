package com.unhuman.outlookalerter.ui;

import com.unhuman.outlookalerter.model.CalendarEvent;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Modal dialog shown after the user dismisses a flash window (click or key press).
 * Lists all alerted meetings with join buttons.  Meetings that have a join URL are
 * shown as clickable buttons; meetings without a URL are shown as disabled buttons
 * with a "(No Link)" suffix.  Clicking an enabled button opens the URL in the
 * default browser and closes the dialog.
 */
public class JoinMeetingDialog extends JDialog {

    /**
     * Creates the dialog.
     *
     * @param parent      owning window (used for centering); may be null
     * @param events      alerted calendar events to show (non-null, non-empty)
     * @param urlResolver maps each event to its join URL, or null if none
     */
    public JoinMeetingDialog(Window parent, List<CalendarEvent> events,
            Function<CalendarEvent, String> urlResolver) {
        super(parent, events.size() == 1 ? "Join Meeting?" : "Join a Meeting",
                ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        // Title label
        JLabel titleLabel = new JLabel(events.size() == 1 ? "Join Meeting?" : "Join a Meeting");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(titleLabel);
        content.add(Box.createVerticalStrut(12));

        // Sort events by start time (earliest first)
        List<CalendarEvent> sorted = events.stream()
                .sorted(Comparator.comparing(e -> e.getStartTime() != null
                        ? e.getStartTime().toInstant()
                        : java.time.Instant.MAX))
                .collect(Collectors.toList());

        for (CalendarEvent event : sorted) {
            String url = urlResolver.apply(event);
            String buttonText = buildButtonLabel(event);

            JButton btn;
            if (url != null) {
                btn = new JButton(buttonText);
                final String finalUrl = url;
                btn.addActionListener(e -> {
                    try {
                        Desktop.getDesktop().browse(new URI(finalUrl));
                    } catch (Exception ex) {
                        // Non-fatal — dialog still closes
                    }
                    dispose();
                });
            } else {
                btn = new JButton(buttonText + " (No Link)");
                btn.setEnabled(false);
            }

            btn.setAlignmentX(Component.CENTER_ALIGNMENT);
            btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, btn.getPreferredSize().height));
            content.add(btn);
            content.add(Box.createVerticalStrut(6));
        }

        content.add(Box.createVerticalStrut(4));

        // Cancel button
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        cancelBtn.addActionListener(e -> dispose());
        content.add(cancelBtn);

        // Escape also closes
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(cancelBtn);

        setContentPane(content);
        pack();
        setMinimumSize(new Dimension(340, getHeight()));
        // Stay on top of all other windows — critical on macOS where the app may be
        // in the background (system tray) and a dialog with an invisible parent would
        // otherwise appear behind the frontmost application.
        setAlwaysOnTop(true);
    }

    /**
     * Positions the dialog centered on the given screen rectangle.
     * If {@code screenBounds} is null, falls back to centering relative to {@code parent}.
     */
    private void positionOnScreen(Rectangle screenBounds) {
        if (screenBounds == null) {
            // null centers the dialog on the primary display regardless of owner visibility
            setLocationRelativeTo(null);
            return;
        }
        Dimension size = getSize();
        int x = screenBounds.x + (screenBounds.width  - size.width)  / 2;
        int y = screenBounds.y + (screenBounds.height - size.height) / 2;
        setLocation(x, y);
    }

    private static String buildButtonLabel(CalendarEvent event) {
        String subject = (event.getSubject() != null && !event.getSubject().isBlank())
                ? event.getSubject() : "(No Subject)";
        if (event.getStartTime() == null) return subject;
        long minutes = event.getMinutesToStart();
        String timeSuffix = (minutes <= 0) ? "now" : "in " + minutes + "m";
        return subject + " (" + timeSuffix + ")";
    }

    /**
     * Convenience factory: constructs and shows the dialog on the calling thread.
     * Must be called on the EDT.  Does nothing if {@code events} is null or empty.
     *
     * @param parent      owning window; may be null
     * @param events      alerted calendar events
     * @param urlResolver maps each event to its join URL, or null if none
     */
    public static void show(Window parent, List<CalendarEvent> events,
            Function<CalendarEvent, String> urlResolver) {
        show(parent, events, urlResolver, null);
    }

    /**
     * Convenience factory that shows the dialog centered on the specified screen.
     * If {@code screenBounds} is null, centers relative to {@code parent}.
     * Must be called on the EDT.  Does nothing if {@code events} is null or empty.
     */
    public static void show(Window parent, List<CalendarEvent> events,
            Function<CalendarEvent, String> urlResolver, Rectangle screenBounds) {
        if (events == null || events.isEmpty()) return;
        JoinMeetingDialog dialog = new JoinMeetingDialog(parent, events, urlResolver);
        dialog.positionOnScreen(screenBounds);
        // Schedule toFront() to run inside the modal event loop so the dialog
        // rises above other windows on the first pump of the event queue.
        SwingUtilities.invokeLater(dialog::toFront);
        dialog.setVisible(true);
    }
}
