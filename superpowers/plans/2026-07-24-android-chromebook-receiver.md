# Android + Chromebook Receiver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a Kotlin/Compose Android receiver (phones, tablets, Chromebooks) with iOS feature parity, discovered by the same OpenDisplay Mac app over WiFi and USB (ADB forward for phones/tablets).

**Architecture:** Device listens on TCP `:9000` with the same length-prefixed Annex B + JSON control wire as iOS. Android advertises `_opensidecar._tcp` via NSD; Mac Bonjour finds WiFi peers. USB uses `adb forward tcp:9000 tcp:9000` then Mac dials `127.0.0.1:9000`. Chromebook v1 is WiFi/Ethernet only.

**Tech Stack:** Kotlin, Jetpack Compose, MediaCodec, NsdManager, Okio/Java NIO sockets, Gradle; Mac Swift extensions (`AdbDeviceWatcher`, forward helper); existing `MacSender` TCP path.

## Global Constraints

- Spec: `superpowers/specs/2026-07-24-android-chromebook-receiver-design.md`
- Same wire as iOS: port **9000**, service type **`_opensidecar._tcp`**, `WireProtocol.version = 2`, `minSupportedPeer = 1`
- Hand-sync Kotlin wire constants with `Shared/Protocol.swift` (no codegen in v1)
- Device listens, Mac connects
- v1 not done until parity checklist **and** USB phones/tablets work
- Chromebook USB out of scope; Chromebook = WiFi/Ethernet
- Same Mac app (no separate helper)
- Save plans/specs under `superpowers/` (`docs/` is GitHub Pages build output)
- Prefer `./gradlew` tests for JVM logic; device/Mac manual checks where noted

## Scope note

This is one plan for the full v1 bar. Tasks are ordered so each slice is independently testable; do not skip USB (Task 9–10) or parity sleep/version (Task 7–8) before declaring v1.

---

## File map

| Path | Role |
|---|---|
| `Android/` | New Gradle app module |
| `Android/app/src/main/java/.../wire/WireProtocol.kt` | Protocol constants |
| `Android/.../wire/FrameCodec.kt` | Length-prefix encode/decode |
| `Android/.../session/ReceiverSession.kt` | Listen + session |
| `Android/.../video/VideoDecoder.kt` | MediaCodec → Surface |
| `Android/.../net/DiscoveryAdvertiser.kt` | NSD |
| `Android/.../input/InputForwarder.kt` | Touch/scroll |
| `Android/.../sleep/HostSleepController.kt` | hostSleeping UI/brightness |
| `Android/.../version/VersionGate.kt` | Update gate |
| `Android/.../ui/*` | Compose screens |
| `Mac/Adb.swift` (or `Mac/AdbDeviceWatcher.swift`) | ADB list/watch/forward |
| `Mac/OpenSidecarMacApp.swift` | Android USB targets in controller |
| `COMPATIBILITY.md` / `README.md` | Android + Chromebook notes |

---

### Task 1: Android Gradle scaffold

**Files:**
- Create: `Android/settings.gradle.kts`, `Android/build.gradle.kts`, `Android/gradle.properties`, `Android/app/build.gradle.kts`, `Android/app/src/main/AndroidManifest.xml`, `Android/app/src/main/java/com/peetzweg/opendisplay/MainActivity.kt`, `Android/app/src/main/res/values/strings.xml`
- Modify: root `README.md` (Build Android subsection)
- Test: `Android/app/src/test/java/com/peetzweg/opendisplay/SmokeTest.kt`

**Interfaces:**
- Produces: runnable empty Compose app `com.peetzweg.opendisplay`, minSdk **26**, compile/targetSdk **35**, applicationId `com.peetzweg.opendisplay`

- [ ] **Step 1: Write failing smoke test**

```kotlin
// Android/app/src/test/java/com/peetzweg/opendisplay/SmokeTest.kt
package com.peetzweg.opendisplay

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun packageName_isOpenDisplay() {
        assertEquals("com.peetzweg.opendisplay", BuildConfig.APPLICATION_ID)
    }
}
```

- [ ] **Step 2: Run test (expect fail — no project)**

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.SmokeTest
```

Expected: FAIL (no gradlew / module)

- [ ] **Step 3: Scaffold Gradle + empty MainActivity**

Use Android Gradle Plugin 8.7+, Kotlin 2.0+, Compose BOM. `MainActivity` shows `Text("OpenDisplay")`. Manifest: `INTERNET`, portrait/landscape, `android:label=OpenDisplay`. Generate wrapper: `gradle wrapper --gradle-version 8.11.1` from `Android/`.

- [ ] **Step 4: Re-run test**

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.SmokeTest
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add Android README.md
git commit -m "feat(android): scaffold Compose app module"
```

