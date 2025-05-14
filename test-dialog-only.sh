#!/bin/bash

# A simpler test script that only tests the token dialog component

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}===== Testing Token Dialog Only =====${NC}"

# Set essential environment variables
export JAVA_AWT_HEADLESS=false
export AWT_TOOLKIT=CToolkit
export DISPLAY=:0

# Create and run a simple test class that just shows the dialog
cat > ./TestTokenDialog.groovy << 'EOF'
import com.unhuman.outlookalerter.SimpleTokenDialog

class TestTokenDialog {
    static void main(String[] args) {
        println "Creating token dialog..."
        SimpleTokenDialog dialog = new SimpleTokenDialog("https://login.microsoftonline.com/common/oauth2/v2.0/authorize")
        
        println "Showing dialog..."
        dialog.show()
        
        println "Waiting for token input (max 5 minutes)..."
        def tokens = dialog.waitForTokens(300)
        
        if (tokens) {
            println "Token received: " + tokens.accessToken.substring(0, Math.min(10, tokens.accessToken.length())) + "..."
            if (tokens.refreshToken) {
                println "Refresh token received"
            }
        } else {
            println "No token received (dialog was canceled or timed out)"
        }
    }
}
EOF

echo -e "${GREEN}✓ Created test script${NC}"
echo -e "${YELLOW}Running test dialog in 3 seconds...${NC}"
sleep 3

# Run the test
java -Djava.awt.headless=false \
     -Dapple.awt.UIElement=false \
     -Dapple.laf.useScreenMenuBar=true \
     -Dswing.defaultlaf=com.apple.laf.AquaLookAndFeel \
     -Dgroovy.debug=true \
     -cp "./dist/OutlookAlerter.jar:./lib/*" \
     groovy.ui.GroovyMain ./TestTokenDialog.groovy

echo -e "\n${GREEN}===== Test Complete =====${NC}"
