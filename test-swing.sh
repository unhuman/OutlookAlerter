#!/bin/bash

# A minimal test that only uses Java Swing directly to verify GUI functionality

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}===== Testing Basic Java Swing Dialog =====${NC}"

# Set essential environment variables
export JAVA_AWT_HEADLESS=false
export AWT_TOOLKIT=CToolkit
export DISPLAY=:0

# Create and run a simple Java Swing test
cat > ./SwingTest.java << 'EOF'
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class SwingTest {
    public static void main(String[] args) {
        System.out.println("Creating Java Swing window...");
        
        // Must run UI operations on the EDT
        SwingUtilities.invokeLater(() -> {
            try {
                // Create a simple frame
                JFrame frame = new JFrame("Java Swing Test");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                
                // Create a panel with a button
                JPanel panel = new JPanel();
                JButton button = new JButton("Click Me");
                button.addActionListener((e) -> {
                    JOptionPane.showMessageDialog(frame, "Button clicked!");
                });
                panel.add(button);
                
                // Set up the frame
                frame.getContentPane().add(panel);
                frame.setSize(300, 200);
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
                
                System.out.println("Dialog should now be visible");
                
                // Request focus
                frame.toFront();
                frame.requestFocus();
            } catch (Exception e) {
                System.err.println("Error creating UI: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
}
EOF

echo -e "${GREEN}✓ Created Java Swing test${NC}"
echo -e "${YELLOW}Compiling and running test in 3 seconds...${NC}"
sleep 3

# Compile and run
echo -e "${YELLOW}Compiling...${NC}"
javac SwingTest.java

echo -e "${YELLOW}Running...${NC}"
java -Djava.awt.headless=false \
     -Dapple.awt.UIElement=false \
     -Dapple.laf.useScreenMenuBar=true \
     -Dswing.defaultlaf=com.apple.laf.AquaLookAndFeel \
     SwingTest

echo -e "\n${GREEN}===== Test Complete =====${NC}"
