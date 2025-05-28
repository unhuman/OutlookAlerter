#!/bin/bash

# Colors for better output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}===== Testing Token Dialog =====${NC}"
echo -e "${BLUE}This test will force the token dialog to appear to verify it works correctly${NC}"

# Config file location
CONFIG_DIR=~/.outlookalerter
CONFIG_FILE=$CONFIG_DIR/config.properties

# Make a backup of the existing config
if [ -f "$CONFIG_FILE" ]; then
    echo -e "${YELLOW}Backing up existing config file...${NC}"
    cp -f "$CONFIG_FILE" "$CONFIG_FILE.backup"
    echo -e "${GREEN}✓ Backup created: $CONFIG_FILE.backup${NC}"
fi

# Create a test configuration with invalid token
echo -e "${YELLOW}Creating test configuration with invalid token...${NC}"
mkdir -p "$CONFIG_DIR"

cat > "$CONFIG_FILE" << EOF
# Test configuration with invalid token
accessToken=invalid_token_for_testing
refreshToken=
expiryTime=0
signInUrl=https://login.microsoftonline.com/common/oauth2/v2.0/authorize
EOF

echo -e "${GREEN}✓ Test configuration created${NC}"

# Clear any existing Java processes that might interfere
echo -e "${YELLOW}Checking for existing Java processes...${NC}"
if pgrep -x "java" > /dev/null; then
    echo -e "${BLUE}Found existing Java processes, consider terminating them if this test fails${NC}"
fi

# Run the application with the simple token dialog
echo -e "\n${YELLOW}Running OutlookAlerter with debug mode to test token dialog...${NC}"
echo -e "${BLUE}You should see a token entry dialog appear.${NC}"
echo -e "${BLUE}• If it works: Enter a test token and click Submit${NC}"
echo -e "${BLUE}• If it fails: Press Ctrl+C to terminate the test${NC}"
echo -e "\n${YELLOW}Starting in 3 seconds...${NC}"
sleep 3

# Set environment variables to ensure GUI works
export JAVA_AWT_HEADLESS=false
export AWT_TOOLKIT=CToolkit

# Run with debug 
./run-gui.sh --debug

# Capture exit code
EXIT_CODE=$?

echo -e "\n${YELLOW}Test completed with exit code: $EXIT_CODE${NC}"

# Prompt to restore original config
echo -e "${YELLOW}Do you want to restore your original configuration? (y/n)${NC}"
read -r RESTORE
if [[ "$RESTORE" =~ ^[Yy]$ ]]; then
    if [ -f "$CONFIG_FILE.backup" ]; then
        cp -f "$CONFIG_FILE.backup" "$CONFIG_FILE"
        echo -e "${GREEN}✓ Original configuration restored${NC}"
    else
        echo -e "${RED}× No backup file found${NC}"
    fi
fi

echo -e "\n${GREEN}===== End of Token Dialog Test =====${NC}"
