# Outlook Alerter

A Groovy application that monitors your Microsoft Outlook/Office 365 calendar and alerts you when meetings are about to start by flashing the screen.

## Features

- Connects to Microsoft Graph API to retrieve your calendar events
- Configurable alert time (1-30 minutes before meetings)
- Cross-platform screen flashing alerts for upcoming meetings (with robust support for macOS, Windows, and Linux)
- User-configurable alert background color, text color, and opacity (with a simple swatch-based color picker)
- Alerts appear above full screen apps and on all virtual desktops/displays (macOS/Windows)
- Supports authentication through Okta SSO or direct Microsoft authentication
- Automatic token refresh using refresh tokens (no repeated user prompts)
- Comprehensive calendar event handling:
  - Shows meeting response status (accepted, tentative, declined)
  - Displays online meeting status
  - Shows meeting organizer information
  - Handles concurrent meetings
  - Includes tentative and all-day events
- Smart UI features:
  - System tray integration with minimizable window
  - Real-time calendar updates
  - Status indicators for connection and updates
  - Configurable settings via GUI dialog
- Console mode support for command-line operation
- Robust timezone handling with configurable preferences
- Extensive diagnostic and troubleshooting tools (see `scripts/`)

## Authentication and Token Refresh

Outlook Alerter uses OAuth2 for authentication. To avoid repeated sign-ins, it requests the `offline_access` scope and stores a refresh token. When your access token expires, the app automatically uses the refresh token to obtain a new one. This process is seamless and requires no user intervention unless the refresh token is revoked or expires (e.g., password change, admin action).

- **Initial sign-in**: The app opens your browser for authentication and prompts for the token.
- **Refresh tokens**: Stored securely and used to renew access tokens as needed.
- **No indefinite tokens**: Microsoft does not allow truly permanent tokens, but refresh tokens last weeks/months unless revoked.
- **Manual re-authentication**: Only required if the refresh token is invalidated by Microsoft.

## User Interface

### Main Window
- **Current Meetings**: Shows in-progress meetings with duration and status
- **Next Meetings**: Displays upcoming meetings within alert window
- **Later Meetings**: Lists future meetings beyond the current timeframe
- **Status Bar**: Shows connection state and last update time
- **Quick Actions**: Refresh, Settings, and Exit buttons

### Settings Dialog
- **Alert Configuration**: Set alert time (1-30 minutes before meetings)
- **Timezone Settings**: Configure preferred timezone
- **Authentication**: Set up Okta SSO or direct Microsoft authentication
- **Visual Preferences**:
  - Choose alert background color (with swatch-only color picker)
  - Choose alert text color (with swatch-only color picker)
  - Set alert opacity (0–100%)
- **All settings are saved to your config file and take effect immediately**

### System Tray Integration
- **Background Operation**: Runs minimized while monitoring calendar
- **Quick Menu**: Right-click for common actions
- **Status Notifications**: Meeting alerts appear as system notifications
- **Double-click Restore**: Quickly access the main window

## Platform-Specific Alert Behavior

- **macOS**: Alerts appear above full screen apps and on all virtual desktops/displays using robust window level logic. No menu bar icon flashes during alerts.
- **Windows**: Alerts use always-on-top windows and system tray notifications. Color and opacity settings are fully respected.
- **Linux/Cross-Platform**: Alerts use always-on-top windows with color/opacity support (subject to window manager limitations).

## Running the Application

You can run Outlook Alerter in two modes, either using the JAR directly or using the provided shell scripts:

### Using Maven-built JAR

#### GUI Mode (Default)
```zsh
java -jar target/OutlookAlerter-1.0.0-SNAPSHOT-jar-with-dependencies.jar
```

#### Console Mode
```zsh
java -jar target/OutlookAlerter-1.0.0-SNAPSHOT-jar-with-dependencies.jar --console
```

### Using Shell Scripts

#### GUI Mode (Default)
```zsh
./run.sh
```

#### Console Mode
```zsh
./run.sh --console
```

### Command Line Options
- `--config <path>`: Custom config file location (default: ~/.outlookalerter/config.properties)
- `--console`: Run in console mode
- `--debug`: Enable detailed logging
- `--timezone <zone>`: Override timezone setting
- `--help`: Show help message

## Configuration

The application stores its configuration in `~/.outlookalerter/config.properties`. This file is created automatically on first run with default values.

### Core Settings
```properties
# Authentication
signInUrl=https://your-company.okta.com/home/office365/...  # Your Okta SSO URL
loginHint=your.name@company.com                             # Your email (optional)

# Application Settings
preferredTimezone=America/New_York                          # Your preferred timezone
alertMinutes=1                                              # Minutes before meeting to alert (1-30)

# Visual Settings
flashColor=#800000                                          # Alert background color (hex)
flashTextColor=#ffffff                                      # Alert text color (hex)
flashOpacity=1.0                                            # Alert opacity (0.0–1.0)

# Advanced Settings (usually auto-configured)
clientId=                                                   # OAuth client ID
clientSecret=                                               # OAuth client secret
tenantId=common                                            # Azure AD tenant
redirectUri=http://localhost:8888/redirect                 # OAuth redirect URI
```

### Settings Management
- Most settings can be changed through the Settings dialog in GUI mode
- Direct file editing is required only for advanced configuration
- Changes take effect immediately after saving

## Diagnostic Tools

All diagnostic and troubleshooting scripts are in the `scripts/` directory. See the README and comments in each script for usage details.

- `run-debug.sh`: Run with detailed logging
- `test-calendar-events.sh`: Validate event retrieval
- `test-timezones.sh`: Test timezone handling
- `diagnose-missing-meetings.sh`: Find missing meetings
- `diagnose-multi-calendar.sh`: Diagnose multi-calendar issues
- `enhanced-calendar-diagnostics.sh`: Deep calendar diagnostics
- `run-all-diagnostics.sh`: Run all diagnostics

## Requirements

- Java 11 or later
- Maven 3.6 or later for building

## Build System

Outlook Alerter uses Maven for dependency management and building. The project's key dependencies are managed through the `pom.xml` file:
- Groovy 4.x (core, json, and dateutil modules)
- JNA 5.13.0 for native system access

---

For more details, see the `docs/` and `documentation/` directories, and the comments in the `scripts/` folder.


