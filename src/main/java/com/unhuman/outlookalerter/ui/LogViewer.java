package com.unhuman.outlookalerter.ui;

import com.unhuman.outlookalerter.util.LogManager;
import com.unhuman.outlookalerter.util.LogCategory;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Log viewer window to display application logs
 */
public class LogViewer extends JFrame {
    private JTextArea logTextArea;
    private JScrollPane scrollPane;
    private JButton clearButton;
    private JButton refreshButton;
    private JButton saveButton;
    private Map<LogCategory, JCheckBox> filterCheckboxes = new HashMap<>();

    public LogViewer(Frame parent) {
        super("Outlook Alerter Logs");
        initializeUI();
        positionRelativeToParent(parent);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                LogManager.getInstance().setLogTextArea(null);
                dispose();
            }
        });
    }

    private void initializeUI() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(900, 600);

        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filterPanel.setBorder(BorderFactory.createTitledBorder("Filter by Category"));

        LogManager logManager = LogManager.getInstance();
        for (LogCategory category : LogManager.getCategories()) {
            final LogCategory capturedCategory = category;
            JCheckBox checkbox = new JCheckBox(capturedCategory.getDisplayName(), logManager.isFilterEnabled(capturedCategory));
            checkbox.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    JCheckBox source = (JCheckBox) e.getSource();
                    logManager.setFilterEnabled(capturedCategory, source.isSelected());
                }
            });
            filterCheckboxes.put(capturedCategory, checkbox);
            filterPanel.add(checkbox);
        }

        JButton selectAllButton = new JButton("All");
        selectAllButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                for (LogCategory cat : LogManager.getCategories()) {
                    logManager.setFilterEnabled(cat, true);
                    filterCheckboxes.get(cat).setSelected(true);
                }
            }
        });
        filterPanel.add(selectAllButton);

        JButton selectNoneButton = new JButton("None");
        selectNoneButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                for (LogCategory cat : LogManager.getCategories()) {
                    logManager.setFilterEnabled(cat, false);
                    filterCheckboxes.get(cat).setSelected(false);
                }
            }
        });
        filterPanel.add(selectNoneButton);

        filterPanel.add(Box.createHorizontalStrut(10));
        filterPanel.add(new JLabel("Text Filter:"));
        JTextField textFilterField = new JTextField(logManager.getTextFilter(), 12);
        textFilterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
            private void applyFilter() {
                logManager.setTextFilter(textFilterField.getText());
            }
        });
        filterPanel.add(textFilterField);

        logTextArea = new JTextArea();
        logTextArea.setEditable(false);
        logTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        DefaultCaret caret = (DefaultCaret) logTextArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        scrollPane = new JScrollPane(logTextArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        clearButton = new JButton("Clear Logs");
        clearButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                LogManager.getInstance().clearLogs();
                LogManager.getInstance().info("Log cleared by user");
            }
        });
        buttonPanel.add(clearButton);

        refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                LogManager.getInstance().setLogTextArea(logTextArea);
                LogManager.getInstance().info("Log view refreshed by user");
            }
        });
        buttonPanel.add(refreshButton);

        saveButton = new JButton("Save Logs");
        saveButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveLogsToFile();
            }
        });
        buttonPanel.add(saveButton);

        setLayout(new BorderLayout());
        add(filterPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        LogManager.getInstance().setLogTextArea(logTextArea);
    }

    private void positionRelativeToParent(Frame parent) {
        if (parent != null) {
            int x = parent.getX() + (parent.getWidth() - getWidth()) / 2;
            int y = parent.getY() + (parent.getHeight() - getHeight()) / 2;
            setLocation(Math.max(0, x), Math.max(0, y));
        } else {
            setLocationRelativeTo(null);
        }
    }

    private void saveLogsToFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Log File");

        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        fileChooser.setSelectedFile(new File("outlook_alerter_log_" + timestamp + ".txt"));

        int userSelection = fileChooser.showSaveDialog(this);

        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();
            try {
                Files.writeString(fileToSave.toPath(), LogManager.getInstance().getLogsAsString());
                LogManager.getInstance().info("Logs saved to: " + fileToSave.getAbsolutePath());
                JOptionPane.showMessageDialog(this, "Logs saved successfully to:\n" +
                        fileToSave.getAbsolutePath(), "Logs Saved", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                LogManager.getInstance().error("Error saving logs", e);
                JOptionPane.showMessageDialog(this, "Error saving logs: " + e.getMessage(),
                        "Save Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
