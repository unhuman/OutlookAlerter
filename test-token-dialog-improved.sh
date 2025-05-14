#!/bin/bash

# Colors for better output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}===== Reliable Token Dialog Launcher =====${NC}"
echo -e "${BLUE}This script provides the most reliable way to open the token dialog${NC}"

# Config directory
CONFIG_DIR=~/.outlookalerter
CONFIG_FILE=$CONFIG_DIR/config.properties

# Make a backup of the config file if it exists
if [ -f "$CONFIG_FILE" ]; then
    echo -e "${YELLOW}Backing up existing config...${NC}"
    cp -f "$CONFIG_FILE" "$CONFIG_FILE.bak" 
    echo -e "${GREEN}Backup created at $CONFIG_FILE.bak${NC}"
fi

# Create a new config with an invalid/expired token
echo -e "${YELLOW}Creating test config with invalid token...${NC}"
mkdir -p "$CONFIG_DIR"
echo "accessToken=eyJinvalid_token" > "$CONFIG_FILE"
echo "refreshToken=" >> "$CONFIG_FILE"
echo "expiryTime=0" >> "$CONFIG_FILE"
echo "signInUrl=https://login.microsoftonline.com/common/oauth2/v2.0/authorize" >> "$CONFIG_FILE"
echo -e "${GREEN}Test config created${NC}"

# Set essential environment variables - these are critical for macOS GUI apps
export JAVA_AWT_HEADLESS=false
export AWT_TOOLKIT=CToolkit
export DISPLAY=:0

# Clean up any old temporary test files
rm -f TestTokenDialogRunner.groovy 2>/dev/null

# Create a script to test just the token dialog
cat > TestTokenDialogRunner.groovy << 'EOF'
import com.unhuman.outlookalerter.*

class TestTokenDialogRunner {
    static void main(String[] args) {
        try {
            println "\n==== Testing SimpleTokenDialog ====\n"
            
            // Create dialog
            println "Creating token dialog..."
            SimpleTokenDialog dialog = new SimpleTokenDialog("https://login.microsoftonline.com/common/oauth2/v2.0/authorize")
            
            // Show it (should open browser automatically)
            println "Showing dialog..."
            dialog.show()
            
            println "Dialog is showing. Please interact with it."
            println "Waiting for user input (max 10 minutes)..."
            
            // Wait for token input (10 minutes max)
            def tokens = dialog.waitForTokens(600)
            
            if (tokens) {
                println "\n✅ SUCCESS: Token received!"
                println "Token starts with: " + tokens.accessToken.substring(0, Math.min(10, tokens.accessToken.length())) + "..."
                if (tokens.refreshToken) {
                    println "Refresh token received"
                }
            } else {
                println "\n❌ NO TOKEN: Dialog was canceled or timed out"
            }
            
            println "\n==== Test Complete ====\n"
            System.exit(0)
        } catch (Exception e) {
            println "\n❌ ERROR: " + e.getMessage()
            e.printStackTrace()
            System.exit(1)
        }
    }
}
EOF

echo -e "${GREEN}✓ Created test runner${NC}"

# Launch with optimal Java settings
echo -e "${YELLOW}Launching token dialog test...${NC}"
java -Djava.awt.headless=false \
     -Dapple.awt.UIElement=false \
     -Dapple.laf.useScreenMenuBar=true \
     -Dswing.defaultlaf=com.apple.laf.AquaLookAndFeel \
     -Dsun.java2d.opengl=false \
     -Dgroovy.debug=true \
     -cp "./dist/OutlookAlerter.jar:./lib/*" \
     groovy.ui.GroovyMain TestTokenDialogRunner.groovy

# Capture result
RESULT=$?
if [ $RESULT -eq 0 ]; then
    echo -e "\n${GREEN}✓ Test completed successfully${NC}"
else
    echo -e "\n${RED}× Test failed with error code $RESULT${NC}"
fi

# Cleanup
rm -f TestTokenDialogRunner.groovy 2>/dev/null

# Instructions for restoring the backup
echo -e "\n${YELLOW}Test completed.${NC}"
echo -e "${YELLOW}To restore your original config, run:${NC}"
echo -e "${GREEN}cp -f $CONFIG_FILE.bak $CONFIG_FILE${NC}"

echo -e "\n${BLUE}If the token dialog worked correctly, you can now run the application with:${NC}"
echo -e "${GREEN}./run-gui.sh${NC}"
