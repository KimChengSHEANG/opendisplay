# Chromebook Mac→Device Connection Speed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Mac→Chromebook connect and stream feel fast: cut time-to-first-frame (TTFF) and steady-state e2e latency on WiFi without green/black reconnect regressions.

**Architecture:** Instrument the connect timeline first (dial → hello → virtual display → capture → first paint). Then shrink the known Chromebook waits (VirtualDisplay retry backoff, SCShareableContent poll, late IDR). Finally tighten the steady-state path (TCP/UDP video knobs already in-tree) and gate UDP auto with the existing Chromebook A/B checklist.

**Tech Stack:** Swift `MacSender` / `VirtualDisplay` / ScreenCaptureKit / VideoToolbox; Kotlin `ReceiverSession` / `VideoDecoder` / `StreamingScreen`; Android unit tests (JUnit); Mac logs + Android perf overlay for device A/B.

## Global Constraints

- Do **not** tear TCP mid-session on Chromebook after the first successful paint (`ChromebookRecoverPolicy.allowForceReconnect` stays false once painted).
- Do **not** flip `udpAutoEnabled` to true until the keep-UDP gate in `superpowers/plans/2026-07-26-android-udp-latency-baseline.md` is filled and passes on a real Chromebook.
- Keep DisplayImmediately present (no Choreographer hold) on Android decode output.
- Prefer ARC `c2.vda.avc.decoder` on Chromebook; software decode is fallback-only and must not become the default “fast” path.
- Preserve iOS and USB TCP behavior; Chromebook WiFi is the target.
- YAGNI: no new transport protocols; reuse existing `kf`, qos, UDP FEC, stall watch.

## File map

| File | Responsibility |
|---|---|
| `Mac/ConnectTiming.swift` (new) | Pure stage timestamps + duration helpers for TTFF logs/tests |
| `Mac/MacSender.swift` | Emit connect stages; tighten VD retry + SCDisplay poll; early IDR |
| `Mac/VirtualDisplay.swift` | (read-only unless retry needs a create hook) |
| `Android/.../session/ConnectTimingPolicy.kt` (new) | Pure thresholds for “slow connect” / first-paint budget |
| `Android/.../MainActivity.kt` | Log Android-side dial→paint ages using session timestamps |
| `Android/.../session/ReceiverSession.kt` | Expose connect/hello/first-frame wall times |
| `Android/.../video/VideoDecoder.kt` | Optional: note first present for TTFF (already has `hasRendered`) |
| `superpowers/plans/2026-07-26-android-udp-latency-baseline.md` | Fill A/B rows after Tasks 4–5 |
| Unit tests under `Mac/` (XCTest if present) or pure Swift file tested via small host test; Android `app/src/test/...` |

---

### Task 1: Connect timing model (pure)

**Files:**
- Create: `Mac/ConnectTiming.swift`
- Create: `Android/app/src/main/java/com/peetzweg/opendisplay/session/ConnectTimingPolicy.kt`
- Test: `Android/app/src/test/java/com/peetzweg/opendisplay/session/ConnectTimingPolicyTest.kt`

**Interfaces:**
- Produces: `ConnectTiming` (Mac) with stages `dialStart`, `tcpReady`, `helloReceived`, `virtualDisplayReady`, `captureStarted`, `firstEncoded`; `durationMs(from:to:)`.
- Produces: `ConnectTimingPolicy` (Android) with `SLOW_TTFF_MS = 3000`, `isSlowTtff(dialToPaintMs: Long): Boolean`.

- [ ] **Step 1: Write the failing Android policy test**

```kotlin
package com.peetzweg.opendisplay.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectTimingPolicyTest {
    @Test
    fun slowTtff_flagsAboveBudget() {
        assertFalse(ConnectTimingPolicy.isSlowTtff(dialToPaintMs = 2_500))
        assertTrue(ConnectTimingPolicy.isSlowTtff(dialToPaintMs = ConnectTimingPolicy.SLOW_TTFF_MS + 1))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests 'com.peetzweg.opendisplay.session.ConnectTimingPolicyTest'`

