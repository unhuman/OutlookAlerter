# OutlookAlerter — UI Components Spec

## Overview

The GUI is a Swing-based desktop application that runs as a system tray icon. The main window displays upcoming calendar events and provides controls for settings, logs, refresh, and test alerts.

---

## OutlookAlerterUI (Main Window)

**Location:** `com.unhuman.outlookalerter.ui.OutlookAlerterUI`  
**Extends:** `JFrame`  
**Entry:** `start(showWindow = false)` — starts schedulers, sets up sleep/wake monitoring, minimizes to tray

### Window Layout

```
┌──────────────────────────────────────────────────────┐
│  Controls                                            │
│ ┌──────────────────────────┐ ┌─────────────────────┐ │
│ │ [Refresh] [Settings]     │ │ Status: Connected   │ │
│ │ [Logs] [Exit] [Test]     │ │ Last update: 2:30pm │ │
│ └──────────────────────────┘ └─────────────────────┘ │
├──────────────────────────────────────────────────────┤
│  Upcoming Calendar Events                            │
│ ┌──────────────────────────────────────────────────┐ │
│ │ == CURRENTLY IN PROGRESS ==                      │ │
│ │ 2:00 PM - 2:30 PM  Team Standup (In progress)   │ │
│ │                                                  │ │
│ │ == NEXT MEETINGS ==                              │ │
│ │ 3:00 PM - 3:30 PM  Design Review (in 28 min)    │ │
│ │ ...                                              │ │
│ └──────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

- **Size:** 800×600, centered
- **Close behavior:** Minimizes to tray (if supported), else prompts to exit
- **Icon:** Programmatically generated via `IconManager`

### Key Fields

| Field | Type | Description |
|---|---|---|
| `configManager` | `ConfigManager` | Singleton config access |
| `outlookClient` | `OutlookClient` | Graph API client |
| `screenFlasher` | `ScreenFlasher` | Platform-specific flasher |
| `eventsTextArea` | `JTextArea` | Monospaced 12pt, non-editable, shows events |
| `statusLabel` | `JLabel` | Connection/alert status |
| `lastUpdateLabel` | `JLabel` | Timestamp of last refresh |
| `trayIcon` | `TrayIcon` | System tray icon |
| `alertScheduler` | `ScheduledExecutorService` | 60s polling for alert checks |
| `calendarScheduler` | `ScheduledExecutorService` | Configurable polling for API fetches |
| `alertedEventIds` | `Set<String>` | ConcurrentHashMap-backed, prevents duplicate alerts |
| `lastFetchedEvents` | `List<CalendarEvent>` | Cached events from last API call |
| `lastCalendarRefresh` | `volatile ZonedDateTime` | Timestamp of last successful fetch |
| `alertBannerWindows` | `List<JFrame>` | Active banner overlay windows |
| `currentIconInvalidState` | `Boolean` | Tracks icon state to avoid redundant updates |

### Public Methods

| Method | Description |
|---|---|
| `start(showWindow)` | Initializes schedulers and sleep/wake monitoring |
| `performFullAlert(banner, title, msg, events)` | Fires all 4 alert components |
| `promptForTokens(signInUrl)` | Shows token dialog, returns `{accessToken, ignoreCertValidation}` |
| `restartSchedulers()` | Stops and restarts both schedulers (called after settings changes) |
| `setTimezone(timezone)` | Updates preferred timezone |
| `updateLastWakeTime()` | Called by sleep/wake monitor |

### Event Categories in Display

Events are sorted by `minutesToStart` and grouped into:
1. **CURRENTLY IN PROGRESS** — `isInProgress()` is true
2. **NEXT MEETINGS** — starts within current hour
3. **TODAY'S MEETINGS** — starts today
4. **TOMORROW'S MEETINGS** — starts tomorrow
5. **LATER MEETINGS** — everything else

### Refresh Flow

```
refreshCalendarEvents()  [daemon thread]
  ├─ statusLabel → "Refreshing..."
  ├─ refreshButton.setEnabled(false)
  ├─ outlookClient.getUpcomingEventsUsingCalendarView()
  ├─ updateIcons(tokenInvalid)
  ├─ updateEventsDisplay(events)     [EDT]
  ├─ lastFetchedEvents = events
  ├─ lastCalendarRefresh = now
  └─ title → "OutlookAlerter - N meetings today"
