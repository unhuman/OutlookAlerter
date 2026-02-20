package com.unhuman.outlookalerter.ui;

import com.unhuman.outlookalerter.core.ConfigManager;
import com.unhuman.outlookalerter.core.OutlookClient;
import com.unhuman.outlookalerter.util.LogManager;
import com.unhuman.outlookalerter.util.LogCategory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Settings dialog for OutlookAlerter
 * Provides UI for configuring application settings
 */
public class SettingsDialog extends JDialog {
    private ConfigManager configManager;
    private OutlookClient outlookClient;
    private OutlookAlerterUI parent;

    // UI Components
    private JTextField timezoneField;
    private JSpinner alertMinutesSpinner;
    private JSpinner resyncIntervalSpinner;
    private JSpinner flashDurationSpinner;
    private JSpinner flashOpacitySpinner;
    private JSpinner alertBeepCountSpinner;
    private JCheckBox alertBeepAfterFlashCheckbox;
    private JTextField signInUrlField;
    private JTextField clientIdField;
    private JTextField tenantIdField;
    private JCheckBox defaultIgnoreCertValidationCheckbox;

    /**
     * Create a new settings dialog
     *
     * @param parent The parent window
     * @param configManager The configuration manager
     * @param outlookClient The outlook client for API interactions
     */
    public SettingsDialog(OutlookAlerterUI parent, ConfigManager configManager, OutlookClient outlookClient) {
        super(parent, "OutlookAlerter Settings", true);
        this.parent = parent;
        this.configManager = configManager;
        this.outlookClient = outlookClient;

        initUI();
    }

