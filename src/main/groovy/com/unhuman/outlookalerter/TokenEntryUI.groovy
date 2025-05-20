package com.unhuman.outlookalerter

import groovy.transform.CompileStatic

import javax.swing.*
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.URI

/**
 * A GUI-based token entry dialog that handles Microsoft OAuth token entry
 * This is a more user-friendly alternative to the web-based TokenEntryServer
 */
@CompileStatic
class TokenEntryUI {
    private JDialog dialog
    private JTextField accessTokenField
    private JTextField refreshTokenField
    private JLabel tokenValidationLabel
    private Map<String, String> tokens
    private CountDownLatch tokenLatch = new CountDownLatch(1)
    private final String signInUrl
    
    /**
     * Create a new TokenEntryUI
     * @param signInUrl The URL for signing in to Microsoft/Okta
     */
    TokenEntryUI(String signInUrl) {
        this.signInUrl = signInUrl
    }
    
    /**
     * Display the token entry dialog
     */
    void showDialog() {
        System.out.println("Preparing to show token entry dialog...")
        
        // Create the dialog and its components synchronously on the EDT
        try {
            SwingUtilities.invokeAndWait({
                System.out.println("Now on EDT thread, creating dialog...")
                createAndShowGUI()
            } as Runnable)
        } catch (Exception e) {
            System.err.println("Error creating token dialog: " + e.getMessage())
            e.printStackTrace()
        }
    }
    
