# OpenDisplay Android receiver

Kotlin + Jetpack Compose receiver for Android phones, tablets, and Chromebooks.
Uses the same wire protocol as the iOS app (TCP port 9000, length-prefixed H.264,
JSON control). The Mac OpenDisplay app discovers and connects to this receiver.

## Transports

| Device | Connection | Notes |
|---|---|---|
| Android phone / tablet | **USB** (recommended) or **WiFi** | USB needs `adb` on the Mac + USB debugging on the device |
| Chromebook | **WiFi / Ethernet only** | No USB cable path in v1 — install the APK and join the same LAN as the Mac |

## Prerequisites

- JDK 17+
- Android SDK (compileSdk / targetSdk 35, minSdk 26)
- Android Studio (optional) or command-line SDK tools

Set `sdk.dir` in `local.properties` if the SDK is not in the default location:

```properties
sdk.dir=/path/to/android-sdk
```

## Build

From the repo root:

```sh
cd Android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Or open the `Android/` folder in Android Studio and use **Run**.

## Install (sideload)

There is no Play Store build yet — sideloading the debug (or release) APK is the
equivalent of joining the iOS TestFlight beta.

1. Build the APK (above).
2. Enable **Install unknown apps** / developer sideloading on the device.
3. Transfer and install the APK (`adb install app/build/outputs/apk/debug/app-debug.apk`,
   or copy the file and open it on the device).
4. On **phones/tablets for USB**: enable **USB debugging** in Developer options.
5. Open **OpenDisplay** on the device and keep it in the foreground for WiFi discovery.

## Connect from the Mac

- **WiFi / Chromebook:** same WiFi as the Mac → pick the device in the Mac app's
  Devices list (Bonjour `_opensidecar._tcp`).
- **USB (phones/tablets):** plug in with USB debugging authorized → Mac auto-connects
  via `adb forward`. Install platform-tools on the Mac if the app shows a missing-`adb`
  banner (`brew install --cask android-platform-tools`).

## Staying awake while streaming

v1 keeps the display alive with an **Activity-scoped `FLAG_KEEP_SCREEN_ON`**
only — the screen stays on and immersive (system bars hidden) while a session
is connected or the host display is off, and the flag is cleared on disconnect.

A **foreground service (FGS) is intentionally deferred**. The receiver is meant
to run in the foreground on a dedicated/plugged-in secondary screen, so the
Activity keep-screen-on covers the v1 use case without the extra
`FOREGROUND_SERVICE` permission, notification channel, and lifecycle plumbing.
If a future version needs streaming to survive the app being backgrounded (or
another app taking focus), add a minimal `mediaProjection`/`connectedDevice`
foreground service started on connect and stopped on disconnect.

See the root [README.md](../README.md) and [COMPATIBILITY.md](../COMPATIBILITY.md)
for Mac-side setup and protocol notes.
