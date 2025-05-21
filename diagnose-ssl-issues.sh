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

# Function to check all possible Netskope certificate locations
find_netskope_certificate() {
    log "\n===== Searching for Netskope Certificate ====="
    
    local found=false
    local locations=(
        "/Library/Application Support/Netskope/STAgent/download/nscacert_combined.pem"
        "/Applications/Netskope Client.app/Contents/Resources/nscacert.pem"
        "/Library/Application Support/Netskope/STAgent/download/nscacert.pem"
        "/Library/Netskope/STAgent/download/nscacert_combined.pem"
    )
    
    for loc in "${locations[@]}"; do
        if check_certificate "$loc" "Netskope"; then
            found=true
            NETSKOPE_CERT="$loc"
        fi
    done
    
    if [[ "$found" == "false" ]]; then
        log "Could not find Netskope certificate in any of the expected locations"
        log "This may be the cause of your SSL certificate issues"
    fi
    
    return 0
}

# Function to check truststores
check_truststores() {
    log "\n===== Checking Java and Application Truststores ====="
    
    # Check system truststore
    local system_truststore="$JAVA_HOME/lib/security/cacerts"
    check_certificate "$system_truststore" "System"
    
    # Check app bundle truststores
    local app_bundle="$APP_DIR/target/OutlookAlerter.app"
    if [[ -d "$app_bundle" ]]; then
        local app_truststore="$app_bundle/Contents/Resources/truststore.jks"
        check_certificate "$app_truststore" "App bundle"
        
        # Check for Netskope cert in app truststore
        if [[ -f "$app_truststore" ]]; then
            log "  - Checking for Netskope certificate in app truststore:"
            keytool -list -keystore "$app_truststore" -storepass changeit | grep -E "netskope|nscacert" | sed 's/^/      /' | tee -a "$LOG_FILE" || \
                log "      No Netskope certificate found in app truststore"
        fi
    else
        log "App bundle not found at $app_bundle"
    fi
    
    # Check target truststore
    local target_truststore="$APP_DIR/target/truststore/truststore.jks"
    check_certificate "$target_truststore" "Target"
    
    return 0
}

