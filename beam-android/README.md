# Beam — Android App

Native Android app for Beam file transfer. Enables background transfers when screen is off or another app is open.

## Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Kotlin 1.9+
- Min Android version: 8.0 (API 26)

## Setup

1. Open Android Studio
2. File → Open → select the `beam-android/` folder
3. Wait for Gradle sync to complete
4. Copy `index.html` from the project root into `app/src/main/assets/`:
   ```
   cp ../index.html app/src/main/assets/index.html
   ```
5. Connect your Android device (enable USB debugging)
6. Click Run

## How it works

- The app loads the Beam web UI in a WebView
- A Foreground Service keeps running even when screen is off
- File transfers complete in background via OkHttp
- Notifications show progress and completion

## Building APK

In Android Studio: Build → Build Bundle(s)/APK(s) → Build APK(s)
Output: `app/build/outputs/apk/debug/app-debug.apk`

## Key files

| File | Purpose |
|---|---|
| `MainActivity.kt` | WebView setup + JS bridge |
| `BeamTransferService.kt` | Foreground Service — background transfers |
| `BeamWebInterface.kt` | JavaScript <-> Kotlin bridge |
| `LanDiscovery.kt` | Auto-discover Beam LAN server via NSD/mDNS |
| `BeamNotificationHelper.kt` | Notification management |
