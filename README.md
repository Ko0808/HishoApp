# Hisho

Android notifications are captured into a durable, encrypted queue and progressively converted into Google Tasks and executable Calendar time blocks.

## Current milestone

Phase 1 capture foundation:

- `NotificationListenerService`
- generic notification normalization
- Gmail, Slack, Discord, and LINE source filters
- SHA-256/time-window deduplication
- AES-GCM payload encryption backed by Android Keystore
- durable SQLite retry queue
- WorkManager synchronization seam
- privacy-preserving diagnostics screen

Google OAuth and Tasks synchronization are intentionally the next increment because they require an Android OAuth client configured for the final application id and signing certificate.

## Build

Open the repository in Android Studio and run the `app` configuration, or run:

```powershell
.\gradlew.bat test assembleDebug
```

Target package: `app.hisho`

## Device validation

1. Install the debug APK on a physical Android device.
2. Open Hisho and grant notification access.
3. Generate individual and grouped notifications from each supported app.
4. Confirm that the pending count increments once per unique notification and duplicate count increments for repeated updates.

Raw notification content is never written to Logcat or shown in diagnostics.