    /**
     * Initialize the UI components
     */
    private void initUI() {
        setLayout(new BorderLayout(10, 10));
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        // Form panel with GridBagLayout for more control
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.weightx = 0.5;

        // Timezone setting
        gbc.gridx = 0;
        gbc.gridy = 0;
        formPanel.add(new JLabel("Preferred Timezone (e.g., America/New_York):"), gbc);

        timezoneField = new JTextField(configManager.getPreferredTimezone() != null ? configManager.getPreferredTimezone() : "", 20);
        gbc.gridx = 1;
        gbc.gridy = 0;
        formPanel.add(timezoneField, gbc);

        // Alert minutes setting
        gbc.gridx = 0;
        gbc.gridy = 1;
        formPanel.add(new JLabel("Alert Minutes Before Meeting:"), gbc);

        SpinnerNumberModel alertMinutesModel = new SpinnerNumberModel(
            configManager.getAlertMinutes(),  // initial value
            1,                               // min
            60,                              // max
            1                                // step
        );
        alertMinutesSpinner = new JSpinner(alertMinutesModel);
        gbc.gridx = 1;
        gbc.gridy = 1;
        formPanel.add(alertMinutesSpinner, gbc);

        // Resync interval setting
        gbc.gridx = 0;
        gbc.gridy = 2;
        formPanel.add(new JLabel("Resync Calendar Every (minutes):"), gbc);

        SpinnerNumberModel resyncModel = new SpinnerNumberModel(
            configManager.getResyncIntervalMinutes(),  // initial value
            5,                                        // min
            1440,                                     // max (24 hours)
            5                                         // step
        );
        resyncIntervalSpinner = new JSpinner(resyncModel);
        gbc.gridx = 1;
        gbc.gridy = 2;
        formPanel.add(resyncIntervalSpinner, gbc);

        // Flash duration setting
        gbc.gridx = 0;
        gbc.gridy = 3;
        formPanel.add(new JLabel("Screen Flash Duration (seconds):"), gbc);

        SpinnerNumberModel flashDurationModel = new SpinnerNumberModel(
            configManager.getFlashDurationSeconds(),  // initial value
            1,                                       // min
            30,                                      // max (30 seconds)
            1                                        // step
        );
        flashDurationSpinner = new JSpinner(flashDurationModel);
        gbc.gridx = 1;
        gbc.gridy = 3;
        formPanel.add(flashDurationSpinner, gbc);

        // Flash opacity setting
        gbc.gridx = 0;
        gbc.gridy = 4;
        formPanel.add(new JLabel("Screen Flash Opacity (%):"), gbc);

        SpinnerNumberModel flashOpacityModel = new SpinnerNumberModel(
            (int) Math.round(configManager.getFlashOpacity() * 100),  // convert decimal to percentage
            10,                              // min (10%)
            100,                             // max (100%)
            10                               // step
        );
        flashOpacitySpinner = new JSpinner(flashOpacityModel);
        gbc.gridx = 1;
        gbc.gridy = 4;
        formPanel.add(flashOpacitySpinner, gbc);

        // Alert beep count setting
        gbc.gridx = 0;
        gbc.gridy = 5;
        formPanel.add(new JLabel("Alert Beep Count:"), gbc);

        SpinnerNumberModel beepCountModel = new SpinnerNumberModel(
            configManager.getAlertBeepCount(),  // initial value
            0,                                 // min (allow mute)
            20,                                // max
            1                                  // step
        );
        alertBeepCountSpinner = new JSpinner(beepCountModel);
        gbc.gridx = 1;
        gbc.gridy = 5;
        formPanel.add(alertBeepCountSpinner, gbc);

        // Alert beep after flash setting
        gbc.gridx = 0;
        gbc.gridy = 6;
        formPanel.add(new JLabel("Alert Beep Again After Flash:"), gbc);

        alertBeepAfterFlashCheckbox = new JCheckBox("", configManager.getAlertBeepAfterFlash());
        gbc.gridx = 1;
        gbc.gridy = 6;
        formPanel.add(alertBeepAfterFlashCheckbox, gbc);

        // Sign-in URL setting
        gbc.gridx = 0;
        gbc.gridy = 7;
        formPanel.add(new JLabel("Sign-in URL:"), gbc);

        signInUrlField = new JTextField(configManager.getSignInUrl() != null ? configManager.getSignInUrl() : "", 20);
        gbc.gridx = 1;
        gbc.gridy = 7;
        formPanel.add(signInUrlField, gbc);

        // OAuth Client ID setting
        gbc.gridx = 0;
        gbc.gridy = 8;
        formPanel.add(new JLabel("Client ID (Azure AD App):"), gbc);

        clientIdField = new JTextField(configManager.getClientId() != null ? configManager.getClientId() : "", 20);
        clientIdField.setToolTipText("Register an app at portal.azure.com to enable automatic browser sign-in");
        gbc.gridx = 1;
        gbc.gridy = 8;
        formPanel.add(clientIdField, gbc);

        // OAuth Tenant ID setting
        gbc.gridx = 0;
        gbc.gridy = 9;
        formPanel.add(new JLabel("Tenant ID (default: common):"), gbc);

        tenantIdField = new JTextField(configManager.getTenantId() != null ? configManager.getTenantId() : "common", 20);
        tenantIdField.setToolTipText("Your Azure AD tenant ID or 'common' for multi-tenant");
        gbc.gridx = 1;
        gbc.gridy = 9;
        formPanel.add(tenantIdField, gbc);

        // Test Sign In button
        gbc.gridx = 0;
        gbc.gridy = 10;
        formPanel.add(new JLabel("Test OAuth Sign-in:"), gbc);

        JButton testSignInButton = new JButton("Test Sign In");
        testSignInButton.setToolTipText("Test MSAL browser sign-in with the configured Client ID");
        testSignInButton.addActionListener(e -> testMsalSignIn(testSignInButton));
        gbc.gridx = 1;
        gbc.gridy = 10;
        formPanel.add(testSignInButton, gbc);

        // Default Ignore SSL certificate validation setting
        gbc.gridx = 0;
        gbc.gridy = 11;
        formPanel.add(new JLabel("Default Ignore SSL certificate validation:"), gbc);

        defaultIgnoreCertValidationCheckbox = new JCheckBox("(note security implications)", configManager.getDefaultIgnoreCertValidation());
        // No longer update immediately when checkbox changes
        gbc.gridx = 1;
        gbc.gridy = 11;
        formPanel.add(defaultIgnoreCertValidationCheckbox, gbc);

        // Button panel
        JPanel buttonPanel = new JPanel();
        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveSettings();
            }
        });

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });

        buttonPanel.add(saveButton);
        buttonPanel.add(cancelButton);

        add(formPanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(parent);

        // Add window listener to handle dialog closing
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dispose();
            }
        });
    }

    /**
     * Save all settings from the dialog
     */
    private void saveSettings() {
        try {
            // Save timezone
            String timezone = timezoneField.getText().trim();
            if (!timezone.isEmpty()) {
                java.time.ZoneId.of(timezone); // Validate timezone
                configManager.updatePreferredTimezone(timezone);
            }

            // Save sign-in URL
            String signInUrl = signInUrlField.getText().trim();
            if (!signInUrl.isEmpty()) {
                configManager.updateSignInUrl(signInUrl);
            }

            // Save Client ID
            String clientId = clientIdField.getText().trim();
            configManager.updateClientId(clientId);

            // Save Tenant ID
            String tenantId = tenantIdField.getText().trim();
            if (tenantId.isEmpty()) {
                tenantId = "common";
            }
            configManager.updateTenantId(tenantId);

            // Save alert minutes
            int alertMinutes = (Integer) alertMinutesSpinner.getValue();
            configManager.updateAlertMinutes(alertMinutes);

            // Save resync interval
            int resyncInterval = (Integer) resyncIntervalSpinner.getValue();
            configManager.updateResyncIntervalMinutes(resyncInterval);

            // Save flash duration
            int flashDuration = (Integer) flashDurationSpinner.getValue();
            configManager.updateFlashDurationSeconds(flashDuration);

            // Save flash opacity (convert percentage to decimal)
            int flashOpacityPercent = (Integer) flashOpacitySpinner.getValue();
            configManager.updateFlashOpacity(flashOpacityPercent / 100.0);

            // Save alert beep count
            int beepCount = (Integer) alertBeepCountSpinner.getValue();
            configManager.updateAlertBeepCount(beepCount);

            // Save alert beep after flash
            configManager.updateAlertBeepAfterFlash(alertBeepAfterFlashCheckbox.isSelected());

            // Save the SSL certificate validation setting
            boolean defaultIgnoreCertVal = defaultIgnoreCertValidationCheckbox.isSelected();
            configManager.updateDefaultIgnoreCertValidation(defaultIgnoreCertVal);

            // Update the HTTP client to respect the new certificate validation setting
            outlookClient.updateCertificateValidation(defaultIgnoreCertVal);

            // Notify parent UI to restart schedulers with the new settings
            parent.restartSchedulers();

            dispose();
            JOptionPane.showMessageDialog(parent, "Settings saved successfully", "Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            String errorMessage = "Invalid settings: " + ex.getMessage();
            LogManager.getInstance().error(LogCategory.GENERAL, errorMessage);
            JOptionPane.showMessageDialog(this, errorMessage, "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Test MSAL browser sign-in with the currently entered Client ID and Tenant ID.
     */
    private void testMsalSignIn(JButton triggerButton) {
        String clientId = clientIdField.getText().trim();
        if (clientId.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please enter a Client ID first.\n\n" +
                    "To get a Client ID:\n" +
                    "1. Go to portal.azure.com → Azure Active Directory → App registrations\n" +
                    "2. Click 'New registration'\n" +
                    "3. Name: 'OutlookAlerter', Redirect URI: http://localhost:8888\n" +
                    "4. Copy the 'Application (client) ID'",
                    "Client ID Required",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Temporarily save the config so MsalAuthProvider picks it up
        configManager.updateClientId(clientId);
        String tenantId = tenantIdField.getText().trim();
        if (tenantId.isEmpty()) tenantId = "common";
        configManager.updateTenantId(tenantId);

        triggerButton.setEnabled(false);
        triggerButton.setText("Waiting for browser...");

        // Run on background thread
        Thread testThread = new Thread(() -> {
            try {
                com.unhuman.outlookalerter.core.MsalAuthProvider testProvider =
                        new com.unhuman.outlookalerter.core.MsalAuthProvider(configManager);
                String token = testProvider.acquireTokenInteractively();

                SwingUtilities.invokeLater(() -> {
                    triggerButton.setEnabled(true);
                    triggerButton.setText("Test Sign In");
                    if (token != null && !token.isEmpty()) {
                        JOptionPane.showMessageDialog(SettingsDialog.this,
                                "Sign-in successful! OAuth is configured correctly.",
                                "Success",
                                JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(SettingsDialog.this,
                                "Sign-in failed or was cancelled.",
                                "Test Failed",
                                JOptionPane.WARNING_MESSAGE);
                    }
                });
            } catch (Exception ex) {
                LogManager.getInstance().error(LogCategory.GENERAL,
                        "MSAL test sign-in failed: " + ex.getMessage(), ex);
                SwingUtilities.invokeLater(() -> {
                    triggerButton.setEnabled(true);
                    triggerButton.setText("Test Sign In");
                    JOptionPane.showMessageDialog(SettingsDialog.this,
                            "Sign-in error: " + ex.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "MsalTestSignInThread");
        testThread.setDaemon(true);
        testThread.start();
    }
}
