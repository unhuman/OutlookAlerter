#!/bin/bash

# Test script to verify that the token dialog automatically shows when there's no access token
# This script will clear the token and then run the application to see if the token dialog appears

# Directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR/.."

echo "Temporarily backing up any existing token settings..."
if [ -f ~/.config/OutlookAlerter/config.properties ]; then
    cp ~/.config/OutlookAlerter/config.properties ~/.config/OutlookAlerter/config.properties.bak
fi

echo "Clearing out token settings to simulate missing token scenario..."
mkdir -p ~/.config/OutlookAlerter
echo "# Test configuration with no token" > ~/.config/OutlookAlerter/config.properties
echo "signInUrl=https://developer.microsoft.com/en-us/graph" >> ~/.config/OutlookAlerter/config.properties
echo "ignoreCertValidation=false" >> ~/.config/OutlookAlerter/config.properties

echo "Running OutlookAlerter with missing token - dialog should automatically appear..."
java -jar target/OutlookAlerter-1.0.0-SNAPSHOT-jar-with-dependencies.jar

echo "Test complete - restoring original settings..."
if [ -f ~/.config/OutlookAlerter/config.properties.bak ]; then
    mv ~/.config/OutlookAlerter/config.properties.bak ~/.config/OutlookAlerter/config.properties
fi

echo "Done."
