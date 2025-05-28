#!/bin/bash

# Define colors for better output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}===== OutlookAlerter Token Authentication Test =====${NC}"
echo -e "${BLUE}This script will test the token authentication process${NC}"

# Config directory
CONFIG_DIR=~/.outlookalerter
CONFIG_FILE=$CONFIG_DIR/config.properties

# Make sure config directory exists
mkdir -p $CONFIG_DIR

# Make a backup of the config file if it exists
if [ -f "$CONFIG_FILE" ]; then
    echo -e "${YELLOW}Backing up existing config...${NC}"
    cp -f "$CONFIG_FILE" "$CONFIG_FILE.bak" 
    echo -e "${GREEN}Backup created at $CONFIG_FILE.bak${NC}"
fi

# Create a test configuration with an invalid token
echo -e "${YELLOW}Creating test config with invalid token...${NC}"
echo "accessToken=eyJinvalid_token" > "$CONFIG_FILE"
echo "refreshToken=" >> "$CONFIG_FILE"
echo "expiryTime=0" >> "$CONFIG_FILE"

# Make sure we have a sign-in URL, using Microsoft's generic one if none exists
if grep -q "signInUrl=" "$CONFIG_FILE.bak" 2>/dev/null; then
    grep "signInUrl=" "$CONFIG_FILE.bak" >> "$CONFIG_FILE"
else
    echo "signInUrl=https://login.microsoftonline.com/common/oauth2/v2.0/authorize" >> "$CONFIG_FILE"
fi

# Add any other non-token config settings from the backup
if [ -f "$CONFIG_FILE.bak" ]; then
    echo -e "${BLUE}Preserving other configuration settings...${NC}"
    grep -v "accessToken" "$CONFIG_FILE.bak" | grep -v "refreshToken" | grep -v "expiryTime" | grep -v "signInUrl" >> "$CONFIG_FILE"
fi

echo -e "${GREEN}Test config created${NC}"

# Set environment variables to ensure GUI components work correctly
export JAVA_OPTS="-Djava.awt.headless=false $JAVA_OPTS"

# Instructions
echo -e "${BLUE}==================== INSTRUCTIONS ====================${NC}"
echo -e "${YELLOW}1. The application will start with an invalid token${NC}"
echo -e "${YELLOW}2. The token entry dialog should appear${NC}"
echo -e "${YELLOW}3. A browser window should open for authentication${NC}"
echo -e "${YELLOW}4. After signing in, extract your token from the browser:${NC}"
echo -e "   ${BLUE}- Open Developer Tools (F12)${NC}"
echo -e "   ${BLUE}- Go to Application or Storage tab${NC}"
echo -e "   ${BLUE}- Find 'Local Storage' with 'token' entries${NC}"
echo -e "   ${BLUE}- Copy the access token (starts with 'eyJ')${NC}"
echo -e "${YELLOW}5. Paste the token into the dialog and click Submit${NC}"
echo -e "${YELLOW}6. The application should authenticate successfully${NC}"
echo -e "${BLUE}=====================================================${NC}"
echo ""
echo -e "${YELLOW}Press Enter to begin the test, or Ctrl+C to cancel${NC}"
read -r

# Run the application in GUI mode with debug logging
echo -e "${YELLOW}Starting application in GUI mode with debug logging...${NC}"
./run-gui.sh --debug

# Check exit code
EXIT_CODE=$?
if [ $EXIT_CODE -eq 0 ]; then
    echo -e "\n${GREEN}Test completed successfully!${NC}"
    echo -e "${BLUE}The token was accepted and the application authenticated properly.${NC}"
else
    echo -e "\n${RED}Test exited with error code: $EXIT_CODE${NC}"
    echo -e "${RED}Authentication may have failed or was interrupted.${NC}"
fi

# Prompt to restore the backup
echo -e "\n${YELLOW}Do you want to restore your original configuration? (y/n)${NC}"
read -r RESTORE
if [[ "$RESTORE" =~ ^[Yy]$ ]]; then
    if [ -f "$CONFIG_FILE.bak" ]; then
        cp -f "$CONFIG_FILE.bak" "$CONFIG_FILE"
        echo -e "${GREEN}Original configuration restored.${NC}"
    else
        echo -e "${RED}Backup file not found.${NC}"
    fi
else
    echo -e "${BLUE}Keeping the new configuration with the authenticated token.${NC}"
fi

echo -e "\n${GREEN}Test completed. If you encountered any issues, please check the docs/token-authentication-guide.md file.${NC}"
