# OutlookAlerter — Alert System Spec

## Overview

The alert system notifies users of upcoming calendar events (and token entry requirements) through four simultaneous components: audio beeps, screen flash, banner frame overlay, and system tray notification. All four are coordinated through a single entry point.

## Entry Point: `performFullAlert()`

**Location:** `OutlookAlerterUI.java`  
**Signature:**
```java
void performFullAlert(String bannerText, String notificationTitle,
                      String notificationMessage, List<CalendarEvent> events)
```

### Callers

| Caller | Location | Context |
|---|---|---|
| `checkForEventAlerts()` | OutlookAlerterUI | Real meeting alerts (from alert scheduler) |
| Test Alerts button | OutlookAlerterUI | User-triggered test |
| `getTokensFromUser()` | OutlookClient | Token failure (first attempt only) |

### Execution Order

```
performFullAlert()
 ├─ 1. AudioBeepThread  → background thread → n beeps at 250ms intervals
 ├─ 2. onFlashReady callback registered → will fire showAlertBanner() once flash is visible
 ├─ 3. ScreenFlasherThread → background thread → flashMultiple(events)
 │     └─ invokeAndWait: create flash windows on EDT
 │     └─ invokeLater: fire onFlashReady → showAlertBanner() on EDT
 └─ 4. invokeLater: showTrayNotification() on EDT
```

**Key design constraints:**
- Beeps start immediately on a daemon thread (highest urgency)
- Flash windows are created on EDT via `invokeAndWait` (Swing thread safety)
- Banner waits for flash via `onFlashReady` callback (prevents banner-under-flash flicker)
- If `events` is null/empty, banner shows immediately without waiting for flash
- Tray notification is posted to EDT independently

---

## Component 1: Audio Beeps

- **Thread:** `AlertBeepThread` (daemon)
- **Count:** `configManager.alertBeepCount` (clamped ≥ 0)
- **Interval:** 250ms between beeps
- **Primary API:** `Toolkit.getDefaultToolkit().beep()` (macOS system alert channel)
- **macOS fallback:** `afplay <sound-file>` launched once before the beep loop (and again before the post-flash beep loop when *Alert Beep Again After Flash* is enabled). Plays through the standard audio channel, so it is audible even when System Settings → Sound → Alert Volume is 0. Failure is silently logged (non-critical). The sound file path is user-configurable via **Settings → Alert Sound File**; the default is `/System/Library/Sounds/Glass.aiff` (`ConfigManager.DEFAULT_ALERT_SOUND_PATH`). If the configured file does not exist, `playMacAudio()` falls back through `Glass.aiff` → `Funk.aiff` → `Submarine.aiff` (first existing file wins).
- **Interruption-safe:** catches `InterruptedException`, sets interrupt flag

## Component 2: Screen Flash

### Interface

```java
interface ScreenFlasher {
    void flash(CalendarEvent event)
    void flashMultiple(List<CalendarEvent> events)
}
```

### Factory

`ScreenFlasherFactory.createScreenFlasher()` returns:
- **macOS** → `MacScreenFlasher`
- **Windows** → `WindowsScreenFlasher`
- **Other** → `CrossPlatformScreenFlasher`

### MacScreenFlasher

**Configuration (from ConfigManager):**

| Property | Default | Description |
|---|---|---|
| `flashColor` | `#800000` | Background color |
| `flashTextColor` | `#ffffff` | Text color |
| `flashOpacity` | `1.0` | Window opacity |
| `flashDurationSeconds` | `5` | Display duration |

**Window creation (`flashMultiple`):**
1. Validates system state, display environment, and event content
2. Cleans up any existing flash windows (`forceCleanup()`)
3. On EDT (via `invokeAndWait`): iterates `GraphicsEnvironment.getScreenDevices()`, calls `createFlashWindowForScreen()` per screen
4. Adds frames to `activeFlashFrames` tracking list
5. Fires one-shot `onFlashReady` callback via `invokeLater`
6. Requests macOS user attention via `Taskbar.requestUserAttention(true, true)`
7. Starts cleanup timer

**Flash window properties:**
- `JFrame`, undecorated, `Type.POPUP`
- Full-screen bounds per monitor
- `alwaysOnTop = true`
- Custom `JPanel` painting: colored background + event text

### Elevation Timer (per flash window)

Ensures flash windows remain visible above other applications.

| Parameter | Value |
|---|---|
| Initial delay | 100ms |
| Repeat interval | 1000ms |
| Max attempts | `max(10, ceil(flashDurationMs / 1000) + 3)` — covers full flash duration with buffer |

