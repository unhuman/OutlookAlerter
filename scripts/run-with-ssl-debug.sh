#!/bin/bash

# This script runs the OutlookAlerter app with the correct SSL truststore configuration
# It ensures the app can connect to Microsoft's servers through Netskope

APP_PATH="target/OutlookAlerter.app"
if [ ! -d "$APP_PATH" ]; then
    echo "Error: App not found at $APP_PATH"
    echo "Please run ./build-with-fixed-certs.sh first"
    exit 1
fi

# Ensure the truststore exists
TRUSTSTORE="$APP_PATH/Contents/Resources/truststore.jks"
if [ ! -f "$TRUSTSTORE" ]; then
    echo "Error: Truststore not found at $TRUSTSTORE"
    echo "Please run ./build-with-fixed-certs.sh to rebuild the app"
    exit 1
fi

# Enable SSL debugging for this run
export JAVAX_NET_DEBUG=ssl:handshake
export OUTLOOKALERTER_SSL_DEBUG=true

echo "Running OutlookAlerter with SSL debugging enabled..."
echo "Truststore: $TRUSTSTORE"

# Open the app
open "$APP_PATH"

echo "App started. Check ~/Library/Logs/OutlookAlerter/ for logs."
