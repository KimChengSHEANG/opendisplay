# OpenDisplay Android receiver

Kotlin + Jetpack Compose receiver for Android phones, tablets, and Chromebooks.
Uses the same wire protocol as the iOS app (TCP port 9000, length-prefixed H.264,
JSON control). The Mac OpenDisplay app discovers and connects to this receiver.

## Transports

| Device | Connection | Notes |
|---|---|---|
| Android phone / tablet | **USB** (recommended) or **WiFi** | USB needs `adb` on the Mac + USB debugging on the device; Mac auto-connects and prefers USB over WiFi for the same device |
| Chromebook | **WiFi / Ethernet**, or **ADB** (`adb connect`) | Physical USB-C into a Mac usually does **not** expose ADB. Use Bonjour WiFi, or `adb connect <ip>` so the Mac can use the same `adb forward` USB path |

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

### Phones / tablets (USB)

1. Build the APK (above).
2. Enable **USB debugging** in Developer options; plug into the Mac; tap **Allow**.
3. `adb devices` should list the phone, then:
   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

### Chromebook (no USB ADB on most models)

Plugging a Chromebook into the Mac usually leaves `adb devices` **empty**. That is
normal — as of 2023 most Chromebooks do not expose ADB on the USB cable. Use one
of these instead:

**A. ADB over Wi‑Fi from the Mac (recommended for install)**

1. Chromebook: **Settings → Advanced → Developers → Linux development environment**
   — turn Linux on if needed, then **Develop Android apps → Enable ADB debugging**
   (Chromebook restarts).
2. Note the Chromebook IP (clock → network → connection details).
3. On the Mac:
   ```sh
   adb connect <chromebook-ip>        # e.g. adb connect 192.168.1.42
   adb devices                        # should show <ip>:5555  device
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Tap **Allow** on the Chromebook debugging prompt.

**B. Install from Linux on the Chromebook**

1. Same ADB-debugging toggle as above.
2. Chromebook **Terminal** (Linux):
   ```sh
   sudo apt update && sudo apt install -y adb
   adb connect arc
   adb install -r /path/to/app-debug.apk
   ```
3. Copy the APK onto the Chromebook first (Drive, USB stick, `scp`, etc.).

Streaming still uses **WiFi/Ethernet Bonjour**, not the ADB tunnel.

## Connect from the Mac

- **WiFi / Chromebook:** same WiFi as the Mac → OpenDisplay auto-connects when
  the receiver appears (Bonjour `_opensidecar._tcp`).
- **USB (phones/tablets):** plug in with USB debugging authorized → Mac
  auto-connects via `adb forward`, and will migrate a live WiFi session onto
  the cable (iOS-style). Unplug fails over to WiFi when the service is still up.
- **Chromebook via ADB:** `adb connect <chromebook-ip>` makes the device show
  up like USB; same forward tunnel and WiFi failover. Install platform-tools
  on the Mac if the app shows a missing-`adb` banner
  (`brew install --cask android-platform-tools`).

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