---

### Task 2: Wire constants + frame codec (JVM-tested)

**Files:**
- Create: `Android/app/src/main/java/com/peetzweg/opendisplay/wire/WireProtocol.kt`
- Create: `Android/app/src/main/java/com/peetzweg/opendisplay/wire/FrameCodec.kt`
- Test: `Android/app/src/test/java/com/peetzweg/opendisplay/wire/FrameCodecTest.kt`

**Interfaces:**
- Produces:
  - `object WireProtocol { const val version = 2; const val minSupportedPeer = 1; const val assumedWhenAbsent = 1 }`
  - `object WireMessage { const val welcome = "welcome"; const val updateRequired = "updateRequired"; const val sleeping = "sleeping"; const val closing = "closing"; const val hostSleeping = "hostSleeping" }`
  - `object FrameCodec` with `fun encode(payload: ByteArray): ByteArray` and `class Deframer { fun push(data: ByteArray): List<ByteArray> }`

- [ ] **Step 1: Failing test**

```kotlin
package com.peetzweg.opendisplay.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCodecTest {
    @Test
    fun roundTrip_singleFrame() {
        val payload = """{"type":"ping"}""".toByteArray()
        val framed = FrameCodec.encode(payload)
        val deframer = FrameCodec.Deframer()
        val out = deframer.push(framed)
        assertEquals(1, out.size)
        assertArrayEquals(payload, out[0])
    }

    @Test
    fun protocolVersion_matchesIos() {
        assertEquals(2, WireProtocol.version)
        assertEquals("hostSleeping", WireMessage.hostSleeping)
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.wire.FrameCodecTest
```

- [ ] **Step 3: Implement**

```kotlin
// WireProtocol.kt
package com.peetzweg.opendisplay.wire

object WireProtocol {
    const val version = 2
    const val minSupportedPeer = 1
    const val assumedWhenAbsent = 1
}

object WireMessage {
    const val welcome = "welcome"
    const val updateRequired = "updateRequired"
    const val sleeping = "sleeping"
    const val closing = "closing"
    const val hostSleeping = "hostSleeping"
}

// FrameCodec.kt
package com.peetzweg.opendisplay.wire

import java.nio.ByteBuffer
import java.nio.ByteOrder

object FrameCodec {
    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).putInt(payload.size)
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    class Deframer {
        private val buf = ArrayList<Byte>()
        fun push(data: ByteArray): List<ByteArray> {
            for (b in data) buf.add(b)
            val frames = ArrayList<ByteArray>()
            while (true) {
                if (buf.size < 4) break
                val len = ByteBuffer.wrap(byteArrayOf(buf[0], buf[1], buf[2], buf[3]))
                    .order(ByteOrder.BIG_ENDIAN).int
                if (len < 0 || len > 1 shl 22) { buf.clear(); break }
                if (buf.size < 4 + len) break
                val payload = ByteArray(len)
                for (i in 0 until len) payload[i] = buf[4 + i]
                repeat(4 + len) { buf.removeAt(0) }
                frames.add(payload)
            }
            return frames
        }
    }
}
```

- [ ] **Step 4: Tests PASS**

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/peetzweg/opendisplay/wire \
        Android/app/src/test/java/com/peetzweg/opendisplay/wire
