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
 ├─ 2. onFlashReady callback registered → will fire showAlertBanner() AND
 │      showJoinMeetingDialogOnAllScreens() once flash is visible
 ├─ 3. ScreenFlasherThread → background thread → flashMultiple(events)
 │     └─ invokeAndWait: create flash windows on EDT
 │     └─ invokeLater: fire onFlashReady → showAlertBanner() + showJoinMeetingDialogOnAllScreens() on EDT
 └─ 4. invokeLater: showTrayNotification() on EDT
```

**Key design constraints:**
- Beeps start immediately on a daemon thread (highest urgency)
- Flash windows are created on EDT via `invokeAndWait` (Swing thread safety)
- Banner and join dialogs wait for flash via `onFlashReady` callback (prevents banner-under-flash flicker)
- Join dialogs appear on **all connected screens** simultaneously, linked for coordinated close
- If `events` is null/empty, banner shows immediately without waiting for flash; no join dial is shown
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
    default boolean wasUserDismissed()   // true if dismissed by click/key; false if timer
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
| `flashColor` | `#800000` | Alert flash background color |
| `flashTextColor` | `#ffffff` | Alert flash text color |
| `alertBannerColor` | `#dc0000` | Banner/border overlay color |
| `flashOpacity` | `1.0` | Window opacity |
| `flashDurationSeconds` | `5` | Display duration |

**Window creation (`flashMultiple`) — blocking call:**
1. Acquires per-instance `Semaphore(1)` (serialises concurrent requests)
2. Validates system state, display environment, and event content
3. Cleans up any existing flash windows (`forceCleanup()`)
4. On EDT (via `invokeAndWait`): iterates `GraphicsEnvironment.getScreenDevices()`, calls `createFlashWindowForScreen()` per screen
5. Adds frames to `activeFlashFrames` tracking list
6. Creates a `CountDownLatch(1)` and stores it in `activeFlashLatch`
7. Fires one-shot `onFlashReady` callback via `invokeLater`
8. Requests macOS user attention via `Taskbar.requestUserAttention(true, true)`
9. Starts cleanup timer
10. **Blocks** on `activeFlashLatch.await(flashDurationMs + 10 000ms)` until `forceCleanup()` signals completion
11. Releases the `Semaphore` in the `finally` block

> **Why blocking?** Making `flashMultiple()` block until `forceCleanup()` runs eliminates
> two race conditions that caused flash windows to remain stuck on screen when a laptop-wake
> alert and an upcoming-meeting alert fired at the same time:
>
> 1. *Stale `invokeLater` race* — a deferred `cleanupTask` could snapshot `activeFlashFrames`
>    before new frames were added, clear the list, and silently lose track of the new windows;
>    those windows would stay visible forever.
>
> 2. *`activeTimers` strip race* — `forceCleanup()` iterates and stops timers while
>    `setupCleanupTimer()` in a concurrent thread is simultaneously inserting new timers;
>    the new timers were cleared from `activeTimers` but continued firing against stale state.
>
> With the `Semaphore` the second `flashMultiple()` call cannot start until the first
> has been fully cleaned up, eliminating both races on a clean-slate basis.

**`forceCleanup()` interaction:**
- When `forceCleanup()` runs (timer, wake handler, or next `flashMultiple()`), its `finally`
  block reads `activeFlashLatch`, clears the field to `null`, and calls `latch.countDown()`.
- This is an atomic read-and-clear pattern (local variable) so backup/failsafe timers that
  call `forceCleanup()` a second time are no-ops (they find `null`).

**Flash window properties:**
- `JFrame`, undecorated, `Type.POPUP`
- Full-screen bounds per monitor

**Click / Key-press dismissal:**
Each flash window registers a `MouseAdapter.mouseClicked()` and a `KeyAdapter.keyPressed()`
listener (on the frame, panel, and label). When triggered:
1. Sets `userDismissed = true`
2. Calls `forceCleanup()` — which disposes all windows and unblocks `flashMultiple()`

`wasUserDismissed()` returns `userDismissed.get()`. The flag is reset to `false` at the
start of every new `flashMultiple()` call.
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

## Immediate Join Meeting Dialog