Expected: FAIL — `Unresolved reference: ConnectTimingPolicy`

- [ ] **Step 3: Write minimal Android policy**

```kotlin
package com.peetzweg.opendisplay.session

/** Budgets for Mac→Chromebook connect feel (wall-clock, device-side). */
object ConnectTimingPolicy {
    /** Dial accept → first decoded present should usually beat this on WiFi. */
    const val SLOW_TTFF_MS = 3_000L

    fun isSlowTtff(dialToPaintMs: Long): Boolean = dialToPaintMs > SLOW_TTFF_MS
}
```

- [ ] **Step 4: Add Mac `ConnectTiming.swift`**

```swift
import Foundation

/// Wall-clock stages for Mac→receiver connect (TTFF diagnosis).
struct ConnectTiming {
    var dialStart: Date?
    var tcpReady: Date?
    var helloReceived: Date?
    var virtualDisplayReady: Date?
    var captureStarted: Date?
    var firstEncoded: Date?

    mutating func mark(_ keyPath: WritableKeyPath<ConnectTiming, Date?>, at date: Date = Date()) {
        self[keyPath: keyPath] = date
    }

    func durationMs(from: KeyPath<ConnectTiming, Date?>, to: KeyPath<ConnectTiming, Date?>) -> Double? {
        guard let a = self[keyPath: from], let b = self[keyPath: to] else { return nil }
        return b.timeIntervalSince(a) * 1000
    }

    func summaryLine() -> String {
        func fmt(_ ms: Double?) -> String {
            guard let ms else { return "-" }
            return String(format: "%.0f", ms)
        }
        return "connectTiming dial→ready=\(fmt(durationMs(from: \.dialStart, to: \.tcpReady)))ms ready→hello=\(fmt(durationMs(from: \.tcpReady, to: \.helloReceived)))ms hello→vd=\(fmt(durationMs(from: \.helloReceived, to: \.virtualDisplayReady)))ms vd→capture=\(fmt(durationMs(from: \.virtualDisplayReady, to: \.captureStarted)))ms capture→encode=\(fmt(durationMs(from: \.captureStarted, to: \.firstEncoded)))ms dial→encode=\(fmt(durationMs(from: \.dialStart, to: \.firstEncoded)))ms"
    }
}
```

Add `ConnectTiming.swift` to the Xcode / `project.yml` Mac sources the same way other `Mac/*.swift` files are listed (open `project.yml` and append under the Mac target sources if not globbed).

- [ ] **Step 5: Run Android test to verify it passes**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests 'com.peetzweg.opendisplay.session.ConnectTimingPolicyTest'`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add Mac/ConnectTiming.swift Android/app/src/main/java/com/peetzweg/opendisplay/session/ConnectTimingPolicy.kt Android/app/src/test/java/com/peetzweg/opendisplay/session/ConnectTimingPolicyTest.kt project.yml
git commit -m "feat(timing): add Mac/Android connect TTFF timing helpers"
```

---

### Task 2: Emit connect stages on Mac + Android

**Files:**
- Modify: `Mac/MacSender.swift` (fields near other session state; `connectTCP` / `becomeReady`; hello handler; `setupExtend`; `startCapture`; first encode callback)
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/session/ReceiverSession.kt` (record `connectedAtMs` / first video frame)
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/MainActivity.kt` (log slow TTFF when `hasRendered` flips true)

**Interfaces:**
- Consumes: `ConnectTiming` from Task 1; `ConnectTimingPolicy` from Task 1
- Produces: Mac log line `connectTiming …`; Android logcat `MainActivity: ttff=…ms` when paint happens

- [ ] **Step 1: Wire MacSender timing marks**

In `MacSender` add:

```swift
private var connectTiming = ConnectTiming()
```

In `connectTCP` / USB dial start (immediately before creating `NWConnection` / usbmux dial):

```swift
connectTiming = ConnectTiming()
connectTiming.mark(\.dialStart)
```

