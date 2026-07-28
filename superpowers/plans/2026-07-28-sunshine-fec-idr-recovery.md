# Sunshine-Style FEC + IDR Recovery (Drop Packet NACK) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align OpenDisplay UDP video recovery with real Sunshine/Moonlight: keep ~20% Reed–Solomon FEC, remove classic packet NACK/retransmit, and recover unrecoverable frames via control-channel IDR (`kf`) only.

**Architecture:** Video stays on paced UDP with per-frame Reed–Solomon parity. The receiver reassembles shards and uses FEC when possible. If a frame cannot be completed after a short reorder window, drop it and send `{"type":"kf"}` over TCP — never ask the Mac to retransmit UDP datagrams. QoS reports `incompleteRate` (not `nackRate`) so AIMD bitrate reacts to real frame loss after FEC.

**Tech Stack:** Kotlin/JUnit 4 (Android), Swift/Network.framework (Mac), shared wire JSON on TCP, UDP datagrams with 32-byte OpenDisplay header + RS parity.

**Supersedes (recovery path only):** `superpowers/plans/2026-07-28-sunshine-udp-android-chromebook.md` Tasks that added `WireMessage.nack`, `UdpVideoSender.handleNack` / retransmit cache, and qos `nackRate`. Keep FEC, pacing, jitter buffer, transport negotiation, and TCP fallback from that plan.

**Spec source:** Update `superpowers/specs/2026-07-28-sunshine-udp-transport.md` in Task 1; rationale in `superpowers/SUNSHINE_UDP_NOTES.md`.

## Global Constraints

- Android/Chromebook WiFi UDP video only; iOS + USB TCP video unchanged.
- Control JSON stays on TCP `:9000`; video datagrams on negotiated UDP port.
- Default FEC remains `fecPct = 20` (Sunshine `fec_percentage` default).
- No packet-level NACK or UDP retransmit cache — FEC first, then IDR/`kf`.
- LTR / Sunshine RFI frame types are **out of scope** (YAGNI); existing `kf` IDR is the encoder recovery signal.
- Additive wire cleanup: stop sending `nack`; Mac ignores unknown types already. Prefer removing `WireMessage.nack` rather than keeping a dead constant.
- Android tests: JUnit 4 via `cd Android && ./gradlew :app:testDebugUnitTest --tests <class>`.
- Mac gate: `make mac` (no XCTest in CI; mirror policy in Kotlin where needed).
- Do not raise `WireProtocol.version` for this change (removing an optional recovery path is compatible with older peers that never sent `nack`).

## File Structure

| File | Responsibility |
|---|---|
| `superpowers/specs/2026-07-28-sunshine-udp-transport.md` | Canonical recovery contract (FEC + kf, no NACK) |
| `superpowers/SUNSHINE_UDP_NOTES.md` | Correct “no packet NACK” wording |
| `Shared/Protocol.swift` | Drop `WireMessage.nack` |
| `Android/.../wire/WireProtocol.kt` | Drop `WireMessage.nack` |
| `Android/.../session/UdpHealthPolicy.kt` | qos map uses `incompleteRate`; keyframe policy unchanged |
| `Android/.../session/ReceiverSession.kt` | Incomplete → `kf` only; qos uses incomplete counters |
| `Android/.../net/JitterBuffer.kt` | Comments: incomplete = reorder give-up, not NACK |
| `Android/.../net/UdpJitterTiming.kt` | Reorder window (not RTT NACK wait) |
| `Mac/UdpVideoSender.swift` | Remove retransmit cache + `handleNack` |
| `Mac/MacSender.swift` | Remove `nack` demux; qos reads `incompleteRate` |
| `Mac/VideoRateController.swift` | AIMD uses `incompleteRate` instead of `nackRate` |
| `Android/.../net/VideoRatePolicy.kt` | Kotlin mirror of AIMD for unit tests |
| `COMPATIBILITY.md`, `Android/README.md`, Mac settings copy | Docs: FEC + IDR, not NACK |