    /**
     * Create and display the GUI
     */
    private void createAndShowGUI() {
        try {
            // Enable antialiasing for better text rendering
            System.setProperty("awt.useSystemAAFontSettings", "on");
            System.setProperty("swing.aatext", "true");
            
            // Log that we're showing the dialog
            System.out.println("Creating token entry dialog...");
            
            // Create a JDialog - using false for non-modal to prevent EDT deadlock
            dialog = new JDialog((Frame)null, "Outlook Alerter - Token Entry", false);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            
            // Set a nice background color
            dialog.getContentPane().setBackground(new Color(240, 240, 245));
            
            // Main content panel
            JPanel contentPanel = new JPanel();
            contentPanel.setLayout(new BorderLayout(10, 10));
            contentPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
            contentPanel.setBackground(new Color(240, 240, 245));
            
            // Instructions panel at the top
            JPanel instructionsPanel = createInstructionsPanel();
            contentPanel.add(instructionsPanel, BorderLayout.NORTH);
            
            // Form panel in the center
            JPanel formPanel = createFormPanel();
            contentPanel.add(formPanel, BorderLayout.CENTER);
            
            // Button panel at the bottom
            JPanel buttonPanel = createButtonPanel();
            contentPanel.add(buttonPanel, BorderLayout.SOUTH);
            
            // Set content and size before showing
            dialog.setContentPane(contentPanel);
            dialog.pack();
            dialog.setSize(800, 600);
            dialog.setMinimumSize(new Dimension(600, 400));
            dialog.setLocationRelativeTo(null); // Center on screen
            
            // Add a window listener to handle dialog closure
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                    System.out.println("Token dialog window closing event detected");
                    cancelToken();
                }
            });
            
            // Display the dialog and bring it to front
            System.out.println("Making token dialog visible...");
            dialog.setVisible(true);
            dialog.toFront();
            dialog.requestFocus();
            
            // On Mac, we need to request focus a bit later to ensure it works
            Timer focusTimer = new Timer(500, new ActionListener() {
                @Override
                void actionPerformed(ActionEvent e) {
                    dialog.toFront();
                    dialog.requestFocus();
                    accessTokenField.requestFocusInWindow();
                    System.out.println("Dialog focus requested. Dialog visible: " + dialog.isVisible());
                }
            });
            focusTimer.setRepeats(false);
            focusTimer.start();
            
            System.out.println("Token entry dialog is now visible. Dialog state: " + 
                               (dialog.isVisible() ? "Visible" : "Not visible"));
        } catch (Exception e) {
            System.err.println("Error creating token dialog: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Create the instructions panel
     */
    private JPanel createInstructionsPanel() {
        JPanel panel = new JPanel()
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS))
        panel.setBackground(new Color(240, 240, 245))
        
        // Create a nice header with a colored background
        JPanel headerPanel = new JPanel()
        headerPanel.setBackground(new Color(70, 130, 180))
        headerPanel.setLayout(new BorderLayout())
        headerPanel.setPreferredSize(new Dimension(800, 50))
        headerPanel.setMaximumSize(new Dimension(1600, 50))
        
        JLabel titleLabel = new JLabel("Outlook Alerter - Token Entry")
        titleLabel.setFont(new Font("Dialog", Font.BOLD, 18))
        titleLabel.setForeground(Color.WHITE)
        titleLabel.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 10))
        headerPanel.add(titleLabel, BorderLayout.WEST)
        
        panel.add(headerPanel)
        panel.add(Box.createRigidArea(new Dimension(0, 15)))
        
        // Instructions
        JTextArea instructionsText = new JTextArea(
            "To authenticate with Microsoft Graph API, follow these steps:\n\n" +
            "1. A browser window should open with Microsoft/Okta sign-in page\n" +
            "2. Complete the authentication process in the browser\n" +
            "3. After successful authentication, extract your access token:\n" +
            "   a. In your browser's Developer Tools (F12), go to 'Application' or 'Storage' tab\n" +
            "   b. Look for 'Local Storage' and find items with 'token' in the name\n" +
            "   c. The access token is typically a long string that starts with 'eyJ'\n" +
            "4. Paste the token below and click Submit\n\n" +
            "If no browser opened, click the 'Open Sign-in Page' button below."
        )
        instructionsText.setEditable(false)
        instructionsText.setLineWrap(true)
        instructionsText.setWrapStyleWord(true)
        instructionsText.setBackground(new Color(240, 240, 245))
        instructionsText.setFont(new Font("Dialog", Font.PLAIN, 13))
        instructionsText.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(200, 200, 200)),
            BorderFactory.createEmptyBorder(10, 10, 15, 10)
        ))
        
        JScrollPane scrollPane = new JScrollPane(instructionsText)
        scrollPane.setBorder(BorderFactory.createEmptyBorder())
        scrollPane.setBackground(new Color(240, 240, 245))
        panel.add(scrollPane)
        
        JButton openBrowserButton = new JButton("Open Sign-in Page")
        openBrowserButton.setFont(new Font("Dialog", Font.BOLD, 12))
        openBrowserButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                openSignInPage()
            }
        })
        
        JPanel buttonWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER))
        buttonWrapper.setBackground(new Color(240, 240, 245))
        buttonWrapper.add(openBrowserButton)
        
        panel.add(Box.createRigidArea(new Dimension(0, 10)))
        panel.add(buttonWrapper)
        panel.add(Box.createRigidArea(new Dimension(0, 15)))
        
        return panel
    }
    
    /**
     * Create the form panel
     */
    private JPanel createFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout())
        panel.setBackground(new Color(240, 240, 245))
        GridBagConstraints gbc = new GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = new Insets(5, 5, 5, 5)
        
        // Use a titled border
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(100, 149, 237), 1),
                "Token Information"
            ),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ))
        
        // Access Token
        gbc.gridx = 0
        gbc.gridy = 0
        JLabel accessTokenLabel = new JLabel("Access Token:")
        accessTokenLabel.setFont(new Font("Dialog", Font.BOLD, 12))
        panel.add(accessTokenLabel, gbc)
        
        accessTokenField = new JTextField(40)
        accessTokenField.setFont(new Font("Monospaced", Font.PLAIN, 12))
        // Make field more visible with a border and background color
        accessTokenField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(180, 180, 180)),
            BorderFactory.createEmptyBorder(6, 6, 6, 6)
        ))
        
        // Add enter key support
        accessTokenField.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                // When enter is pressed, either move focus to refresh token field if empty, or submit
                if (refreshTokenField.getText().trim().isEmpty()) {
                    refreshTokenField.requestFocusInWindow()
                } else {
                    submitToken()
                }
            }
        })
        
        // Add document listener to validate token format as user types
        accessTokenField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void validateToken() {
                String token = accessTokenField.getText().trim()
                if (token.isEmpty()) {
                    tokenValidationLabel.setText("Token is required")
                    tokenValidationLabel.setForeground(Color.RED)
                } else if (isValidTokenFormat(token)) {
                    tokenValidationLabel.setText("✓ Token format looks valid")
                    tokenValidationLabel.setForeground(new Color(0, 128, 0)) // Dark green
                } else {
                    tokenValidationLabel.setText("⚠ Invalid token format - should contain three parts separated by periods (.)")
                    tokenValidationLabel.setForeground(Color.RED)
                }
            }
            
            void insertUpdate(javax.swing.event.DocumentEvent e) { validateToken() }
            void removeUpdate(javax.swing.event.DocumentEvent e) { validateToken() }
            void changedUpdate(javax.swing.event.DocumentEvent e) { validateToken() }
        })
        
        gbc.gridx = 1
        gbc.gridy = 0
        panel.add(accessTokenField, gbc)
        
        // Token validation label
        tokenValidationLabel = new JLabel("Paste a valid token (starts with 'eyJ' and contains two periods)")
        tokenValidationLabel.setFont(new Font("Dialog", Font.ITALIC, 11))
        tokenValidationLabel.setForeground(new Color(100, 100, 100))
        gbc.gridx = 1
        gbc.gridy = 1
        panel.add(tokenValidationLabel, gbc)
        
        // Refresh Token (optional)
        gbc.gridx = 0
        gbc.gridy = 2
        JLabel refreshTokenLabel = new JLabel("Refresh Token (optional):")
        refreshTokenLabel.setFont(new Font("Dialog", Font.BOLD, 12))
        panel.add(refreshTokenLabel, gbc)
        
        refreshTokenField = new JTextField(40)
        refreshTokenField.setFont(new Font("Monospaced", Font.PLAIN, 12))
        refreshTokenField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(180, 180, 180)),
            BorderFactory.createEmptyBorder(6, 6, 6, 6)
        ))
        
        // Add enter key support
        refreshTokenField.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                submitToken() // Submit when enter is pressed in refresh token field
            }
        })
        
        gbc.gridx = 1
        gbc.gridy = 2
        panel.add(refreshTokenField, gbc)
        
        // Optional label
        JLabel optionalLabel = new JLabel("Leave blank if not available")
        optionalLabel.setFont(new Font("Dialog", Font.ITALIC, 11))
        optionalLabel.setForeground(new Color(100, 100, 100))
        gbc.gridx = 1
        gbc.gridy = 3
        panel.add(optionalLabel, gbc)
        
        return panel
    }
    
    /**
     * Create the button panel
     */
    private JPanel createButtonPanel() {
        JPanel panel = new JPanel()
        panel.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 10))
        panel.setBackground(new Color(240, 240, 245))
        
        JButton submitButton = new JButton("Submit Token")
        submitButton.setFont(new Font("Dialog", Font.BOLD, 12))
        submitButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                submitToken()
            }
        })
        
        JButton cancelButton = new JButton("Cancel")
        cancelButton.setFont(new Font("Dialog", Font.BOLD, 12))
        cancelButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                cancelToken()
            }
        })
        
        panel.add(submitButton)
        panel.add(cancelButton)
        
        return panel
    }
    
    /**
     * Submit the entered token
     */
    private void submitToken() {
        try {
            String accessToken = accessTokenField.getText().trim()
            String refreshToken = refreshTokenField.getText().trim()
            
            // Strip off "Bearer " prefix if present (case insensitive)
            if (accessToken.toLowerCase().startsWith("bearer ")) {
                accessToken = accessToken.substring(7).trim()
            }
            
            System.out.println("Token submit action triggered. Access token length: " + 
                              (accessToken != null ? accessToken.length() : 0))
            
            // Validate access token
            if (accessToken.isEmpty() || !isValidTokenFormat(accessToken)) {
                JOptionPane.showMessageDialog(
                    dialog,
                    "The access token appears to be invalid. A valid token should contain two periods (.) and usually starts with 'eyJ'.",
                    "Invalid Token",
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }
            
            // Visual feedback that we're working
            dialog.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR))
            
            // Store the tokens
            tokens = [
                accessToken: accessToken,
                refreshToken: refreshToken.isEmpty() ? null : refreshToken,
                expiryTime: Long.toString(System.currentTimeMillis() + (3600 - 300) * 1000) // Default 1-hour expiry, minus 5 min safety
            ]
            
            JOptionPane.showMessageDialog(
                dialog,
                "Token format validated. The application will now proceed with server validation.",
                "Token Accepted",
                JOptionPane.INFORMATION_MESSAGE
            )
            
            // Signal completion and close dialog
            System.out.println("Token submitted successfully")
            tokenLatch.countDown()
            
            // Dispose the dialog on the EDT
            SwingUtilities.invokeLater({
                if (dialog != null && dialog.isVisible()) {
                    dialog.dispose()
                }
            } as Runnable)
        } catch (Exception e) {
            System.err.println("Error during token submission: " + e.getMessage())
            e.printStackTrace()
            JOptionPane.showMessageDialog(
                dialog,
                "Error processing token: " + e.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE
            )
            tokens = null
        } finally {
            if (dialog != null) {
                dialog.setCursor(Cursor.getDefaultCursor())
            }
        }
    }
    
    /**
     * Check if the token has a valid format (contains exactly two periods)
     */
    private boolean isValidTokenFormat(String token) {
        if (token == null || token.isEmpty()) {
            return false
        }
        
        // A valid JWT token consists of three parts separated by dots
        String[] parts = token.split("\\.")
        return parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty() && !parts[2].isEmpty()
    }
    
    /**
     * Cancel token entry
     */
    private void cancelToken() {
        try {
            System.out.println("Token entry canceled by user")
            
            // Show a confirmation message
            JOptionPane.showMessageDialog(
                dialog,
                "Token entry canceled. You'll need to authenticate later to use the application.",
                "Authentication Canceled",
                JOptionPane.WARNING_MESSAGE
            )
            
            tokens = null
            tokenLatch.countDown()
            
            // Dispose the dialog on the EDT
            SwingUtilities.invokeLater({
                if (dialog != null && dialog.isVisible()) {
                    dialog.dispose()
                }
            } as Runnable)
        } catch (Exception e) {
            System.err.println("Error during token cancellation: " + e.getMessage())
            e.printStackTrace()
        }
    }
    
    /**
     * Open the sign-in page in a browser
     */
    private void openSignInPage() {
        try {
            if (signInUrl == null || signInUrl.isEmpty()) {
                JOptionPane.showMessageDialog(
                    dialog,
                    "No sign-in URL configured. Please configure a valid sign-in URL in the settings dialog.\n\n" +
                    "For Okta SSO users: Configure your organization's Okta SSO URL for Microsoft 365.\n" +
                    "Example: https://your-company.okta.com/home/office365/0oa1b2c3d4/aln5b6c7d8",
                    "Missing Configuration",
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }
            
            // Check if Desktop is supported
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                JOptionPane.showMessageDialog(
                    dialog,
                    "Cannot open browser automatically. Please manually open: " + signInUrl,
                    "Browser Launch Failed",
                    JOptionPane.WARNING_MESSAGE
                )
                return
            }
            
            // Use Desktop API to open browser
            Desktop.getDesktop().browse(new URI(signInUrl))
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                dialog,
                "Failed to open browser: " + e.getMessage() + "\nPlease manually open: " + signInUrl,
                "Browser Launch Failed",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }
    
    /**
     * Wait for token entry to complete
     * @param timeoutSeconds Maximum time to wait in seconds
     * @return Map containing access_token, refresh_token, and expiry_time, or null if timed out/canceled
     */
    Map<String, String> waitForTokens(int timeoutSeconds) {
        try {
            System.out.println("Waiting for token entry from user (timeout: " + timeoutSeconds + " sec)...")
            boolean received = tokenLatch.await(timeoutSeconds, TimeUnit.SECONDS)
            if (!received) {
                System.out.println("Token entry timed out after " + timeoutSeconds + " seconds")
                if (dialog != null && dialog.isVisible()) {
                    dialog.dispose()
                }
            }
            return received ? tokens : null
        } catch (InterruptedException e) {
            System.out.println("Token entry was interrupted: " + e.getMessage())
            Thread.currentThread().interrupt()
            return null
        }
    }
}
