#!/bin/bash

# Test the token validation enhancements
# This script validates that token validation works without using expiry time
#
# CHANGES IMPLEMENTED:
# 1. Removed any usage of expiration time when checking token validity
# 2. Now relying entirely on actual API calls to determine if a token is valid
# 3. Ensuring that 400-level HTTP errors trigger prompting for a new token
# 4. Updated token entry dialog to no longer ask for or store expiry time
# 5. Made ConfigManager no longer store token expiry times

echo "Testing token validation enhancements..."

# Ensure the TOKEN_PATH variable is set to a predictable location
export TOKEN_PATH="$HOME/.outlookalerter/config.properties"

# First, make sure we have an app built
cd "$(dirname "$0")/.." && ./scripts/build.sh

# Now run the app with debug output enabled
java -Dgroovy.debug=true -jar "target/OutlookAlerter-1.0.0-SNAPSHOT-jar-with-dependencies.jar" --gui

