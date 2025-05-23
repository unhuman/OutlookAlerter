package com.unhuman.outlookalerter

import groovy.transform.CompileStatic

import javax.swing.*
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A simplified token entry dialog for Microsoft OAuth authentication
 * Designed for maximum compatibility and simplicity
 */
@CompileStatic
class SimpleTokenDialog {
    // Singleton instance
    private static SimpleTokenDialog instance
    private static boolean isShowing = false
    
    // Lock object for thread safety
    private static final Object LOCK = new Object()
    
    // Instance variables
    private JDialog frame
    private JFrame parentFrame
    private JTextField tokenField
    private CountDownLatch latch = new CountDownLatch(1)
    private Map<String, String> tokens
    private final String signInUrl
    
    /**
     * Get or create singleton instance
     */
    static SimpleTokenDialog getInstance(String signInUrl) {
        synchronized(LOCK) {
            if (instance == null) {
                instance = new SimpleTokenDialog(signInUrl)
            }
            return instance
        }
    }
    
    /**
     * Private constructor to enforce singleton pattern
     */
    private SimpleTokenDialog(String signInUrl) {
        this.signInUrl = signInUrl
    }
    
    /**
     * Display the token entry dialog and wait for input
     */
    void show() {
        synchronized(LOCK) {
            // If dialog is already showing, just bring it to front
            if (isShowing && frame != null && frame.isDisplayable()) {
                SwingUtilities.invokeLater(() -> {
                    frame.toFront()
                    frame.requestFocus()
                })
                return
            }
            
            // Ensure any existing dialog is cleaned up
            cleanup()
            
            // Reset state
            tokens = null
            latch = new CountDownLatch(1)
            isShowing = true
            
            try {
                System.out.println("SimpleTokenDialog: Preparing to show token entry dialog")
                
                // Create UI on EDT and show immediately
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        createUI()
                        System.out.println("SimpleTokenDialog: UI created successfully")
                        
                        // Show dialog immediately after creation
                        if (frame != null) {
                            frame.pack()
                            frame.setLocationRelativeTo(parentFrame)
                            frame.setVisible(true)
                            frame.toFront()
                            frame.requestFocus()
                            
                            // Clear any previous text
                            if (tokenField != null) tokenField.setText("")
                            
                            System.out.println("SimpleTokenDialog: Frame state - visible: " + frame.isVisible() + 
                                             ", showing: " + frame.isShowing())
                        }
                    } catch (Exception e) {
                        System.err.println("SimpleTokenDialog: Error creating UI: " + e.getMessage())
                        e.printStackTrace()
                        cleanup()
                    }
                })
            } catch (Exception e) {
                System.err.println("Error showing token dialog: " + e.getMessage())
                e.printStackTrace()
                cleanup() // Ensure cleanup even on error
            }
        }
    }
    
    /**
     * Create the UI components
     */
    private void createUI() {
        try {
            // Get active window to use as parent
            Window activeWindow = FocusManager.getCurrentManager().getActiveWindow()
            if (activeWindow == null) {
                // Fall back to focused window
                activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow()
            }
            if (activeWindow == null) {
                // Last resort - get all frames
                Frame[] frames = Frame.getFrames()
                for (Frame frame : frames) {
                    if (frame.isVisible()) {
                        activeWindow = frame
                        break
                    }
                }
            }
            parentFrame = activeWindow instanceof JFrame ? (JFrame)activeWindow : null
            
            frame = new JDialog(parentFrame, "Outlook Alerter - Token Entry", true)
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
            frame.addWindowListener(new WindowAdapter() {
                @Override
                void windowClosing(WindowEvent e) {
                    cleanup()
                }
            })
        
            try {
                System.out.println("SimpleTokenDialog: Creating UI components")
                
                // Main panel with simple layout
                JPanel panel = new JPanel(new BorderLayout(10, 10))
                panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20))
                
                // Instructions at top - simplified to avoid HTML rendering issues
                JLabel instructionsLabel = new JLabel(
                    "<html><div style='width: 400px'>" +
                    "<h2>Outlook Alerter Authentication</h2>" +
                    "<p><b>Recommended Method:</b> Use Microsoft Graph Explorer to get your token:</p>" +
                    "<ol>" +
                    "<li>Click the 'Open Graph Explorer' button below</li>" +
                    "<li>Sign in with your Microsoft account</li>" +
                    "<li>Click your profile picture → 'Access token' tab</li>" +
                    "<li>Copy the displayed token</li>" +
                    "</ol>" +
                    "<p><b>Alternative Method:</b> If Graph Explorer doesn't work, you can use the legacy method:</p>" +
                    "<ol>" +
                    "<li>Click 'Open Sign-in Page' and complete the sign-in</li>" +
                    "<li>Open Developer Tools (F12) → Application/Storage tab</li>" +
                    "<li>Find the access token in Local Storage (typically starts with 'eyJ')</li>" +
                    "</ol>" +
                    "</div></html>"
                )
                panel.add(instructionsLabel, BorderLayout.NORTH)
            
                // Form in center with more spacing
                JPanel formPanel = new JPanel(new GridLayout(2, 1, 10, 15))
                
                // Add some padding around the form
                formPanel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(15, 15, 15, 15),
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createEtchedBorder(),
                        BorderFactory.createEmptyBorder(10, 10, 10, 10)
                    )
                ));
                
                JLabel tokenLabel = new JLabel("Access Token:")
                tokenLabel.setFont(tokenLabel.getFont().deriveFont(Font.BOLD))
                formPanel.add(tokenLabel)
                
                // Add token field with enter key support
                tokenField = new JTextField(30)
                tokenField.setEnabled(true)
                tokenField.setEditable(true)
                tokenField.addActionListener(new ActionListener() {
                    @Override
                    void actionPerformed(ActionEvent e) {
                        submitToken() // Submit when enter is pressed
                    }
                })
                formPanel.add(tokenField)
                
                // Add certificate validation option (always initially unchecked)
                JPanel certPanel = new JPanel(new FlowLayout(FlowLayout.LEFT))
                JCheckBox ignoreCertValidationCheckbox = new JCheckBox("Ignore SSL certificate validation (security risk)", false)
                certPanel.add(ignoreCertValidationCheckbox)
                formPanel.add(certPanel)
                
                panel.add(formPanel, BorderLayout.CENTER)
                
                // Buttons at bottom
                JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10))
                buttonPanel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                    BorderFactory.createEmptyBorder(15, 10, 10, 10)
                ))
                
                JButton graphExplorerButton = new JButton("Open Graph Explorer")
                graphExplorerButton.setFont(graphExplorerButton.getFont().deriveFont(Font.BOLD))
                graphExplorerButton.addActionListener(new ActionListener() {
                    @Override
                    void actionPerformed(ActionEvent e) {
                        try {
                            System.out.println("Opening Graph Explorer...")
                            Desktop.getDesktop().browse(new java.net.URI("https://developer.microsoft.com/en-us/graph/graph-explorer"))
                        } catch (Exception ex) {
                            System.err.println("Error opening Graph Explorer: " + ex.getMessage())
                            JOptionPane.showMessageDialog(
                                frame,
                                "Error opening Graph Explorer: " + ex.getMessage(),
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                            )
                        }
                    }
                })
                
                JButton openBrowserButton = new JButton("Open Sign-in Page")
                openBrowserButton.addActionListener(new ActionListener() {
                    @Override
                    void actionPerformed(ActionEvent e) {
                        try {
                            System.out.println("Opening browser for sign-in...")
                            Desktop.getDesktop().browse(new java.net.URI(signInUrl))
                        } catch (Exception ex) {
                            System.err.println("Error opening browser: " + ex.getMessage())
                            JOptionPane.showMessageDialog(
                                frame,
                                "Error opening browser: " + ex.getMessage(),
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                            )
                        }
                    }
                })
                
                JButton submitButton = new JButton("Submit")
                submitButton.addActionListener(new ActionListener() {
                    @Override
                    void actionPerformed(ActionEvent e) {
                        // Get token and trim whitespace
                        String accessToken = tokenField.getText().trim()
                        
                        // Strip off "Bearer " prefix if present (case insensitive)
                        if (accessToken.toLowerCase().startsWith("bearer ")) {
                            accessToken = accessToken.substring(7).trim()
                        }
                        
                        if (accessToken.isEmpty()) {
                            JOptionPane.showMessageDialog(frame,
                                "Please enter an access token.",
                                "Required Field Missing",
                                JOptionPane.WARNING_MESSAGE)
                            return
                        }
                        
                        // Get certificate validation setting
                        boolean ignoreCertValidation = false
                        try {
                            // Find the checkbox in the form
                            for (Component component : formPanel.getComponents()) {
                                if (component instanceof JPanel) {
                                    JPanel innerCertPanel = (JPanel)component
                                    Component[] certComponents = innerCertPanel.getComponents()
                                    for (Component certComponent : certComponents) {
                                        if (certComponent instanceof JCheckBox) {
                                            JCheckBox checkbox = (JCheckBox)certComponent
                                            if (checkbox.getText().contains("certificate validation")) {
                                                ignoreCertValidation = checkbox.isSelected()
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            System.err.println("Error getting certificate validation setting: " + ex.getMessage())
                        }
                        
                        // Store token and certificate validation setting
                        tokens = [
                            accessToken: accessToken,
                            ignoreCertValidation: String.valueOf(ignoreCertValidation)
                        ]
                        
                        // Signal completion and close
                        latch.countDown()
                        frame.setVisible(false)
                        frame.dispose()
                    }
                })
                buttonPanel.add(graphExplorerButton)  // Add Graph Explorer button first
                buttonPanel.add(openBrowserButton)  // Then sign-in page button
                buttonPanel.add(submitButton)       // Then submit
                
                JButton cancelButton = new JButton("Cancel")
                cancelButton.addActionListener(new ActionListener() {
                    @Override
                    void actionPerformed(ActionEvent e) {
                        tokens = null
                        latch.countDown()
                        frame.setVisible(false)
                        frame.dispose()
                    }
                })
                buttonPanel.add(cancelButton)      // Cancel button last
                
                panel.add(buttonPanel, BorderLayout.SOUTH)
                
                // Set content and show
                frame.setContentPane(panel)
                frame.pack()
                frame.setSize(550, 400)
                frame.setLocationRelativeTo(null)
                
                // Configure dialog behavior
                frame.setAlwaysOnTop(true)
                frame.setResizable(false)
                
                // Don't set visible here - we'll do it in the show() method
                
                System.out.println("SimpleTokenDialog: UI components created successfully")
            } catch (Exception e) {
                System.err.println("SimpleTokenDialog: Error creating UI: " + e.getMessage())
                e.printStackTrace()
                
                // Try a fallback UI with minimal components if something went wrong
                try {
                    createFallbackUI()
                } catch (Exception ex) {
                    System.err.println("SimpleTokenDialog: Even fallback UI failed: " + ex.getMessage())
                }
            }
        } catch (Exception e) {
            System.err.println("SimpleTokenDialog: Error in parent window detection: " + e.getMessage())
        }
    }
    
    private void cleanup() {
        synchronized(LOCK) {
            isShowing = false
            
            if (frame != null) {
                // Remove all listeners
                frame.getWindowListeners().each { frame.removeWindowListener(it) }
                if (tokenField != null) {
                    tokenField.getActionListeners().each { tokenField.removeActionListener(it) }
                }
                
                // Dispose frame on EDT
                SwingUtilities.invokeLater(() -> {
                    if (frame.isDisplayable()) {
                        frame.setVisible(false)
                        frame.dispose()
                    }
                    frame = null
                    tokenField = null
                    if (parentFrame != null && parentFrame.isUndecorated()) {
                        parentFrame.dispose()
                        parentFrame = null
                    }
                })
            }
            
            // Complete latch if it hasn't been already
            if (latch != null) {
                latch.countDown()
            }
        }
    }
    
    void dispose() {
        synchronized(LOCK) {
            cleanup()
            instance = null
        }
    }
    
    boolean isVisible() {
        return isShowing && frame != null && frame.isVisible()
    }
    
    /**
     * Create a minimal fallback UI as a last resort
     */
    private void createFallbackUI() {
        System.out.println("SimpleTokenDialog: Creating fallback UI")
        
        // Create a temporary parent frame for modality
        parentFrame = new JFrame()
        parentFrame.setUndecorated(true)
        parentFrame.setVisible(true)
        parentFrame.setLocationRelativeTo(null)
        
        // Create modal dialog
        frame = new JDialog(parentFrame, "Outlook Alerter - Token Entry (Fallback)", true)
        frame.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE)
        
        // Use a simple layout
        JPanel panel = new JPanel(new GridLayout(4, 1, 10, 10))
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20))
        
        JLabel label = new JLabel("Enter access token:")
        tokenField = new JTextField(20)
        
        // Add certificate validation option (always initially unchecked)
        JCheckBox ignoreCertValidationCheckbox = new JCheckBox("Ignore SSL certificate validation (security risk)", false)
            
        JButton submitButton = new JButton("Submit")
        submitButton.addActionListener(e -> submitToken())
        
        panel.add(label)
        panel.add(tokenField)
        panel.add(ignoreCertValidationCheckbox)
        panel.add(submitButton)
        
        frame.add(panel)
        frame.pack()
        frame.setSize(400, 200)
        frame.setLocationRelativeTo(null)
        frame.setAlwaysOnTop(true)
        
        System.out.println("SimpleTokenDialog: Fallback UI created")
    }
    
    /**
     * Submit the entered token
     */
    private void submitToken() {
        try {
            System.out.println("SimpleTokenDialog: Processing token submission")
            
            // Get token values - with fallbacks if UI components fail
            String token = ""
            boolean ignoreCertValidation = false
            
            try {
                token = tokenField != null ? tokenField.getText().trim() : ""
                
                // Get certificate validation setting
                Container contentPane = frame.getContentPane()
                if (contentPane instanceof JPanel) {
                    JPanel panel = (JPanel)contentPane
                    Component[] components = panel.getComponents()
                    for (Component component : components) {
                        if (component instanceof JPanel && ((JPanel)component).getLayout() instanceof GridLayout) {
                            JPanel formPanel = (JPanel)component
                            Component[] formComponents = formPanel.getComponents()
                            for (Component formComponent : formComponents) {
                                if (formComponent instanceof JPanel) {
                                    JPanel innerCertPanel = (JPanel)formComponent
                                    Component[] certComponents = innerCertPanel.getComponents()
                                    for (Component certComponent : certComponents) {
                                        if (certComponent instanceof JCheckBox) {
                                            JCheckBox checkbox = (JCheckBox)certComponent
                                            if (checkbox.getText().contains("certificate validation")) {
                                                ignoreCertValidation = checkbox.isSelected()
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("SimpleTokenDialog: Error getting token text or certificate setting: " + e.getMessage())
            }
            
            // Strip off "Bearer " prefix if present (case insensitive)
            if (token.toLowerCase().startsWith("bearer ")) {
                token = token.substring(7).trim()
            }
            
            if (token.isEmpty()) {
                System.out.println("SimpleTokenDialog: Empty token submitted, showing error message")
                try {
                    JOptionPane.showMessageDialog(
                        frame,
                        "Please enter an access token",
                        "Missing Token",
                        JOptionPane.ERROR_MESSAGE
                    )
                } catch (Exception e) {
                    System.err.println("SimpleTokenDialog: Error showing error message: " + e.getMessage())
                }
                return
            }
            
            // Validate token format - must contain two periods (JWT format)
            String[] parts = token.split("\\.")
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                JOptionPane.showMessageDialog(
                    frame,
                    "The token does not appear to be valid. It should contain three parts separated by periods (.).",
                    "Invalid Token Format",
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }

            // Store token data
            tokens = [
                accessToken: token,
                ignoreCertValidation: String.valueOf(ignoreCertValidation) // Convert boolean to String to match Map<String, String>
            ]
            
            // Update the certificate validation setting in ConfigManager
            ConfigManager configManager = ConfigManager.getInstance()
            if (configManager != null) {
                boolean certSettingChanged = configManager.ignoreCertValidation != ignoreCertValidation
                if (certSettingChanged) {
                    System.out.println("SimpleTokenDialog: Certificate validation setting changed to: " +
                            (ignoreCertValidation ? "disabled" : "enabled"))
                    configManager.updateIgnoreCertValidation(ignoreCertValidation)
                } else {
                    System.out.println("SimpleTokenDialog: Certificate validation setting unchanged: " +
                            (ignoreCertValidation ? "disabled" : "enabled"))
                }
            }
            
            System.out.println("SimpleTokenDialog: Token submitted (first 10 chars): " + 
                               token.substring(0, Math.min(10, token.length())) + "...")
            
            // Signal completion
            latch.countDown()
            frame.dispose()
            if (parentFrame != null) {
                parentFrame.dispose()
            }
            
        } catch (Exception e) {
            System.err.println("SimpleTokenDialog: Unexpected error in submitToken: " + e.getMessage())
            e.printStackTrace()
            
            // Still signal completion to avoid hanging
            tokens = null
            latch.countDown()
        }
    }
    
    /**
     * Cancel the dialog
     */
    private void cancelDialog() {
        try {
            System.out.println("SimpleTokenDialog: User canceled token entry")
            tokens = null
            
            // Signal completion
            latch.countDown()
            
            // Dispose dialog and parent frame on UI thread
            SwingUtilities.invokeLater(() -> {
                try {
                    if (frame != null && frame.isDisplayable()) {
                        frame.dispose()
                    }
                    if (parentFrame != null && parentFrame.isDisplayable()) {
                        parentFrame.dispose()
                    }
                    synchronized(LOCK) {
                        isShowing = false
                    }
                } catch (Exception e) {
                    System.err.println("SimpleTokenDialog: Error disposing dialog: " + e.getMessage())
                }
            })
        } catch (Exception e) {
            System.err.println("SimpleTokenDialog: Error in cancelDialog: " + e.getMessage())
            e.printStackTrace()
            
            // Still signal completion to avoid hanging
            tokens = null
            latch.countDown()
        }
    }
    
    /**
     * Wait for the user to enter a token or cancel
     * @param timeout Maximum seconds to wait
     * @return Map with accessToken and ignoreCertValidation, or null if canceled
     */
    Map<String, String> waitForTokens(int timeout) {
        try {
            System.out.println("SimpleTokenDialog: Waiting for token entry (timeout: " + timeout + " seconds)")
            boolean completed = latch.await(timeout, TimeUnit.SECONDS)
            
            if (!completed) {
                System.out.println("SimpleTokenDialog: Token entry timed out after " + timeout + " seconds")
                
                // Use invokeAndWait to ensure cleanup happens before returning
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            if (frame != null && frame.isDisplayable()) {
                                frame.dispose()
                            }
                            if (parentFrame != null && parentFrame.isDisplayable()) {
                                parentFrame.dispose()
                            }
                            synchronized(LOCK) {
                                isShowing = false
                            }
                        } catch (Exception e) {
                            System.err.println("SimpleTokenDialog: Error disposing dialog after timeout: " + e.getMessage())
                        }
                    })
                } catch (Exception e) {
                    System.err.println("SimpleTokenDialog: Error in invokeAndWait after timeout: " + e.getMessage())
                }
                
                return null
            }
            
            System.out.println("SimpleTokenDialog: Token entry completed, returning " + 
                              (tokens != null ? "valid token data" : "null (canceled)"))
            return tokens
        } catch (InterruptedException e) {
            System.err.println("SimpleTokenDialog: Token entry interrupted: " + e.getMessage())
            Thread.currentThread().interrupt()
            return null
        } catch (Exception e) {
            System.err.println("SimpleTokenDialog: Unexpected error in waitForTokens: " + e.getMessage())
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * Get tokens entered by user. Waits until tokens are submitted or dialog is closed.
     * @return Map containing accessToken and ignoreCertValidation, or null if canceled
     */
    Map<String, String> getTokens() {
        try {
            // Wait for token entry or dialog close, up to 5 minutes
            boolean completed = latch.await(5, TimeUnit.MINUTES)
            if (!completed) {
                System.out.println("SimpleTokenDialog: Token entry timed out")
                cleanup()
                return null
            }
            
            // Return copy of tokens to avoid external modification
            return tokens != null ? new HashMap<>(tokens) : null
        } catch (InterruptedException e) {
            System.err.println("SimpleTokenDialog: Token wait interrupted: " + e.getMessage())
            Thread.currentThread().interrupt()
            return null
        } finally {
            cleanup()
        }
    }
}
