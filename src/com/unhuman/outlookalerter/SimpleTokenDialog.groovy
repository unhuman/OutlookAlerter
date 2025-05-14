package com.unhuman.outlookalerter

import groovy.transform.CompileStatic

import javax.swing.*
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A simplified token entry dialog for Microsoft OAuth authentication
 * Designed for maximum compatibility and simplicity
 */
@CompileStatic
class SimpleTokenDialog {
    private JFrame frame
    private JTextField tokenField
    private JTextField refreshTokenField
    private CountDownLatch latch = new CountDownLatch(1)
    private Map<String, String> tokens
    private final String signInUrl
    
    /**
     * Constructor with sign-in URL
     */
    SimpleTokenDialog(String signInUrl) {
        this.signInUrl = signInUrl
    }
    
    /**
     * Display the token entry dialog and wait for input
     */
    void show() {
        try {
            System.out.println("SimpleTokenDialog: Preparing to show token entry dialog")
            
            // Must run UI creation on EDT
            SwingUtilities.invokeAndWait(() -> {
                try {
                    createUI()
                    System.out.println("SimpleTokenDialog: UI created successfully")
                } catch (Exception e) {
                    System.err.println("SimpleTokenDialog: Error creating UI: " + e.getMessage())
                    e.printStackTrace()
                }
            })
            
            // Ensure dialog is visible after creation
            SwingUtilities.invokeLater(() -> {
                if (frame != null) {
                    if (!frame.isVisible()) {
                        System.out.println("SimpleTokenDialog: Frame was not visible, making it visible now")
                        frame.setVisible(true)
                    }
                    
                    // Force to front and request focus
                    frame.toFront()
                    frame.requestFocus()
                    
                    System.out.println("SimpleTokenDialog: Frame state - visible: " + frame.isVisible() + 
                                      ", showing: " + frame.isShowing())
                }
            })
            
            // Open browser in background thread if URL is available
            if (signInUrl) {
                new Thread(() -> {
                    try {
                        // Brief delay to allow UI to stabilize first
                        Thread.sleep(1000)
                        
                        System.out.println("SimpleTokenDialog: Opening browser with sign-in URL: " + signInUrl)
                        Desktop.getDesktop().browse(new java.net.URI(signInUrl))
                        System.out.println("SimpleTokenDialog: Browser opened successfully")
                    } catch (Exception e) {
                        System.err.println("SimpleTokenDialog: Error opening browser: " + e.getMessage())
                        e.printStackTrace()
                    }
                }, "BrowserOpener-Thread").start()
            }
        } catch (Exception e) {
            System.err.println("Error showing token dialog: " + e.getMessage())
            e.printStackTrace()
        }
    }
    
