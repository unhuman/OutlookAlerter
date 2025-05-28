#!/bin/bash

# Run OutlookAlerter with special macOS settings to fix the token dialog
# This script should solve the issue with the white screen token dialog

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}===== OutlookAlerter with Fixed Token Dialog =====${NC}"
echo -e "${BLUE}This script runs the application with special settings to fix the token dialog${NC}"

# Force the application to use macOS-specific settings
export JAVA_AWT_HEADLESS=false
export AWT_TOOLKIT=CToolkit
export DISPLAY=:0

# Run with enhanced Java options for macOS
echo -e "${YELLOW}Starting OutlookAlerter with special macOS GUI options...${NC}"

# Java options optimized for macOS GUI applications
# These settings help ensure Swing components display and function correctly
java -Djava.awt.headless=false \
     -Dapple.awt.UIElement=false \
     -Dapple.laf.useScreenMenuBar=true \
     -Dapple.awt.application.appearance=system \
     -Dswing.defaultlaf=com.apple.laf.AquaLookAndFeel \
     -Dswing.crossplatformlaf=com.apple.laf.AquaLookAndFeel \
     -Dsun.awt.disablegrab=true \
     -Dsun.java2d.opengl=false \
     -Dsun.java2d.metal=false \
     -Dgroovy.debug=true \
     -cp "./dist/OutlookAlerter.jar:./lib/*" \
     com.unhuman.outlookalerter.OutlookAlerter --debug

# Check the exit code
EXIT_CODE=$?
if [ $EXIT_CODE -eq 0 ]; then
    echo -e "\n${GREEN}Application exited successfully${NC}"
else
    echo -e "\n${RED}Application exited with error code: $EXIT_CODE${NC}"
fi