**Behavior per tick:**

| Condition | Action |
|---|---|
| Every tick | Toggle `alwaysOnTop` off→on to re-assert always-on-top above normal windows |
| No overlays registered | Also call `frame.toFront()` to raise flash fully |
| Overlays registered | Skip `frame.toFront()` on flash (banner's own `toFront()` below maintains correct z-order without flicker) |
| Every tick | Re-elevate all registered overlay windows via `overlay.toFront()` |
| Frame disposed | Timer self-terminates via `!frame.isDisplayable()` check |

**Rationale:** Toggling `setAlwaysOnTop(false/true)` on every tick keeps the flash window above all normal (non-alwaysOnTop) windows for the entire flash duration, even if another application was activated mid-flash. When banner overlays are registered, calling `toFront()` on the flash would momentarily push it above the banner causing visible flicker; the `setAlwaysOnTop` toggle alone is sufficient to maintain visibility against normal windows, and the overlay's own `toFront()` call preserves the correct z-order (banner above flash).

### Cooperative Overlay System

Allows banner windows to coexist above flash windows without timer conflicts.

```java
// Static shared state in MacScreenFlasher
private static final List<JFrame> overlayWindows = new CopyOnWriteArrayList<JFrame>()

static void registerOverlayWindows(List<JFrame> windows)
static void clearOverlayWindows()
```

- Banner calls `registerOverlayWindows()` immediately after creating banner windows
- Banner calls `clearOverlayWindows()` when banners are disposed (5s hide timer)
- Elevation timer re-elevates registered overlay windows after each tick
- `CopyOnWriteArrayList` for thread safety (elevation timer on EDT, registration from caller thread)

### onFlashReady Callback

```java
private static volatile Runnable onFlashReady = null
static void setOnFlashReady(Runnable callback)
```

- **Set by:** `performFullAlert()` before starting flash thread
- **Fired by:** `flashMultiple()` via `invokeLater` immediately after `invokeAndWait` completes
- **One-shot:** cleared to `null` after firing
- **Purpose:** Shows banner at exactly the right moment — after flash windows exist but before elevation timer fights begin
- **Volatile:** ensures visibility across threads (set on caller thread, read on flash thread)

---

## Component 3: Alert Banner

**Location:** `OutlookAlerterUI.showAlertBanner(String message)`  
**Tracking field:** `private List<JFrame> alertBannerWindows = []`

### Window Structure

One transparent `JFrame` per screen, painting only a red border frame:

```
┌───────────────── borderThickness ─────────────────┐
│              ▓▓▓ BANNER TEXT ▓▓▓                  │  ← top bar (red, with white text)
├─────┬───────────────────────────────────────┬─────┤
│     │                                       │     │
│  ◀──│    transparent center (click-through) │──▶  │  ← left/right strips
│     │                                       │     │
├─────┴───────────────────────────────────────┴─────┤
│                                                   │  ← bottom strip
└───────────────────────────────────────────────────┘
```

### Window Properties

| Property | Value |
|---|---|
| Type | `JFrame`, undecorated, `Type.POPUP` |
| Background | `Color(0, 0, 0, 0)` — fully transparent |
| Content pane | `JPanel` with `opaque=false`, custom `paintComponent` |
| Always on top | `true` |
| Border color | `Color(220, 0, 0)` — bright red |
| Text color | `Color.WHITE` |
| Text font | system default, Bold, 18pt |

### Menu Bar Offset

```java
Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(screen.getDefaultConfiguration())
int frameY = bounds.y + screenInsets.top      // below menu bar
int frameH = bounds.height - screenInsets.top  // reduced height
```

On macOS, `screenInsets.top` is the menu bar height (typically 25px). The banner frame starts below the menu bar so text is not clipped.

### Border Thickness Calculation

```java
JLabel sizeRef = new JLabel("X")
sizeRef.setFont(sizeRef.getFont().deriveFont(Font.BOLD, 18f))
sizeRef.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20))
int borderThickness = sizeRef.getPreferredSize().height + 10
```

Dynamic — adapts to font metrics. Typically ~45px.

### Lifecycle

1. Dispose existing banners (`alertBannerWindows.each { it.dispose() }`)
2. Create one `JFrame` per screen with custom painting
3. Register with `MacScreenFlasher.registerOverlayWindows()`
4. Start auto-hide `Timer(5000)`:
   - `MacScreenFlasher.clearOverlayWindows()`
   - Dispose all banner windows
   - Clear `alertBannerWindows` list

**Auto-hide duration:** 5000ms (hardcoded)

---

## Component 4: Tray Notification

```java
showTrayNotification(notificationTitle, notificationMessage, TrayIcon.MessageType.INFO)
```

- Posted via `invokeLater` (EDT required for Swing tray)
- Independent of flash/banner timing
- Standard `java.awt.TrayIcon.displayMessage()`

---

## Alert Decision Logic

**Location:** `checkForEventAlerts(List<CalendarEvent> events)`  
**Called by:** `checkAlertsFromCache()` (alert scheduler, every 60s)

### Per-Event Evaluation

```
for each event:
  ├─ Skip if event.hasEnded() → also remove from alertedEventIds
  ├─ Skip if event.isAllDay()
  │    All-day events are ALWAYS excluded from time-based alerting regardless of the
  │    "Ignore All Day Events" setting. Their start times use a non-standard timezone
  │    ("tzone://Microsoft/Custom") that fails to parse and falls back to now(), giving
  │    them minutesToStart ≈ 0. They must never trigger a time-based alert.
  ├─ minutesToStart = event.getMinutesToStart() + 1  (account for current time)
  ├─ Skip if alertedEventIds.contains(event.id)
  └─ Alert if: minutesToStart <= configManager.alertMinutes AND minutesToStart >= -1
```

### Aggregation

- Events passing the filter are collected into `eventsToAlert`
- All are marked in `alertedEventIds` **before** `performFullAlert()` (prevents duplicate alerts if a component fails)
- Single event → specific text: `"Upcoming meeting: <subject>"`
- Multiple events → aggregated: `"N upcoming meetings starting soon"`

---

## Token Failure Alert

**Location:** `OutlookClient.getTokensFromUser()`

On the first attempt (of up to 3), creates a synthetic `CalendarEvent`:
```java
CalendarEvent tokenEvent = new CalendarEvent(
    subject: "⚠️ TOKEN ENTRY REQUIRED ⚠️",
    startTime: ZonedDateTime.now(),
    endTime: ZonedDateTime.now().plusMinutes(1),
    isOnlineMeeting: false,
    organizer: "Token Entry",
    responseStatus: "Flash",
    calendarName: "Token Entry")
```

Then calls:
```java
outlookAlerterUI.performFullAlert(
    "⚠️ Token Entry Required - Please enter your OAuth tokens",
    "Token Entry Required",
    "Please enter your OAuth tokens in the dialog that will appear.",
    [tokenEvent])
```

**Console-mode fallback:** If `outlookAlerterUI` is null, fires flash only via `ScreenFlasherFactory.createScreenFlasher().flash(tokenEvent)`.

---

## Wake Alert / Startup Alert

**Location:** `OutlookAlerterUI.checkAlertsOnWake()` \
**Triggers:**
- System wake event detected by `MacSleepWakeMonitor`, 3 seconds after wake
- Application startup: called once after the first successful calendar fetch completes (`initialAlertCheckDone` flag prevents repeat calls)

### Problem

`checkForEventAlerts()` skips events in `alertedEventIds` (events that already fired before sleep) and events with `minutesToStart < -1` (meetings that started more than ~1 minute ago). If the system sleeps during a meeting or wakes past a meeting's start time, no alert would ever fire for those events.

### Sequence on wake

```
MacSleepWakeMonitor detects time jump > 65s
  └─ notifyWakeListeners()
       ├─ MacScreenFlasher.updateLastWakeTime()      (cleanup stuck windows)
       └─ OutlookAlerterUI wake listener (background thread)
            ├─ silent token refresh attempt
            └─ SwingUtilities.invokeLater → EDT
                 ├─ restartSchedulers()
                 ├─ refreshCalendarEvents()           (async API refresh)
                 └─ Timer(3000ms) → checkAlertsOnWake()
```

### Sequence on startup

```
refreshCalendarEvents() → fetch thread → EDT callback
  ├─ lastFetchedEvents updated
  ├─ initialAlertCheckDone == false → set true
  └─ checkAlertsOnWake()   (called directly on the EDT, no delay needed)
```

### checkAlertsOnWake() logic

```
Using lastFetchedEvents (cached):
  1. Collect all non-ended event IDs → remove from alertedEventIds
     (allows upcoming events to re-fire via normal window)
  2. Find events where isInProgress() == true AND !isAllDay()
     (all-day events are always in-progress all day; exclude them unconditionally
      to prevent spurious "meeting in progress" alerts for non-meeting day events)
     → fire performFullAlert() immediately with banner text "Meeting in progress: <subject>"
     → mark those event IDs in alertedEventIds (prevent double-alert)
  3. Call checkForEventAlerts(events)
     → handles upcoming events within the normal alert-minutes window
     → checkForEventAlerts also skips all-day events unconditionally
```

**Banner text for in-progress alert:**
- Single event: `"Meeting in progress: <subject>"`
- Multiple events: `"N meetings in progress"`

---

## Threading Summary

```
                    ┌──────────────────────────────────┐
                    │      performFullAlert()           │
                    │      (caller thread)              │
                    └──┬──────┬──────────┬─────────────┘
                       │      │          │
            ┌──────────▼┐  ┌──▼────────┐ │
            │ BeepThread │  │ FlashThread│ │
            │ (daemon)   │  │ (daemon)  │ │
            └────────────┘  └──┬────────┘ │
                               │          │
                    ┌──────────▼──────┐   │
                    │  invokeAndWait  │   │
                    │  (create flash  │   │
                    │   windows)      │   │
                    └──────┬──────────┘   │
                           │              │
                    ┌──────▼──────┐  ┌────▼───────────┐
                    │ invokeLater │  │  invokeLater   │
                    │ onFlashReady│  │  trayNotify    │
                    │→ showBanner │  │                │
                    └─────────────┘  └────────────────┘
                           │
                    ┌──────▼──────────────┐
                    │   EDT               │
                    │ • banner windows    │
                    │ • registerOverlays  │
                    │ • elevation timer   │
                    │ • hide timer (5s)   │
                    └─────────────────────┘
```

---

## Test Coverage

**File:** `OutlookAlerterUIAlertTest.java`

Uses `sun.misc.Unsafe.allocateInstance()` to create a minimal `OutlookAlerterUI` without the heavy constructor (no `OutlookClient`, OAuth, or Swing frame). A `RecordingScreenFlasher` captures all flash calls instead of creating windows.

### performFullAlert() tests
| Test | Verifies |
|------|----------|
| invokesFlash | `flashMultiple` called once with correct event subject |
| handlesMultipleEvents | Two events passed in single batch |
| handlesNullEvents | Null events list does not throw |
| handlesEmptyEvents | Empty list produces no flash |
| beepThreadRuns | Beep thread concurrent with flash (headless: audio is no-op) |

### Screen flash tests
| Test | Verifies |
|------|----------|
| flashReceivesCorrectEventData | Subject, organizer, online-meeting flag preserved |
| flashReceivesAllEventsInSingleBatch | Three events arrive in one `flashMultiple` call, in order |
| flashNotTriggeredForNull | Null events → no `flash()` or `flashMultiple()` calls |
| flashNotTriggeredForEmpty | Empty events list → no flash calls |
| flashPreservedThroughPipeline | Full `checkForEventAlerts` → `performFullAlert` → `flashMultiple` path preserves event data |
| forceCleanupSafe | `forceCleanup()` callable without error |
| separateFlashBatches | Two `performFullAlert` calls produce two distinct flash batches |

### checkForEventAlerts() tests
| Test | Verifies |
|------|----------|
| alertsWithinThreshold | Event within alert-minutes fires flash |
| doesNotAlertOutsideThreshold | Event far in future produces no alert |
| noDuplicateAlerts | Same event checked twice only fires one alert |
| cleansUpEndedEvents | Ended events removed from `alertedEventIds` |
| alertsMultipleEvents | Multiple qualifying events batched into single alert || allDayEventNeverAlerted_settingOff | All-day event never triggers alert when *Ignore All Day Events* is OFF |
| allDayEventNeverAlerted_settingOn | All-day event never triggers alert when *Ignore All Day Events* is ON |
### checkAlertsOnWake() tests
| Test | Verifies |
|------|----------|
| alertsForInProgressOnWake | In-progress meeting → flash immediately on wake |
| inProgressAlreadyAlertedIsReAlertedonWake | Event alerted before sleep has its alerted status cleared and re-fires |
| doesNotAlertForEndedMeetingOnWake | Ended meeting produces no alert |
| alertsForUpcomingOnWake | Upcoming meeting within threshold fires via `checkForEventAlerts` path |
| doesNothingWithNoCachedEvents | No cached events → no exception, no flash |
| alertsBothInProgressAndUpcoming | In-progress + upcoming → two separate flash batches |