```

### Staleness Check

`checkAlertsFromCache()` triggers a full refresh if `lastCalendarRefresh` is > 4 hours old.

---

## System Tray

**Setup:** `setupSystemTray()`  
**Popup field:** `trayPopupMenu` (instance field, rebuilt on every event update)

### Tray Menu Structure

The menu is fully rebuilt each time events are refreshed (every fetch and every 60 s cache check).

```
Show Outlook Alerter
Refresh Calendar
─── separator ───           (only present when ≥1 meeting qualifies)
Meeting A (now)             clickable — opens join URL in browser
Meeting B (in 7m)           clickable — opens join URL in browser
Meeting C (now) (No Link)   disabled — no join URL found
─── separator ───
Settings
─── separator ───
Exit
```

**Meeting items appear when:** `!hasEnded()` AND (`isInProgress()` OR `getMinutesToStart() <= 10`)

**Join URL lookup priority (`getEffectiveJoinUrl`):**
1. `onlineMeeting.joinUrl` from Graph API
2. `location` field, if it starts with `http`
3. First Zoom/Teams `href` link extracted from full body HTML
4. First bare URL found in `bodyPreview` plain text

**Sorting:** by `startTime` ascending, then subject alphabetically for ties

**Label format:** `Subject (now)` or `Subject (in Xm)` — subject truncated to 35 chars with `…`

**No-link items:** label appended with ` (No Link)`, `setEnabled(false)`

### Tray Icon

- **Normal:** Blue rounded rectangle with white "O" + red siren
- **Invalid token:** Red base + blue siren (inverted colors)
- **Double-click:** Shows main window
- **Size:** 16×16 (tray), 32×32 (window icon)
- **Generated by:** `IconManager.getIconImage(isTokenInvalid)`

---

## SettingsDialog

**Location:** `com.unhuman.outlookalerter.ui.SettingsDialog`  
**Extends:** `JDialog` (modal)

### Settings Form

| Setting | Control | Range | Config Key |
|---|---|---|---|
| Preferred Timezone | `JTextField` | Valid timezone ID | `preferredTimezone` |
| Alert Minutes Before | `JSpinner` | 1–60 | `alertMinutes` |
| Resync Interval (min) | `JSpinner` | 5–1440 | `resyncIntervalMinutes` |
| Flash Duration (sec) | `JSpinner` | 1–30 | `flashDurationSeconds` |
| Flash Opacity (%) | `JSpinner` | 10–100 (step 10) | `flashOpacity` |
| Alert Beep Count | `JSpinner` | 0–20 | `alertBeepCount` |
| Alert Beep After Flash | `JCheckBox` | boolean | `alertBeepAfterFlash` |
| Sign-in URL | `JTextField` | URL | `signInUrl` |
| Ignore SSL Cert | `JCheckBox` | boolean | `defaultIgnoreCertValidation` |

### Save Flow

```
Save button →
  ├─ Validate timezone (via ZoneId.of())
  ├─ configManager.update*() for each setting
  ├─ outlookClient.updateCertificateValidation()
  ├─ parent.restartSchedulers()
  └─ dispose()
```

---

## SimpleTokenDialog

**Location:** `com.unhuman.outlookalerter.ui.SimpleTokenDialog`  
**Pattern:** Singleton (`getInstance(signInUrl)`)  
**Extends:** Uses internal `JDialog`

### Dialog Layout

```
┌─────────────────────────────────────────────┐
│  Token Entry Required                       │
│                                             │
│  <HTML instructions for getting token>      │
│                                             │
│  Access Token: [________________________]   │
│  ☐ Ignore SSL Certificate Validation        │
│                                             │
│  [Open Graph Explorer] [Open Sign-in Page]  │
│  [Submit] [Cancel]                          │
└─────────────────────────────────────────────┘
```

- **Size:** 550×400, always on top, modal
- **Shown via:** `invokeAndWait(createUI())` (EDT-safe)
- **Token validation:** Non-empty, strips "Bearer " prefix; manual entry requires JWT 3-part format, MSAL-acquired tokens (compact/opaque) are accepted without format check

### Token Retrieval Flow

```
Caller                     SimpleTokenDialog
  │                              │
  ├─ getInstance(url) ──────────▶│
  ├─ show() ────────────────────▶│ (blocks on EDT, shows UI)
  ├─ getTokens() ───────────────▶│ (blocks on CountDownLatch, 5 min timeout)
  │                              ├─ user enters token
  │                              ├─ submitToken() validates
  │                              ├─ latch.countDown()
  │◀──── tokens map ─────────────┤
