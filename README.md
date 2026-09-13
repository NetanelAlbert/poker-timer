# Poker Timer

A tournament blind clock for home poker games, built natively for Android in Kotlin and Jetpack Compose. The app runs a countdown for each level, sounds an alarm when blinds go up, and continues timing even with the screen off or the app backgrounded.

## How It Works

The app counts down the duration of each blind level. When the countdown reaches zero, a looping alarm sounds and the display immediately shows the next level's blinds. However, the countdown for the next level does **not** start automatically. The app remains in this alarming state until you tap the primary action (the large button), which silences the alarm and starts the next level's countdown. Nothing advances while nobody is looking.

If the alarm goes off mid-hand, **Snooze** silences it without advancing anything: the clock stays parked on the new blinds and the alarm comes back after the snooze interval. It can be snoozed as many times as you like.

## Features

- **Editable blind structure**: Add, remove, or reorder levels; apply a single duration to all levels
- **Three configurable sounds**: Pick from system ringtone picker for blinds-up alarm, pre-end warning (with configurable lead time, default 30 seconds), and level-start chime. The alarm defaults to the device alarm tone; the warning and chime default to short built-in tones (~0.2s), since a ringtone is far too long for a confirmation blip
- **Snooze**: Silence a ringing alarm and have it return after a configurable interval (default 2 minutes), without starting the next level; repeatable
- **Vibration and volume control**: Enable/disable vibration; adjust alarm volume
- **Screen-on behavior**: Option to keep screen on during the tournament
- **Persistent timing**: Runs in a foreground service with an ongoing notification carrying pause, next, and reset actions; timing continues with the screen off
- **Session persistence**: The current level and remaining time are saved as you play, so if the process is killed the app comes back parked at that level, waiting for a tap

## Building

**Debug build:**
```
./gradlew assembleDebug
```
Produces: `app/build/outputs/apk/debug/app-debug.apk`

**Unit tests:**
```
./gradlew testDebugUnitTest
```

**Requirements**: JDK 17, Android SDK (minSdk 26, targetSdk/compileSdk 35). GitHub Actions CI builds and uploads the debug APK on every push as a downloadable artifact, so you can test without a local SDK.

## Project Layout

| Package | Purpose |
|---------|---------|
| `model/` | `BlindLevel`, `TimerPhase`/`TimerState`, `TimerSettings` — immutable data classes |
| `timer/` | `TimerEngine` — Android-free countdown state machine; `TimerService` — foreground service and wake lock; `TimerNotifications` — notification UI |
| `sound/` | `AlarmPlayer` — plays alarm, warning, and chime sounds via platform audio system |
| `data/` | `SettingsRepository` — persists settings and session state via DataStore |
| `ui/` | Compose screens: `TimerScreen` (main display), `LevelsScreen` (edit structure), `SettingsScreen` (sounds and options) |

## Design Notes

- **Drift-free countdown**: `TimerEngine` derives elapsed time from a deadline on `SystemClock.elapsedRealtime()`, never accumulated ticks. The countdown is accurate regardless of irregular or missed tick calls.
- **Wake lock for background timing**: A foreground service alone does not keep the CPU awake. The service holds a `PowerManager.PARTIAL_WAKE_LOCK` while the clock is live, ensuring the alarm fires on schedule even with the screen off.
- **Efficient notifications**: The notification uses a countdown chronometer that updates itself, so the notification state only rebuilds when the phase or level changes, not on every tick.
- **Fully testable state machine**: `TimerEngine` takes its clock as an injected function `() -> Long`, making the entire state machine unit-testable on the JVM without Android mocks (see `app/src/test/`).
- **Alarm audio stream**: The alarm plays on the alarm stream via `USAGE_ALARM` and does not request audio focus, matching system alarm-clock behaviour. This is deliberate. Behaviour during an active phone call is untested.

## Permissions

| Permission | Purpose |
|-----------|---------|
| `POST_NOTIFICATIONS` | Post the foreground service notification on API 33+ |
| `FOREGROUND_SERVICE` | Run a foreground service |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Declare the service as a tournament clock (used on API 34+) |
| `WAKE_LOCK` | Hold a partial wake lock to keep the CPU awake while timing with the screen off |
| `VIBRATE` | Vibrate the device when the alarm sounds |
| `USE_FULL_SCREEN_INTENT` | Put the blinds-up alarm on screen over the keyguard, rather than only making a noise from nowhere |