---

### Task 1: Spec + notes — Sunshine recovery contract

**Files:**
- Modify: `superpowers/specs/2026-07-28-sunshine-udp-transport.md`
- Modify: `superpowers/SUNSHINE_UDP_NOTES.md`

**Interfaces:**
- Consumes: none
- Produces: written contract later tasks implement — Recovery = FEC then `kf`; qos field `incompleteRate`; no `nack` message

- [ ] **Step 1: Replace the Recovery section in the transport spec**

Replace the `## Recovery` block in `superpowers/specs/2026-07-28-sunshine-udp-transport.md` with:

```markdown
## Recovery

Sunshine/Moonlight model (not WebRTC):

- **FEC first:** ~20% Reed–Solomon parity shards per video frame (`fecPct`, Sunshine `fec_percentage` default). Receiver reconstructs missing data shards from parity when possible.
- **No packet NACK:** Do **not** send `{"type":"nack",...}` and do **not** cache/retransmit UDP datagrams. Late shards may still arrive within a short reorder window; after that the frame is abandoned.
- **IDR recovery:** When a frame is incomplete after the reorder window, late-dropped, or a decode error breaks the reference chain, receiver sends `{"type":"kf"}` over TCP. Mac forces an IDR.
- **Disposable frames:** Prefer forward progress over perfect delivery; incomplete/late frames are dropped.
- **`qos` (~2Hz):** `{"type":"qos","lossPct":…,"jitterMs":…,"incompleteRate":…,"lateFrames":…,"fecRecoveries":…}` for AIMD bitrate and TCP fallback. `incompleteRate` = incompleteFrames / frames in the window (0 if frames == 0).
```

- [ ] **Step 2: Fix SUNSHINE_UDP_NOTES wording**

In `superpowers/SUNSHINE_UDP_NOTES.md`, replace the second bullet under Big Reasons with:

```markdown
- Reliability is added selectively instead of globally. Sequence numbers, timestamps, frame boundaries, loss detection, Reed–Solomon FEC, and fast IDR/keyframe recovery help without forcing the whole stream to stall. Sunshine does **not** rely on classic packet NACK retransmit for video; FEC absorbs small losses, then the client requests encoder-level recovery (IDR / RFI) over the control channel.
```

- [ ] **Step 3: Commit**

```bash
git add \
  superpowers/specs/2026-07-28-sunshine-udp-transport.md \
  superpowers/SUNSHINE_UDP_NOTES.md \
  superpowers/plans/2026-07-28-sunshine-fec-idr-recovery.md
git commit -m "$(cat <<'EOF'
docs(udp): specify Sunshine FEC + IDR recovery, not packet NACK

EOF
)"
```

---

### Task 2: qos `incompleteRate` + health policy tests

