package com.unhuman.outlookalerter.ui;

import com.unhuman.outlookalerter.model.CalendarEvent;
import com.unhuman.outlookalerter.util.MacScreenFlasher;

import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

    // Non-static: read from ConfigManager at construction time so tests can override it.
    // The legacy constant is kept for test compatibility (tests may still read it).
    static final int AUTO_DISMISS_SECONDS = 15;

    // ── Fixed structural colours (independent of user settings) ──────────────
    private static final Color BG_DIALOG    = new Color( 28,  28,  42);
    private static final Color BTN_DISABLED = new Color( 55,  55,  75);
    private static final Color BTN_CANCEL   = new Color( 65,  65,  90);
    private static final Color BTN_CANCEL_HOV = new Color( 90,  90, 120);
    private static final Color PROGRESS_BG  = new Color( 50,  50,  70);

    /** Decodes a hex color string, returning {@code fallback} on any failure. */
    private static Color configColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        try { return Color.decode(hex); } catch (Exception e) { return fallback; }
    }

    /** Returns a brighter version of {@code c} by scaling each channel by {@code factor}. */
    private static Color brighten(Color c, float factor) {
        return new Color(
            Math.min(255, (int) (c.getRed()   * factor)),
            Math.min(255, (int) (c.getGreen() * factor)),
            Math.min(255, (int) (c.getBlue()  * factor)));
    }

    // ── WCAG 2.1 contrast utilities ──────────────────────────────────────────

    private static double srgbLinear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double relativeLuminance(Color c) {
        return 0.2126 * srgbLinear(c.getRed())
             + 0.7152 * srgbLinear(c.getGreen())
             + 0.0722 * srgbLinear(c.getBlue());
    }

    private static double contrastRatio(Color c1, Color c2) {
        double l1 = relativeLuminance(c1), l2 = relativeLuminance(c2);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    /**
     * Returns white or near-black, whichever has higher contrast against {@code bg}.
     * Used as the starting point for text on an unknown background colour.
     */
    private static Color contrastingText(Color bg) {
        return relativeLuminance(bg) > 0.179 ? new Color(28, 28, 42) : Color.WHITE;
    }

    /**
     * Binary-searches for the most blended colour between {@code fg} and {@code bg}
     * that still achieves a contrast ratio of at least {@code minRatio} against {@code bg}.
     * Returns unmodified {@code fg} if even that fails (caller fallback).
     */
    private static Color wcagBlend(Color fg, Color bg, double minRatio) {
        if (contrastRatio(fg, bg) < minRatio) return fg;   // starting colour already fails
        float lo = 0, hi = 1;
        for (int i = 0; i < 16; i++) {
            float mid = (lo + hi) / 2f;
            Color blended = blend(fg, bg, mid);
            if (contrastRatio(blended, bg) >= minRatio) lo = mid; else hi = mid;
        }
        return blend(fg, bg, lo);
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(
            Math.min(255, Math.max(0, (int) (a.getRed()   * (1-t) + b.getRed()   * t))),
            Math.min(255, Math.max(0, (int) (a.getGreen() * (1-t) + b.getGreen() * t))),
            Math.min(255, Math.max(0, (int) (a.getBlue()  * (1-t) + b.getBlue()  * t))));
    }

    private final List<CalendarEvent> displayedEvents;
    private final int autoDismissSeconds;
    /** Shared closer: when set, closeAll() will invoke it after dispose(). */
    private Runnable dismissAll = null;

    /** Called by showOnAllScreens() to link sibling dialogs together. */
    void setDismissAll(Runnable r) { this.dismissAll = r; }

    /**
     * Disposes this dialog and, if a shared closer is installed, closes all sibling
     * dialogs in the same session (idempotent via null-clear to prevent re-entrancy).
     */
    private void closeAll() {
        dispose();
        Runnable r = dismissAll;
        dismissAll = null;
        if (r != null) r.run();
    }

    /**
     * Creates the dialog.
     *
     * @param parent      owning window (used for centering); may be null
     * @param events      alerted calendar events to show (non-null, non-empty)
     * @param urlResolver maps each event to its join URL, or null if none
     */
    public JoinMeetingDialog(Window parent, List<CalendarEvent> events,
            Function<CalendarEvent, String> urlResolver) {
        super(parent, "Meeting Alert", ModalityType.MODELESS);
        this.displayedEvents = new java.util.ArrayList<>(events);
        com.unhuman.outlookalerter.core.ConfigManager cfgForTimeout =
                com.unhuman.outlookalerter.core.ConfigManager.getInstance();
        this.autoDismissSeconds = (cfgForTimeout != null) ? cfgForTimeout.getJoinDialogTimeoutSeconds() : AUTO_DISMISS_SECONDS;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);

        // ── Resolve colours from configuration ───────────────────────────────
        com.unhuman.outlookalerter.core.ConfigManager cfg =
                com.unhuman.outlookalerter.core.ConfigManager.getInstance();
        Color headerBg   = configColor(cfg != null ? cfg.getAlertBannerColor()     : null, new Color(220, 48,  0));
        Color headerFg   = configColor(cfg != null ? cfg.getAlertBannerTextColor() : null, Color.WHITE);
        // Subtitle: blend toward bg as far as possible while keeping ≥4.5:1 contrast (WCAG AA)
        Color headerSub  = wcagBlend(headerFg, headerBg, 4.5);
        Color btnJoinBg  = configColor(cfg != null ? cfg.getFlashColor()     : null, new Color(128, 0, 0));
        Color btnJoinFg  = configColor(cfg != null ? cfg.getFlashTextColor() : null, Color.WHITE);
        Color btnJoinHov = brighten(btnJoinBg, 1.35f);
        // Disabled button: maximally muted text that still clears WCAG AA against the dark bg
        Color btnDisabledFg = wcagBlend(contrastingText(BTN_DISABLED), BTN_DISABLED, 4.5);

        // ── Root: dark background ─────────────────────────────────────────────
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BG_DIALOG);

        // ── Header: coloured band with icon and title ─────────────────────────
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(headerBg);
        header.setOpaque(true);
        header.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel iconLabel = new JLabel("\u25B6");
        iconLabel.setFont(iconLabel.getFont().deriveFont(Font.BOLD, 28f));
        iconLabel.setForeground(headerFg);
        iconLabel.setOpaque(false);
        header.add(iconLabel, BorderLayout.WEST);

        JPanel headerText = new JPanel();
        headerText.setLayout(new BoxLayout(headerText, BoxLayout.Y_AXIS));
        headerText.setOpaque(false);

        JLabel titleLabel = new JLabel(events.size() == 1 ? "Join Meeting?" : "Join a Meeting");
        titleLabel.setForeground(headerFg);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        headerText.add(titleLabel);

        String subText = events.size() == 1
                ? "A meeting is starting soon"
                : events.size() + " meetings are starting soon";
        JLabel subLabel = new JLabel(subText);
        subLabel.setForeground(headerSub);
        subLabel.setFont(subLabel.getFont().deriveFont(Font.PLAIN, 12f));
        headerText.add(subLabel);

        header.add(headerText, BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);

        // ── Body: per-meeting join buttons ────────────────────────────────────
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(BG_DIALOG);
        body.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));

        // Sort events by start time (earliest first)
        List<CalendarEvent> sorted = events.stream()
                .sorted(Comparator.comparing(e -> e.getStartTime() != null
                        ? e.getStartTime().toInstant()
                        : java.time.Instant.MAX))
                .collect(Collectors.toList());

        for (CalendarEvent event : sorted) {
            String url = urlResolver.apply(event);
            String buttonText = buildButtonLabel(event);
            JButton btn = makeStyledButton(
                    url != null ? "\u25B6  " + buttonText : buttonText + "  (No Link)",
                    url != null ? btnJoinBg      : BTN_DISABLED,
                    url != null ? btnJoinHov     : BTN_DISABLED,
                    url != null ? btnJoinFg      : btnDisabledFg);
            // Never call setEnabled(false): BasicButtonUI ignores setForeground() when
            // disabled and paints its own barely-visible disabled colour instead.
            // Visual cues (dark bg, italic, default cursor, no hover, "(No Link)" label)
            // communicate non-interactivity without sacrificing legibility.
            if (url == null) {
                btn.setFont(btn.getFont().deriveFont(Font.ITALIC, 13f));
                btn.setCursor(Cursor.getDefaultCursor());
            }
            if (url != null) {
                final String finalUrl = url;
                btn.addActionListener(e -> {
                    try {
                        Desktop.getDesktop().browse(new URI(finalUrl));
                    } catch (Exception ex) {
                        // Non-fatal — dialog still closes
                    }
                    closeAll();
                });
            }
            body.add(btn);
            body.add(Box.createVerticalStrut(8));
        }
        root.add(body, BorderLayout.CENTER);

        // ── Footer: shrinking countdown bar + cancel button ───────────────────
        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBackground(BG_DIALOG);
        footer.setBorder(BorderFactory.createEmptyBorder(0, 16, 14, 16));

        // Custom-painted so macOS Aqua L&F cannot override the colours.
        // headerBg is effectively-final — captured by the anonymous paintComponent below.
        JProgressBar bar = new JProgressBar(0, autoDismissSeconds > 0 ? autoDismissSeconds : AUTO_DISMISS_SECONDS) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(PROGRESS_BG);
                g2.fillRect(0, 0, getWidth(), getHeight());
                int filled = (int) Math.round(getWidth() * (double) getValue() / getMaximum());
                g2.setColor(headerBg);
                g2.fillRect(0, 0, filled, getHeight());
                g2.dispose();
            }
        };
        bar.setValue(autoDismissSeconds > 0 ? autoDismissSeconds : AUTO_DISMISS_SECONDS);
        bar.setMinimumSize(new Dimension(0, 6));
        bar.setPreferredSize(new Dimension(380, 6));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        bar.setOpaque(false);
        bar.setBorderPainted(false);
        bar.setStringPainted(false);
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(bar);
        footer.add(Box.createVerticalStrut(8));

        JButton cancelBtn = makeStyledButton(
                autoDismissSeconds > 0 ? cancelLabel(autoDismissSeconds) : "Dismiss",
                BTN_CANCEL, BTN_CANCEL_HOV, Color.WHITE);
        cancelBtn.addActionListener(e -> closeAll());
        footer.add(cancelBtn);
        root.add(footer, BorderLayout.SOUTH);

        // Escape also closes
        getRootPane().registerKeyboardAction(
                e -> closeAll(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(cancelBtn);

        setContentPane(root);
        pack();
        setMinimumSize(new Dimension(380, getHeight()));
        // Stay on top of all other windows — critical on macOS where the app may be
        // in the background (system tray) and a dialog with an invisible parent would
        // otherwise appear behind the frontmost application.
        setAlwaysOnTop(true);

        // ── Countdown timer ───────────────────────────────────────────────────
        if (autoDismissSeconds > 0) {
            AtomicInteger remaining = new AtomicInteger(autoDismissSeconds);
            bar.setValue(autoDismissSeconds);
            bar.setMaximum(autoDismissSeconds);
            Timer countdown = new Timer(1000, null);
            countdown.addActionListener(tick -> {
                int left = remaining.decrementAndGet();
                if (left <= 0) {
                    countdown.stop();
                    closeAll();
                } else {
                    cancelBtn.setText(cancelLabel(left));
                    bar.setValue(left);
                }
            });
            // Stop the timer when the dialog closes (user clicked a button or Escape).
            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    countdown.stop();
                }
            });
            countdown.start();
        } else {
            // 0 = indefinite: hide the progress bar; cancel button has no countdown.
            bar.setVisible(false);
        }
    }

    /**
     * Creates a flat, custom-coloured button that bypasses the macOS Aqua L&F
     * so {@code bg} and {@code hoverBg} are always respected.
     */
    private static JButton makeStyledButton(String text, Color bg, Color hoverBg, Color fg) {
        JButton btn = new JButton(text);
        btn.setUI(new BasicButtonUI());
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 13f));
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        btn.setPreferredSize(new Dimension(380, 44));
        btn.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        if (!hoverBg.equals(bg)) {
            btn.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { btn.setBackground(hoverBg); }
                @Override public void mouseExited(MouseEvent e)  { btn.setBackground(bg); }
            });
        }
        return btn;
    }

    private static String cancelLabel(int seconds) {
        return "Dismiss (" + seconds + "s)";
    }

    /** Returns an unmodifiable view of the events this dialog was built with. */
    public List<CalendarEvent> getDisplayedEvents() {
        return java.util.Collections.unmodifiableList(displayedEvents);
    }

    /**
     * Positions the dialog centered on the given screen rectangle.
     * If {@code screenBounds} is null, falls back to centering relative to {@code parent}.
     */
    void positionOnScreen(Rectangle screenBounds) {
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
        SwingUtilities.invokeLater(dialog::toFront);
        dialog.setVisible(true);
    }

    /**
     * Shows one {@link JoinMeetingDialog} per connected screen simultaneously.
     * All dialogs are linked: interacting with any one (join, cancel, Escape, or the
     * auto-dismiss timer reaching zero) disposes every dialog in the group and then
     * invokes {@code onDismiss} exactly once.
     *
     * <p>Must be called on the EDT.  Does nothing if {@code events} is null or empty.
     *
     * @param parent      owning window; may be null
     * @param events      alerted calendar events
     * @param urlResolver maps each event to its join URL, or null if none
     * @param onDismiss   called once when the group is closed; may be null
     * @return a {@code Runnable} that closes all dialogs in the group when invoked
     *         (safe to call even after the dialogs are already disposed)
     */
    public static Runnable showOnAllScreens(Window parent, List<CalendarEvent> events,
            Function<CalendarEvent, String> urlResolver, Runnable onDismiss) {
        if (events == null || events.isEmpty()) return () -> {};

        GraphicsDevice[] screens = java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getScreenDevices();

        java.util.List<JoinMeetingDialog> all = new java.util.ArrayList<>();
        for (GraphicsDevice screen : screens) {
            Rectangle bounds = screen.getDefaultConfiguration().getBounds();
            JoinMeetingDialog d = new JoinMeetingDialog(parent, events, urlResolver);
            d.positionOnScreen(bounds);
            all.add(d);
        }
        if (all.isEmpty()) {
            // Fallback: single dialog on default screen
            JoinMeetingDialog d = new JoinMeetingDialog(parent, events, urlResolver);
            d.positionOnScreen(null);
            all.add(d);
        }

        java.util.concurrent.atomic.AtomicBoolean closing =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable doDispose = () -> {
            if (closing.compareAndSet(false, true)) {
                MacScreenFlasher.clearTopDialogWindows();
                all.forEach(JDialog::dispose);
                if (onDismiss != null) onDismiss.run();
            }
        };
        Runnable dismissAll = () -> {
            if (SwingUtilities.isEventDispatchThread()) {
                doDispose.run();
            } else {
                SwingUtilities.invokeLater(doDispose);
            }
        };

        all.forEach(d -> d.setDismissAll(dismissAll));

        // Show all dialogs and schedule toFront
        all.forEach(d -> {
            d.setVisible(true);
            SwingUtilities.invokeLater(d::toFront);
        });

        // Register with MacScreenFlasher so the elevation timer keeps these dialogs
        // above the banner overlay windows (z-order: flash → banner → dialog).
        MacScreenFlasher.registerTopDialogWindows(all);

        return dismissAll;
    }
}
