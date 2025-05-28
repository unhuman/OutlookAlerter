#!/bin/bash

# Test the token validation enhancements
# This script validates that we provide clear feedback when a token is already valid

echo "Testing token validation enhancements..."

# Ensure the TOKEN_PATH variable is set to a predictable location
export TOKEN_PATH="$HOME/.outlookalerter/config.properties"

# First, make sure we have an app built
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
"$SCRIPT_DIR/build.sh"

# Now run the app with debug output enabled
java -Dgroovy.debug=true -cp "dist/OutlookAlerter.jar:lib/*" com.unhuman.outlookalerter.OutlookAlerter --gui

