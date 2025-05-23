#!/bin/bash

# Advanced SSL Certificate Diagnostic Tool for OutlookAlerter
# This script thoroughly analyzes your SSL certificate configuration
# to find and fix issues with Microsoft Graph API connections

LOG_FILE="ssl-diagnostics-$(date +%Y%m%d_%H%M%S).log"
APP_DIR="$(cd "$(dirname "$0")" && pwd)"

# Helper function to log output
log() {
    echo "$@" | tee -a "$LOG_FILE"
}

log "===== OutlookAlerter SSL Certificate Diagnostic Tool ====="
log "Date: $(date)"
log "Log file: $LOG_FILE"
log "Java version: $(java -version 2>&1 | head -1)"
log "Java home: $JAVA_HOME"
log

# Create diagnostic directory
DIAG_DIR="$APP_DIR/ssl-diagnostics-$(date +%Y%m%d_%H%M%S)"
mkdir -p "$DIAG_DIR"
log "Created diagnostic directory: $DIAG_DIR"

# Function to check certificate file
check_certificate() {
    local cert_file="$1"
    local cert_name="$2"
    
    log "Checking $cert_name certificate: $cert_file"
    
    if [[ ! -f "$cert_file" ]]; then
        log "  - File not found"
        return 1
    fi
    
    log "  - File exists ($(du -h "$cert_file" | cut -f1) bytes)"
    
    # Try to get certificate info
    if [[ "$cert_file" == *.pem ]]; then
        log "  - Certificate details:"
        openssl x509 -in "$cert_file" -text -noout 2>/dev/null | grep -E "Subject:|Issuer:|Not Before:|Not After:" | sed 's/^/      /' | tee -a "$LOG_FILE"
        
        # Copy to diagnostic directory
        cp "$cert_file" "$DIAG_DIR/$(basename "$cert_file")"
        log "  - Copied to diagnostic directory"
    elif [[ "$cert_file" == *.jks ]]; then
        log "  - Keystore details:"
        keytool -list -keystore "$cert_file" -storepass changeit 2>/dev/null | head -10 | sed 's/^/      /' | tee -a "$LOG_FILE"
        
        # Copy to diagnostic directory
        cp "$cert_file" "$DIAG_DIR/$(basename "$cert_file")"
        log "  - Copied to diagnostic directory"
    fi
    
    return 0
}

# Check for Netskope certificate
log "=== Checking for Netskope certificates ==="
NETSKOPE_CERT="/Library/Application Support/Netskope/STAgent/download/nscacert_combined.pem"
ALT_NETSKOPE_CERT="/Applications/Netskope Client.app/Contents/Resources/nscacert.pem"

if [[ -f "$NETSKOPE_CERT" ]]; then
    check_certificate "$NETSKOPE_CERT" "Netskope combined"
elif [[ -f "$ALT_NETSKOPE_CERT" ]]; then
    check_certificate "$ALT_NETSKOPE_CERT" "Netskope alternative"
    # Copy it with the expected name for consistency
    cp "$ALT_NETSKOPE_CERT" "$DIAG_DIR/nscacert_combined.pem"
    log "  - Copied as nscacert_combined.pem for consistency"
    NETSKOPE_CERT="$ALT_NETSKOPE_CERT"
else
    log "  - No Netskope certificate found at standard locations"
    
    # Try to find it elsewhere
    log "  - Searching for Netskope certificates elsewhere..."
    FOUND_CERT=$(find /Applications -name "Netskope*.app" -type d -exec find {} -name "*.pem" \; 2>/dev/null | head -1)
    
    if [[ -n "$FOUND_CERT" ]]; then
        log "  - Found potential Netskope certificate: $FOUND_CERT"
        check_certificate "$FOUND_CERT" "Netskope found"
        cp "$FOUND_CERT" "$DIAG_DIR/nscacert.pem"
        NETSKOPE_CERT="$FOUND_CERT"
    else
        log "  - No Netskope certificate found anywhere, this may cause SSL errors"
    fi
fi

