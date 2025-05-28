#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}===== Final Token Dialog Fix =====${NC}"
echo -e "${BLUE}This script will attempt to fix token dialog issues using the absolute simplest approach${NC}"

# Config
CONFIG_FILE=~/.outlookalerter/config.properties

# Back up config
if [ -f "$CONFIG_FILE" ]; then
    echo -e "${YELLOW}Backing up your config...${NC}"
    cp -f "$CONFIG_FILE" "$CONFIG_FILE.backup"
    echo -e "${GREEN}✓ Backup created${NC}"
fi

# Create a minimal test config
echo -e "${YELLOW}Creating minimal test config...${NC}"
mkdir -p ~/.outlookalerter
cat > "$CONFIG_FILE" << EOF
accessToken=eyJINVALID
refreshToken=
expiryTime=0
signInUrl=https://login.microsoftonline.com/common/oauth2/v2.0/authorize
EOF
echo -e "${GREEN}✓ Config created${NC}"

# Create a launcher script with explicit env vars
echo -e "${YELLOW}Creating special launcher script...${NC}"
cat > ./run-dialog-fix.sh << 'EOF'
#!/bin/bash
export JAVA_AWT_HEADLESS=false
export AWT_TOOLKIT=CToolkit
export DISPLAY=:0

echo "Starting with special environmental settings for GUI..."
java -Djava.awt.headless=false \
     -Dapple.awt.UIElement=false \
     -Dapple.laf.useScreenMenuBar=true \
     -Dswing.defaultlaf=com.apple.laf.AquaLookAndFeel \
     -Dgroovy.debug=true \
     -cp "./dist/OutlookAlerter.jar:./lib/*" \
     com.unhuman.outlookalerter.OutlookAlerter --debug
EOF
chmod +x ./run-dialog-fix.sh
echo -e "${GREEN}✓ Launcher created${NC}"

# Test java version
echo -e "${YELLOW}Checking Java environment...${NC}"
java -version
echo ""

# Instructions
echo -e "${BLUE}==== INSTRUCTIONS ====${NC}"
echo -e "${YELLOW}1. Run the application with:${NC} ${GREEN}./run-dialog-fix.sh${NC}"
echo -e "${YELLOW}2. A simpler token dialog should appear${NC}"
echo -e "${YELLOW}3. To restore your config after testing:${NC} ${GREEN}cp ~/.outlookalerter/config.properties.backup ~/.outlookalerter/config.properties${NC}"
echo ""
echo -e "${BLUE}Press Enter to continue, or Ctrl+C to cancel${NC}"
read

# Run the script
echo -e "${YELLOW}Running application...${NC}"
./run-dialog-fix.sh