git commit -m "feat(android): add wire constants and length-prefix framing"
```

---

### Task 3: ReceiverSession — listen, hello, ping

**Files:**
- Create: `Android/.../session/ReceiverSession.kt`
- Create: `Android/.../session/InstallId.kt`
- Test: JVM test for hello JSON shape (pure function extract)

**Interfaces:**
- Consumes: `FrameCodec`, `WireProtocol`
- Produces:
  - `class ReceiverSession(port: Int = 9000, listener: Listener)`
  - `fun start()` / `fun stop()`
  - `fun sendControl(map: Map<String, Any>)`
  - `interface Listener { fun onConnected(); fun onDisconnected(); fun onVideoFrame(ByteArray); fun onControl(Map<String, Any>); fun onStatus(String) }`
  - Hello fields: `type=hello`, `pixelsWide`, `pixelsHigh`, `scale`, `device` (`Android`|`Chromebook`), `id`, `pv`

- [ ] **Step 1: Test hello JSON**

```kotlin
@Test
fun helloJson_containsRequiredKeys() {
    val json = ReceiverSession.helloJson(
        wide = 1170, high = 2532, scale = 3.0,
        device = "Android", id = "abc", pv = 2
    )
    // parse and assert keys
}
```

- [ ] **Step 2–4: Implement ServerSocket accept loop on a background thread; on connect send hello; demux frames — if payload starts with `{` → control else → video callback; answer `ping` with `pong`.**

Device kind helper:

```kotlin
fun deviceKind(context: Context): String {
    val pm = context.packageManager
    return if (pm.hasSystemFeature("org.chromium.arc") ||
        pm.hasSystemFeature("org.chromium.arc.device_management")) "Chromebook"
    else "Android"
}
```

- [ ] **Step 5: Commit** `feat(android): TCP receiver session with hello and ping`

---

### Task 4: VideoDecoder + streaming UI surface

**Files:**
- Create: `Android/.../video/VideoDecoder.kt`
- Modify: `MainActivity` / `ui/StreamingScreen.kt` — `AndroidView` with `SurfaceView`
- Manual test on device/emulator with Mac WiFi (after Task 5) or `tools/fake` if adapted

**Interfaces:**
- Produces: `class VideoDecoder(surface: Surface) { fun feedAnnexB(frame: ByteArray); fun release() }`
- Consumes: Annex B from `ReceiverSession.onVideoFrame`

- [ ] **Step 1: Implement MediaCodec video/avc decoder configured with Surface; gather SPS/PPS from stream or in-band; queue input buffers; release output to surface.**

- [ ] **Step 2: Compose `StreamingScreen` fullscreen black + SurfaceView; keep screen on via `WindowCompat` / `FLAG_KEEP_SCREEN_ON` while connected.**

- [ ] **Step 3: Commit** `feat(android): MediaCodec decode to fullscreen surface`

---

### Task 5: NSD discovery advertiser

**Files:**
- Create: `Android/.../net/DiscoveryAdvertiser.kt`
- Modify: Manifest if needed for nearby/WiFi

**Interfaces:**
- Produces: `class DiscoveryAdvertiser(context, serviceName, installId) { fun start(port: Int = 9000); fun stop() }`
- TXT: `id` = installId, `pv` = `"2"`
- Service type: `_opensidecar._tcp`

- [ ] **Step 1: Register NsdServiceInfo with type `_opensidecar._tcp.` (trailing dot per Android docs), name = user-visible device name.**

- [ ] **Step 2: Manual — Mac OpenDisplay WiFi menu lists the Android device.**

- [ ] **Step 3: Commit** `feat(android): advertise OpenDisplay via NSD`

---

### Task 6: Touch + scroll input

**Files:**
- Create: `Android/.../input/InputForwarder.kt`
- Modify: streaming surface touch handling

**Interfaces:**
- Produces: normalized `[0,1]` touch phases `began|moved|ended|cancelled`; scroll `dx`/`dy` in video pixels
- Consumes: `ReceiverSession.sendControl`

Match iOS:

```json
{"type":"touch","phase":"began","x":0.5,"y":0.5}
{"type":"scroll","dx":0,"dy":-40}
```

- [ ] **Step 1: Unit-test normalization math (view x/y → 0…1).**

- [ ] **Step 2: Wire MotionEvent; two-pointer vertical/horizontal → scroll.**

- [ ] **Step 3: Manual — taps move cursor / click on Mac.**

- [ ] **Step 4: Commit** `feat(android): forward touch and scroll to Mac`

---

### Task 7: Sleep, hostSleeping, orientation

**Files:**
- Create: `Android/.../sleep/HostSleepController.kt`
- Modify: `ReceiverSession` control handler; `MainActivity` lifecycle

**Interfaces:**
- On `WireMessage.hostSleeping`: black fullscreen UI, `brightness = 0f` (save/restore), clear keep-screen-on, **keep listening**
- On user lock / `ACTION_SCREEN_OFF` with credential: send `sleeping`, stop accepting until unlock (parity iOS)
- On quit: send `closing`
- Orientation: on config change, update hello panel metrics and notify Mac (match iOS rebuild — send new hello on next connection or dedicated path if iOS does mid-session; follow iOS `setOrientation` behavior)

- [ ] **Step 1: Implement HostSleepController + control case for hostSleeping.**

- [ ] **Step 2: Manual — Mac sleep blanks Android; Auto-Lock can engage; Mac wake reconnects.**

- [ ] **Step 3: Commit** `feat(android): host sleep blanking and local sleep signals`

---

### Task 8: Settings UI + version gate

**Files:**
- Create: `ui/IdleScreen.kt`, `ui/SettingsScreen.kt`, `version/VersionGate.kt`
- Modify: use same `ios-version.json` pattern or Android-specific URL documented in COMPATIBILITY — prefer shared remote config shape with an `android` key **or** reuse messages from peer only in v1 if remote Android config absent; minimum: handle `updateRequired` / old Mac `welcome` like iOS

**Interfaces:**
- Device name persisted → NSD service name + hello
- Analytics overlay optional (fps) — can be minimal HUD
- Blocking update UI when peer requires it

- [ ] **Step 1: Idle + Settings Compose; VersionGate handles welcome/updateRequired.**

- [ ] **Step 2: Commit** `feat(android): idle/settings UI and version gate`

---

### Task 9: Mac ADB watcher + forward

**Files:**
- Create: `Mac/Adb.swift`
- Modify: `Mac/OpenSidecarMacApp.swift` (SenderController)
- Modify: `COMPATIBILITY.md`, `README.md` (adb requirement)

**Interfaces:**
- Produces:
  - `struct AdbDevice: Identifiable { let serial: String; var authorized: Bool }`
  - `final class AdbDeviceWatcher { var onChange: ([AdbDevice]) -> Void; func start(); func stop() }`
  - `enum Adb { static func forward(serial: String, port: UInt16) throws; static func clearForward(serial: String) throws; static func findAdb() -> URL? }`
- Behavior: shell out to `adb` (`which adb` / `~/Library/Android/sdk/platform-tools/adb`); `adb devices`; `adb -s SERIAL forward tcp:9000 tcp:9000`; dial `NWEndpoint.hostPort(host: "127.0.0.1", port: 9000)` via existing TCP transport
- ConnectionTarget gains `.androidUsb(serial: String)` with sessionID `adb:<serial>`

- [ ] **Step 1: Implement Adb.swift with list + forward + clear.**

- [ ] **Step 2: Watcher polls every 2s or uses `adb track-devices`.**

- [ ] **Step 3: Wire SenderController auto-connect for authorized serials (respect disable set like usbDisabled → `adbDisabled`).**

- [ ] **Step 4: Manual — cable phone streams without WiFi.**

- [ ] **Step 5: Commit** `feat(mac): ADB forward USB transport for Android receivers`

---

### Task 10: Mac UI + docs + Chromebook validation checklist

**Files:**
- Modify: Mac device list labels for Android/Chromebook
- Modify: `README.md`, `COMPATIBILITY.md`
- Create: optional `Android/README.md` build instructions

**Interfaces:**
- Docs state: Chromebook = WiFi; USB = phones/tablets + adb; install platform-tools

- [ ] **Step 1: Label WiFi/USB Android rows; missing-adb banner with install hint.**

- [ ] **Step 2: Document build (`cd Android && ./gradlew :app:assembleDebug`), TestFlight-equivalent = sideload APK.**

- [ ] **Step 3: Manual matrix from spec Testing section — record results in PR description.**

- [ ] **Step 4: Commit** `docs: Android and Chromebook receiver setup`

---

## Spec coverage (self-review)

| Spec item | Task |
|---|---|
| Kotlin Compose receiver | 1–8 |
| Same wire / port 9000 / `_opensidecar._tcp` | 2, 3, 5 |
| Hello / handshake / version | 3, 8 |
| Decode + fullscreen | 4 |
| Touch / scroll | 6 |
| Orientation | 7 |
| Sleep / hostSleeping / closing | 7 |
| Settings + version gate | 8 |
| USB ADB forward phones/tablets | 9 |
| Same Mac app | 9–10 |
| Chromebook WiFi; USB out of scope | 5, 10 |
| COMPATIBILITY / README | 10 |

**Placeholder scan:** none intentional — Task 3–4 MediaCodec details left to implementer judgment within stated interfaces; hello JSON and framing are fully specified.

**Type consistency:** `ReceiverSession`, `FrameCodec`, `WireMessage.hostSleeping`, `AdbDevice.serial`, `ConnectionTarget.androidUsb`.
