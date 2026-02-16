package com.unhuman.outlookalerter.ui

import com.unhuman.outlookalerter.util.LogManager
import com.unhuman.outlookalerter.util.LogCategory
import groovy.transform.CompileStatic
import javax.swing.*
import javax.swing.text.DefaultCaret
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * Log viewer window to display application logs
 */
@CompileStatic
class LogViewer extends JFrame {
    private JTextArea logTextArea
    private JScrollPane scrollPane
    private JButton clearButton
    private JButton refreshButton
    private JButton saveButton
    private Map<LogCategory, JCheckBox> filterCheckboxes = new HashMap<>()

    /**
     * Constructor
     * @param parent The parent frame for positioning
     */
    LogViewer(Frame parent) {
        super("Outlook Alerter Logs")
        initializeUI()
        positionRelativeToParent(parent)
        
        // Set up window listener
        addWindowListener(new WindowAdapter() {
            @Override
            void windowClosing(WindowEvent e) {
                // Detach from LogManager
                LogManager.getInstance().setLogTextArea(null)
                dispose()
            }
        })
    }
    
    /**
     * Initialize the UI components
     */
    private void initializeUI() {
        // Set basic properties
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
        setSize(900, 600)

        // Create filter panel at top
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT))
        filterPanel.setBorder(BorderFactory.createTitledBorder("Filter by Category"))

        LogManager logManager = LogManager.getInstance()
        for (LogCategory category : LogManager.getCategories()) {
            // Create a final copy to avoid closure capture issues
            final LogCategory capturedCategory = category
            JCheckBox checkbox = new JCheckBox(capturedCategory.displayName, logManager.isFilterEnabled(capturedCategory))
            checkbox.addActionListener(new ActionListener() {
                @Override
                void actionPerformed(ActionEvent e) {
                    JCheckBox source = (JCheckBox) e.getSource()
                    logManager.setFilterEnabled(capturedCategory, source.isSelected())
                }
            })
            filterCheckboxes.put(capturedCategory, checkbox)
            filterPanel.add(checkbox)
        }

        // Add "Select All" and "Select None" buttons
        JButton selectAllButton = new JButton("All")
        selectAllButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                for (LogCategory cat : LogManager.getCategories()) {
                    logManager.setFilterEnabled(cat, true)
                    filterCheckboxes.get(cat).setSelected(true)
                }
            }
        })
        filterPanel.add(selectAllButton)

        JButton selectNoneButton = new JButton("None")
        selectNoneButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                for (LogCategory cat : LogManager.getCategories()) {
                    logManager.setFilterEnabled(cat, false)
                    filterCheckboxes.get(cat).setSelected(false)
                }
            }
        })
        filterPanel.add(selectNoneButton)

        // Create text area for logs
        logTextArea = new JTextArea()
        logTextArea.setEditable(false)
        logTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12))
        
        // Set the caret to always show the latest logs
        DefaultCaret caret = (DefaultCaret)logTextArea.getCaret()
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE)
        
        // Add to scroll pane
        scrollPane = new JScrollPane(logTextArea)
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS)
        
        // Create button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
        
        // Clear button
        clearButton = new JButton("Clear Logs")
        clearButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                LogManager.getInstance().clearLogs()
                LogManager.getInstance().info("Log cleared by user")
            }
        })
        buttonPanel.add(clearButton)
        
        // Refresh button
        refreshButton = new JButton("Refresh")
        refreshButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                // Re-attaching will refresh the display
                LogManager.getInstance().setLogTextArea(logTextArea)
                LogManager.getInstance().info("Log view refreshed by user")
            }
        })
        buttonPanel.add(refreshButton)
        
        // Save button
        saveButton = new JButton("Save Logs")
        saveButton.addActionListener(new ActionListener() {
            @Override
            void actionPerformed(ActionEvent e) {
                saveLogsToFile()
            }
        })
        buttonPanel.add(saveButton)
        
        // Set up the layout
        setLayout(new BorderLayout())
        add(filterPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)
        
        // Connect to LogManager
        LogManager.getInstance().setLogTextArea(logTextArea)
    }
    
    /**
     * Position this window relative to the parent
     * @param parent The parent frame
     */
    private void positionRelativeToParent(Frame parent) {
        if (parent != null) {
            // Center relative to parent
            int x = parent.getX() + (int)((parent.getWidth() - getWidth()) / 2)
            int y = parent.getY() + (int)((parent.getHeight() - getHeight()) / 2)
            setLocation(Math.max(0, x), Math.max(0, y))
        } else {
            // Center on screen if no parent
            setLocationRelativeTo(null)
        }
    }
    
    /**
     * Save logs to a file
     */
    private void saveLogsToFile() {
        JFileChooser fileChooser = new JFileChooser()
        fileChooser.setDialogTitle("Save Log File")
        
        // Set default filename with timestamp
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date())
        fileChooser.setSelectedFile(new File("outlook_alerter_log_" + timestamp + ".txt"))
        
        // Show save dialog
        int userSelection = fileChooser.showSaveDialog(this)
        
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile()
            try {
                // Write logs to file
                fileToSave.text = LogManager.getInstance().getLogsAsString()
                LogManager.getInstance().info("Logs saved to: " + fileToSave.getAbsolutePath())
                JOptionPane.showMessageDialog(this, "Logs saved successfully to:\n" + 
                        fileToSave.getAbsolutePath(), "Logs Saved", JOptionPane.INFORMATION_MESSAGE)
            } catch (Exception e) {
                LogManager.getInstance().error("Error saving logs", e)
                JOptionPane.showMessageDialog(this, "Error saving logs: " + e.getMessage(), 
                        "Save Error", JOptionPane.ERROR_MESSAGE)
            }
        }
    }
}