In `becomeReady`:

```swift
connectTiming.mark(\.tcpReady)
```

Where hello is parsed / `lastHello` set (same place `setupExtend` is triggered for extend mode):

```swift
connectTiming.mark(\.helloReceived)
```

After `virtualDisplay = vd` succeeds in `setupExtend`:

```swift
connectTiming.mark(\.virtualDisplayReady)
```

At end of `startCapture` after `try await stream.startCapture()`:

```swift
connectTiming.mark(\.captureStarted)
```

In the VT encode completion (first successful annex-B send only):

```swift
if connectTiming.firstEncoded == nil {
    connectTiming.mark(\.firstEncoded)
    Log.info(connectTiming.summaryLine())
}
```

- [ ] **Step 2: Wire Android TTFF log**

In `ReceiverSession`, add:

```kotlin
@Volatile var connectedAtWallMs: Long = 0L
    private set
@Volatile var firstVideoAtWallMs: Long = 0L
    private set
```

Set `connectedAtWallMs = System.currentTimeMillis()` in `handleClient` when calling `listener.onConnected()`. Set `firstVideoAtWallMs` once in `noteVideoFrame` / first `onVideoFrame` path when still 0. Reset both in `resetStreamState` / `closeClient`.

In `MainActivity` when `d.hasRendered` becomes true the first time for a connection:

```kotlin
val s = session
val connectedAt = s?.connectedAtWallMs ?: 0L
if (connectedAt > 0L && !paintedThisConnection) {
    val ttff = System.currentTimeMillis() - connectedAt
    val slow = ConnectTimingPolicy.isSlowTtff(ttff)
    android.util.Log.i("MainActivity", "ttff=${ttff}ms slow=$slow")
}
paintedThisConnection = true
```

- [ ] **Step 3: Manual smoke (required for baseline)**

On Mac + Chromebook WiFi:

1. Force-stop OpenDisplay on Chromebook, reconnect from Mac.
2. Capture Mac log `connectTiming …` and Chromebook logcat `ttff=…ms`.
3. Record numbers in the plan’s A/B table below (baseline row).

Expected: both sides emit once per connect; stages sum ≈ dial→encode.

- [ ] **Step 4: Commit**

```bash
git add Mac/MacSender.swift Android/app/src/main/java/com/peetzweg/opendisplay/session/ReceiverSession.kt Android/app/src/main/java/com/peetzweg/opendisplay/MainActivity.kt
git commit -m "feat(timing): log Mac and Chromebook connect TTFF stages"
```

---

### Task 3: Shrink VirtualDisplay + SCDisplay wait (biggest TTFF win)

**Files:**
- Modify: `Mac/MacSender.swift` (`setupExtend` retry loop ~498–520; `findSCDisplay` ~641–651)
- Test: add pure helper in `Mac/ConnectTiming.swift` (or new `Mac/VirtualDisplayRetry.swift`) for backoff schedule — unit-test via Android-equivalent pattern: put schedule in a tiny shared-style Swift struct and assert delays in a host test if XCTest exists; otherwise document the schedule in a Kotlin twin under Android tests is overkill — prefer a pure Swift function tested by a small `#if DEBUG` assert OR extract delays to:

```swift
enum VirtualDisplayRetry {
    /// Attempt index 0..<maxAttempts → sleep before that attempt (attempt 0 = 0).
    static func sleepSeconds(beforeAttempt attempt: Int) -> Double {
        if attempt <= 0 { return 0 }
        // Was fixed 2.0s × 7. Ramp: 0.25, 0.5, 1.0, then 1.5…
        return min(1.5, 0.25 * pow(2.0, Double(attempt - 1)))
    }
    static let maxAttempts = 8
}
```

Put `VirtualDisplayRetry` in `Mac/ConnectTiming.swift` (same file) to avoid project.yml churn beyond Task 1.

**Interfaces:**
- Consumes: none beyond Task 1 timing marks
- Produces: faster `hello→vd` and `vd→capture` on typical reconnects

