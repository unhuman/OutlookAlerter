#!/bin/bash

# Run OutlookAlerter with fixed SSL configuration
APP_PATH="target/OutlookAlerter.app"
TRUSTSTORE="$APP_PATH/Contents/Resources/truststore.jks"

export JAVAX_NET_DEBUG=ssl:handshake
export OUTLOOKALERTER_SSL_DEBUG=true
export JAVAX_NET_SSL_TRUSTSTORE="$TRUSTSTORE"
export JAVAX_NET_SSL_TRUSTSTOREPASSWORD="changeit"

echo "Running OutlookAlerter with fixed SSL configuration..."
echo "Truststore: $TRUSTSTORE"
open "$APP_PATH"