# Function to check Info.plist configuration
check_info_plist() {
    log "\n===== Checking Info.plist Configuration ====="
    
    # Check template Info.plist
    local template_plist="$APP_DIR/src/main/resources/Info.plist"
    if [[ -f "$template_plist" ]]; then
        log "Template Info.plist:"
        grep -A 10 "JVMOptions" "$template_plist" | sed 's/^/    /' | tee -a "$LOG_FILE"
        
        # Look for trustStore setting
        if grep -q "trustStore" "$template_plist"; then
            local trust_store_path=$(grep "trustStore" "$template_plist" | sed 's/.*trustStore=\([^"<]*\).*/\1/')
            log "  - TrustStore path in template: $trust_store_path"
            
            # Check if the path is correct
            if [[ "$trust_store_path" == "Contents/Resources/truststore.jks" ]]; then
                log "  - WARNING: TrustStore path is relative - this may cause certificate validation issues"
                log "    Consider updating to use \$APP_ROOT/Contents/Resources/truststore.jks"
            elif [[ "$trust_store_path" == "\$APP_ROOT/Contents/Resources/truststore.jks" ]]; then
                log "  - TrustStore path uses \$APP_ROOT - this is good"
            fi
        else
            log "  - No trustStore setting found in template"
        fi
    else
        log "Template Info.plist not found at $template_plist"
    fi
    
    # Check built Info.plist
    local app_bundle="$APP_DIR/target/OutlookAlerter.app"
    if [[ -d "$app_bundle" ]]; then
        local built_plist="$app_bundle/Contents/Info.plist"
        if [[ -f "$built_plist" ]]; then
            log "\nBuilt Info.plist:"
            grep -A 10 "JVMOptions" "$built_plist" | sed 's/^/    /' | tee -a "$LOG_FILE"
            
            # Look for trustStore setting
            if grep -q "trustStore" "$built_plist"; then
                local trust_store_path=$(grep "trustStore" "$built_plist" | sed 's/.*trustStore=\([^"<]*\).*/\1/')
                log "  - TrustStore path in built app: $trust_store_path"
                
                # Check if the path is absolute or relative
                if [[ "$trust_store_path" != /* && "$trust_store_path" != "\$APP_ROOT"* ]]; then
                    log "  - WARNING: TrustStore path is relative - this will cause certificate validation issues"
                    log "    The path must be absolute or use \$APP_ROOT"
                fi
                
                # Check if the file exists
                if [[ "$trust_store_path" == "\$APP_ROOT"* ]]; then
                    local resolved_path="${trust_store_path/\$APP_ROOT/$app_bundle}"
                    log "  - Resolved path would be: $resolved_path"
                    if [[ -f "$resolved_path" ]]; then
                        log "  - Truststore file exists at resolved path"
                    else
                        log "  - WARNING: Truststore file does not exist at resolved path"
                    fi
                elif [[ -f "$trust_store_path" ]]; then
                    log "  - Truststore file exists at absolute path"
                else
                    log "  - WARNING: Truststore file does not exist at specified path"
                fi
            else
                log "  - No trustStore setting found in built app"
            fi
            
            # Copy to diagnostic directory
            cp "$built_plist" "$DIAG_DIR/Info.plist"
            log "  - Copied to diagnostic directory"
        else
            log "Built Info.plist not found at $built_plist"
        fi
    else
        log "App bundle not found at $app_bundle"
    fi
    
    return 0
}

# Function to test network connectivity
test_network() {
    log "\n===== Testing Network Connectivity ====="
    
    # Test general connectivity
    log "Testing connectivity to login.microsoftonline.com..."
    if curl -s -o /dev/null -w "%{http_code}" https://login.microsoftonline.com >/dev/null; then
        log "  - Connection successful"
    else
        log "  - Connection failed"
    fi
    
    # Test graph API
    log "Testing connectivity to graph.microsoft.com..."
    if curl -s -o /dev/null -w "%{http_code}" https://graph.microsoft.com >/dev/null; then
        log "  - Connection successful"
    else
        log "  - Connection failed"
    fi
    
    # Test with certificate path
    if [[ -n "$NETSKOPE_CERT" ]]; then
        log "Testing with Netskope certificate explicitly specified..."
        curl --cacert "$NETSKOPE_CERT" -s -o /dev/null -w "%{http_code}" https://graph.microsoft.com >/dev/null
        if [[ $? -eq 0 ]]; then
            log "  - Connection successful with Netskope certificate"
        else
            log "  - Connection failed with Netskope certificate"
        fi
    fi
    
    return 0
}

# Function to fix certificate issues
fix_certificate_issues() {
    log "\n===== Fixing Certificate Issues ====="
    
    # Create a truststore with both system and Netskope certificates
    local new_truststore="$DIAG_DIR/fixed-truststore.jks"
    log "Creating new truststore at $new_truststore"
    
    # Import system certificates
    log "Importing system certificates..."
    keytool -importkeystore -srckeystore "$JAVA_HOME/lib/security/cacerts" \
        -destkeystore "$new_truststore" -srcstorepass changeit -deststorepass changeit || true
    
    # Import Netskope certificate if found
    if [[ -n "$NETSKOPE_CERT" && -f "$NETSKOPE_CERT" ]]; then
        log "Importing Netskope certificate..."
        keytool -importcert -file "$NETSKOPE_CERT" -keystore "$new_truststore" \
            -storepass changeit -alias "nscacert" -noprompt || true
        
        # Verify import
        if keytool -list -keystore "$new_truststore" -storepass changeit | grep -q "nscacert"; then
            log "  - Netskope certificate imported successfully"
        else
            log "  - Failed to import Netskope certificate"
        fi
    else
        log "No Netskope certificate found to import"
    fi
    
    # Copy to app bundle
    local app_bundle="$APP_DIR/target/OutlookAlerter.app"
    if [[ -d "$app_bundle" ]]; then
        local app_resources="$app_bundle/Contents/Resources"
        mkdir -p "$app_resources"
        cp "$new_truststore" "$app_resources/truststore.jks"
        log "Copied fixed truststore to $app_resources/truststore.jks"
    else
        log "App bundle not found at $app_bundle"
    fi
    
    # Suggest Info.plist fix
    log "\nTo fix Info.plist, run:"
    log "  ./fix-app-certificates.sh \"$app_bundle\""
    log "This will update the Info.plist to use the correct truststore path"
    
    return 0
}

# Main diagnostic flow
log "\n===== Starting diagnostic process ====="

# Step 1: Find Netskope certificate
find_netskope_certificate

# Step 2: Check truststores
check_truststores

# Step 3: Check Info.plist configuration
check_info_plist

# Step 4: Test network connectivity
test_network

# Step 5: Fix certificate issues
fix_certificate_issues

log "\n===== Diagnostic Summary ====="
log "1. Diagnostic information saved to: $DIAG_DIR"
log "2. A fixed truststore has been created at: $DIAG_DIR/fixed-truststore.jks"
log "3. To fix your app bundle, run: ./fix-app-certificates.sh /path/to/OutlookAlerter.app"
log "4. After fixing, test your app with: open /path/to/OutlookAlerter.app"
log
log "If you still experience issues, consider:"
log "  - Checking if your JVM is correctly interpreting the \$APP_ROOT variable"
log "  - Verifying Netskope certificate is correctly installed and valid"
log "  - Using an absolute path for the truststore in Info.plist"
log
log "Complete diagnostic log available at: $LOG_FILE"
