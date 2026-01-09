package com.unhuman.outlookalerter.ui

import com.unhuman.outlookalerter.core.ConfigManager
import com.unhuman.outlookalerter.core.OutlookClient

import javax.swing.*
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import groovy.transform.CompileStatic

/**
 * Settings dialog for OutlookAlerter
 * Provides UI for configuring application settings
 */
@CompileStatic
class SettingsDialog extends JDialog {
    private ConfigManager configManager
    private OutlookClient outlookClient
    private OutlookAlerterUI parent
    
    // UI Components
    private JTextField timezoneField
    private JSpinner alertMinutesSpinner
    private JSpinner resyncIntervalSpinner
    private JSpinner flashDurationSpinner
    private JSpinner alertBeepCountSpinner
    private JTextField signInUrlField
    private JCheckBox defaultIgnoreCertValidationCheckbox

    /**
     * Create a new settings dialog
     *
     * @param parent The parent window
     * @param configManager The configuration manager
     * @param outlookClient The outlook client for API interactions
     */
    SettingsDialog(OutlookAlerterUI parent, ConfigManager configManager, OutlookClient outlookClient) {
        super(parent, "OutlookAlerter Settings", true)
        this.parent = parent
        this.configManager = configManager
        this.outlookClient = outlookClient
        
        initUI()
    }
    