- [ ] **Step 1: Write failing test for backoff schedule**

If the repo has no Mac unit-test target, add Android-side documentation twin is wrong. Prefer adding to `ConnectTiming.swift`:

```swift
enum VirtualDisplayRetry {
    static let maxAttempts = 8
    static func sleepSeconds(beforeAttempt attempt: Int) -> Double {
        precondition(attempt >= 0)
        if attempt == 0 { return 0 }
        return min(1.5, 0.25 * pow(2.0, Double(attempt - 1)))
    }
}
```

And add Android mirror for CI (same numbers) so we keep TDD in the Android suite:

Create `Android/app/src/main/java/com/peetzweg/opendisplay/session/VirtualDisplayRetryPolicy.kt`:

```kotlin
object VirtualDisplayRetryPolicy {
    const val MAX_ATTEMPTS = 8
    fun sleepSecondsBeforeAttempt(attempt: Int): Double {
        require(attempt >= 0)
        if (attempt == 0) return 0.0
        return minOf(1.5, 0.25 * Math.pow(2.0, (attempt - 1).toDouble()))
    }
}
```

Test:

```kotlin
class VirtualDisplayRetryPolicyTest {
    @Test
    fun backoff_rampsThenCaps() {
        assertEquals(0.0, VirtualDisplayRetryPolicy.sleepSecondsBeforeAttempt(0), 0.0)
        assertEquals(0.25, VirtualDisplayRetryPolicy.sleepSecondsBeforeAttempt(1), 0.0)
        assertEquals(0.5, VirtualDisplayRetryPolicy.sleepSecondsBeforeAttempt(2), 0.0)
        assertEquals(1.0, VirtualDisplayRetryPolicy.sleepSecondsBeforeAttempt(3), 0.0)
        assertEquals(1.5, VirtualDisplayRetryPolicy.sleepSecondsBeforeAttempt(4), 0.0)
        assertEquals(1.5, VirtualDisplayRetryPolicy.sleepSecondsBeforeAttempt(7), 0.0)
    }
}
```

- [ ] **Step 2: Run failing test**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests 'com.peetzweg.opendisplay.session.VirtualDisplayRetryPolicyTest'`

Expected: FAIL until policy file exists.

- [ ] **Step 3: Implement policy + swap MacSender loops**

Replace the fixed `Task.sleep(for: .seconds(2))` loop with:

```swift
for attempt in 0..<VirtualDisplayRetry.maxAttempts {
    let delay = VirtualDisplayRetry.sleepSeconds(beforeAttempt: attempt)
    if delay > 0 { try await Task.sleep(for: .seconds(delay)) }
    if stopped { return }
    vd = await MainActor.run { VirtualDisplay(...) }
    if vd != nil { break }
    Log.info("virtual display creation failed (attempt \(attempt + 1)) — retrying")
    await status("Preparing virtual display…")
}
```

In `findSCDisplay`, change poll from 250ms × 20 to 50ms × 40 (same ~2s budget, faster success when display appears early):

```swift
for _ in 0..<40 {
    let content = try await SCShareableContent.current
    if let display = content.displays.first(where: { $0.displayID == id }) {
        return display
    }
    try await Task.sleep(for: .milliseconds(50))
}
```

Keep Mac `VirtualDisplayRetry` numbers identical to Kotlin policy.

- [ ] **Step 4: Run tests**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests 'com.peetzweg.opendisplay.session.VirtualDisplayRetryPolicyTest'`

Expected: PASS

- [ ] **Step 5: Re-measure TTFF** (same procedure as Task 2 Step 3)

Expected: `hello→vd` and/or `vd→capture` drop vs baseline when retry was needed; cold path without retry unchanged or slightly better via SC poll.

- [ ] **Step 6: Commit**

```bash
git add Mac/ConnectTiming.swift Mac/MacSender.swift \
  Android/app/src/main/java/com/peetzweg/opendisplay/session/VirtualDisplayRetryPolicy.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/session/VirtualDisplayRetryPolicyTest.kt
git commit -m "perf(mac): faster virtual display retry and SCDisplay poll"
```

