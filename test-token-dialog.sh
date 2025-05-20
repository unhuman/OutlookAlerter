#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}Testing Token Dialog${NC}"

CONFIG_FILE=~/.outlookalerter/config.properties

# Make a backup of the config file if it doesn't exist
if [ ! -f "${CONFIG_FILE}.bak" ]; then
  if [ -f "$CONFIG_FILE" ]; then
    cp -f "$CONFIG_FILE" "${CONFIG_FILE}.bak"
    echo -e "${GREEN}Created backup of config file at ${CONFIG_FILE}.bak${NC}"
  fi
fi

echo -e "${YELLOW}Creating test configuration...${NC}"

# Create config directory if it doesn't exist
mkdir -p ~/.outlookalerter

# Create a new config with an invalid token but keeping other settings
if [ -f "${CONFIG_FILE}" ]; then
    # First get all non-token settings
    grep -v "accessToken" "$CONFIG_FILE" | \
      grep -v "refreshToken" | \
      grep -v "expiryTime" > "${CONFIG_FILE}.new"

    # Add invalid token data
    echo "accessToken=invalid_token_for_testing" >> "${CONFIG_FILE}.new"
    echo "refreshToken=" >> "${CONFIG_FILE}.new"
    echo "expiryTime=0" >> "${CONFIG_FILE}.new"
else
    # Create new config if none exists
    cat > "${CONFIG_FILE}.new" << EOF
accessToken=invalid_token_for_testing
refreshToken=
expiryTime=0
signInUrl=https://login.microsoftonline.com/common/oauth2/v2.0/authorize
EOF
fi

# Replace config file
mv "${CONFIG_FILE}.new" "$CONFIG_FILE"
echo -e "${GREEN}Configuration updated with invalid token${NC}"

# First make sure the project is built
echo -e "${YELLOW}Building project...${NC}"
./build.sh

if [ $? -ne 0 ]; then
    echo -e "${RED}Build failed!${NC}"
    exit 1
fi

# Set GUI environment variables
export JAVA_AWT_HEADLESS=false
export AWT_TOOLKIT=CToolkit

echo -e "${BLUE}Running application in GUI mode for token dialog test...${NC}"
mvn exec:java -Dexec.mainClass=com.unhuman.outlookalerter.OutlookAlerter -Dexec.args="--gui"

# Check result
TEST_EXIT=$?
if [ $TEST_EXIT -eq 0 ]; then
    echo -e "\n${GREEN}Test completed successfully!${NC}"
else
    echo -e "\n${RED}Test failed with exit code: $TEST_EXIT${NC}"
fi

# Prompt to restore configuration
echo -e "\n${YELLOW}Do you want to restore your original configuration? (y/n)${NC}"
read -r RESTORE
if [[ "$RESTORE" =~ ^[Yy]$ ]]; then
    if [ -f "${CONFIG_FILE}.bak" ]; then
        cp -f "${CONFIG_FILE}.bak" "$CONFIG_FILE"
        echo -e "${GREEN}Original configuration restored${NC}"
    else
        echo -e "${RED}No backup file found${NC}"
    fi
fi

exit $TEST_EXIT