# Check application truststore
log "=== Checking application truststore ==="
APP_BUNDLE="$APP_DIR/target/OutlookAlerter.app"
if [[ -d "$APP_BUNDLE" ]]; then
    log "Found app bundle: $APP_BUNDLE"
    
    # Check Info.plist
    INFO_PLIST="$APP_BUNDLE/Contents/Info.plist"
    log "Checking Info.plist..."
    if [[ -f "$INFO_PLIST" ]]; then
        cp "$INFO_PLIST" "$DIAG_DIR/Info.plist"
        log "  - Info.plist found and copied to diagnostic directory"
        
        # Check truststore configuration
        TRUSTSTORE_CONFIG=$(grep -A 2 "trustStore" "$INFO_PLIST" || echo "No trustStore configuration found")
        log "  - TrustStore configuration in Info.plist:"
        log "$(echo "$TRUSTSTORE_CONFIG" | sed 's/^/      /')"
        
        # Extract trustStore path
        TRUSTSTORE_PATH=$(grep -A 1 "trustStore" "$INFO_PLIST" | grep -o '[^"]*truststore.jks[^"]*' || echo "")
        
        if [[ -n "$TRUSTSTORE_PATH" ]]; then
            log "  - Extracted truststore path: $TRUSTSTORE_PATH"
            
            # Expand $APP_ROOT if present
            if [[ "$TRUSTSTORE_PATH" == *'$APP_ROOT'* ]]; then
                EXPANDED_PATH="${TRUSTSTORE_PATH/\$APP_ROOT/$APP_BUNDLE}"
                log "  - Expanded path: $EXPANDED_PATH"
                
                # Check if expanded path exists
                if [[ -f "$EXPANDED_PATH" ]]; then
                    log "  - Truststore file exists at expanded path"
                    check_certificate "$EXPANDED_PATH" "App bundle truststore"
                else
                    log "  - WARNING: Truststore file does not exist at expanded path"
                fi
            else
                # If it's a relative path, try to resolve it
                if [[ "$TRUSTSTORE_PATH" != /* ]]; then
                    RESOLVED_PATH="$APP_BUNDLE/$TRUSTSTORE_PATH"
                    log "  - Resolved path would be: $RESOLVED_PATH"
                    
                    if [[ -f "$RESOLVED_PATH" ]]; then
                        log "  - Truststore file exists at resolved path"
                        check_certificate "$RESOLVED_PATH" "App bundle truststore"
                    else
                        log "  - WARNING: Truststore file does not exist at resolved path"
                    fi
                else
                    # Absolute path
                    if [[ -f "$TRUSTSTORE_PATH" ]]; then
                        log "  - Truststore file exists at specified absolute path"
                        check_certificate "$TRUSTSTORE_PATH" "App bundle truststore"
                    else
                        log "  - WARNING: Truststore file does not exist at specified absolute path"
                    fi
                fi
            fi
        else
            log "  - WARNING: Could not extract truststore path from Info.plist"
        fi
    else
        log "  - WARNING: Info.plist not found in app bundle"
    fi
    
    # Check for truststore in standard locations
    STANDARD_TRUSTSTORE="$APP_BUNDLE/Contents/Resources/truststore.jks"
    if [[ -f "$STANDARD_TRUSTSTORE" ]]; then
        log "Found standard truststore location: $STANDARD_TRUSTSTORE"
        check_certificate "$STANDARD_TRUSTSTORE" "Standard location truststore"
    else
        log "No truststore found at standard location: $STANDARD_TRUSTSTORE"
    fi
else
    log "App bundle not found at: $APP_BUNDLE"
fi

# Create a fixed truststore
log "=== Creating a fixed truststore ==="
FIXED_TRUSTSTORE="$DIAG_DIR/fixed-truststore.jks"
log "Creating new truststore at $FIXED_TRUSTSTORE"

# First import the system certificates using keytool
if [[ -n "$JAVA_HOME" ]]; then
    log "Importing system certificates from Java cacerts..."
    keytool -importkeystore -srckeystore "$JAVA_HOME/lib/security/cacerts" \
        -destkeystore "$FIXED_TRUSTSTORE" -srcstorepass changeit -deststorepass changeit > /dev/null 2>&1
        
    if [[ $? -eq 0 ]]; then
        log "  - Successfully imported system certificates"
    else
        log "  - ERROR: Failed to import system certificates"
        
        # Alternate method
        log "  - Trying alternate method..."
        keytool -genkeypair -keyalg RSA -alias dummy -keystore "$FIXED_TRUSTSTORE" \
            -storepass changeit -keypass changeit -validity 365 -keysize 2048 \
            -dname "CN=Dummy, OU=Dummy, O=Dummy, L=Dummy, ST=Dummy, C=US" > /dev/null 2>&1
        keytool -delete -alias dummy -keystore "$FIXED_TRUSTSTORE" -storepass changeit > /dev/null 2>&1
    fi
else
    log "JAVA_HOME not set, creating empty truststore..."
    keytool -genkeypair -keyalg RSA -alias dummy -keystore "$FIXED_TRUSTSTORE" \
        -storepass changeit -keypass changeit -validity 365 -keysize 2048 \
        -dname "CN=Dummy, OU=Dummy, O=Dummy, L=Dummy, ST=Dummy, C=US" > /dev/null 2>&1
    keytool -delete -alias dummy -keystore "$FIXED_TRUSTSTORE" -storepass changeit > /dev/null 2>&1
fi

# Import Netskope certificate if available
if [[ -f "$NETSKOPE_CERT" ]]; then
    log "Importing Netskope certificate from $NETSKOPE_CERT to fixed truststore..."
    keytool -importcert -file "$NETSKOPE_CERT" -keystore "$FIXED_TRUSTSTORE" \
        -storepass changeit -alias "nscacert" -noprompt > /dev/null 2>&1
        
    if [[ $? -eq 0 ]]; then
        log "  - Successfully imported Netskope certificate"
    else
        log "  - ERROR: Failed to import Netskope certificate"
    fi
else
    log "  - No Netskope certificate available to import"
fi

# Check fixed truststore
log "Verifying fixed truststore:"
keytool -list -keystore "$FIXED_TRUSTSTORE" -storepass changeit | head -10 | sed 's/^/      /' | tee -a "$LOG_FILE"

# Create fix script
log "=== Creating fix script ==="
FIX_SCRIPT="fix-app-certificates.sh"

if [[ ! -f "$FIX_SCRIPT" ]]; then
    cat > "$FIX_SCRIPT" << 'EOF'
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
        read -p "Enter the path to the Netskope certificate (or press Enter to skip): " CUSTOM_CERT_PATH
        if [[ -n "$CUSTOM_CERT_PATH" && -f "$CUSTOM_CERT_PATH" ]]; then
            NETSKOPE_CERT="$CUSTOM_CERT_PATH"
        else
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
fi

# Update the Info.plist to use absolute path
INFO_PLIST="$APP_PATH/Contents/Info.plist"
if [[ -f "$INFO_PLIST" ]]; then
    echo "Updating $INFO_PLIST to use absolute truststore path"
    
    # Create backup
    cp "$INFO_PLIST" "$INFO_PLIST.bak"
    
    # Replace trustStore path with absolute path
    sed -i '' 's|-Djavax.net.ssl.trustStore=.*|-Djavax.net.ssl.trustStore='$TRUSTSTORE'|g' "$INFO_PLIST"
    
    echo "Updated Info.plist. Backup saved as $INFO_PLIST.bak"
else
    echo "Error: Info.plist not found at $INFO_PLIST"
    exit 1
fi

echo
echo "Certificate fix complete!"
echo "To run the app with this fix:"
echo "1. Double-click the app in Finder, or"
echo "2. Run: open \"$APP_PATH\""
echo
echo "If you still have issues, check the diagnostic log in ~/Library/Logs/OutlookAlerter/"
EOF
    chmod +x "$FIX_SCRIPT"
    log "Created fix script: $FIX_SCRIPT"
else
    log "Fix script already exists: $FIX_SCRIPT"
fi

log "=== Creating build script with fixed certificates ==="
BUILD_SCRIPT="build-with-fixed-certs.sh"

if [[ ! -f "$BUILD_SCRIPT" ]]; then
    cat > "$BUILD_SCRIPT" << 'EOF'
#!/bin/bash

# This script rebuilds the OutlookAlerter app with correct SSL certificate configuration
# It ensures that both system certificates and Netskope certificates are included

echo "=== Building OutlookAlerter with fixed certificate configuration ==="

# Clean up previous build artifacts
echo "Cleaning up previous build artifacts..."
mvn clean

# Modify pom.xml to use correct truststore path
echo "Updating pom.xml to use absolute truststore path..."
sed -i '' 's|-Djavax.net.ssl.trustStore=Contents/Resources/truststore.jks|-Djavax.net.ssl.trustStore=$APP_ROOT/Contents/Resources/truststore.jks|g' pom.xml

# Build the project
echo "Building the project with Maven..."
mvn package

# Verify the app was built
if [ ! -d "target/OutlookAlerter.app" ]; then
  echo "Error: App bundle was not created!"
  exit 1
fi

# Fix the truststore in the built app
echo "Fixing certificates in the built app..."
./fix-app-certificates.sh target/OutlookAlerter.app

echo "=== Build complete! ==="
echo "You can run the app with: open target/OutlookAlerter.app"
EOF
    chmod +x "$BUILD_SCRIPT"
    log "Created build script: $BUILD_SCRIPT"
else
    log "Build script already exists: $BUILD_SCRIPT"
fi

log "=== Diagnostics Complete ==="
log "The diagnostic information and fixed truststore are available in: $DIAG_DIR"
log "To fix your app, run:"
log "  ./fix-app-certificates.sh /path/to/OutlookAlerter.app"
log
log "To rebuild with fixed certificates, run:"
log "  ./build-with-fixed-certs.sh"
log
log "Thank you for using OutlookAlerter SSL Certificate Diagnostic Tool"
