#!/bin/bash

# This script organizes test and diagnostic scripts into the scripts/ directory
# It also updates permissions and maintains script functionality

# Make sure we're in the project root
cd "$(dirname "$0")"

# Create scripts directory if it doesn't exist
mkdir -p scripts

# Define the scripts to be moved
SCRIPTS=(
  # SSL certificate scripts
  "diagnose-ssl-issues.sh"
  "fix-app-certificates.sh"
  "build-with-fixed-certs.sh"
  "run-ssl-debug.sh"
  "run-with-ssl.sh"
  "run-with-ssl-debug.sh"
  "run-with-proper-ssl.sh"
  "compare-certificates.sh"
  
  # Calendar diagnostic scripts
  "diagnose-calendar.sh"
  "diagnose-missing-meetings.sh"
  "diagnose-multi-calendar.sh" 
  "enhanced-calendar-diagnostics.sh"
  "run-all-diagnostics.sh"
  
  # Testing scripts
  "test-calendar-events.sh"
  "test-timezones.sh"
  "test-time-comparisons.sh"
  "test-tentative-meetings.sh"
  "test-token-validation.sh"
  "test-screen-flash.sh"
  "run-flash-test.sh"
  "test-token-dialog.sh"
  "test-token-dialog-improved.sh"
  "test-token-authentication.sh"
  "test-new-token-dialog.sh"
  "test-enhanced-retrieval.sh"
  "test-modal-and-refresh.sh"
  "test-refresh-button.sh"
  "test-dialog-only.sh"
  
  # Debug scripts
  "run-debug.sh"
  "fix-token-dialog.sh"
)

echo "Moving scripts to scripts/ directory..."

# Move each script
for script in "${SCRIPTS[@]}"; do
  if [ -f "$script" ]; then
    echo "Moving $script to scripts/"
    mv "$script" "scripts/"
    chmod +x "scripts/$script"
  else
    echo "Warning: $script not found or already moved"
  fi
done

echo "Creating wrapper scripts for common operations..."

# Create wrapper scripts for commonly used scripts
for script in "diagnose-ssl-issues.sh" "fix-app-certificates.sh" "build-with-fixed-certs.sh" "run-ssl-debug.sh"; do
  cat > "$script" << EOF
#!/bin/bash

# This is a wrapper script that redirects to scripts/$script
# Maintained for backward compatibility

echo "Running scripts/$script \$@"
exec ./scripts/$script "\$@"
EOF
  chmod +x "$script"
  echo "Created wrapper for $script"
done

echo "Script organization complete!"