When a meeting alert fires, `OutlookAlerterUI.showJoinMeetingDialogOnAllScreens()` is called
**inside the `onFlashReady` callback** (Mac) or the 300 ms banner-delay timer (non-Mac), so the
dialogs appear the instant flash windows become visible — no user action required.

### Multi-Screen Behavior

- `JoinMeetingDialog.showOnAllScreens()` iterates `GraphicsEnvironment.getScreenDevices()` and
  creates one modeless `JoinMeetingDialog` per screen.
- All dialogs in a session share a coordinated-close `Runnable` (`dismissAll`): interacting with
  **any** dialog calls `closeAll()` → disposes all sibling dialogs → calls `onDismiss` (which
  calls `screenFlasher.forceCleanup()` and clears `activeDismissAll`).
- When a new alert fires while dialogs are open, the previous `activeDismissAll` runnable is
  invoked first, then a fresh session opens.

### JoinMeetingDialog

**Location:** `com.unhuman.outlookalerter.ui.JoinMeetingDialog`  
**Extends:** `JDialog` (modeless, `MODELESS`)  
**Factory:** `JoinMeetingDialog.showOnAllScreens(Window, List<CalendarEvent>, Function, Runnable) → Runnable`

**Layout (one button per alerted event, sorted by start time):**
```
┌─────────────────────────────────────┐
│  Join Meeting?                      │
│  ─────────────────────────────────  │
│  [Team Standup (in 1m)]             │  ← enabled; click opens URL + closes all
│  [Design Review (now)]              │  ← enabled
│  [Budget Review (in 3m) (No Link)]  │  ← disabled; no join URL found
│  ─────────────────────────────────  │
│  [Dismiss (Ns)]                     │  (plain "Dismiss" when timeout=0)
└─────────────────────────────────────┘
```

- Events **with** a join URL: enabled `JButton`; clicking opens `Desktop.browse(url)` and calls `closeAll()` (all group dialogs + flash stop)
- Events **without** a join URL: disabled `JButton` with `" (No Link)"` suffix
- URL resolution uses the same four-tier priority as the tray menu (`getEffectiveJoinUrl()`)
- Escape key, Dismiss button, and the countdown timer all call `closeAll()` (all group dialogs close + flash stops)
- `urlResolver` is injected as a `Function<CalendarEvent, String>` for headless testability
- Auto-dismiss timeout reads from `ConfigManager.getJoinDialogTimeoutSeconds()` (default 15 s; 0 = indefinite)
- **All-day events are excluded from the dialog when "Ignore All Day Events" is enabled** — they have no actionable join URL and would only appear as disabled `(No Link)` buttons. If filtering removes all events, the dialog is suppressed entirely.

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

---

**File:** `MacScreenFlasherTest.java`

Targets `MacScreenFlasher` directly. In headless environments `flashMultiple()` short-circuits
after display-environment validation, but the serialisation infrastructure (Semaphore +
completion latch) runs before that guard and is fully exercisable without a real display.

### Semaphore management tests
| Test | Verifies |
|------|----------|
| semaphoreInitialPermit | `flashSemaphore` starts at 1 permit |
| semaphoreReleasedAfterHeadlessCall | Headless short-circuit still releases the semaphore |
| semaphoreReleasedOnEarlyExit | Empty-list early return (before semaphore acquire) leaves permit count at 1 |
| semaphoreReleasedAfterForceCleanup | `forceCleanup()` does not consume/alter the semaphore |
| semaphoreReleasedAfterRepeatedCalls | 5 sequential headless calls leave exactly 1 permit |

### Concurrency tests
| Test | Verifies |
|------|----------|
| concurrentCallsCompleteWithoutDeadlock | 2 simultaneous `flashMultiple()` calls both complete within 5 s |
| manyConurrentCallsCompleteWithoutDeadlock | 5 simultaneous calls all complete within 10 s; permit back to 1 |

### Completion latch tests
| Test | Verifies |
|------|----------|
| latchNullAfterHeadlessCall | `activeFlashLatch` is `null` before and after a headless call |
| forceCleanupSignalsLatch | Injected latch is counted down to 0 and field cleared to `null` |
| forceCleanupIdempotentWithNullLatch | `forceCleanup()` with `null` latch never throws |
| forceCleanupRepeatedDoesNotDoubleSignal | Second `forceCleanup()` after latch already cleared is a no-op |
