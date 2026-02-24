package com.unhuman.outlookalerter.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import com.unhuman.outlookalerter.core.OutlookClient;
import com.unhuman.outlookalerter.core.ConfigManager;
import com.unhuman.outlookalerter.core.FederationDiscovery;
import com.unhuman.outlookalerter.core.MsalAuthProvider;
import com.unhuman.outlookalerter.util.LogManager;
import com.unhuman.outlookalerter.util.LogCategory;
import java.awt.datatransfer.StringSelection;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.microsoft.aad.msal4j.DeviceCode;

/**
 * A simplified token entry dialog for Microsoft OAuth authentication
 * Designed for maximum compatibility and simplicity
 */
public class SimpleTokenDialog {
    // Singleton instance
    private static SimpleTokenDialog instance;
    private static volatile boolean isShowing = false;
    private boolean isTokenSubmitted = false;  // Track if token was successfully submitted
    private boolean isExplicitCancel = false;  // Track if user explicitly cancelled
    private boolean msalAuthenticated = false; // Skip JWT format validation for MSAL-acquired tokens

    // Lock object for thread safety
    private static final Object LOCK = new Object();

    // Default Microsoft Graph URL
    public static final String DEFAULT_GRAPH_URL = "https://developer.microsoft.com/en-us/graph";

    // Instance variables
    private JDialog frame;
    private JFrame parentFrame;
    private JTextField tokenField;
    private CountDownLatch latch = new CountDownLatch(1);
    private Map<String, String> tokens;
    private final String signInUrl;
    private final MsalAuthProvider msalAuthProvider;
    private JCheckBox ignoreCertCheckbox;  // stored as field for direct access
    private JLabel statusLabel;  // status label for MSAL auth feedback

    /**
     * Get or create singleton instance (with MSAL provider)
     */
    public static SimpleTokenDialog getInstance(String signInUrl, MsalAuthProvider msalAuthProvider) {
        synchronized (LOCK) {
            if (instance == null) {
                String url = signInUrl != null ? signInUrl : DEFAULT_GRAPH_URL;
                instance = new SimpleTokenDialog(url, msalAuthProvider);
            }
            return instance;
        }
    }

    /**
     * Get or create singleton instance (without MSAL provider — backwards compatibility)
     */
    public static SimpleTokenDialog getInstance(String signInUrl) {
        return getInstance(signInUrl, null);
    }

    /**
     * Private constructor to enforce singleton pattern
     */
    private SimpleTokenDialog(String signInUrl, MsalAuthProvider msalAuthProvider) {
        // Default to Microsoft Graph developer site if no URL provided
        this.signInUrl = signInUrl != null ? signInUrl : DEFAULT_GRAPH_URL;
        this.msalAuthProvider = msalAuthProvider;
    }