    /**
     * Create the UI components
     */
    private void createUI() {
        try {
            System.out.println("SimpleTokenDialog: Creating UI components")
            
            // Create JFrame instead of JDialog
            frame = new JFrame("Microsoft OAuth Token Entry")
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
            
            // Set utility mode for better macOS handling
            frame.setType(javax.swing.JFrame.Type.UTILITY);
            
            // Make it undecorated on macOS to solve some display issues
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                frame.setUndecorated(false); // Try decorated first for macOS
                System.out.println("SimpleTokenDialog: Using macOS-specific frame settings");
            }
            
            // Main panel with simple layout
            JPanel panel = new JPanel(new BorderLayout(10, 10))
            panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20))
            
            // Instructions at top - simplified to avoid HTML rendering issues
            JLabel instructionsLabel = new JLabel(
                "<html><div style='width: 400px'>" +
                "<h2>Microsoft Token Authentication</h2>" +
                "<p>After signing in through your browser, copy the access token from the browser.</p>" +
                "<p>The token typically starts with 'eyJ' and can be found in:</p>" +
                "<p>Developer Tools (F12) → Application/Storage tab → Local Storage</p>" +
                "</div></html>"
            )
            panel.add(instructionsLabel, BorderLayout.NORTH)
        
            // Form in center with more spacing
            JPanel formPanel = new JPanel(new GridLayout(4, 1, 10, 15))
            
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
            formPanel.add(tokenLabel)
            
            tokenField = new JTextField(30)
            // Make sure the text field is enabled and editable
            tokenField.setEnabled(true);
            tokenField.setEditable(true);
            formPanel.add(tokenField)
            
            JLabel refreshLabel = new JLabel("Refresh Token (optional):");
            refreshLabel.setFont(refreshLabel.getFont().deriveFont(Font.BOLD));
            formPanel.add(refreshLabel)
            
            refreshTokenField = new JTextField(30)
            refreshTokenField.setEnabled(true);
            refreshTokenField.setEditable(true);
            formPanel.add(refreshTokenField)
            
            panel.add(formPanel, BorderLayout.CENTER)
            
            // Buttons at bottom
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10))
            buttonPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(15, 10, 10, 10)
            ));
            
            JButton openBrowserButton = new JButton("Open Browser")
            openBrowserButton.addActionListener(new ActionListener() {
                @Override
                void actionPerformed(ActionEvent e) {
                    try {
                        System.out.println("Opening browser from button click...")
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
            
            // Create a more obvious submit button
            JButton submitButton = new JButton("Submit Token")
            submitButton.setFont(submitButton.getFont().deriveFont(Font.BOLD));
            
            // Use a platform-specific approach for button styling
            if (!System.getProperty("os.name").toLowerCase().contains("mac")) {
                // Custom styling for Windows and Linux
                submitButton.setBackground(new Color(0, 120, 215));
                submitButton.setForeground(Color.WHITE);
            } else {
                // For macOS, use the system's look & feel but make the button more prominent
                submitButton.putClientProperty("JButton.buttonType", "default");
                submitButton.setOpaque(true);
            }
            
            submitButton.addActionListener(new ActionListener() {
                @Override
                void actionPerformed(ActionEvent e) {
                    System.out.println("Submit button clicked")
                    submitToken()
                }
            })
            
            JButton cancelButton = new JButton("Cancel")
            cancelButton.addActionListener(new ActionListener() {
                @Override
                void actionPerformed(ActionEvent e) {
                    System.out.println("Cancel button clicked")
                    cancelDialog()
                }
            })
            
            buttonPanel.add(openBrowserButton)
            buttonPanel.add(submitButton)
            buttonPanel.add(cancelButton)
            
            panel.add(buttonPanel, BorderLayout.SOUTH)
            
            // Set content and show
            frame.setContentPane(panel)
            frame.pack()
            frame.setSize(550, 450)
            frame.setLocationRelativeTo(null)
            
            // Don't set visible here - we'll do it in the show() method
            
            System.out.println("SimpleTokenDialog: UI components created successfully")
        } catch (Exception e) {
            System.err.println("SimpleTokenDialog: Error creating UI: " + e.getMessage())
            e.printStackTrace()
            
            // Try a fallback UI with minimal components if something went wrong
            try {
                createFallbackUI();
            } catch (Exception ex) {
                System.err.println("SimpleTokenDialog: Even fallback UI failed: " + ex.getMessage())
            }
        }
    }
    
    /**
     * Create a minimal fallback UI as a last resort
     */
    private void createFallbackUI() {
        System.out.println("SimpleTokenDialog: Creating fallback UI")
        
        frame = new JFrame("Token Entry (Fallback)");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        
        // Use the simplest possible layout
        frame.setLayout(new BorderLayout());
        
        JPanel panel = new JPanel(new GridLayout(3, 1));
        
        JLabel label = new JLabel("Enter access token:");
        tokenField = new JTextField(20);
        
        JButton submitButton = new JButton("Submit");
        submitButton.addActionListener(e -> submitToken());
        
        panel.add(label);
        panel.add(tokenField);
        panel.add(submitButton);
        
        frame.add(panel, BorderLayout.CENTER);
        frame.setSize(400, 200);
        frame.setLocationRelativeTo(null);
        
        System.out.println("SimpleTokenDialog: Fallback UI created");
    }

    
    /**
     * Submit the entered token
     */
    private void submitToken() {
        try {
            System.out.println("SimpleTokenDialog: Processing token submission")
            
            // Get token values - with fallbacks if UI components fail
            String token = "";
            String refreshToken = "";
            
            try {
                token = tokenField != null ? tokenField.getText().trim() : "";
                // If refresh token field is null, it might be the fallback UI
                refreshToken = refreshTokenField != null ? refreshTokenField.getText().trim() : "";
            } catch (Exception e) {
                System.err.println("SimpleTokenDialog: Error getting token text: " + e.getMessage());
            }
            
            if (token.isEmpty()) {
                System.out.println("SimpleTokenDialog: Empty token submitted, showing error message");
                try {
                    JOptionPane.showMessageDialog(
                        frame,
                        "Please enter an access token",
                        "Missing Token",
                        JOptionPane.ERROR_MESSAGE
                    );
                } catch (Exception e) {
                    System.err.println("SimpleTokenDialog: Error showing error message: " + e.getMessage());
                }
                return;
            }
            
            // Store token data
            tokens = [
                accessToken: token,
                refreshToken: refreshToken.isEmpty() ? null : refreshToken,
                expiryTime: Long.toString(System.currentTimeMillis() + 3300000) // 55 minutes
            ]
            
            System.out.println("SimpleTokenDialog: Token submitted (first 10 chars): " + 
                               token.substring(0, Math.min(10, token.length())) + "...")
            
            // Signal completion
            latch.countDown()
            
            // Dispose frame on UI thread
            SwingUtilities.invokeLater(() -> {
                try {
                    if (frame != null && frame.isDisplayable()) {
                        frame.dispose()
                    }
                } catch (Exception e) {
                    System.err.println("SimpleTokenDialog: Error disposing frame: " + e.getMessage())
                }
            });
        } catch (Exception e) {
            System.err.println("SimpleTokenDialog: Unexpected error in submitToken: " + e.getMessage())
            e.printStackTrace()
            
            // Still signal completion to avoid hanging
            tokens = null;
            latch.countDown();
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
            
            // Dispose frame on UI thread
            SwingUtilities.invokeLater(() -> {
                try {
                    if (frame != null && frame.isDisplayable()) {
                        frame.dispose()
                    }
                } catch (Exception e) {
                    System.err.println("SimpleTokenDialog: Error disposing frame: " + e.getMessage())
                }
            });
        } catch (Exception e) {
            System.err.println("SimpleTokenDialog: Error in cancelDialog: " + e.getMessage())
            e.printStackTrace()
            
            // Still signal completion to avoid hanging
            tokens = null;
            latch.countDown();
        }
    }
    
    /**
     * Wait for the user to enter a token or cancel
     * @param timeout Maximum seconds to wait
     * @return Map with accessToken and refreshToken, or null if canceled
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
                        } catch (Exception e) {
                            System.err.println("SimpleTokenDialog: Error disposing frame after timeout: " + e.getMessage())
                        }
                    });
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
}