**Files:**
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/session/UdpHealthPolicy.kt`
- Modify: `Android/app/src/test/java/com/peetzweg/opendisplay/session/UdpHealthPolicyTest.kt`

**Interfaces:**
- Consumes: existing `shouldRequestKeyframe`, `shouldFallbackToTcp`, `FallbackTracker`
- Produces: `UdpHealthPolicy.qosMap(lossPct, jitterMs, incompleteRate, lateFrames, fecRecoveries): Map<String, Any>` with key `"incompleteRate"` (no `"nackRate"`); `UdpHealthPolicy.incompleteRate(incompleteFrames: Int, frames: Int): Double`

- [ ] **Step 1: Write the failing tests**

Replace `qos_map_contains_required_keys` and add rate helper tests in `UdpHealthPolicyTest.kt`:

```kotlin
package com.peetzweg.opendisplay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpHealthPolicyTest {
    @Test
    fun requests_keyframe_after_late_drops_or_incomplete() {
        assertTrue(UdpHealthPolicy.shouldRequestKeyframe(lateFrames = 2, incompleteFrames = 0, decodeErrors = 0))
        assertTrue(UdpHealthPolicy.shouldRequestKeyframe(lateFrames = 0, incompleteFrames = 1, decodeErrors = 0))
        assertFalse(UdpHealthPolicy.shouldRequestKeyframe(lateFrames = 0, incompleteFrames = 0, decodeErrors = 0))
    }

    @Test
    fun falls_back_to_tcp_on_sustained_high_loss() {
        assertFalse(UdpHealthPolicy.shouldFallbackToTcp(lossPct = 10.0, consecutiveBadWindows = 1))
        assertTrue(UdpHealthPolicy.shouldFallbackToTcp(lossPct = 25.0, consecutiveBadWindows = 3))
    }

    @Test
    fun falls_back_after_three_bad_qos_windows() {
        val policy = UdpHealthPolicy.FallbackTracker()
        repeat(3) { policy.noteWindow(lossPct = 30.0) }
        assertEquals("tcp", policy.preferredVideoTransport())
    }

    @Test
    fun incomplete_rate_is_incomplete_over_frames() {
        assertEquals(0.0, UdpHealthPolicy.incompleteRate(incompleteFrames = 3, frames = 0), 0.0)
        assertEquals(0.25, UdpHealthPolicy.incompleteRate(incompleteFrames = 1, frames = 4), 0.0)
    }

    @Test
    fun qos_map_uses_incomplete_rate_not_nack_rate() {
        val map = UdpHealthPolicy.qosMap(
            lossPct = 4.0,
            jitterMs = 12.0,
            incompleteRate = 0.05,
            lateFrames = 1,
            fecRecoveries = 3,
        )
        assertEquals("qos", map["type"])
        assertEquals(4.0, map["lossPct"])
        assertEquals(12.0, map["jitterMs"])
        assertEquals(0.05, map["incompleteRate"])
        assertEquals(1, map["lateFrames"])
        assertEquals(3, map["fecRecoveries"])
        assertFalse(map.containsKey("nackRate"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.UdpHealthPolicyTest`

Expected: FAIL — unresolved `incompleteRate` / wrong `qosMap` signature / still has `nackRate`.

- [ ] **Step 3: Implement minimal policy**

Replace `UdpHealthPolicy.kt` with:

```kotlin
package com.peetzweg.opendisplay.session

object UdpHealthPolicy {
    private const val LOSS_FALLBACK_PCT = 20.0
    private const val BAD_WINDOWS_FOR_FALLBACK = 3

    fun shouldRequestKeyframe(lateFrames: Int, incompleteFrames: Int, decodeErrors: Int): Boolean =
        lateFrames > 0 || incompleteFrames > 0 || decodeErrors > 0

    fun shouldFallbackToTcp(lossPct: Double, consecutiveBadWindows: Int): Boolean =
        lossPct >= LOSS_FALLBACK_PCT && consecutiveBadWindows >= BAD_WINDOWS_FOR_FALLBACK

    fun incompleteRate(incompleteFrames: Int, frames: Int): Double =
        if (frames > 0) incompleteFrames.toDouble() / frames.toDouble() else 0.0

    fun qosMap(
        lossPct: Double,
        jitterMs: Double,
        incompleteRate: Double,
        lateFrames: Int,
        fecRecoveries: Int,
    ): Map<String, Any> = mapOf(
        "type" to "qos",
        "lossPct" to lossPct,
        "jitterMs" to jitterMs,
        "incompleteRate" to incompleteRate,
        "lateFrames" to lateFrames,
        "fecRecoveries" to fecRecoveries,
    )

    class FallbackTracker {
        private var bad = 0
        private var preferred = "udp"

        fun noteWindow(lossPct: Double) {
            if (shouldFallbackToTcp(lossPct, bad + 1)) {
                bad += 1
                if (bad >= BAD_WINDOWS_FOR_FALLBACK) preferred = "tcp"
            } else if (lossPct < 5.0) {
                bad = 0
            } else {
                bad += 1
            }
        }

        fun preferredVideoTransport(): String = preferred

        fun reset() {
            bad = 0
            preferred = "udp"
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.UdpHealthPolicyTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add \
  Android/app/src/main/java/com/peetzweg/opendisplay/session/UdpHealthPolicy.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/session/UdpHealthPolicyTest.kt
git commit -m "$(cat <<'EOF'
feat(udp): report incompleteRate in qos instead of nackRate

EOF
)"
```

---

### Task 3: ReceiverSession — drop NACK, IDR-only on unrecoverable frames

**Files:**
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/session/ReceiverSession.kt`
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/wire/WireProtocol.kt`
- Modify: `Android/app/src/test/java/com/peetzweg/opendisplay/session/ReceiverSessionTest.kt` (add incomplete→kf test if hooks exist; otherwise extend with package-visible hooks already used by negotiation tests)

**Interfaces:**
- Consumes: `UdpHealthPolicy.incompleteRate`, `UdpHealthPolicy.qosMap`, `UdpHealthPolicy.shouldRequestKeyframe`
- Produces: `handleUdpIncomplete` sends at most one `{"type":"kf"}` per loss event (dedupe with `idrRequestedUdpFrames`); never sends `type=nack`; `publishQosWindow` sends `incompleteRate`

- [ ] **Step 1: Write the failing receiver incomplete test**

Append to `ReceiverSessionTest.kt` (reuses the existing `noopListener` and `testHookSendControl`):

```kotlin
@Test
fun udp_incomplete_requests_kf_not_nack() {
    val sent = mutableListOf<Map<String, Any>>()
    val session = ReceiverSession(listener = noopListener)
    session.testHookSendControl = { sent += it }

    session.testHookUdpIncomplete(
        streamId = 7,
        frameId = 42L,
        missingSeqs = intArrayOf(9002, 9003),
        isKeyframe = false,
    )

    val types = sent.map { it["type"] as String }
    assertTrue(types.contains("kf"))
    assertFalse(types.contains("nack"))
    assertFalse(sent.any { it.containsKey("missing") })
}
```

Add this package-visible hook on `ReceiverSession` in Step 3 (next to `fallbackToTcpVideoForTest`):

```kotlin
internal fun testHookUdpIncomplete(
    streamId: Int,
    frameId: Long,
    missingSeqs: IntArray,
    isKeyframe: Boolean,
) = handleUdpIncomplete(streamId, frameId, missingSeqs, isKeyframe)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.ReceiverSessionTest`

Expected: FAIL — still sends `nack`, or hook missing.

- [ ] **Step 3: Implement IDR-only incomplete handling**

In `WireProtocol.kt`, delete `const val nack = "nack"`.

In `ReceiverSession.kt`:

1. Rename `nackedUdpFrames` → `idrRequestedUdpFrames` and `qosNacksWindow` → remove; keep `qosIncompleteFramesWindow`.
2. Replace `handleUdpIncomplete` with:

```kotlin
private fun handleUdpIncomplete(
    streamId: Int,
    frameId: Long,
    missingSeqs: IntArray,
    isKeyframe: Boolean,
) {
    if (missingSeqs.isEmpty()) return
    val needKeyframe = synchronized(udpStateLock) {
        val need = shouldRequestKeyframeAfterUdpLoss(isKeyframe)
        val firstForFrame = idrRequestedUdpFrames.add(frameId)
        udpAwaitingKeyframe = true
        need || firstForFrame
    }
    if (needKeyframe) {
        sendControl(mapOf("type" to "kf"))
    }
}
```

3. Replace qos publish math:

```kotlin
val incompleteRate = UdpHealthPolicy.incompleteRate(window.incompleteFrames, window.frames)
sendControl(
    UdpHealthPolicy.qosMap(
        lossPct = snapshot.lossPct,
        jitterMs = snapshot.jitterMs,
        incompleteRate = incompleteRate,
        lateFrames = window.lateFrames,
        fecRecoveries = snapshot.fecRecoveries,
    ),
)
```

4. Remove `nacks` from `QosWindowCounters` and all `qosNacksWindow` increments/resets.
5. Clear `idrRequestedUdpFrames` on keyframe received (same place `udpAwaitingKeyframe = false`) and on `startUdpVideo` / `stopUdpVideo`.

- [ ] **Step 4: Run tests**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.ReceiverSessionTest`

Expected: PASS (including new incomplete test; existing negotiation still passes).

- [ ] **Step 5: Commit**

```bash
git add \
  Android/app/src/main/java/com/peetzweg/opendisplay/session/ReceiverSession.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/wire/WireProtocol.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/session/ReceiverSessionTest.kt
git commit -m "$(cat <<'EOF'
fix(udp): recover unrecoverable frames with kf only, drop packet NACK

EOF
)"
```

---

### Task 4: Reorder window (not NACK RTT wait)

**Files:**
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/net/UdpJitterTiming.kt`
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/net/JitterBuffer.kt` (comments only)
- Modify: `Android/app/src/test/java/com/peetzweg/opendisplay/net/UdpJitterTimingTest.kt`
- Modify: `Android/app/src/test/java/com/peetzweg/opendisplay/net/JitterBufferTest.kt`

**Interfaces:**
- Consumes: none
- Produces: `UdpJitterTiming.TARGET_DELAY_MS = 16`; `MAX_DELAY_MS = 32` (reorder give-up); `maxDelayMs(rttMs)` clamps to `[24, 40]` using `min(40, max(24, round(rttMs * 0.5)))` — half-RTT reorder, **not** RTT+20 NACK budget

- [ ] **Step 1: Write failing timing tests**

Replace `UdpJitterTimingTest.kt` with:

```kotlin
package com.peetzweg.opendisplay.net

import org.junit.Assert.assertEquals
import org.junit.Test

class UdpJitterTimingTest {
    @Test
    fun defaults_are_sunshine_small_jitter() {
        assertEquals(16L, UdpJitterTiming.TARGET_DELAY_MS)
        assertEquals(32L, UdpJitterTiming.MAX_DELAY_MS)
    }

    @Test
    fun max_delay_is_reorder_window_not_nack_rtt() {
        assertEquals(32L, UdpJitterTiming.maxDelayMs(null))
        assertEquals(24L, UdpJitterTiming.maxDelayMs(10.0))
        assertEquals(40L, UdpJitterTiming.maxDelayMs(100.0))
        assertEquals(30L, UdpJitterTiming.maxDelayMs(60.0))
    }
}
```

Rename `incomplete_waits_full_wifi_nack_window` in `JitterBufferTest.kt` to `incomplete_waits_reorder_window` and keep asserting give-up at `UdpJitterTiming.MAX_DELAY_MS`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.UdpJitterTimingTest --tests com.peetzweg.opendisplay.net.JitterBufferTest`

Expected: FAIL on new expected constants (still 60 / RTT+20).

- [ ] **Step 3: Implement reorder timing**

```kotlin
package com.peetzweg.opendisplay.net

/**
 * WiFi UDP jitter / reorder playout window (Sunshine-style: small hold).
 *
 * Incomplete wait is only for late shards / FEC assembly — not a packet-NACK
 * RTT budget. Playout delay is measured from arrival of a complete frame.
 */
object UdpJitterTiming {
    const val TARGET_DELAY_MS = 16L
    /** Default incomplete/reorder give-up when RTT unknown. */
    const val MAX_DELAY_MS = 32L
    const val MIN_MAX_DELAY_MS = 24L
    const val MAX_MAX_DELAY_MS = 40L

    /** Incomplete deadline ≈ half RTT, clamped — enough for reorder, not retransmit. */
    fun maxDelayMs(rttMs: Double?): Long {
        if (rttMs == null || rttMs <= 0) return MAX_DELAY_MS
        return (rttMs * 0.5).toLong().coerceIn(MIN_MAX_DELAY_MS, MAX_MAX_DELAY_MS)
    }
}
```

Update `JitterBuffer` KDoc to say incomplete frames are held until reorder give-up, then dropped (IDR requested by session) — remove “NACK until”.

- [ ] **Step 4: Run tests**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.UdpJitterTimingTest --tests com.peetzweg.opendisplay.net.JitterBufferTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add \
  Android/app/src/main/java/com/peetzweg/opendisplay/net/UdpJitterTiming.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/net/JitterBuffer.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/net/UdpJitterTimingTest.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/net/JitterBufferTest.kt
git commit -m "$(cat <<'EOF'
fix(udp): shorten incomplete wait to reorder window, not NACK RTT

EOF
)"
```

---

### Task 5: Mac — remove retransmit cache; AIMD on `incompleteRate`

**Files:**
- Modify: `Mac/UdpVideoSender.swift`
- Modify: `Mac/MacSender.swift`
- Modify: `Mac/VideoRateController.swift`
- Modify: `Shared/Protocol.swift`
- Create: `Android/app/src/main/java/com/peetzweg/opendisplay/net/VideoRatePolicy.kt`
- Create: `Android/app/src/test/java/com/peetzweg/opendisplay/net/VideoRatePolicyTest.kt`

**Interfaces:**
- Consumes: qos JSON `incompleteRate` (Double); ignore legacy `nackRate` if present
- Produces: `VideoRateController.next(lossPct:jitterMs:incompleteRate:) -> RateAction`; Kotlin `VideoRatePolicy.next(...)` with identical thresholds; `UdpVideoSender` has no `handleNack` / no retransmit cache

- [ ] **Step 1: Write failing Kotlin AIMD mirror tests**

Create `VideoRatePolicyTest.kt`:

```kotlin
package com.peetzweg.opendisplay.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRatePolicyTest {
    @Test
    fun high_incomplete_rate_forces_keyframe_without_tcp_fallback() {
        val policy = VideoRatePolicy(initialBitrate = 24_000_000)
        val action = policy.next(lossPct = 1.0, jitterMs = 10.0, incompleteRate = 0.2)
        assertNull(action.bitrate)
        assertTrue(action.forceKeyframe)
        assertFalse(action.preferTcpNextSession)
    }

    @Test
    fun sustained_high_loss_prefers_tcp() {
        val policy = VideoRatePolicy(initialBitrate = 24_000_000)
        val action = policy.next(lossPct = 25.0, jitterMs = 10.0, incompleteRate = 0.0)
        assertTrue(action.forceKeyframe)
        assertTrue(action.preferTcpNextSession)
    }

    @Test
    fun low_loss_climbs_bitrate() {
        val policy = VideoRatePolicy(initialBitrate = 24_000_000)
        val action = policy.next(lossPct = 1.0, jitterMs = 10.0, incompleteRate = 0.0)
        assertEquals(26_000_000, action.bitrate)
        assertFalse(action.forceKeyframe)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.VideoRatePolicyTest`

Expected: FAIL — `VideoRatePolicy` missing.

- [ ] **Step 3: Implement Kotlin mirror + Swift changes**

`VideoRatePolicy.kt`:

```kotlin
package com.peetzweg.opendisplay.net

data class RateAction(
    val bitrate: Int?,
    val forceKeyframe: Boolean,
    val preferTcpNextSession: Boolean,
)

/** AIMD mirror of Mac `VideoRateController` — incompleteRate replaces nackRate. */
class VideoRatePolicy(initialBitrate: Int) {
    private var bitrate: Int = maxOf(2_000_000, initialBitrate)

    fun next(lossPct: Double, jitterMs: Double, incompleteRate: Double): RateAction {
        if (lossPct >= 20.0) {
            return RateAction(bitrate = null, forceKeyframe = true, preferTcpNextSession = true)
        }
        if (lossPct > 8.0 || jitterMs > 30.0) {
            bitrate = maxOf(4_000_000, bitrate - 2_000_000)
            return RateAction(bitrate = bitrate, forceKeyframe = false, preferTcpNextSession = false)
        }
        if (incompleteRate > 0.15) {
            return RateAction(bitrate = null, forceKeyframe = true, preferTcpNextSession = false)
        }
        if (lossPct < 2.0 && jitterMs < 15.0) {
            bitrate = minOf(bitrate + 2_000_000, 40_000_000)
            return RateAction(bitrate = bitrate, forceKeyframe = false, preferTcpNextSession = false)
        }
        return RateAction(bitrate = null, forceKeyframe = false, preferTcpNextSession = false)
    }

    companion object {
        const val UDP_INITIAL_BITRATE = 24_000_000
    }
}
```

`VideoRateController.swift` — change signature and threshold:

```swift
func next(lossPct: Double, jitterMs: Double, incompleteRate: Double) -> RateAction {
    if lossPct >= 20 {
        return RateAction(bitrate: nil, forceKeyframe: true, preferTcpNextSession: true)
    }
    if lossPct > 8 || jitterMs > 30 {
        bitrate = max(4_000_000, bitrate - 2_000_000)
        return RateAction(bitrate: bitrate, forceKeyframe: false, preferTcpNextSession: false)
    }
    if incompleteRate > 0.15 {
        return RateAction(bitrate: nil, forceKeyframe: true, preferTcpNextSession: false)
    }
    if lossPct < 2 && jitterMs < 15 {
        bitrate = min(bitrate + 2_000_000, 40_000_000)
        return RateAction(bitrate: bitrate, forceKeyframe: false, preferTcpNextSession: false)
    }
    return RateAction(bitrate: nil, forceKeyframe: false, preferTcpNextSession: false)
}
```

In `MacSender.swift` qos case:

```swift
let incompleteRate = (obj["incompleteRate"] as? NSNumber)?.doubleValue
    ?? (obj["nackRate"] as? NSNumber)?.doubleValue
    ?? 0
let action = controller.next(lossPct: lossPct, jitterMs: jitterMs, incompleteRate: incompleteRate)
```

Delete the entire `case WireMessage.nack:` branch.

In `UdpVideoSender.swift`:
- Remove `retransmitCache`, `retransmitCacheOrder`, `retransmitCacheLimit`, `cacheLocked`, `handleNack`.
- Delete the `cacheLocked(seq:pkt.seq, datagram:pkt.datagram)` call near the send-path packaging loop (~line 158).
- Update file comment to: `Paced UDP video sender with Reed-Solomon FEC (no packet retransmit).`

In `Shared/Protocol.swift`, delete `static let nack = "nack"`.

- [ ] **Step 4: Run Android tests + Mac build**

Run:

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.VideoRatePolicyTest
cd .. && make mac
```

Expected: PASS unit tests; Mac build succeeds.

- [ ] **Step 5: Commit**

```bash
git add \
  Android/app/src/main/java/com/peetzweg/opendisplay/net/VideoRatePolicy.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/net/VideoRatePolicyTest.kt \
  Mac/VideoRateController.swift \
  Mac/UdpVideoSender.swift \
  Mac/MacSender.swift \
  Shared/Protocol.swift
git commit -m "$(cat <<'EOF'
refactor(udp): drop Mac NACK retransmit; AIMD on incompleteRate

EOF
)"
```

---

### Task 6: Docs + Mac UI copy

**Files:**
- Modify: `COMPATIBILITY.md` (Protocol 3 bullet ~lines 160–166)
- Modify: `Android/README.md` (WiFi UDP section)
- Modify: `Mac/OpenSidecarMacApp.swift` (settings string mentioning FEC/NACK)

**Interfaces:**
- Consumes: Task 1 recovery contract
- Produces: user-facing docs that say FEC + IDR/`kf`, not NACK retransmit

- [ ] **Step 1: Update COMPATIBILITY.md Protocol 3 bullet**

Replace the Protocol 3 sentence with:

```markdown
- **Protocol 3 (additive):** Android/Chromebook WiFi may negotiate **UDP video**
  with ~20% Reed–Solomon FEC, paced sends, jitter/reorder buffer, IDR recovery
  via `kf` when FEC cannot rebuild a frame, and qos-driven bitrate
  (`incompleteRate`, `fecRecoveries`). Control JSON stays on TCP. There is
  **no** packet NACK / UDP retransmit. Default `videoTransport` auto selects
  TCP until the Chromebook keep-UDP gate passes; opt in with
  `defaults write com.peetzweg.opensidecar.mac videoTransport udp` or the Mac app
  Settings picker. Sustained high loss falls back to TCP video mid-session.
  USB and iOS remain TCP video.
```

- [ ] **Step 2: Update Android/README.md WiFi UDP section**

```markdown
On WiFi, the Mac and receiver may negotiate **UDP video** (protocol 3) with
~20% Reed–Solomon FEC, paced sends, a small jitter/reorder buffer, IDR
recovery (`kf`) when a frame cannot be rebuilt, and qos-driven bitrate.
Control JSON always stays on the TCP control socket. OpenDisplay does **not**
retransmit lost UDP video packets (Sunshine-style: FEC first, then keyframe).
```

- [ ] **Step 3: Fix Mac settings copy**

In `Mac/OpenSidecarMacApp.swift`, change the string that says `FEC/NACK` to:

```swift
return "Negotiate UDP video with FEC + IDR recovery on Android/Chromebook WiFi; falls back to TCP mid-session on sustained loss."
```

- [ ] **Step 4: Commit**

```bash
git add COMPATIBILITY.md Android/README.md Mac/OpenSidecarMacApp.swift
git commit -m "$(cat <<'EOF'
docs(udp): describe FEC + IDR recovery instead of packet NACK

EOF
)"
```

---

### Task 7: Full verification gate

**Files:**
- None new (run existing suites)

- [ ] **Step 1: Android unit tests for touched packages**

Run:

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.UdpHealthPolicyTest --tests com.peetzweg.opendisplay.session.ReceiverSessionTest --tests com.peetzweg.opendisplay.net.UdpJitterTimingTest --tests com.peetzweg.opendisplay.net.JitterBufferTest --tests com.peetzweg.opendisplay.net.VideoRatePolicyTest --tests com.peetzweg.opendisplay.net.UdpVideoFecTest
```

Expected: PASS (FEC suite still green).

- [ ] **Step 2: Grep gate — no live packet NACK path**

Run:

```bash
rg -n 'WireMessage\.nack|handleNack|retransmitCache|"nackRate"|type.*nack' \
  Android/app/src/main Mac Shared \
  --glob '!**/build/**'
```

Expected: no matches in production sources (tests must not assert on sending `nack`). Docs under `superpowers/plans/2026-07-28-sunshine-udp-android-chromebook.md` may still mention historical NACK — leave that file, do not resurrect the path.

- [ ] **Step 3: Mac build**

Run: `make mac`

Expected: success.

- [ ] **Step 4: Manual Chromebook smoke (when device available)**

1. Mac Settings → WiFi video = `udp`.
2. Stream to Chromebook WiFi; confirm picture; briefly congest WiFi (or airplane-mode blip).
3. Confirm stream recovers via keyframe (brief glitch OK) without relying on retransmit.
4. Confirm Mac logs show qos with `incompleteRate` / `fecRecoveries`, no `nack` control messages.

- [ ] **Step 5: Final commit only if Step 4 forced doc tweaks; otherwise done**

If smoke notes change defaults or copy, commit those alone; otherwise no empty commit.

---

## Self-Review

1. **Spec coverage:** FEC 20%, no packet NACK, IDR/`kf`, reorder window, qos `incompleteRate`, AIMD, docs, Mac cache removal — each has a task.
2. **Placeholders:** none; concrete code and commands included.
3. **Type consistency:** `incompleteRate: Double` in Kotlin qos map, Swift `VideoRateController.next`, and `VideoRatePolicy.next`; `kf` remains the IDR control type; `WireMessage.nack` removed on both platforms.
