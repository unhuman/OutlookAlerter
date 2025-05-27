#!/bin/bash

# Test script to verify that the sign-in URL defaults correctly
# This script will clear the config file completely and run the application

# Directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR/.."

echo "Temporarily backing up any existing configuration..."
if [ -f ~/.config/OutlookAlerter/config.properties ]; then
    cp ~/.config/OutlookAlerter/config.properties ~/.config/OutlookAlerter/config.properties.bak
fi

echo "Removing configuration to test default URL handling..."
rm -f ~/.config/OutlookAlerter/config.properties

echo "Running OutlookAlerter with no configuration - dialog should show with default URL..."
java -jar target/OutlookAlerter-1.0.0-SNAPSHOT-jar-with-dependencies.jar

echo "Test complete - restoring original settings..."
if [ -f ~/.config/OutlookAlerter/config.properties.bak ]; then
    mv ~/.config/OutlookAlerter/config.properties.bak ~/.config/OutlookAlerter/config.properties
fi

echo "Done."