    /**
     * Initialize the UI components
     */
    private void initUI() {
        setLayout(new BorderLayout(10, 10))
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
        
        // Form panel with GridBagLayout for more control
        JPanel formPanel = new JPanel(new GridBagLayout())
        formPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15))
        
        GridBagConstraints gbc = new GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = new Insets(5, 5, 5, 5)
        gbc.weightx = 0.5
        
        // Timezone setting
        gbc.gridx = 0
        gbc.gridy = 0
        formPanel.add(new JLabel("Preferred Timezone (e.g., America/New_York):"), gbc)
        
        timezoneField = new JTextField(configManager.getPreferredTimezone() ?: "", 20)
        gbc.gridx = 1
        gbc.gridy = 0
        formPanel.add(timezoneField, gbc)
        
        // Alert minutes setting
        gbc.gridx = 0
        gbc.gridy = 1
        formPanel.add(new JLabel("Alert Minutes Before Meeting:"), gbc)
        
        SpinnerNumberModel alertMinutesModel = new SpinnerNumberModel(
            configManager.getAlertMinutes(),  // initial value
            1,                               // min
            60,                              // max
            1                                // step
        )
        alertMinutesSpinner = new JSpinner(alertMinutesModel)
        gbc.gridx = 1
        gbc.gridy = 1
        formPanel.add(alertMinutesSpinner, gbc)
        
        // Resync interval setting
        gbc.gridx = 0
        gbc.gridy = 2
        formPanel.add(new JLabel("Resync Calendar Every (minutes):"), gbc)
        
        SpinnerNumberModel resyncModel = new SpinnerNumberModel(
            configManager.getResyncIntervalMinutes(),  // initial value
            5,                                        // min
            1440,                                     // max (24 hours)
            5                                         // step
        )
        resyncIntervalSpinner = new JSpinner(resyncModel)
        gbc.gridx = 1
        gbc.gridy = 2
        formPanel.add(resyncIntervalSpinner, gbc)
        
        // Flash duration setting
        gbc.gridx = 0
        gbc.gridy = 3
        formPanel.add(new JLabel("Screen Flash Duration (seconds):"), gbc)
        
        SpinnerNumberModel flashDurationModel = new SpinnerNumberModel(
            configManager.getFlashDurationSeconds(),  // initial value
            1,                                       // min
            30,                                      // max (30 seconds)
            1                                        // step
        )
        flashDurationSpinner = new JSpinner(flashDurationModel)
        gbc.gridx = 1
        gbc.gridy = 3
        formPanel.add(flashDurationSpinner, gbc)
        
        // Alert beep count setting
        gbc.gridx = 0
        gbc.gridy = 4
        formPanel.add(new JLabel("Alert Beep Count:"), gbc)

        SpinnerNumberModel beepCountModel = new SpinnerNumberModel(
            configManager.getAlertBeepCount(),  // initial value
            0,                                 // min (allow mute)
            20,                                // max
            1                                  // step
        )
        alertBeepCountSpinner = new JSpinner(beepCountModel)
        gbc.gridx = 1
        gbc.gridy = 4
        formPanel.add(alertBeepCountSpinner, gbc)

        // Sign-in URL setting
        gbc.gridx = 0
        gbc.gridy = 5
        formPanel.add(new JLabel("Sign-in URL:"), gbc)
        
        signInUrlField = new JTextField(configManager.getSignInUrl() ?: "", 20)
        gbc.gridx = 1
        gbc.gridy = 5
        formPanel.add(signInUrlField, gbc)
        
        // Default Ignore SSL certificate validation setting
        gbc.gridx = 0
        gbc.gridy = 6
        formPanel.add(new JLabel("Default Ignore SSL certificate validation:"), gbc)
        
        defaultIgnoreCertValidationCheckbox = new JCheckBox("(note security implications)", configManager.getDefaultIgnoreCertValidation())
        // No longer update immediately when checkbox changes
        gbc.gridx = 1
        gbc.gridy = 6
        formPanel.add(defaultIgnoreCertValidationCheckbox, gbc)
        
        // Button panel
        JPanel buttonPanel = new JPanel()
        JButton saveButton = new JButton("Save")
        saveButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                saveSettings()
            }
        })
        
        JButton cancelButton = new JButton("Cancel")
        cancelButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                dispose()
            }
        })
        
        buttonPanel.add(saveButton)
        buttonPanel.add(cancelButton)
        
        add(formPanel, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)
        
        pack()
        setLocationRelativeTo(parent)
        
        // Add window listener to handle dialog closing
        addWindowListener(new WindowAdapter() {
            @Override
            void windowClosing(WindowEvent e) {
                dispose()
            }
        })
    }
    
    /**
     * Save all settings from the dialog
     */
    private void saveSettings() {
        try {
            // Save timezone
            String timezone = timezoneField.getText().trim()
            if (!timezone.isEmpty()) {
                java.time.ZoneId.of(timezone) // Validate timezone
                configManager.updatePreferredTimezone(timezone)
            }
            
            // Save sign-in URL
            String signInUrl = signInUrlField.getText().trim()
            if (!signInUrl.isEmpty()) {
                configManager.updateSignInUrl(signInUrl)
            }
            
            // Save alert minutes
            int alertMinutes = (Integer)alertMinutesSpinner.getValue()
            configManager.updateAlertMinutes(alertMinutes)
            
            // Save resync interval
            int resyncInterval = (Integer)resyncIntervalSpinner.getValue()
            configManager.updateResyncIntervalMinutes(resyncInterval)
            
            // Save flash duration
            int flashDuration = (Integer)flashDurationSpinner.getValue()
            configManager.updateFlashDurationSeconds(flashDuration)
            
            // Save alert beep count
            int beepCount = (Integer)alertBeepCountSpinner.getValue()
            configManager.updateAlertBeepCount(beepCount)

            // Save the SSL certificate validation setting
            boolean defaultIgnoreCertVal = defaultIgnoreCertValidationCheckbox.isSelected()
            configManager.updateDefaultIgnoreCertValidation(defaultIgnoreCertVal)
            
            // Update the HTTP client to respect the new certificate validation setting
            outlookClient.updateCertificateValidation(defaultIgnoreCertVal)
            
            // Notify parent UI to restart schedulers with the new settings
            parent.restartSchedulers()
            
            dispose()
            JOptionPane.showMessageDialog(parent, "Settings saved successfully", "Success", JOptionPane.INFORMATION_MESSAGE)
        } catch (Exception ex) {
            String errorMessage = "Invalid settings: " + ex.getMessage()
            System.err.println(errorMessage) // Log the error to the console
            JOptionPane.showMessageDialog(this, errorMessage, "Error", JOptionPane.ERROR_MESSAGE)
        }
    }
}
