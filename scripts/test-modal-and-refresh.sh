#!/bin/bash

# Test script to verify both fixes:
# 1. The token dialog is modal to the application
# 2. The Refresh Now button doesn't ask for a new token when the existing one is valid

# Set colors for better output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}Testing modal dialog and refresh button improvements${NC}"

# Ensure the TOKEN_PATH variable is set to a predictable location
export TOKEN_PATH="$HOME/.outlookalerter/config.properties"

# First, make sure we have an app built
echo -e "${YELLOW}Building the application...${NC}"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
"$SCRIPT_DIR/build.sh"

echo -e "${YELLOW}Starting the application with debug output...${NC}"
echo -e "${BLUE}This will test:${NC}"
echo -e "  ${GREEN}1. The token dialog should appear as a modal dialog (blocks interaction with other windows)${NC}"
echo -e "  ${GREEN}2. After entering a token, clicking 'Refresh Now' should use the existing token ${NC}"

# Run the app with debug output enabled and redirect to a log file
java -Dgroovy.debug=true -cp "dist/OutlookAlerter.jar:lib/*" com.unhuman.outlookalerter.OutlookAlerter --gui 2>&1 | tee modal-refresh-test.log

echo -e "${YELLOW}Test complete. Check if:${NC}"
echo -e "  ${BLUE}1. The token dialog was modal (stayed on top and blocked parent window)${NC}"
echo -e "  ${BLUE}2. When you click 'Refresh Now', it didn't prompt for a new token${NC}"
echo -e "  ${BLUE}Debug log saved to: modal-refresh-test.log${NC}"
