#!/bin/bash

# Fix SSL certificate issues in an existing OutlookAlerter.app by directly
# replacing its truststore with one that includes the Netskope certificate

APP_PATH="$1"
if [[ -z "$APP_PATH" ]]; then
    echo "Usage: $0 /path/to/OutlookAlerter.app"
    exit 1
fi

if [[ ! -d "$APP_PATH" ]]; then
    echo "Error: $APP_PATH is not a valid directory"
    exit 1
fi

if [[ ! -d "$APP_PATH/Contents" ]]; then
    echo "Error: $APP_PATH does not appear to be a valid app bundle"
    exit 1
fi

# Create resources directory if it doesn't exist
RESOURCES_DIR="$APP_PATH/Contents/Resources"
mkdir -p "$RESOURCES_DIR"

# Create a new truststore
TRUSTSTORE="$RESOURCES_DIR/truststore.jks"
TRUSTSTORE_PASSWORD="changeit"
echo "Creating new truststore at $TRUSTSTORE"

# First import the system certificates
echo "Importing system certificates..."
keytool -importkeystore -srckeystore "$JAVA_HOME/lib/security/cacerts" \
    -destkeystore "$TRUSTSTORE" -srcstorepass changeit -deststorepass "$TRUSTSTORE_PASSWORD" || true

# Try to find and import the Netskope certificate
NETSKOPE_CERT="/Library/Application Support/Netskope/STAgent/download/nscacert_combined.pem"
if [[ ! -f "$NETSKOPE_CERT" ]]; then
    # Try alternative location
    ALT_NETSKOPE_CERT="/Applications/Netskope Client.app/Contents/Resources/nscacert.pem"
    if [[ -f "$ALT_NETSKOPE_CERT" ]]; then
        echo "Found Netskope certificate at: $ALT_NETSKOPE_CERT"
        NETSKOPE_CERT="$ALT_NETSKOPE_CERT"
    else
        echo "Warning: Could not find Netskope certificate"
        echo "Will try to generate it using system certificates..."
        
        # Try to extract Netskope certificate from system keychain
        mkdir -p /tmp/netskope
        security find-certificate -a -c "Netskope" -p > /tmp/netskope/nscacert.pem 2>/dev/null
        
        if [[ -s /tmp/netskope/nscacert.pem ]]; then
            echo "Found Netskope certificate in system keychain"
            NETSKOPE_CERT="/tmp/netskope/nscacert.pem"
        else
            echo "Failed to find Netskope certificate in system keychain"
            NETSKOPE_CERT=""
        fi
    fi
fi

# Import Netskope certificate if found
if [[ -n "$NETSKOPE_CERT" && -f "$NETSKOPE_CERT" ]]; then
    echo "Importing Netskope certificate from $NETSKOPE_CERT"
    keytool -importcert -file "$NETSKOPE_CERT" -keystore "$TRUSTSTORE" \
        -storepass "$TRUSTSTORE_PASSWORD" -alias "nscacert" -noprompt || true
    
    # Verify the certificate was imported
    echo "Verifying certificate import:"
    keytool -list -keystore "$TRUSTSTORE" -storepass "$TRUSTSTORE_PASSWORD" | grep -E "nscacert" || \
        echo "Warning: Could not verify Netskope certificate in truststore"
else
    echo "No Netskope certificate found to import"
    
    # Create a self-signed certificate for testing
    echo "Creating a self-signed certificate for testing..."
    keytool -genkeypair -alias netskope-test -keyalg RSA -keystore "$TRUSTSTORE" \
        -storepass "$TRUSTSTORE_PASSWORD" -keypass "$TRUSTSTORE_PASSWORD" -validity 365 \
        -dname "CN=Netskope Test, OU=Test, O=Test, L=Test, ST=Test, C=US"
fi

# Update the Info.plist to use absolute path
INFO_PLIST="$APP_PATH/Contents/Info.plist"
if [[ -f "$INFO_PLIST" ]]; then
    echo "Updating $INFO_PLIST to use absolute truststore path"
    
    # Create backup
    cp "$INFO_PLIST" "$INFO_PLIST.bak"
    
    # Replace trustStore path with absolute path
    sed -i '' "s|-Djavax.net.ssl.trustStore=.*|-Djavax.net.ssl.trustStore=$TRUSTSTORE|g" "$INFO_PLIST"
    
    echo "Updated Info.plist. Backup saved as $INFO_PLIST.bak"
else
    echo "Error: Info.plist not found at $INFO_PLIST"
    exit 1
fi

# Create a special run script that sets environment variables
RUN_SCRIPT="run-with-fixed-ssl.sh"
cat > "$RUN_SCRIPT" << EOF
#!/bin/bash

# Run OutlookAlerter with fixed SSL configuration
export JAVAX_NET_DEBUG=ssl:handshake
export OUTLOOKALERTER_SSL_DEBUG=true
export JAVAX_NET_SSL_TRUSTSTORE="$TRUSTSTORE"
export JAVAX_NET_SSL_TRUSTSTOREPASSWORD="$TRUSTSTORE_PASSWORD"

echo "Running OutlookAlerter with fixed SSL configuration..."
echo "Truststore: $TRUSTSTORE"
open "$APP_PATH"
EOF
chmod +x "$RUN_SCRIPT"

echo
echo "Certificate fix complete!"
echo "To run the app with this fix:"
echo "1. Double-click the app in Finder, or"
echo "2. Run: ./run-with-fixed-ssl.sh"
echo
echo "If you still have issues, set these environment variables before running:"
echo "export JAVAX_NET_SSL_TRUSTSTORE=\"$TRUSTSTORE\""
echo "export JAVAX_NET_SSL_TRUSTSTOREPASSWORD=\"$TRUSTSTORE_PASSWORD\""
