# OutlookAlerter UI Features

This document describes the new UI features added to OutlookAlerter.

## Overview

OutlookAlerter now features a full graphical user interface (GUI) that:
- Displays upcoming meetings
- Provides visual and system tray notifications for meeting alerts
- Includes a user-friendly token entry form for authentication
- Supports system tray minimization for background operation

## Main Application Window

The main window displays:
- Status information
- Next meetings with start times
- In-progress meetings
- Later meetings on the schedule

### Controls
- **Refresh Now**: Manually refresh calendar data
- **Settings**: Configure timezone and sign-in URL

## System Tray Integration

OutlookAlerter can run in the background via system tray:
- Meeting alerts appear as system tray notifications
- Right-click menu provides quick access to:
  - Show application window
  - Refresh calendar
  - Settings
  - Exit application
- Double-click on the tray icon to show the main window
- Close button minimizes to tray instead of quitting

## Token Entry UI

The new token entry dialog provides:
- Clear instructions for obtaining the token
- One-click browser launch for sign-in
- Validation of token format before submission
- Support for both access token and refresh token

## Using Console Mode

For users who prefer the original console interface:
- Run with `--console` flag: `./run.sh --console`
- All original console features are preserved
- Console output shows the same meeting information

## Background Operation

The application can run in either:
- **Foreground**: With the GUI visible
- **Background**: Minimized to system tray, delivering notifications

## Screen Flashing Alerts

When a meeting is about to start:
- The screen flashes to get your attention
- A notification appears with meeting details
- On macOS, the flashing happens without showing a temporary red square in the menu bar
- The flashing gradually alternates between red and orange colors

## Troubleshooting

If you encounter issues with the UI:
1. **Authentication failures**: Use the Settings dialog to update the sign-in URL
2. **Missing system tray icon**: Some platforms have limited system tray support
3. **No notifications**: Ensure your system's notification settings allow OutlookAlerter notifications
