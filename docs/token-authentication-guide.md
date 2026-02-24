# Token Authentication Guide

This guide explains how to use the token authentication dialog in OutlookAlerter.

## What's Changed

OutlookAlerter now uses a simplified token dialog that:

- Displays a straightforward UI for entering authentication tokens
- Uses the more reliable JFrame component instead of JDialog
- Provides clear instructions on how to obtain your token
- Opens a browser window automatically to the sign-in page
- Gives helpful error messages if token entry fails

## Using the Token Dialog

When the token dialog appears, follow these steps:

1. **Sign in via Browser**: A browser window will automatically open to the Microsoft/Okta sign-in page.
   Complete the authentication process in your browser.

2. **Extract Your Token**: After signing in successfully, you'll need to get your access token from the browser:
   - Open Developer Tools (F12 or right-click → Inspect)
   - Go to the "Application" tab (Chrome/Edge) or "Storage" tab (Firefox)
   - Look for "Local Storage" in the left sidebar
   - Find items with "token" in the name
   - The access token is a long string that typically starts with "eyJ"

3. **Enter the Token**: Paste the token into the "Access Token" field in the dialog.

4. **Submit**: Click the "Submit" button to proceed.

## Troubleshooting

If the token dialog appears blank or doesn't display properly:

1. **Try our new test script**: Run `./test-new-token-dialog.sh` to test the simplified token dialog

2. **Check Java Installation**: Make sure you have a compatible Java version with GUI support.

3. **Run with Debug Mode**: Use `./run-gui.sh --debug` to get detailed logs.

4. **Java Environment Variables**: Try setting these environment variables before running:
   ```bash
   export JAVA_AWT_HEADLESS=false
   export AWT_TOOLKIT=CToolkit
   ./run-gui.sh
   ```

5. **Manual Browser Launch**: If the browser doesn't open automatically, copy the URL and code shown in the status label and open the browser manually.

6. **Check Console Output**: Look for any error messages in the terminal where you launched the application.

### Okta SSO / Device Code Flow Diagnostics

When using "Sign In with Okta SSO", the app uses Device Code Flow with well-known
Microsoft client IDs. If the tenant rejects a client ID (AADSTS65002), the app
automatically retries with the next ID in this order:

1. **Microsoft Graph PowerShell SDK** (`14d82eec-...`)
2. **Microsoft Graph Explorer** (`de8bc8b5-...`)
3. **Azure CLI** (`04b07795-...`)

If all three are rejected, an Azure AD admin may need to register a custom app
(see [Azure App Registration Guide](azure-app-registration-guide.md)).

### Automatic Token Refresh

After a successful Device Code Flow sign-in, the app stores the working client ID
and caches a refresh token (in `~/.outlookalerter/msal_okta_cache.json`). On
subsequent runs or after a system sleep/wake:

1. The app first checks whether the current access token is still valid.
2. If expired, it silently uses the cached refresh token to obtain a new access
   token — **no user interaction required**.
3. Only if silent refresh fails (e.g., refresh token expired after ~90 days of
   inactivity) does the app prompt for re-authentication.

This means you typically only need to complete the Device Code Flow **once**, and
the app transparently refreshes tokens in the background for up to 90 days.

A diagnostic log file is written to:

```
~/.outlookalerter/device-code-debug.log
```

This file contains timestamped progress of each step: authority resolution, PCA build, device code request, callback, and token acquisition. If sign-in appears to do nothing, check this file to see exactly which step failed.

## Using the New Test Script

We've created a dedicated test script specifically for verifying the token dialog works properly:

```bash
./test-new-token-dialog.sh
```

This script:
1. Creates a test configuration with an invalid token
2. Sets up the necessary environment variables for GUI applications
3. Runs the application in debug mode
4. Displays the token dialog for testing

This is the most reliable way to test if the token dialog is working correctly on your system.

## What to Do if You Can't Get a Token

If you're unable to extract a token:

1. **Try Different Browser**: Some browsers make it easier to access local storage. Chrome and Edge are recommended.

2. **Network Issues**: Make sure you can access Microsoft's authentication servers.

3. **Company Restrictions**: Some organizations restrict access to authentication APIs. Contact your IT department if you suspect this is the case.

4. **Alternative Authentication**: OutlookAlerter also supports other authentication methods. See the main README for details.

## For Advanced Users

If you already have a valid access token and refresh token, you can add them directly to the config file:

```
accessToken=eyJ...your-token-here...
refreshToken=0.AU...your-refresh-token-here...
```

The application will validate the token with the Microsoft Graph API server.

## macOS-Specific Solutions

If you're running on macOS and experiencing issues with the token dialog:

1. **Force non-headless mode**: Use the dedicated script:
   ```bash
   ./test-new-token-dialog.sh
   ```

2. **Check system permissions**: Ensure your terminal app has Screen Recording permissions in System Preferences > Security & Privacy > Privacy.

3. **Try from a different terminal**: Launch the app from Terminal app instead of an IDE's terminal.

4. **Clear Java preferences**: Delete `~/Library/Preferences/com.apple.java.JavaPreferences.plist` and restart.