---

### Task 4: First-frame urgency (IDR + surface race)

**Files:**
- Modify: `Mac/MacSender.swift` (`startCapture` end; `becomeReady` already pushes IDR if `lastPixelBuffer` exists)
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/MainActivity.kt` (`onSurfaceReady` already stashes pending sync + sends `kf`)
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/session/ReceiverSession.kt` only if hello must advertise “ready for video” earlier

**Problem:** Streaming UI mounts after `connected`, so the opening IDR often arrives before `SurfaceView` exists. Chromebook recovers via `pendingSyncFrame` + delayed `kf`, but that adds 0.5–2s.

**Interfaces:**
- Produces: Mac forces IDR once capture is up **and** again 300ms later if still no phone activity is not available — instead: Mac schedules a single delayed IDR 250ms after `captureStarted` for Chromebook only.

- [ ] **Step 1: Write policy test for delayed IDR**

```kotlin
// Android/app/src/test/java/com/peetzweg/opendisplay/session/FirstFramePolicyTest.kt
package com.peetzweg.opendisplay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstFramePolicyTest {
    @Test
    fun chromebook_getsFollowUpIdrDelay() {
        assertEquals(250L, FirstFramePolicy.followUpIdrDelayMs(deviceKind = "Chromebook"))
        assertNull(FirstFramePolicy.followUpIdrDelayMs(deviceKind = "iPhone"))
        assertNull(FirstFramePolicy.followUpIdrDelayMs(deviceKind = null))
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests 'com.peetzweg.opendisplay.session.FirstFramePolicyTest'`

- [ ] **Step 3: Implement policy + Mac follow-up IDR**

```kotlin
object FirstFramePolicy {
    fun followUpIdrDelayMs(deviceKind: String?): Long? =
        if (deviceKind == "Chromebook") 250L else null
}
```

In Mac `startCapture` after capture starts (and encoder exists), if `lastHello?.device == "Chromebook"`:

```swift
needsKeyframe = true
if let pixelBuffer = lastPixelBuffer {
    encode(pixelBuffer, pts: CMClockGetTime(CMClockGetHostTimeClock()))
}
queue.asyncAfter(deadline: .now() + .milliseconds(250)) { [weak self] in
    guard let self, !self.stopped, self.connectionReady else { return }
    self.needsKeyframe = true
    if let pixelBuffer = self.lastPixelBuffer {
        self.encode(pixelBuffer, pts: CMClockGetTime(CMClockGetHostTimeClock()))
    }
}
```

Do **not** change Chromebook mid-session tear policy.

- [ ] **Step 4: Run tests + cold reconnect smoke**

Expected: Chromebook `ttff` improves when previous miss was “IDR before surface”; no green flash regression.

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/peetzweg/opendisplay/session/FirstFramePolicy.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/session/FirstFramePolicyTest.kt \
  Mac/MacSender.swift
