package com.unhuman.outlookalerter.ui;

import com.unhuman.outlookalerter.core.ConfigManager;
import com.unhuman.outlookalerter.core.OutlookClient;
import com.unhuman.outlookalerter.util.LogManager;
import com.unhuman.outlookalerter.util.LogCategory;

import javax.swing.*;
import javax.swing.colorchooser.AbstractColorChooserPanel;
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
    private JComboBox<String> timezoneCombo;
    private JSpinner alertMinutesSpinner;
    private JSpinner resyncIntervalSpinner;
    private JSpinner flashDurationSpinner;
    private JSpinner flashOpacitySpinner;
    private JButton flashColorButton;
    private JButton flashTextColorButton;
    private JButton alertBannerColorButton;
    private JButton alertBannerTextColorButton;
    private JSpinner alertBeepCountSpinner;
    private JCheckBox alertBeepAfterFlashCheckbox;
    private JSpinner joinDialogTimeoutSpinner;
    private JSpinner snoozeMinutesSpinner;
    private JCheckBox ignoreAllDayEventsCheckbox;
    private JTextField alertSoundPathField;
    private JTextField signInUrlField;
    private JTextField clientIdField;
    private JTextField tenantIdField;
    private JTextField userEmailField;
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
        formPanel.add(new JLabel("Preferred Timezone:"), gbc);

        java.util.List<String> zoneIds = new java.util.ArrayList<>(java.time.ZoneId.getAvailableZoneIds());
        java.util.Collections.sort(zoneIds);
        timezoneCombo = new JComboBox<>(zoneIds.toArray(new String[0]));
        String currentTz = configManager.getPreferredTimezone();
        if (currentTz == null || currentTz.isEmpty()) {
            currentTz = java.time.ZoneId.systemDefault().getId();
        }
        timezoneCombo.setSelectedItem(currentTz);
        gbc.gridx = 1;
        gbc.gridy = 0;
        formPanel.add(timezoneCombo, gbc);

        // Resync interval setting
        gbc.gridx = 0;
        gbc.gridy = 1;
        formPanel.add(new JLabel("Resync Calendar Every (minutes):"), gbc);

        SpinnerNumberModel resyncModel = new SpinnerNumberModel(
            configManager.getResyncIntervalMinutes(),  // initial value
            5,                                        // min
            1440,                                     // max (24 hours)
            5                                         // step
        );
        resyncIntervalSpinner = new JSpinner(resyncModel);
        gbc.gridx = 1;
        gbc.gridy = 1;
        formPanel.add(resyncIntervalSpinner, gbc);

        // Ignore All Day Events setting
        gbc.gridx = 0;
        gbc.gridy = 2;
        formPanel.add(new JLabel("Ignore All Day Events:"), gbc);

        ignoreAllDayEventsCheckbox = new JCheckBox("", configManager.getIgnoreAllDayEvents());
        gbc.gridx = 1;
        gbc.gridy = 2;
        formPanel.add(ignoreAllDayEventsCheckbox, gbc);

        // Separator before alert settings
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        formPanel.add(new JSeparator(SwingConstants.HORIZONTAL), gbc);
        gbc.gridwidth = 1;

        // Alert minutes setting
        gbc.gridx = 0;
        gbc.gridy = 4;
        formPanel.add(new JLabel("Alert Minutes Before Meeting:"), gbc);

        SpinnerNumberModel alertMinutesModel = new SpinnerNumberModel(
            configManager.getAlertMinutes(),  // initial value
            1,                               // min
            60,                              // max
            1                                // step
        );
        alertMinutesSpinner = new JSpinner(alertMinutesModel);
        gbc.gridx = 1;
        gbc.gridy = 4;
        formPanel.add(alertMinutesSpinner, gbc);

        // Flash duration setting
        gbc.gridx = 0;
        gbc.gridy = 5;
        formPanel.add(new JLabel("Alert Flash Duration (seconds):"), gbc);

        SpinnerNumberModel flashDurationModel = new SpinnerNumberModel(
            configManager.getFlashDurationSeconds(),  // initial value
            1,                                       // min
            30,                                      // max (30 seconds)
            1                                        // step
        );
        flashDurationSpinner = new JSpinner(flashDurationModel);
        gbc.gridx = 1;
        gbc.gridy = 5;
        formPanel.add(flashDurationSpinner, gbc);

        // Flash colors setting (background + text on same row)
        gbc.gridx = 0;
        gbc.gridy = 6;
        formPanel.add(new JLabel("Alert Flash Colors:"), gbc);

        String initialFlashColorHex = configManager.getFlashColor() != null ? configManager.getFlashColor() : "#800000";
        Color initialFlashColor;
        try { initialFlashColor = Color.decode(initialFlashColorHex); } catch (Exception ex) { initialFlashColor = new Color(128, 0, 0); }
        flashColorButton = new JButton("  ");
        flashColorButton.setBackground(initialFlashColor);
        flashColorButton.setOpaque(true);
        flashColorButton.setContentAreaFilled(true);
        flashColorButton.setBorderPainted(true);
        flashColorButton.setFocusPainted(false);
        flashColorButton.setPreferredSize(new Dimension(40, 20));
        flashColorButton.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        flashColorButton.addActionListener(e -> {
            Color chosen = showSwatchesColorChooser("Choose Alert Flash Background Color", flashColorButton.getBackground());
            if (chosen != null) {
                flashColorButton.setBackground(chosen);
                flashColorButton.repaint();
                flashColorButton.revalidate();
            }
        });

        String initialFlashTextColorHex = configManager.getFlashTextColor() != null ? configManager.getFlashTextColor() : "#ffffff";
        Color initialFlashTextColor;
        try { initialFlashTextColor = Color.decode(initialFlashTextColorHex); } catch (Exception ex) { initialFlashTextColor = Color.WHITE; }
        flashTextColorButton = new JButton("  ");
        flashTextColorButton.setBackground(initialFlashTextColor);
        flashTextColorButton.setOpaque(true);
        flashTextColorButton.setContentAreaFilled(true);
        flashTextColorButton.setBorderPainted(true);
        flashTextColorButton.setFocusPainted(false);
        flashTextColorButton.setPreferredSize(new Dimension(40, 20));
        flashTextColorButton.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        flashTextColorButton.addActionListener(e -> {
            Color chosen = showSwatchesColorChooser("Choose Alert Flash Text Color", flashTextColorButton.getBackground());
            if (chosen != null) {
                flashTextColorButton.setBackground(chosen);
                flashTextColorButton.repaint();
                flashTextColorButton.revalidate();
            }
        });

        JPanel flashColorsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        flashColorsPanel.setOpaque(false);
        flashColorsPanel.add(new JLabel("Flash Background:"));
        flashColorsPanel.add(flashColorButton);
        flashColorsPanel.add(new JLabel("   Flash Text:"));
        flashColorsPanel.add(flashTextColorButton);
        gbc.gridx = 1;
        gbc.gridy = 6;
        formPanel.add(flashColorsPanel, gbc);

        // Alert flash opacity setting
        gbc.gridx = 0;
        gbc.gridy = 7;
        formPanel.add(new JLabel("Alert Flash Opacity (%):"), gbc);

        SpinnerNumberModel flashOpacityModel = new SpinnerNumberModel(
            (int) Math.round(configManager.getFlashOpacity() * 100),  // convert decimal to percentage
            10,                              // min (10%)
            100,                             // max (100%)
            10                               // step
        );
        flashOpacitySpinner = new JSpinner(flashOpacityModel);
        gbc.gridx = 1;
        gbc.gridy = 7;
        formPanel.add(flashOpacitySpinner, gbc);

        // Alert banner colors setting (background + text on same row)
        gbc.gridx = 0;
        gbc.gridy = 8;
        formPanel.add(new JLabel("Alert Banner Colors:"), gbc);

        String initialBannerColorHex = configManager.getAlertBannerColor() != null ? configManager.getAlertBannerColor() : "#dc0000";
        Color initialBannerColor;
        try { initialBannerColor = Color.decode(initialBannerColorHex); } catch (Exception ex) { initialBannerColor = new Color(220, 0, 0); }
        alertBannerColorButton = new JButton("  ");
        alertBannerColorButton.setBackground(initialBannerColor);
        alertBannerColorButton.setOpaque(true);
        alertBannerColorButton.setContentAreaFilled(true);
        alertBannerColorButton.setBorderPainted(true);
        alertBannerColorButton.setFocusPainted(false);
        alertBannerColorButton.setPreferredSize(new Dimension(40, 20));
        alertBannerColorButton.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        alertBannerColorButton.addActionListener(e -> {
            Color chosen = showSwatchesColorChooser("Choose Alert Banner Background Color", alertBannerColorButton.getBackground());
            if (chosen != null) {
                alertBannerColorButton.setBackground(chosen);
                alertBannerColorButton.repaint();
                alertBannerColorButton.revalidate();
            }
        });

        String initialBannerTextColorHex = configManager.getAlertBannerTextColor() != null ? configManager.getAlertBannerTextColor() : "#ffffff";
        Color initialBannerTextColor;
        try { initialBannerTextColor = Color.decode(initialBannerTextColorHex); } catch (Exception ex) { initialBannerTextColor = Color.WHITE; }
        alertBannerTextColorButton = new JButton("  ");
        alertBannerTextColorButton.setBackground(initialBannerTextColor);
        alertBannerTextColorButton.setOpaque(true);
        alertBannerTextColorButton.setContentAreaFilled(true);
        alertBannerTextColorButton.setBorderPainted(true);
        alertBannerTextColorButton.setFocusPainted(false);
        alertBannerTextColorButton.setPreferredSize(new Dimension(40, 20));
        alertBannerTextColorButton.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        alertBannerTextColorButton.addActionListener(e -> {
            Color chosen = showSwatchesColorChooser("Choose Alert Banner Text Color", alertBannerTextColorButton.getBackground());
            if (chosen != null) {
                alertBannerTextColorButton.setBackground(chosen);
                alertBannerTextColorButton.repaint();
                alertBannerTextColorButton.revalidate();
            }
        });

        JPanel bannerColorsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        bannerColorsPanel.setOpaque(false);
        bannerColorsPanel.add(new JLabel("Banner Background:"));
        bannerColorsPanel.add(alertBannerColorButton);
        bannerColorsPanel.add(new JLabel("   Banner Text:"));
        bannerColorsPanel.add(alertBannerTextColorButton);
        gbc.gridx = 1;
        gbc.gridy = 8;
        formPanel.add(bannerColorsPanel, gbc);

        // Alert beep count setting
        gbc.gridx = 0;
        gbc.gridy = 9;
        formPanel.add(new JLabel("Alert Beep Count:"), gbc);

        SpinnerNumberModel beepCountModel = new SpinnerNumberModel(
            configManager.getAlertBeepCount(),  // initial value
            0,                                 // min (allow mute)
            20,                                // max
            1                                  // step
        );
        alertBeepCountSpinner = new JSpinner(beepCountModel);
        gbc.gridx = 1;
        gbc.gridy = 9;
        formPanel.add(alertBeepCountSpinner, gbc);

        // Alert beep after flash setting
        gbc.gridx = 0;
        gbc.gridy = 10;
        formPanel.add(new JLabel("Alert Beep Again After Flash:"), gbc);

        alertBeepAfterFlashCheckbox = new JCheckBox("", configManager.getAlertBeepAfterFlash());
        gbc.gridx = 1;
        gbc.gridy = 10;
        formPanel.add(alertBeepAfterFlashCheckbox, gbc);

        // Join dialog timeout setting
        gbc.gridx = 0;
        gbc.gridy = 11;
        formPanel.add(new JLabel("Join Dialog Timeout (seconds, 0=indefinite):"), gbc);

        SpinnerNumberModel joinDialogTimeoutModel = new SpinnerNumberModel(
            configManager.getJoinDialogTimeoutSeconds(),  // initial value
            0,                                            // min (0 = indefinite)
            600,                                          // max (10 minutes)
            1                                             // step
        );
        joinDialogTimeoutSpinner = new JSpinner(joinDialogTimeoutModel);
        gbc.gridx = 1;
        gbc.gridy = 11;
        formPanel.add(joinDialogTimeoutSpinner, gbc);

        // Snooze time setting
        gbc.gridx = 0;
        gbc.gridy = 12;
        formPanel.add(new JLabel("Snooze Time (minutes):"), gbc);

        SpinnerNumberModel snoozeMinutesModel = new SpinnerNumberModel(
            configManager.getSnoozeMinutes(),  // initial value
            1,                                  // min
            60,                                 // max
            1                                   // step
        );
        snoozeMinutesSpinner = new JSpinner(snoozeMinutesModel);
        gbc.gridx = 1;
        gbc.gridy = 12;
        formPanel.add(snoozeMinutesSpinner, gbc);

        // Alert sound path setting (macOS only)
        gbc.gridx = 0;
        gbc.gridy = 13;
        formPanel.add(new JLabel("Alert Sound File (macOS):"), gbc);

        JPanel soundPanel = new JPanel(new BorderLayout(4, 0));
        alertSoundPathField = new JTextField(configManager.getAlertSoundPath(), 20);
        JButton soundBrowseButton = new JButton("Browse...");
        soundBrowseButton.addActionListener(e -> {
            final Process[] previewProc = {null};
            Runnable stopPreview = () -> {
                if (previewProc[0] != null && previewProc[0].isAlive()) {
                    previewProc[0].destroy();
                    previewProc[0] = null;
                }
            };

            JFileChooser chooser = new JFileChooser();
            // Open at the directory of the currently configured file, defaulting to /System/Library/Sounds/
            java.io.File currentFile = new java.io.File(alertSoundPathField.getText().trim());
            java.io.File startDir = currentFile.getParentFile();
            if (startDir == null || !startDir.exists()) {
                startDir = new java.io.File("/System/Library/Sounds");
            }
            chooser.setCurrentDirectory(startDir);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Audio files (*.aiff, *.wav, *.mp3)", "aiff", "wav", "mp3"));
            // Hide the L&F's own Open/Cancel buttons so we can supply our own row
            chooser.setControlButtonsAreShown(false);

            // Build our own button row: [▶ Preview]  [Open]  [Cancel]
            JButton previewBtn = new JButton("▶  Preview");
            JButton openBtn    = new JButton("Open");
            JButton cancelBtn  = new JButton("Cancel");
            previewBtn.setEnabled(false);
            openBtn.setEnabled(false);

            chooser.addPropertyChangeListener(JFileChooser.SELECTED_FILE_CHANGED_PROPERTY, pce -> {
                stopPreview.run();
                java.io.File sel = (java.io.File) pce.getNewValue();
                boolean isFile = sel != null && sel.isFile();
                previewBtn.setEnabled(isFile);
                openBtn.setEnabled(isFile);
            });

            previewBtn.addActionListener(pe -> {
                stopPreview.run();
                java.io.File sel = chooser.getSelectedFile();
                if (sel != null && sel.isFile()) {
                    try {
                        previewProc[0] = Runtime.getRuntime()
                                .exec(new String[]{"afplay", sel.getAbsolutePath()});
                    } catch (java.io.IOException ex) { /* ignore */ }
                }
            });

            // Build the dialog manually so we fully control the layout.
            // Owner is SettingsDialog.this (a Dialog) so the file picker surfaces
            // on top of — and is modal to — the Settings window that launched it.
            javax.swing.JDialog fileDialog = new javax.swing.JDialog(
                    SettingsDialog.this,
                    "Select Alert Sound File",
                    java.awt.Dialog.ModalityType.DOCUMENT_MODAL);
            fileDialog.setDefaultCloseOperation(javax.swing.JDialog.DISPOSE_ON_CLOSE);

            final int[] result = {JFileChooser.CANCEL_OPTION};

            openBtn.addActionListener(oe -> {
                result[0] = JFileChooser.APPROVE_OPTION;
                stopPreview.run();
                fileDialog.dispose();
            });
            cancelBtn.addActionListener(ce -> {
                stopPreview.run();
                fileDialog.dispose();
            });
            // Double-click / Enter in the chooser fires approveSelection
            chooser.addActionListener(ae -> {
                if (JFileChooser.APPROVE_SELECTION.equals(ae.getActionCommand())
                        && chooser.getSelectedFile() != null
                        && chooser.getSelectedFile().isFile()) {
                    result[0] = JFileChooser.APPROVE_OPTION;
                    stopPreview.run();
                    fileDialog.dispose();
                }
            });

            JPanel buttonRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 6));
            buttonRow.add(previewBtn);
            buttonRow.add(openBtn);
            buttonRow.add(cancelBtn);

            fileDialog.getContentPane().add(chooser, java.awt.BorderLayout.CENTER);
            fileDialog.getContentPane().add(buttonRow, java.awt.BorderLayout.SOUTH);
            fileDialog.pack();
            fileDialog.setLocationRelativeTo(SettingsDialog.this);
            fileDialog.setVisible(true); // blocks until disposed

            if (result[0] == JFileChooser.APPROVE_OPTION) {
                alertSoundPathField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JButton soundPreviewButton = new JButton("Preview");
        soundPreviewButton.setToolTipText("Play the selected sound file");
        soundPreviewButton.addActionListener(e -> {
            String path = alertSoundPathField.getText().trim();
            if (path.isEmpty()) {
                return;
            }
            if (!new java.io.File(path).exists()) {
                javax.swing.JOptionPane.showMessageDialog(SettingsDialog.this,
                        "File not found: " + path, "Preview", javax.swing.JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                Runtime.getRuntime().exec(new String[]{"afplay", path});
            } catch (java.io.IOException ex) {
                javax.swing.JOptionPane.showMessageDialog(SettingsDialog.this,
                        "Could not play sound: " + ex.getMessage(), "Preview", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        });
        JPanel soundButtonPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        soundButtonPanel.add(soundPreviewButton);
        soundButtonPanel.add(soundBrowseButton);
        soundPanel.add(alertSoundPathField, BorderLayout.CENTER);
        soundPanel.add(soundButtonPanel, BorderLayout.EAST);
        gbc.gridx = 1;
        gbc.gridy = 13;
        formPanel.add(soundPanel, gbc);

        // Separator after alert settings
        gbc.gridx = 0;
        gbc.gridy = 14;
        gbc.gridwidth = 2;
        formPanel.add(new JSeparator(SwingConstants.HORIZONTAL), gbc);
        gbc.gridwidth = 1;

        // Sign-in URL setting
        gbc.gridx = 0;
        gbc.gridy = 15;
        formPanel.add(new JLabel("Sign-in URL:"), gbc);

        signInUrlField = new JTextField(configManager.getSignInUrl() != null ? configManager.getSignInUrl() : "", 20);
        gbc.gridx = 1;
        gbc.gridy = 15;
        formPanel.add(signInUrlField, gbc);

        // OAuth Client ID setting
        gbc.gridx = 0;
        gbc.gridy = 16;
        formPanel.add(new JLabel("Client ID (Azure AD App):"), gbc);

        clientIdField = new JTextField(configManager.getClientId() != null ? configManager.getClientId() : "", 20);
        clientIdField.setToolTipText("Register an app at portal.azure.com to enable automatic browser sign-in");
        gbc.gridx = 1;
        gbc.gridy = 16;
        formPanel.add(clientIdField, gbc);

        // OAuth Tenant ID setting
        gbc.gridx = 0;
        gbc.gridy = 17;
        formPanel.add(new JLabel("Tenant ID (default: common):"), gbc);

        tenantIdField = new JTextField(configManager.getTenantId() != null ? configManager.getTenantId() : "common", 20);
        tenantIdField.setToolTipText("Your Azure AD tenant ID or 'common' for multi-tenant");
        gbc.gridx = 1;
        gbc.gridy = 17;
        formPanel.add(tenantIdField, gbc);

        // Test Sign In button
        gbc.gridx = 0;
        gbc.gridy = 18;
        formPanel.add(new JLabel("Test OAuth Sign-in:"), gbc);

        JButton testSignInButton = new JButton("Test Sign In");
        testSignInButton.setToolTipText("Test MSAL browser sign-in with the configured Client ID");
        testSignInButton.addActionListener(e -> testMsalSignIn(testSignInButton));
        gbc.gridx = 1;
        gbc.gridy = 18;
        formPanel.add(testSignInButton, gbc);

        // Default Ignore SSL certificate validation setting
        gbc.gridx = 0;
        gbc.gridy = 19;
        formPanel.add(new JLabel("Default Ignore SSL certificate validation:"), gbc);

        defaultIgnoreCertValidationCheckbox = new JCheckBox("(note security implications)", configManager.getDefaultIgnoreCertValidation());
        // No longer update immediately when checkbox changes
        gbc.gridx = 1;
        gbc.gridy = 19;
        formPanel.add(defaultIgnoreCertValidationCheckbox, gbc);

        // Okta SSO email setting
        gbc.gridx = 0;
        gbc.gridy = 20;
        formPanel.add(new JLabel("Okta SSO Email:"), gbc);

        userEmailField = new JTextField(
                configManager.getUserEmail() != null ? configManager.getUserEmail() : "", 20);
        userEmailField.setToolTipText(
                "Your work email used for Okta SSO federation discovery. "
                + "Set automatically when you click \"Sign In with Okta SSO\"");
        gbc.gridx = 1;
        gbc.gridy = 20;
        formPanel.add(userEmailField, gbc);

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
            String timezone = (String) timezoneCombo.getSelectedItem();
            if (timezone != null && !timezone.isEmpty()) {
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

            // Save flash color
            Color chosenFlashColor = flashColorButton.getBackground();
            configManager.updateFlashColor(String.format("#%02x%02x%02x",
                    chosenFlashColor.getRed(), chosenFlashColor.getGreen(), chosenFlashColor.getBlue()));

            // Save flash text color
            Color chosenFlashTextColor = flashTextColorButton.getBackground();
            configManager.updateFlashTextColor(String.format("#%02x%02x%02x",
                    chosenFlashTextColor.getRed(), chosenFlashTextColor.getGreen(), chosenFlashTextColor.getBlue()));

            // Save alert banner color
            Color chosenBannerColor = alertBannerColorButton.getBackground();
            configManager.updateAlertBannerColor(String.format("#%02x%02x%02x",
                    chosenBannerColor.getRed(), chosenBannerColor.getGreen(), chosenBannerColor.getBlue()));

            // Save alert banner text color
            Color chosenBannerTextColor = alertBannerTextColorButton.getBackground();
            configManager.updateAlertBannerTextColor(String.format("#%02x%02x%02x",
                    chosenBannerTextColor.getRed(), chosenBannerTextColor.getGreen(), chosenBannerTextColor.getBlue()));

            // Save flash opacity (convert percentage to decimal)
            int flashOpacityPercent = (Integer) flashOpacitySpinner.getValue();
            configManager.updateFlashOpacity(flashOpacityPercent / 100.0);

            // Save alert beep count
            int beepCount = (Integer) alertBeepCountSpinner.getValue();
            configManager.updateAlertBeepCount(beepCount);

            // Save alert beep after flash
            configManager.updateAlertBeepAfterFlash(alertBeepAfterFlashCheckbox.isSelected());

            // Save join dialog timeout
            int joinDialogTimeout = (Integer) joinDialogTimeoutSpinner.getValue();
            configManager.updateJoinDialogTimeoutSeconds(joinDialogTimeout);

            // Save snooze time
            int snoozeMinutes = (Integer) snoozeMinutesSpinner.getValue();
            configManager.updateSnoozeMinutes(snoozeMinutes);

            // Save ignore all day events
            configManager.updateIgnoreAllDayEvents(ignoreAllDayEventsCheckbox.isSelected());

            // Save alert sound path
            String soundPath = alertSoundPathField.getText().trim();
            configManager.updateAlertSoundPath(soundPath.isEmpty() ? null : soundPath);

            // Save the SSL certificate validation setting
            boolean defaultIgnoreCertVal = defaultIgnoreCertValidationCheckbox.isSelected();
            configManager.updateDefaultIgnoreCertValidation(defaultIgnoreCertVal);

            // Save Okta SSO email
            String userEmail = userEmailField.getText().trim();
            configManager.updateUserEmail(userEmail);

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

    /**
     * Shows a color chooser dialog restricted to the Swatches panel only,
     * giving users a simple color grid instead of the full tabbed chooser.
     */
    private Color showSwatchesColorChooser(String title, Color initial) {
        JColorChooser chooser = new JColorChooser(initial != null ? initial : Color.WHITE);
        // Remove all panels except Swatches
        for (AbstractColorChooserPanel panel : chooser.getChooserPanels()) {
            if (!panel.getDisplayName().equals("Swatches")) {
                chooser.removeChooserPanel(panel);
            }
        }
        chooser.setPreviewPanel(new JPanel()); // hide the preview strip
        int result = JOptionPane.showConfirmDialog(
                this, chooser, title,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        return (result == JOptionPane.OK_OPTION) ? chooser.getColor() : null;
    }
}
