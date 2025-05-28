#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}Running OutlookAlerter${NC}"

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Use Maven's target directory for the executable jar
JAR_PATH=$(ls $SCRIPT_DIR/../target/OutlookAlerter-*-jar-with-dependencies.jar 2>/dev/null | head -n 1)

# Check if jar exists, if not try to build
if [ ! -f "$JAR_PATH" ]; then
    echo -e "${YELLOW}Executable jar not found, running build...${NC}"
    "$SCRIPT_DIR/build.sh"
    if [ $? -ne 0 ]; then
        echo -e "${RED}Build failed, cannot run application${NC}"
        exit 1
    fi
fi

# Check if running in console mode
if [[ "$*" == *"--console"* ]]; then
    echo -e "${YELLOW}Running in console mode${NC}"
    java -jar "$JAR_PATH" --console "$@"
else
    echo -e "${YELLOW}Running in GUI mode${NC}"
    # Add -Dapple.awt.UIElement=true for macOS to avoid dock icon when running in background
    if [[ "$OSTYPE" == "darwin"* ]]; then
        java -Dapple.awt.UIElement=true -jar "$JAR_PATH" "$@"
    else
        java -jar "$JAR_PATH" "$@"
    fi
fi