git commit -m "perf(chromebook): follow-up IDR after capture for faster first paint"
```

---

### Task 5: Steady-state latency (TCP now; UDP gated)

**Files:**
- Modify: `Mac/MacSender.swift` only if TCP encode/send caps need Chromebook-specific tweak (`maxPendingSends`, keep-alive already 33ms)
- Modify: `Android/.../net/UdpJitterTiming.kt` only if measuring UDP path (`TARGET_DELAY_MS` already 16)
- Update: `superpowers/plans/2026-07-26-android-udp-latency-baseline.md` A/B table
- Read: `Mac/VideoRateController.swift`, `Android/.../session/VideoStallPolicy.kt` (already shipped)

**Problem:** Default WiFi video is still TCP (`udpAutoEnabled` false). Steady-state “connection feels slow” is often e2e latency under motion, not only TTFF.

- [ ] **Step 1: Record TCP baseline on device**

Follow `superpowers/plans/2026-07-26-android-udp-latency-baseline.md` Phase 0 exactly (Best / 60 / Standard / overlay ON). Fill the TCP baseline row.

- [ ] **Step 2: Optional explicit UDP A/B (no auto flip)**

```bash
defaults write sh.peet.opensidecar.mac videoTransport udp
# reconnect, measure, then:
defaults write sh.peet.opensidecar.mac videoTransport auto
```

Fill UDP row. Only if keep-UDP gate passes, a **later** commit may set `udpAutoEnabled` default true — **out of scope unless gate passes**; this task only measures and documents.

- [ ] **Step 3: If TCP e2e P50 is the pain and UDP not used — verify encoder low-latency**

Confirm `setupEncoder` still sets low-latency rate control / expected frame rate for Chromebook 60fps. If Chromebook session fell back to software decode (`VideoDecoder` log `falling back to software decode`), reduce bitrate via existing `StreamQuality.bitrate(forDeviceKind:)` path is already 18Mbps — do **not** raise bitrate for SW decode.

No code change required if logs show VDA + 60fps + e2e already < ~40ms; then TTFF tasks are the win.

- [ ] **Step 4: Commit docs only when A/B table filled**

```bash
git add superpowers/plans/2026-07-26-android-udp-latency-baseline.md
git commit -m "docs(udp): record Chromebook WiFi TCP/UDP latency A/B"
```

---

### Task 6: Verification checklist (manual)

**Files:** none (execution only)

- [ ] **Step 1: Cold connect ×3**

Force-stop Chromebook app → Mac Connect. Record `ttff` and `connectTiming` each time.

Pass criteria:

- Median Android `ttff` ≤ 3000ms on the same LAN used for baseline
- No solid green lasting >2s after first paint
- No mid-session TCP tear flash after paint

- [ ] **Step 2: Steady drag 20s**

Overlay: e2e P50/P95, stalls, FPS. Compare to Task 5 baseline.

- [ ] **Step 3: Long idle 10+ minutes**

Confirm stall watch / screen-off park from recent commits still recover (no frozen unclickable panel).

- [ ] **Step 4: Final note in plan A/B table**

| Path | Device | ttff median | e2e50 | e2e95 | stalls | notes |
|---|---|---|---|---|---|---|
| Baseline (before plan) | | | | | | |
| After Tasks 3–4 (TCP) | | | | | | |
| UDP explicit (Task 5) | | | | | | |

---

## A/B table (fill during execution)

| Path | Device | ttff median | e2e50 | e2e95 | stalls | notes |
|---|---|---|---|---|---|---|
| Baseline | Chromebook WiFi | SKIPPED (no device) | SKIPPED (no device) | SKIPPED (no device) | SKIPPED (no device) | Tasks 1–4 code ready for on-device fill |
| After VD/SC + follow-up IDR | Chromebook WiFi | SKIPPED (no device) | SKIPPED (no device) | SKIPPED (no device) | SKIPPED (no device) | Re-measure after Tasks 3–4 on hardware |
| UDP opt-in | Chromebook WiFi | SKIPPED (no device) | SKIPPED (no device) | SKIPPED (no device) | SKIPPED (no device) | Explicit UDP only; no `udpAutoEnabled` flip |

---

## Self-review

1. **Spec coverage:** User ask = improve slow Mac→Chromebook connection. Plan covers measurement (T1–2), TTFF waits (T3–4), steady-state/UDP gate (T5), verification (T6). No separate iOS work (constraint).
2. **Placeholders:** None — policies, tests, commands, and commit messages are concrete.
3. **Type consistency:** `ConnectTimingPolicy.SLOW_TTFF_MS`, `VirtualDisplayRetry` / `VirtualDisplayRetryPolicy` shared numbers, `FirstFramePolicy.followUpIdrDelayMs` → Mac 250ms delay.

**Out of scope (explicit):** Enabling `udpAutoEnabled` by default; redesigning Bonjour; raising Chromebook SW-decode bitrate; mid-session TCP tears.