```

**Returns:** `{accessToken: "...", ignoreCertValidation: "true"/"false"}` or `null` on cancel/timeout.

---

## LogViewer

**Location:** `com.unhuman.outlookalerter.ui.LogViewer`  
**Extends:** `JFrame`  
**Size:** 900×600

### Layout

```
┌─────────────────────────────────────────────────┐
│  Filters: ☑DATA_FETCH ☑MEETING_INFO            │
│           ☑ALERT_PROCESSING ☑GENERAL [All][None]│
├─────────────────────────────────────────────────┤
│  [Monospaced log output with auto-scroll]       │
│  ...                                            │
│  ...                                            │
├─────────────────────────────────────────────────┤
│  [Clear Logs] [Refresh] [Save Logs]             │
└─────────────────────────────────────────────────┘
```

- **Auto-scroll:** `DefaultCaret.ALWAYS_UPDATE`
- **Save:** `JFileChooser`, timestamped filename
- **Connection:** Links to `LogManager.getInstance().setLogTextArea()`
- **On close:** Detaches from `LogManager`

### Log Categories

| Category | Content |
|---|---|
| `DATA_FETCH` | API calls, responses, parsing |
| `MEETING_INFO` | Event details, times, status |
| `ALERT_PROCESSING` | Alert decisions, component execution |
| `GENERAL` | Startup, config, misc |

---

## IconManager

**Location:** `com.unhuman.outlookalerter.ui.IconManager`  
**Pattern:** Static utility with 4-variant cache

### Icon Design

```
  Valid Token          Invalid Token
  ┌─────────┐          ┌─────────┐
  │ 🔴 siren│          │ 🔵 siren│
  │┌───────┐│          │┌───────┐│
  ││ 🔵    ││          ││ 🔴    ││
  ││  "O"  ││          ││  "O"  ││
  │└───────┘│          │└───────┘│
  └─────────┘          └─────────┘
```

- **Blue:** `Color(0, 114, 198)` (Outlook blue)
- **Red:** `Color(220, 0, 0)` (alert red)
- **Sizes:** 16×16 (tray), 32×32 (window)
- **Rendering:** Anti-aliased, scaled from 16px base

---

## OutlookAlerterConsole

**Location:** `com.unhuman.outlookalerter.ui.OutlookAlerterConsole`

Headless mode that prints events to stdout and fires screen flash only (no beep, no banner, no tray).

- **Polling:** Every 1 minute
- **Display:** Console text with in-progress/upcoming/later categories
- **Alerts:** `screenFlasher.flashMultiple(events)` only
- **Exit:** Enter key (non-daemon) or signal (daemon)

---

## Component Interaction Map

```
                    ┌──────────────┐
                    │ OutlookAlerter│ (entry point)
                    │  main()      │
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              ▼                         ▼
    ┌─────────────────┐      ┌───────────────────┐
    │ OutlookAlerterUI│◀────▶│  OutlookClient    │
    │                 │      │                    │
    │ • performFullAlert()   │ • authenticate()   │
    │ • promptForTokens()    │ • getUpcoming...() │
    │ • restartSchedulers()  │ • getTokensFromUser()→UI
    └────┬──┬──┬──┬───┘      └────────┬───────────┘
         │  │  │  │                    │
    ┌────┘  │  │  └──────┐        ┌───┘
    ▼       │  │         ▼        ▼
┌────────┐  │  │  ┌──────────┐ ┌──────────────┐
│Settings│  │  │  │LogViewer │ │SimpleToken   │
│Dialog  │  │  │  │          │ │Dialog        │
└────────┘  │  │  └──────────┘ └──────────────┘
            │  │
    ┌───────┘  └──────────┐
    ▼                     ▼
┌──────────────┐  ┌───────────┐
│MacScreen     │  │IconManager│
│Flasher       │  │           │
│              │  └───────────┘
│• overlayWindows (shared with banner)
│• onFlashReady   (callback from UI)
└──────────────┘

All components share: ConfigManager (singleton), LogManager (singleton)
```