    /**
     * Display the token entry dialog and wait for input
     */
    public void show() {
        synchronized (LOCK) {
            // If dialog is already showing, just bring it to front
            if (isShowing && frame != null && frame.isDisplayable()) {
                SwingUtilities.invokeLater(() -> {
                    frame.toFront();
                    frame.requestFocus();
                });
                return;
            }

            // Ensure any existing dialog is cleaned up
            cleanup();

            // Reset state
            tokens = null;
            latch = new CountDownLatch(1);
            isShowing = true;
            isTokenSubmitted = false;
            isExplicitCancel = false;
            LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Dialog state set to showing");

            try {
                LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Preparing to show token entry dialog");

                // Create UI on EDT and show immediately
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        createUI();
                        LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: UI created successfully");

                        // Show dialog immediately after creation
                        if (frame != null) {
                            frame.pack();
                            frame.setLocationRelativeTo(parentFrame);
                            frame.setVisible(true);
                            frame.toFront();
                            frame.requestFocus();

                            // Reset UI components
                            resetDialogUI();

                            LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Frame state - visible: " + frame.isVisible() +
                                             ", showing: " + frame.isShowing());
                        }
                    } catch (Exception e) {
                        LogManager.getInstance().error(LogCategory.GENERAL, "SimpleTokenDialog: Error creating UI: " + e.getMessage(), e);
                        cleanup();
                    }
                });
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.GENERAL, "Error showing token dialog: " + e.getMessage(), e);
                cleanup(); // Ensure cleanup even on error
            }
        }
    }

    /**
     * Create the UI components
     */
    private void createUI() {
        try {
            // Don't parent the dialog to the main application frame.
            // JDialog owned windows are automatically hidden when the owner
            // is hidden (e.g. main window red-X minimises to tray), which
            // makes the token dialog disappear unexpectedly.
            parentFrame = null;

            // Create non-modal dialog — the CountDownLatch in getTokens() handles
            // the blocking wait. Modal dialogs freeze the parent window's input on
            // macOS and can get hidden behind other windows, making the app unusable.
            frame = new JDialog((Frame) null, "Outlook Alerter - Token Entry", false);
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);  // Let the system handle window closing
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Window closing event detected (X button clicked)");
                    isExplicitCancel = true;  // Mark X button click as explicit cancellation
                    cancelDialog();
                }
            });

            try {
                LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Creating UI components");

                // Main panel with simple layout
                JPanel panel = new JPanel(new BorderLayout(10, 10));
                panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

                // Instructions at top - adapt based on whether MSAL is available
                // MSAL is available if the provider exists (default client ID can be used)
                boolean msalEnabled = msalAuthProvider != null;
                String instructionsHtml;
                if (msalEnabled) {
                    instructionsHtml =
                        "<html><div style='width: 400px'>" +
                        "<h2>Outlook Alerter Authentication</h2>" +
                        "<p><b>Recommended:</b> Click <b>'Sign In with Browser'</b> below to sign in automatically.</p>" +
                        "<p>Your browser will open for Microsoft login. Once complete, the token will be captured automatically.</p>" +
                        "<hr>" +
                        "<p><b>Manual Method:</b> If browser sign-in doesn't work, use Graph Explorer:</p>" +
                        "<ol>" +
                        "<li>Click 'Open Graph Explorer' and sign in</li>" +
                        "<li>Click your profile picture &rarr; 'Access token' tab</li>" +
                        "<li>Copy and paste the token below</li>" +
                        "</ol>" +
                        "</div></html>";
                } else {
                    instructionsHtml =
                        "<html><div style='width: 400px'>" +
                        "<h2>Outlook Alerter Authentication</h2>" +
                        "<p><b>Recommended Method:</b> Use Microsoft Graph Explorer to get your token:</p>" +
                        "<ol>" +
                        "<li>Click the 'Open Graph Explorer' button below</li>" +
                        "<li>Sign in with your Microsoft account</li>" +
                        "<li>Click your profile picture &rarr; 'Access token' tab</li>" +
                        "<li>Copy the displayed token</li>" +
                        "</ol>" +
                        "<p><b>Alternative Method:</b> If Graph Explorer doesn't work, you can use the legacy method:</p>" +
                        "<ol>" +
                        "<li>Click 'Open Sign-in Page' and complete the sign-in</li>" +
                        "<li>Open Developer Tools (F12) &rarr; Application/Storage tab</li>" +
                        "<li>Find the access token in Local Storage (typically starts with 'eyJ')</li>" +
                        "</ol>" +
                        "<p><i>Tip: Configure a Client ID in Settings to enable automatic browser sign-in.</i></p>" +
                        "</div></html>";
                }
                JLabel instructionsLabel = new JLabel(instructionsHtml);
                panel.add(instructionsLabel, BorderLayout.NORTH);

                // Form in center with more spacing
                JPanel formPanel = new JPanel(new GridLayout(2, 1, 10, 15));

                // Add some padding around the form
                formPanel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(15, 15, 15, 15),
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createEtchedBorder(),
                        BorderFactory.createEmptyBorder(10, 10, 10, 10)
                    )
                ));

                JLabel tokenLabel = new JLabel("Access Token:");
                tokenLabel.setFont(tokenLabel.getFont().deriveFont(Font.BOLD));
                formPanel.add(tokenLabel);

                // Add token field with enter key support
                tokenField = new JTextField(30);
                tokenField.setEnabled(true);
                tokenField.setEditable(true);
                tokenField.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        submitToken(); // Submit when enter is pressed
                    }
                });
                formPanel.add(tokenField);

                // Add certificate validation option (always initially unchecked)
                JPanel certPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
                ignoreCertCheckbox = new JCheckBox("Ignore SSL certificate validation", ConfigManager.getInstance().getDefaultIgnoreCertValidation());
                certPanel.add(ignoreCertCheckbox);
                formPanel.add(certPanel);

                panel.add(formPanel, BorderLayout.CENTER);

                // Buttons at bottom
                JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
                buttonPanel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                    BorderFactory.createEmptyBorder(15, 10, 10, 10)
                ));

                JButton graphExplorerButton = new JButton("Open Graph Explorer");
                graphExplorerButton.setFont(graphExplorerButton.getFont().deriveFont(Font.BOLD));
                graphExplorerButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        try {
                            LogManager.getInstance().info(LogCategory.DATA_FETCH, "Opening Graph Explorer...");
                            Desktop.getDesktop().browse(new java.net.URI(DEFAULT_GRAPH_URL + "/graph-explorer"));
                        } catch (Exception ex) {
                            LogManager.getInstance().error(LogCategory.DATA_FETCH, "Error opening Graph Explorer: " + ex.getMessage());
                            JOptionPane.showMessageDialog(
                                frame,
                                "Error opening Graph Explorer: " + ex.getMessage(),
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                            );
                        }
                    }
                });

                JButton openBrowserButton = new JButton(
                        msalAuthProvider != null
                                ? "Sign In with Browser"
                                : "Open Sign-in Page");
                openBrowserButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        if (msalAuthProvider != null) {
                            // Auto-configure default client ID if not explicitly set
                            if (!msalAuthProvider.isConfigured()) {
                                String defaultId = ConfigManager.getDefaultClientId();
                                LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                        "Auto-configuring default client ID for OAuth sign-in");
                                ConfigManager.getInstance().updateClientId(defaultId);
                            }
                            // MSAL interactive auth — opens browser, captures token automatically
                            performMsalInteractiveAuth(openBrowserButton);
                        } else {
                            // Legacy: open the sign-in URL in browser.
                            // If the signInUrl is a raw OAuth authorize endpoint (e.g. from
                            // leftover config), fall back to Graph Explorer so the user can
                            // manually obtain a token — a bare authorize endpoint will just
                            // redirect to localhost where nothing is listening.
                            String urlToOpen = signInUrl;
                            if (urlToOpen != null && urlToOpen.contains("/oauth2/") && urlToOpen.contains("/authorize")) {
                                urlToOpen = DEFAULT_GRAPH_URL + "/graph-explorer";
                                LogManager.getInstance().warn(LogCategory.DATA_FETCH,
                                        "Sign-in URL looks like a raw OAuth authorize endpoint; opening Graph Explorer instead: " + urlToOpen);
                            }
                            try {
                                LogManager.getInstance().info(LogCategory.DATA_FETCH, "Opening browser for sign-in: " + urlToOpen);
                                Desktop.getDesktop().browse(new java.net.URI(urlToOpen));
                            } catch (Exception ex) {
                                LogManager.getInstance().error(LogCategory.DATA_FETCH, "Error opening browser: " + ex.getMessage());
                                JOptionPane.showMessageDialog(
                                    frame,
                                    "Error opening browser: " + ex.getMessage(),
                                    "Error",
                                    JOptionPane.ERROR_MESSAGE
                                );
                            }
                        }
                    }
                });
                // Make the browser sign-in button bold when MSAL is configured
                if (msalAuthProvider != null && msalAuthProvider.isConfigured()) {
                    openBrowserButton.setFont(openBrowserButton.getFont().deriveFont(Font.BOLD));
                }

                JButton submitButton = new JButton("Submit");
                submitButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        submitToken();  // Use consistent validation in submitToken()
                    }
                });
                // Okta SSO button — visible when MSAL is configured (reuses same browser flow with domainHint)
                JButton oktaSsoButton = new JButton("Sign In with Okta SSO");
                oktaSsoButton.setToolTipText("Discover your organization's Okta federation and sign in automatically");
                oktaSsoButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        if (!msalAuthProvider.isConfigured()) {
                            String defaultId = ConfigManager.getDefaultClientId();
                            LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                    "Auto-configuring default client ID for Okta SSO sign-in");
                            ConfigManager.getInstance().updateClientId(defaultId);
                        }
                        performOktaAuth(oktaSsoButton);
                    }
                });

                buttonPanel.add(graphExplorerButton);  // Add Graph Explorer button first
                buttonPanel.add(openBrowserButton);  // Then standard sign-in page button
                if (msalAuthProvider != null) {
                    buttonPanel.add(oktaSsoButton);  // Okta SSO button (requires MSAL for redirect capture)
                }
                buttonPanel.add(submitButton);       // Then submit

                // Status label for MSAL auth feedback
                statusLabel = new JLabel(" ");
                statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
                statusLabel.setForeground(Color.BLUE);

                JButton cancelButton = new JButton("Cancel");
                cancelButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        cancelDialog();  // Use the same method as the X button for consistency
                    }
                });
                buttonPanel.add(cancelButton);      // Cancel button last

                // Add status label between buttons and content
                JPanel southPanel = new JPanel(new BorderLayout(5, 5));
                southPanel.add(statusLabel, BorderLayout.NORTH);
                southPanel.add(buttonPanel, BorderLayout.CENTER);

                panel.add(southPanel, BorderLayout.SOUTH);

                // Set content and show
                frame.setContentPane(panel);
                frame.pack();
                frame.setSize(550, 400);
                frame.setLocationRelativeTo(null);

                // Configure dialog behavior
                frame.setAlwaysOnTop(true);
                frame.setResizable(false);

                // Don't set visible here - we'll do it in the show() method

                LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: UI components created successfully");
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.GENERAL, "SimpleTokenDialog: Error creating UI: " + e.getMessage(), e);

                // Try a fallback UI with minimal components if something went wrong
                try {
                    createFallbackUI();
                } catch (Exception ex) {
                    LogManager.getInstance().error(LogCategory.GENERAL, "SimpleTokenDialog: Even fallback UI failed: " + ex.getMessage());
                }
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "SimpleTokenDialog: Error in parent window detection: " + e.getMessage());
        }
    }

    private void cleanup() {
        // Skip if already cleaned up
        if (!isShowing && frame == null) {
            LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Already cleaned up, skipping");
            return;
        }

        LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Cleanup called");
        isShowing = false;
        isTokenSubmitted = false;
        isExplicitCancel = false;  // Reset explicit cancel flag

        // Complete latch if it hasn't been already
        if (latch != null && latch.getCount() > 0) {
            LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Counting down latch during cleanup");
            latch.countDown();
        }

        if (frame != null) {
            // Use invokeLater for UI operations to avoid deadlocks
            SwingUtilities.invokeLater(() -> {
                try {
                    if (frame != null) {
                        LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Disposing frame");
                        frame.setVisible(false);
                        frame.dispose();
                    }

                    if (parentFrame != null && parentFrame.isUndecorated()) {
                        LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Disposing parent frame");
                        parentFrame.dispose();
                    }
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.GENERAL, "SimpleTokenDialog: Error disposing frames: " + e.getMessage());
                }

                // Clear references to UI components
                frame = null;
                tokenField = null;
                parentFrame = null;
            });
        }
    }

    public void dispose() {
        synchronized (LOCK) {
            cleanup();
            instance = null;
        }
    }

    public boolean isVisible() {
        return isShowing && frame != null && frame.isVisible();
    }

    /**
     * Bring the token dialog to the front if it's currently showing.
     * Also re-shows the dialog if it was hidden (e.g. by the main
     * window being minimised to tray).
     * Safe to call from any thread.
     */
    public void bringToFront() {
        if (!isShowing || frame == null || !frame.isDisplayable()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                if (frame != null && frame.isDisplayable()) {
                    if (!frame.isVisible()) {
                        frame.setVisible(true);
                    }
                    frame.toFront();
                    frame.requestFocus();
                }
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.GENERAL,
                        "SimpleTokenDialog: Error bringing dialog to front: " + e.getMessage());
            }
        });
    }

    /**
     * Get the current singleton instance without creating one.
     * @return The current instance, or null if none exists.
     */
    public static SimpleTokenDialog getCurrentInstance() {
        synchronized (LOCK) {
            return instance;
        }
    }

    /**
     * Create a minimal fallback UI as a last resort
     */
    private void createFallbackUI() {
        LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Creating fallback UI");

        // No parent frame — see createUI() for rationale
        parentFrame = null;

        // Create non-modal dialog (see createUI() comment for rationale)
        frame = new JDialog((Frame) null, "Outlook Alerter - Token Entry (Fallback)", false);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        // Add window listener to handle the red X button
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Window closing event detected in fallback UI (X button clicked)");
                cancelDialog();
            }
        });

        // Use a simple layout
        JPanel panel = new JPanel(new GridLayout(4, 1, 10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel label = new JLabel("Enter access token:");
        tokenField = new JTextField(20);

        // Add certificate validation option (always initially unchecked)
        ignoreCertCheckbox = new JCheckBox("Ignore SSL certificate validation (security risk)", false);

        JButton submitButton = new JButton("Submit");
        submitButton.addActionListener(e -> submitToken());

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> cancelDialog());

        // Add components to panel with proper layout
        panel.add(label);
        panel.add(tokenField);
        panel.add(ignoreCertCheckbox);

        // Final row has button panel with both buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        buttonPanel.add(submitButton);
        buttonPanel.add(cancelButton);
        panel.add(buttonPanel);

        frame.add(panel);
        frame.pack();
        frame.setSize(400, 200);
        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(true);

        LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Fallback UI created");
    }

    /**
     * Submit the entered token
     */
    private void submitToken() {
        try {
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "SimpleTokenDialog: Processing token submission");

            // Get token values - with fallbacks if UI components fail
            String token = "";
            boolean ignoreCertValidation = false;

            try {
                // Get token and validation state first
                token = tokenField != null ? tokenField.getText().trim() : "";

                // Strip off "Bearer " prefix if present (case insensitive)
                if (token.toLowerCase().startsWith("bearer ")) {
                    token = token.substring(7).trim();
                }

                if (token.isEmpty()) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "SimpleTokenDialog: Empty token submitted, showing error message");
                    JOptionPane.showMessageDialog(
                        frame,
                        "Please enter an access token",
                        "Missing Token",
                        JOptionPane.ERROR_MESSAGE
                    );
                    return;
                }

                // Validate token format - must contain two periods (JWT format)
                // Skip this check for MSAL browser-authenticated tokens (already validated by Microsoft)
                if (!msalAuthenticated) {
                    String[] parts = token.split("\\.");
                    if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                        JOptionPane.showMessageDialog(
                            frame,
                            "The token does not appear to be valid. It should contain three parts separated by periods (.).",
                            "Invalid Token Format",
                            JOptionPane.ERROR_MESSAGE
                        );
                        return;
                    }
                }

                // Get certificate validation setting directly from field
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "SimpleTokenDialog: Checking certificate validation setting");
                if (ignoreCertCheckbox != null) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH, "SimpleTokenDialog: Certificate validation checkbox found: " + ignoreCertCheckbox.isSelected());
                    ignoreCertValidation = ignoreCertCheckbox.isSelected();
                }
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.DATA_FETCH, "SimpleTokenDialog: Error getting token text or certificate setting: " + e.getMessage());
                // If we failed to read the token, we can't proceed
                if (token.isEmpty()) {
                    return;
                }
            }

            // Store token data and mark as successfully submitted
            // Log the exact boolean value before converting to string
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "SimpleTokenDialog: ignoreCertValidation raw boolean value: " + ignoreCertValidation);

            tokens = new HashMap<>();
            tokens.put("accessToken", token);
            tokens.put("ignoreCertValidation", ignoreCertValidation ? "true" : "false"); // Explicitly convert to "true"/"false" string
            isTokenSubmitted = true; // Mark that we have a valid token submission

            // Update the certificate validation setting in ConfigManager
            ConfigManager configManager = ConfigManager.getInstance();
            if (configManager != null) {
                // Note: We don't update the config directly here anymore.
                // The value will be passed through the tokens map and handled by OutlookAlerterUI
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "SimpleTokenDialog: Certificate validation value will be set to: " +
                        (ignoreCertValidation ? "disabled" : "enabled"));
            }

            LogManager.getInstance().info(LogCategory.DATA_FETCH, "SimpleTokenDialog: Token submitted (first 10 chars): " +
                               token.substring(0, Math.min(10, token.length())) + "...");

            // Signal completion
            latch.countDown();

            // Mark dialog as no longer showing
            isShowing = false;

            // Use invokeLater for UI operations - safer than invokeAndWait which can cause deadlocks
            SwingUtilities.invokeLater(() -> {
                try {
                    if (frame != null) {
                        LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Disposing dialog frame after submit");
                        frame.setVisible(false);
                        frame.dispose();
                    }

                    if (parentFrame != null && parentFrame.isUndecorated()) {
                        LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Disposing parent frame after submit");
                        parentFrame.dispose();
                    }
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.GENERAL, "SimpleTokenDialog: Error disposing dialog after submit: " + e.getMessage());
                }
            });

        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "SimpleTokenDialog: Unexpected error in submitToken: " + e.getMessage(), e);

            // Still signal completion to avoid hanging
            tokens = null;
            latch.countDown();
        }
    }

    /**
     * Perform MSAL interactive authentication on a background thread.
     * Opens the system browser for Microsoft login and captures the token automatically.
     */
    private void performMsalInteractiveAuth(JButton triggerButton) {
        // Disable button and show status
        SwingUtilities.invokeLater(() -> {
            triggerButton.setEnabled(false);
            triggerButton.setText("Cancel Sign-In");
            triggerButton.setEnabled(true);  // Re-enable as a cancel button
            if (statusLabel != null) {
                statusLabel.setText("Browser opened — complete sign-in there...");
                statusLabel.setForeground(Color.BLUE);
            }
        });

        // Track whether auth was cancelled via the button
        final boolean[] cancelled = {false};

        // Run MSAL interactive auth on a background thread to avoid blocking EDT
        Thread authThread = new Thread(() -> {
            try {
                // Temporarily repurpose button as cancel
                SwingUtilities.invokeLater(() -> {
                    // Remove old listeners and add a cancel listener
                    for (java.awt.event.ActionListener al : triggerButton.getActionListeners()) {
                        triggerButton.removeActionListener(al);
                    }
                    triggerButton.addActionListener(ev -> {
                        cancelled[0] = true;
                        LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                "SimpleTokenDialog: User cancelled MSAL interactive auth");
                        if (msalAuthProvider != null) {
                            msalAuthProvider.cancelPendingAuth();
                        }
                        triggerButton.setEnabled(false);
                        triggerButton.setText("Cancelling...");
                        if (statusLabel != null) {
                            statusLabel.setText("Cancelling sign-in...");
                            statusLabel.setForeground(Color.ORANGE);
                        }
                    });
                });

                String accessToken = msalAuthProvider.acquireTokenInteractively();

                if (accessToken != null && !accessToken.isEmpty()) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH,
                            "SimpleTokenDialog: MSAL interactive auth successful");

                    // Auto-populate the token and submit (skip JWT format validation — MSAL token is trusted)
                    SwingUtilities.invokeLater(() -> {
                        if (tokenField != null) {
                            tokenField.setText(accessToken);
                        }
                        if (statusLabel != null) {
                            statusLabel.setText("Authentication successful!");
                            statusLabel.setForeground(new Color(0, 128, 0)); // dark green
                        }
                        // Restore button before submitting
                        restoreSignInButton(triggerButton);
                        // Submit directly, skipping JWT format validation since MSAL tokens are pre-validated
                        msalAuthenticated = true;
                        submitToken();
                        msalAuthenticated = false;
                    });
                } else {
                    LogManager.getInstance().warn(LogCategory.DATA_FETCH,
                            "SimpleTokenDialog: MSAL interactive auth returned no token");
                    final String msg = cancelled[0]
                            ? "Sign-in cancelled."
                            : "Sign-in timed out or failed. Your organization may require admin consent. Try Graph Explorer instead.";
                    SwingUtilities.invokeLater(() -> {
                        restoreSignInButton(triggerButton);
                        if (statusLabel != null) {
                            statusLabel.setText(msg);
                            statusLabel.setForeground(Color.RED);
                        }
                    });
                }
            } catch (Exception ex) {
                // Unwrap to find root cause
                Throwable cause = ex;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                String rootMsg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                LogManager.getInstance().error(LogCategory.DATA_FETCH,
                        "SimpleTokenDialog: MSAL interactive auth error: " + rootMsg, ex);
                final String errorDisplay = rootMsg.length() > 120 ? rootMsg.substring(0, 120) + "..." : rootMsg;
                SwingUtilities.invokeLater(() -> {
                    restoreSignInButton(triggerButton);
                    if (statusLabel != null) {
                        statusLabel.setText("Error: " + errorDisplay);
                        statusLabel.setForeground(Color.RED);
                    }
                });
            }
        }, "MsalInteractiveAuthThread");
        authThread.setDaemon(true);
        authThread.start();
    }

    /**
     * Perform Okta-federated SSO authentication.
     *
     * <p>Flow:
     * <ol>
     *   <li>Prompt user for their email address (pre-populated from config if available)</li>
     *   <li>Query Microsoft's GetUserRealm API to discover federation type</li>
     *   <li>If Okta federation detected: invoke MSAL interactive auth with {@code domainHint}
     *       so Azure AD routes the browser directly to Okta SSO</li>
     *   <li>On success: auto-populate token field and submit</li>
     * </ol>
     */
    private void performOktaAuth(JButton triggerButton) {
        // Prompt for email — pre-populate from config
        String savedEmail = ConfigManager.getInstance().getUserEmail();
        if (savedEmail == null || savedEmail.isBlank()) {
            savedEmail = ConfigManager.getInstance().getLoginHint();
        }
        String email = (String) JOptionPane.showInputDialog(
                frame,
                "Enter your work email address for Microsoft/Okta SSO sign-in:",
                "SSO Login",
                JOptionPane.QUESTION_MESSAGE,
                null,
                null,
                savedEmail != null ? savedEmail : "");

        if (email == null || email.isBlank()) {
            return;
        }
        final String emailFinal = email.trim();

        ConfigManager.getInstance().updateUserEmail(emailFinal);
        ConfigManager.getInstance().updateAuthMode("okta");

        // Immediately wire the button as a Cancel button — before any network I/O.
        // On cancel we restore the UI right away and abandon the background thread.
        final Thread[] threadRef = new Thread[1];
        final boolean[] cancelledFlag = {false};

        triggerButton.setEnabled(false);
        triggerButton.setText("Requesting code...");
        if (statusLabel != null) {
            statusLabel.setText("Requesting sign-in code from Microsoft...");
            statusLabel.setForeground(Color.BLUE);
        }

        // Replace listeners to become a cancel button
        for (java.awt.event.ActionListener al : triggerButton.getActionListeners()) {
            triggerButton.removeActionListener(al);
        }
        triggerButton.addActionListener(ev -> {
            cancelledFlag[0] = true;
            // Restore UI immediately — don't wait for the thread
            restoreOktaButton(triggerButton);
            if (statusLabel != null) {
                statusLabel.setText("Sign-in cancelled.");
                statusLabel.setForeground(Color.DARK_GRAY);
            }
            // Best-effort: cancel MSAL future + interrupt thread
            if (msalAuthProvider != null) {
                msalAuthProvider.cancelPendingAuth();
            }
            Thread t = threadRef[0];
            if (t != null) {
                t.interrupt();
            }
            LogManager.getInstance().info(LogCategory.DATA_FETCH,
                    "SimpleTokenDialog: User cancelled device code auth — UI restored");
        });
        triggerButton.setText("Cancel Sign-In");
        triggerButton.setEnabled(true);

        Thread oktaThread = new Thread(() -> {
            try {
                LogManager.getInstance().info(LogCategory.DATA_FETCH,
                        "SimpleTokenDialog: Starting device code flow for " + emailFinal);

                // Progress callback updates the status label immediately
                java.util.function.Consumer<String> progressCb = (msg) -> {
                    SwingUtilities.invokeLater(() -> {
                        if (statusLabel != null && !cancelledFlag[0]) {
                            statusLabel.setText(msg);
                            statusLabel.setForeground(Color.BLUE);
                        }
                    });
                };

                // Try each well-known client ID in order. Some tenants block
                // certain first-party IDs with AADSTS65002; cycle through until
                // one is accepted.
                String accessToken = null;
                String lastError = null;
                for (String tryClientId : MsalAuthProvider.GRAPH_CLIENT_IDS) {
                    if (cancelledFlag[0]) return;
                    try {
                        LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                "SimpleTokenDialog: Trying device code flow with clientId=" + tryClientId);
                        accessToken = msalAuthProvider.acquireTokenDeviceCode(
                                tryClientId,
                                (DeviceCode deviceCode) -> {
                            String userCode = deviceCode.userCode();
                            String verificationUrl = deviceCode.verificationUri();
                            LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                    "SimpleTokenDialog: Device code received — code=" + userCode
                                    + ", url=" + verificationUrl);

                            // Copy code to clipboard
                            try {
                                Toolkit.getDefaultToolkit().getSystemClipboard()
                                        .setContents(new StringSelection(userCode), null);
                            } catch (Exception clipEx) {
                                LogManager.getInstance().warn(LogCategory.DATA_FETCH,
                                        "SimpleTokenDialog: Clipboard copy failed: " + clipEx.getMessage());
                            }

                            // Open browser — try multiple approaches
                            boolean browserOpened = false;
                            String os = System.getProperty("os.name", "").toLowerCase();
                            LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                    "SimpleTokenDialog: Opening browser on os=" + os + " url=" + verificationUrl);

                            // Approach 1: Runtime.exec with platform command
                            try {
                                Process p;
                                if (os.contains("mac")) {
                                    p = Runtime.getRuntime().exec(new String[]{"/usr/bin/open", verificationUrl});
                                } else if (os.contains("win")) {
                                    p = Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", "", verificationUrl});
                                } else {
                                    p = Runtime.getRuntime().exec(new String[]{"xdg-open", verificationUrl});
                                }
                                boolean exited = p.waitFor(5, TimeUnit.SECONDS);
                                browserOpened = exited && p.exitValue() == 0;
                                LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                        "SimpleTokenDialog: Runtime.exec result: exited=" + exited
                                        + " exitValue=" + (exited ? p.exitValue() : "N/A"));
                            } catch (Exception browseEx) {
                                LogManager.getInstance().error(LogCategory.DATA_FETCH,
                                        "SimpleTokenDialog: Runtime.exec failed: " + browseEx.getMessage(), browseEx);
                            }

                            // Approach 2: Desktop.browse (fallback)
                            if (!browserOpened) {
                                try {
                                    if (java.awt.Desktop.isDesktopSupported()) {
                                        java.awt.Desktop.getDesktop().browse(new URI(verificationUrl));
                                        browserOpened = true;
                                        LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                                "SimpleTokenDialog: Desktop.browse succeeded");
                                    }
                                } catch (Exception deskEx) {
                                    LogManager.getInstance().error(LogCategory.DATA_FETCH,
                                            "SimpleTokenDialog: Desktop.browse failed: " + deskEx.getMessage(), deskEx);
                                }
                            }

                            final boolean finalBrowserOpened = browserOpened;
                            // Update status label on EDT — always show the code prominently
                            SwingUtilities.invokeLater(() -> {
                                if (statusLabel != null) {
                                    String prefix = finalBrowserOpened
                                            ? "<b>Browser opened.</b>"
                                            : "<b>Open browser manually:</b> <font color='blue'>"
                                              + verificationUrl + "</font>";
                                    statusLabel.setText("<html>" + prefix
                                            + " Enter code: <font size='+2' color='#8B0000'><b>" + userCode
                                            + "</b></font>"
                                            + " (copied to clipboard). Waiting for sign-in...</html>");
                                    statusLabel.setForeground(Color.BLUE);
                                }
                            });
                                },
                                progressCb);
                        // Success — remember which client ID worked and break out
                        ConfigManager.getInstance().updateOktaClientId(tryClientId);
                        break;
                    } catch (Exception clientEx) {
                        Throwable root = clientEx;
                        while (root.getCause() != null && root.getCause() != root) { root = root.getCause(); }
                        String rootMsg = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
                        lastError = rootMsg;

                        if (rootMsg.contains("AADSTS65002")) {
                            LogManager.getInstance().info(LogCategory.DATA_FETCH,
                                    "SimpleTokenDialog: AADSTS65002 with clientId=" + tryClientId + ", trying next...");
                            accessToken = null;
                            continue; // try next client ID
                        }
                        // Any other error — don't retry, rethrow
                        throw clientEx;
                    }
                }

                // If all client IDs failed with AADSTS65002
                if (accessToken == null && lastError != null && lastError.contains("AADSTS65002")) {
                    throw new RuntimeException("All well-known client IDs were rejected by the tenant (AADSTS65002). "
                            + "An Azure AD admin may need to register a custom app. Last error: " + lastError);
                }

                // If user cancelled while we were waiting, just bail
                if (cancelledFlag[0]) return;

                if (accessToken != null && !accessToken.isEmpty()) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH,
                            "SimpleTokenDialog: SSO auth successful");
                    final String token = accessToken;
                    SwingUtilities.invokeLater(() -> {
                        if (cancelledFlag[0]) return;
                        if (tokenField != null) {
                            tokenField.setText(token);
                        }
                        if (statusLabel != null) {
                            statusLabel.setText("Sign-in successful!");
                            statusLabel.setForeground(new Color(0, 128, 0));
                        }
                        restoreOktaButton(triggerButton);
                        msalAuthenticated = true;
                        submitToken();
                        msalAuthenticated = false;
                    });
                } else {
                    LogManager.getInstance().warn(LogCategory.DATA_FETCH,
                            "SimpleTokenDialog: SSO auth returned no token");
                    if (!cancelledFlag[0]) {
                        SwingUtilities.invokeLater(() -> {
                            restoreOktaButton(triggerButton);
                            if (statusLabel != null) {
                                statusLabel.setText("Sign-in returned no token. Try again.");
                                statusLabel.setForeground(Color.RED);
                            }
                        });
                    }
                }

            } catch (Exception ex) {
                if (cancelledFlag[0]) return; // user already cancelled, UI already restored

                Throwable c = ex;
                while (c.getCause() != null && c.getCause() != c) { c = c.getCause(); }
                boolean isCancelled = (c instanceof java.util.concurrent.CancellationException)
                        || (c instanceof InterruptedException);
                if (isCancelled) {
                    LogManager.getInstance().info(LogCategory.DATA_FETCH,
                            "SimpleTokenDialog: SSO auth cancelled/interrupted");
                    SwingUtilities.invokeLater(() -> {
                        restoreOktaButton(triggerButton);
                        if (statusLabel != null) {
                            statusLabel.setText("Sign-in cancelled.");
                            statusLabel.setForeground(Color.DARK_GRAY);
                        }
                    });
                } else {
                    String rootMsg = c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
                    LogManager.getInstance().error(LogCategory.DATA_FETCH,
                            "SimpleTokenDialog: SSO auth error: " + rootMsg, ex);
                    final String errDisplay = rootMsg.length() > 120 ? rootMsg.substring(0, 120) + "..." : rootMsg;
                    SwingUtilities.invokeLater(() -> {
                        restoreOktaButton(triggerButton);
                        if (statusLabel != null) {
                            statusLabel.setText("Error: " + errDisplay);
                            statusLabel.setForeground(Color.RED);
                        }
                        // No JOptionPane — status label is sufficient and doesn't block EDT
                    });
                }
            }
        }, "OktaSsoAuthThread");
        oktaThread.setDaemon(true);
        threadRef[0] = oktaThread;
        oktaThread.start();
    }

    /** Restore the Okta SSO button to its original state. */
    private void restoreOktaButton(JButton button) {
        for (java.awt.event.ActionListener al : button.getActionListeners()) {
            button.removeActionListener(al);
        }
        button.addActionListener(e -> {
            if (!msalAuthProvider.isConfigured()) {
                ConfigManager.getInstance().updateClientId(ConfigManager.getDefaultClientId());
            }
            performOktaAuth(button);
        });
        button.setText("Sign In with Okta SSO");
        button.setEnabled(true);
    }

    /**
     * Restore the Sign In button to its original state after MSAL auth completes or is cancelled.
     */
    private void restoreSignInButton(JButton button) {
        for (java.awt.event.ActionListener al : button.getActionListeners()) {
            button.removeActionListener(al);
        }
        button.addActionListener(e -> {
            if (msalAuthProvider != null) {
                if (!msalAuthProvider.isConfigured()) {
                    String defaultId = ConfigManager.getDefaultClientId();
                    LogManager.getInstance().info(LogCategory.DATA_FETCH,
                            "Auto-configuring default client ID for OAuth sign-in");
                    ConfigManager.getInstance().updateClientId(defaultId);
                }
                performMsalInteractiveAuth(button);
            }
        });
        button.setText("Sign In with Browser");
        button.setEnabled(true);
    }

    /**
     * Cancel the dialog
     */
    private void cancelDialog() {
        try {
            LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: User canceled token entry (or clicked the X button)");
            isExplicitCancel = true;  // Mark this as an explicit cancellation

            // Show cancellation message ONLY when user explicitly cancels (via button or X)
            // and when it wasn't a successful submission
            if (!isTokenSubmitted && isExplicitCancel) {
                try {
                    if (frame != null && frame.isVisible()) {
                        JOptionPane.showMessageDialog(
                            frame,
                            "Authentication was cancelled. The application may have limited functionality.",
                            "Authentication Cancelled",
                            JOptionPane.WARNING_MESSAGE
                        );
                    }
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.GENERAL, "SimpleTokenDialog: Error showing cancellation message: " + e.getMessage());
                }
            }

            // Mark tokens as null to indicate cancellation
            tokens = null;

            // Signal completion to any waiting threads
            if (latch != null && latch.getCount() > 0) {
                LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Counting down latch in cancelDialog");
                latch.countDown();
            }

            // Mark dialog as no longer showing
            isShowing = false;

            // Use invokeLater - simpler and reduces risk of deadlock compared to invokeAndWait
            SwingUtilities.invokeLater(() -> {
                try {
                    if (frame != null) {
                        LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Disposing dialog frame in cancelDialog");
                        frame.setVisible(false);
                        frame.dispose();
                    }

                    if (parentFrame != null && parentFrame.isUndecorated()) {
                        LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Disposing parent frame in cancelDialog");
                        parentFrame.dispose();
                    }
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.GENERAL, "SimpleTokenDialog: Error disposing dialog in cancelDialog: " + e.getMessage());
                }
            });

        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "SimpleTokenDialog: Error in cancelDialog: " + e.getMessage(), e);

            // Still signal completion to avoid hanging
            tokens = null;
            if (latch != null && latch.getCount() > 0) {
                latch.countDown();
            }
        }
    }

    /**
     * Wait for the user to enter a token or cancel
     * @param timeout Maximum seconds to wait
     * @return Map with accessToken and ignoreCertValidation, or null if canceled
     */
    public Map<String, String> waitForTokens(int timeout) {
        try {
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "SimpleTokenDialog: Waiting for token entry (timeout: " + timeout + " seconds)");
            boolean completed = latch.await(timeout, TimeUnit.SECONDS);

            if (!completed) {
                LogManager.getInstance().warn(LogCategory.DATA_FETCH, "SimpleTokenDialog: Token entry timed out after " + timeout + " seconds");

                // Mark dialog as no longer showing
                isShowing = false;

                // Use invokeLater for UI operations
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (frame != null) {
                            LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Disposing frame after timeout");
                            frame.setVisible(false);
                            frame.dispose();
                        }
                        if (parentFrame != null && parentFrame.isUndecorated()) {
                            LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Disposing parent frame after timeout");
                            parentFrame.dispose();
                        }
                    } catch (Exception e) {
                        LogManager.getInstance().error(LogCategory.GENERAL, "SimpleTokenDialog: Error disposing dialog after timeout: " + e.getMessage());
                    }
                });

                return null;
            }

            LogManager.getInstance().info(LogCategory.DATA_FETCH, "SimpleTokenDialog: Token entry completed, returning " +
                              (tokens != null ? "valid token data" : "null (canceled)"));
            return tokens;
        } catch (InterruptedException e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "SimpleTokenDialog: Token entry interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "SimpleTokenDialog: Unexpected error in waitForTokens: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Get tokens entered by user. Waits until tokens are submitted or dialog is closed.
     * @return Map containing accessToken and ignoreCertValidation, or null if canceled
     */
    public Map<String, String> getTokens() {
        try {
            LogManager.getInstance().info(LogCategory.DATA_FETCH, "SimpleTokenDialog: Waiting for token input (max 5 minutes)");
            boolean completed = latch.await(5, TimeUnit.MINUTES);

            if (!completed) {
                LogManager.getInstance().warn(LogCategory.DATA_FETCH, "SimpleTokenDialog: Token wait timed out");
                cleanup();
                return null;
            }

            if (tokens == null) {
                LogManager.getInstance().info(LogCategory.DATA_FETCH, "SimpleTokenDialog: Dialog was cancelled (tokens == null)");
                cleanup();
                return null;
            }

            LogManager.getInstance().info(LogCategory.DATA_FETCH, "SimpleTokenDialog: Tokens received successfully");
            Map<String, String> result = new HashMap<>(tokens);
            cleanup();
            return result;

        } catch (InterruptedException e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "SimpleTokenDialog: Token wait interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
            cleanup();
            return null;
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.DATA_FETCH, "SimpleTokenDialog: Unexpected error in getTokens: " + e.getMessage(), e);
            cleanup();
            return null;
        }
    }

    /**
     * Reset the dialog UI components
     */
    private void resetDialogUI() {
        if (frame != null) {
            SwingUtilities.invokeLater(() -> {
                try {
                    LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: Resetting UI components");

                    // Clear previous text and reset font sizes
                    if (tokenField != null) {
                        tokenField.setText("");
                        tokenField.setFont(new Font("Arial", Font.PLAIN, 12));
                    }

                    // Reset other UI components if necessary
                    Container contentPane = frame.getContentPane();
                    if (contentPane instanceof JPanel) {
                        JPanel panel = (JPanel) contentPane;
                        for (Component component : panel.getComponents()) {
                            if (component instanceof JLabel) {
                                ((JLabel) component).setFont(new Font("Arial", Font.PLAIN, 12));
                            } else if (component instanceof JButton) {
                                ((JButton) component).setFont(new Font("Arial", Font.PLAIN, 12));
                            }
                        }
                    }

                    LogManager.getInstance().info(LogCategory.GENERAL, "SimpleTokenDialog: UI components reset successfully");
                } catch (Exception e) {
                    LogManager.getInstance().error(LogCategory.GENERAL, "SimpleTokenDialog: Error resetting UI components: " + e.getMessage(), e);
                }
            });
        }
    }
}
