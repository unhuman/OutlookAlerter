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
  - **Show application window**
  - **Refresh calendar**
  - **Meeting join links** — active and imminent meetings (starting within 10 minutes) appear as clickable items that open the join URL directly in your browser. Meetings without a join link are shown disabled with `(No Link)`. The section is separated from the static items and updates every minute.
  - **Next Meeting at hh:mm am/pm** — when no active or imminent meetings are present, a non-clickable (disabled) label shows the start time of the next upcoming meeting so you always know when to expect the next one.
  - **Cancelled meetings are never shown** — meetings cancelled by the organizer (Graph API `isCancelled` flag) or whose subject starts with `Cancelled:` / `Canceled:` are silently excluded from all views including alerts, the main window, and the tray menu.
  - **Settings**
  - **Exit application**
- Double-click on the tray icon to show the main window
- Close button minimizes to tray instead of quitting

### Meeting join URL resolution

For each meeting the app searches for a join URL in this order:
1. Graph API `onlineMeeting.joinUrl` (Teams / Zoom "Make Online" meetings)
2. `location` field, if it is an HTTP URL
3. First Zoom or Teams `href` link found in the full meeting body HTML (captures "Click Here to Join" buttons, including Zoom URLs with embedded `?pwd=`)
4. First bare URL found in the `bodyPreview` plain-text fallback

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
