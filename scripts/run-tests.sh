#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}Running OutlookAlerter Tests${NC}"

# Run Maven tests
if [[ "$*" == *"--clean"* ]]; then
    echo -e "${YELLOW}Running clean test${NC}"
    mvn clean test
else
    echo -e "${YELLOW}Running tests${NC}"
    mvn test
fi

# Check exit code
if [ $? -eq 0 ]; then
    echo -e "\n${GREEN}All tests passed!${NC}"
else
    echo -e "\n${RED}Some tests failed. Check the Maven output above for details.${NC}"
    exit 1
fi
