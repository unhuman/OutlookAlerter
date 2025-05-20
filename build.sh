#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}Building OutlookAlerter with Maven${NC}"

# Use Maven for compilation and packaging
mvn clean compile package

if [ $? -eq 0 ]; then
    echo -e "${GREEN}Build successful!${NC}"
    echo -e "${BLUE}The application jar is available at: target/OutlookAlerter-1.0-SNAPSHOT-jar-with-dependencies.jar${NC}"
    
    # Create link to jar in dist directory for backwards compatibility
    mkdir -p dist
    rm -f dist/OutlookAlerter.jar
    ln -s ../target/OutlookAlerter-1.0-SNAPSHOT-jar-with-dependencies.jar dist/OutlookAlerter.jar
    
    echo -e "${YELLOW}To run the application:${NC}"
    echo -e "1. ${GREEN}./run.sh${NC} - For console mode"
    echo -e "2. ${GREEN}./run-gui.sh${NC} - For GUI mode"
else
    echo -e "${RED}Build failed! Check the Maven output above for errors.${NC}"
    exit 1
fi