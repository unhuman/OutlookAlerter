# OutlookAlerter

A Groovy application that monitors your Microsoft Outlook/Office 365 calendar and alerts you when meetings are about to start by flashing the screen.

## Features

- Connects to Microsoft Graph API to retrieve your calendar events
- Flashes the screen when meetings are about to start (within 2 minutes)
- Supports authentication through Okta SSO or direct Microsoft authentication
- Cross-platform support for screen flashing (macOS, Windows, Linux)
- Configurable polling interval and alert thresholds

## Timezone Handling

OutlookAlerter now provides improved timezone handling to ensure your calendar events are displayed accurately regardless of your location or system timezone settings.

### Timezone Configuration Options

1. **Config File**: Set your preferred timezone in the config file (`~/.outlookalerter/config.properties`):
   ```
   preferredTimezone=America/New_York
   ```

2. **Command Line**: Override the timezone setting via command line:
   ```
   ./run.sh --timezone America/New_York
   ```

3. **Convenience Script**: Use the provided convenience script:
   ```
   ./run-with-timezone.sh America/New_York
   ```

### Common Timezone IDs

- **North America**: `America/New_York`, `America/Chicago`, `America/Denver`, `America/Los_Angeles`
- **Europe**: `Europe/London`, `Europe/Paris`, `Europe/Berlin`, `Europe/Moscow`
- **Asia**: `Asia/Tokyo`, `Asia/Singapore`, `Asia/Hong_Kong`, `Asia/Dubai`
- **Australia/Pacific**: `Australia/Sydney`, `Pacific/Auckland`

### Debugging Timezone Issues

If you encounter timezone-related issues, you can run the application with the `--debug` flag to see detailed timezone information:

```
./run.sh --debug
```

This will show:
- Your system's current timezone
- The timezone of each event from the calendar
- Detailed time comparisons used for determining upcoming events

## Usage

```
./run.sh [options]
```

Options:
- `--config <path>`: Path to configuration file (default: ~/.outlookalerter/config.properties)
- `--daemon`: Run in daemon mode (background)
- `--debug`: Enable debug mode with detailed timezone logging
- `--timezone <zone>`: Override the timezone for displaying events (e.g., America/New_York)
- `--help`: Show help message

## Installation

1. Clone the repository
2. Run `./build.sh` to compile the application
3. Run `./run.sh` to start the application

## Configuration

On first run, a default configuration file will be created at `~/.outlookalerter/config.properties`. You'll need to edit this file with your authentication details.

For Okta SSO authentication:
1. Set `signInUrl` to your organization's Okta SSO URL for Microsoft 365
2. Set `loginHint` to your email address (optional)
3. Set `preferredTimezone` to your desired timezone (optional)

## Requirements

- Java 11 or later
- Groovy libraries (included in the lib directory)
