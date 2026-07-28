# Sunshine-Style UDP Transport (Android/Chromebook) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Android/Chromebook WiFi the Sunshine/Moonlight-class UDP machinery from `superpowers/SUNSHINE_UDP_NOTES.md` — selective reliability, separate control, paced sends, FEC, NACK/retransmit, jitter buffering with late-frame drops, adaptive bitrate, and fast keyframe recovery — without breaking iOS or USB TCP.

**Architecture:** Keep TCP for discovery, hello/welcome, input, sleep/wake, stats, NACK, and keyframe requests. Move only video to packetized UDP on Android/Chromebook WiFi. Mac packetizes Annex-B with Reed–Solomon FEC, paces datagrams to the wire budget, caches recent packets for NACK retransmit, and adapts bitrate from receiver `qos`/`videoStats`. Android reassembles shards (FEC recover when possible), holds a small jitter buffer, drops late/incomplete frames, asks for IDR when the reference chain breaks, and falls back to TCP video when UDP is unhealthy. iOS and `adb forward` USB stay on TCP video forever in this plan.

**Tech Stack:** Swift (`Network.framework` UDP, VideoToolbox, ScreenCaptureKit), Kotlin (`DatagramSocket`/`DatagramChannel`, MediaCodec, JUnit), existing length-prefixed JSON control on TCP `:9000`, H.264 Annex-B, Bonjour/`_opensidecar._tcp`.

**Prior art:** Branch `udp` / commit `2fee387` shipped optional UDP+FEC with pacing, but field Chromebook WiFi saw ~93% loss vs ~14ms e2e on TCP because NACK, jitter playout, late-frame drops, and congestion feedback were missing. This plan reuses the shard/FEC maths shape from that experiment and adds the missing Sunshine machinery before any default-on rollout.

**Supersedes:** `docs/superpowers/plans/2026-07-28-android-wifi-udp-transport.md` (deferred FEC; incomplete pacing/NACK). Spec source of truth for why: `superpowers/SUNSHINE_UDP_NOTES.md`. Keep-UDP gate checklist: `superpowers/plans/2026-07-26-android-udp-latency-baseline.md` (restore from `udp` branch if missing on this branch).

## Global Constraints

- Spec principles: `superpowers/SUNSHINE_UDP_NOTES.md` — all eight “Big Reasons” are in scope for this plan (including FEC; not deferred).
- Keep `OpenSidecarMac` on `macOS 14.0` and `OpenSidecariOS` on `iOS 15.0` from `project.yml`.
- Do not change iOS or USB/`adb forward` video path; those remain framed TCP.
- Additive protocol upgrade per `COMPATIBILITY.md`: bump `WireProtocol.version` to **3**; keep `minSupportedPeer = 1`.
- Codec stays H.264 Annex-B; no HEVC/AV1.
- Control JSON types already in use stay reliable on TCP: `hello`, `welcome`, `updateRequired`, `sleeping`, `closing`, `hostSleeping`, `bye`, `touch`, `scroll`, `ping`, `pong`, `stats`, `kf`.
- Default preference for `videoTransport` remains **`auto` → TCP** until the keep-UDP gate in the baseline checklist passes on Chromebook WiFi; UDP is opt-in via `udp` until then.
- Plans/specs live under `superpowers/` (`docs/` is GitHub Pages output).
- Android unit tests use **JUnit 4** (`org.junit.Test` / `Assert.*`), matching existing `ReceiverSessionTest`.
- Prefer `./gradlew :app:testDebugUnitTest` for JVM logic; `make mac` / `make android` for build gates; device A/B is manual.

---

## File Structure

| Path | Responsibility |
|---|---|
| `superpowers/specs/2026-07-28-sunshine-udp-transport.md` | Canonical packet + control negotiation spec |
| `Shared/Protocol.swift` | `WireProtocol.version = 3`, new `WireMessage` constants |
| `Shared/UdpVideo.swift` | Shared packet header, FEC helpers, packager (Mac+tests) |
| `Mac/UdpVideoSender.swift` | UDP connect, pace, send, retransmit cache |
| `Mac/VideoRateController.swift` | Loss/jitter → bitrate / fps / force-IDR |
| `Mac/MacSender.swift` | Transport selection; feed UDP or TCP; handle NACK/qos |
| `Mac/OpenSidecarMacApp.swift` | `videoTransport` preference surface if needed |
| `Android/.../wire/WireProtocol.kt` | Mirror `pv=3` + message constants |
| `Android/.../net/UdpVideoProtocol.kt` | Header encode/decode (Android twin of Shared) |
| `Android/.../net/ReedSolomon.kt` | GF(256) RS encode/decode for FEC |
| `Android/.../net/UdpVideoPackager.kt` | Split AU → data+parity shards (tests / symmetry) |
| `Android/.../net/UdpFrameAssembler.kt` | Reassemble + FEC recover → Annex-B AU |
| `Android/.../net/JitterBuffer.kt` | Small playout delay; drop late/incomplete |
| `Android/.../net/UdpVideoReceiver.kt` | Datagram read loop → assembler → jitter → callback |
| `Android/.../session/ReceiverSession.kt` | TCP control; offer/select; NACK/qos; UDP lifecycle |
| `Android/.../session/UdpHealthPolicy.kt` | When to NACK, IDR, or fall back to TCP |
| `Android/.../video/VideoDecoder.kt` | Consume assembled AUs; keep Chromebook recover |
| `Android/.../MainActivity.kt` | Wire UDP frames into decoder; transport preference |
| `COMPATIBILITY.md` / `README.md` / `Android/README.md` | Document offer/select, fallback, knobs |
| Tests under `Android/app/src/test/java/.../net/` and `.../session/` | Packet, FEC, assembler, jitter, health, negotiation |

---

### Task 1: Spec + Protocol V3 Constants + Packet Header

**Files:**
- Create: `superpowers/specs/2026-07-28-sunshine-udp-transport.md`
- Modify: `Shared/Protocol.swift`
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/wire/WireProtocol.kt`
- Create: `Shared/UdpVideo.swift` (header + constants only in this task)
- Create: `Android/app/src/main/java/com/peetzweg/opendisplay/net/UdpVideoProtocol.kt`
- Test: `Android/app/src/test/java/com/peetzweg/opendisplay/net/UdpVideoProtocolTest.kt`

**Interfaces:**
- Consumes: existing `WireProtocol` / `WireMessage`
- Produces:
  - `WireProtocol.version == 3`
  - `WireMessage.transportOffer = "transportOffer"`
  - `WireMessage.transportSelected = "transportSelected"`
  - `WireMessage.nack = "nack"`
  - `WireMessage.qos = "qos"`
  - Kotlin `UdpVideoProtocol.HEADER_SIZE = 32`
  - Header fields: `version`, `flags`, `seq`, `frameId`, `shardIndex`, `dataShardCount`, `fecPct`, `captureMs`, `sendMs`
  - Flags: `KEYFRAME=0x01`, `FEC_PARITY=0x02`, `START=0x04`, `END=0x08`

- [ ] **Step 1: Write the failing packet-header test**

```kotlin
package com.peetzweg.opendisplay.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UdpVideoProtocolTest {
    @Test
    fun header_roundTrips() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val dgram = UdpVideoProtocol.buildDatagram(
            flags = UdpVideoProtocol.FLAG_KEYFRAME or UdpVideoProtocol.FLAG_START or UdpVideoProtocol.FLAG_END,
            seq = 9001,
            frameId = 42L,
            shardIndex = 0,
            dataShardCount = 1,
            fecPct = 20,
            captureMs = 1_000L,
            sendMs = 1_012L,
            payload = payload,
        )
        val header = UdpVideoProtocol.decodeHeader(dgram)!!
        assertEquals(UdpVideoProtocol.VERSION, header.version)
        assertEquals(9001, header.seq)
        assertEquals(42L, header.frameId)
        assertEquals(0, header.shardIndex)
        assertEquals(1, header.dataShardCount)
        assertEquals(20, header.fecPct)
        assertEquals(1_000L, header.captureMs)
        assertEquals(1_012L, header.sendMs)
        assertEquals(true, header.isKeyframe)
        assertArrayEquals(
            payload,
            dgram.copyOfRange(UdpVideoProtocol.HEADER_SIZE, dgram.size),
        )
    }

    @Test
    fun decodeHeader_rejectsWrongVersion() {
        val bad = ByteArray(UdpVideoProtocol.HEADER_SIZE)
        bad[0] = 99
        assertNull(UdpVideoProtocol.decodeHeader(bad))
    }

    @Test
    fun parityShardCount_twentyPercent() {
        assertEquals(2, UdpVideoProtocol.parityShardCount(dataShardCount = 10, fecPct = 20))
        assertEquals(0, UdpVideoProtocol.parityShardCount(dataShardCount = 10, fecPct = 0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.UdpVideoProtocolTest`

Expected: FAIL with unresolved reference `UdpVideoProtocol`.

- [ ] **Step 3: Write spec stub, protocol constants, and header helpers**

Create `superpowers/specs/2026-07-28-sunshine-udp-transport.md` with these normative rules (copy into the file verbatim):

```markdown
# Sunshine UDP transport (protocol 3)

## Scope
Android/Chromebook WiFi video over UDP. TCP control on :9000. iOS + USB TCP unchanged.

## Negotiation
- Hello may include `"video":["tcp","udp"]` and `"udpPort":<u16>`.
- Mac sends `transportOffer` when it will try UDP: `{"type":"transportOffer","video":["udp","tcp"],"control":"tcp","udpPort":9001,"streamId":N,"fecPct":20}`.
- Receiver replies `transportSelected`: `{"type":"transportSelected","video":"udp"|"tcp","streamId":N}`.
- If either side selects `tcp`, video stays on the existing framed TCP path.

## Datagram (32-byte header + payload)
| Offset | Type | Field |
|---|---|---|
| 0 | u8 | version (=1 for UDP framing; independent of WireProtocol pv) |
| 1 | u8 | flags |
| 2 | u16 BE | seq |
| 4 | u32 BE | frameId |
| 8 | u16 BE | shardIndex |
| 10 | u16 BE | dataShardCount |
| 12 | u8 | fecPct |
| 13 | u8 | reserved |
| 14 | u16 BE | reserved2 |
| 16 | u64 BE | captureMs |
| 24 | u64 BE | sendMs |
| 32 | bytes | payload |

Flags: KEYFRAME 0x01, FEC_PARITY 0x02, START 0x04, END 0x08.

## Recovery
- FEC: ~20% Reed–Solomon parity shards per frame (Sunshine default).
- NACK: `{"type":"nack","streamId":N,"missing":[seq,...]}` over TCP; Mac retransmits from cache.
- Late/incomplete frames are dropped; receiver requests `kf` when reference chain breaks.
- `qos`: `{"type":"qos","lossPct":…,"jitterMs":…,"nackRate":…,"lateFrames":…}` ~2Hz for AIMD bitrate.
```

Then implement constants + header:

```swift
// Shared/Protocol.swift — bump + new messages
enum WireProtocol {
    static let version = 3
    static let minSupportedPeer = 1
    static let assumedWhenAbsent = 1
}

enum WireMessage {
    static let welcome = "welcome"
    static let updateRequired = "updateRequired"
    static let sleeping = "sleeping"
    static let closing = "closing"
    static let hostSleeping = "hostSleeping"
    static let bye = "bye"
    static let transportOffer = "transportOffer"
    static let transportSelected = "transportSelected"
    static let nack = "nack"
    static let qos = "qos"
}
```

```swift
// Shared/UdpVideo.swift (header section)
import Foundation

enum UdpVideoProtocol {
    static let version: UInt8 = 1
    static let headerSize = 32
    static let maxDatagram = 1200
    static let maxPayload = maxDatagram - headerSize
    static let defaultFecPct = 20
    static let maxRsShards = 255

    static let flagKeyframe: UInt8 = 0x01
    static let flagFecParity: UInt8 = 0x02
    static let flagStart: UInt8 = 0x04
    static let flagEnd: UInt8 = 0x08

    static func parityShardCount(dataShardCount: Int, fecPct: Int) -> Int {
        guard fecPct > 0, dataShardCount > 0 else { return 0 }
        let parity = (dataShardCount * fecPct + 99) / 100
        if dataShardCount + parity > maxRsShards { return 0 }
        return parity
    }
}
```

```kotlin
// Android/.../net/UdpVideoProtocol.kt
package com.peetzweg.opendisplay.net

object UdpVideoProtocol {
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 32
    const val MAX_DATAGRAM: Int = 1200
    const val MAX_PAYLOAD: Int = MAX_DATAGRAM - HEADER_SIZE
    const val DEFAULT_FEC_PCT: Int = 20
    const val MAX_RS_SHARDS: Int = 255

    const val FLAG_KEYFRAME: Int = 0x01
    const val FLAG_FEC_PARITY: Int = 0x02
    const val FLAG_START: Int = 0x04
    const val FLAG_END: Int = 0x08

    data class Header(
        val version: Int,
        val flags: Int,
        val seq: Int,
        val frameId: Long,
        val shardIndex: Int,
        val dataShardCount: Int,
        val fecPct: Int,
        val captureMs: Long,
        val sendMs: Long,
    ) {
        val isKeyframe: Boolean get() = flags and FLAG_KEYFRAME != 0
        val isParity: Boolean get() = flags and FLAG_FEC_PARITY != 0
        val isStart: Boolean get() = flags and FLAG_START != 0
        val isEnd: Boolean get() = flags and FLAG_END != 0
    }

    fun buildDatagram(
        flags: Int,
        seq: Int,
        frameId: Long,
        shardIndex: Int,
        dataShardCount: Int,
        fecPct: Int,
        captureMs: Long,
        sendMs: Long,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLen: Int = payload.size,
    ): ByteArray {
        val out = ByteArray(HEADER_SIZE + payloadLen)
        encodeHeader(flags, seq, frameId, shardIndex, dataShardCount, fecPct, captureMs, sendMs, out)
        System.arraycopy(payload, payloadOffset, out, HEADER_SIZE, payloadLen)
        return out
    }

    fun encodeHeader(
        flags: Int,
        seq: Int,
        frameId: Long,
        shardIndex: Int,
        dataShardCount: Int,
        fecPct: Int,
        captureMs: Long,
        sendMs: Long,
        out: ByteArray,
        offset: Int = 0,
    ) {
        require(out.size >= offset + HEADER_SIZE)
        out[offset] = VERSION.toByte()
        out[offset + 1] = flags.toByte()
        writeU16(out, offset + 2, seq)
        writeU32(out, offset + 4, frameId)
        writeU16(out, offset + 8, shardIndex)
        writeU16(out, offset + 10, dataShardCount)
        out[offset + 12] = fecPct.toByte()
        out[offset + 13] = 0
        writeU16(out, offset + 14, 0)
        writeU64(out, offset + 16, captureMs)
        writeU64(out, offset + 24, sendMs)
    }

    fun decodeHeader(data: ByteArray, offset: Int = 0): Header? {
        if (data.size < offset + HEADER_SIZE) return null
        val version = data[offset].toInt() and 0xFF
        if (version != VERSION) return null
        return Header(
            version = version,
            flags = data[offset + 1].toInt() and 0xFF,
            seq = readU16(data, offset + 2),
            frameId = readU32(data, offset + 4),
            shardIndex = readU16(data, offset + 8),
            dataShardCount = readU16(data, offset + 10),
            fecPct = data[offset + 12].toInt() and 0xFF,
            captureMs = readU64(data, offset + 16),
            sendMs = readU64(data, offset + 24),
        )
    }

    fun parityShardCount(dataShardCount: Int, fecPct: Int): Int {
        if (fecPct <= 0 || dataShardCount <= 0) return 0
        val parity = (dataShardCount * fecPct + 99) / 100
        if (dataShardCount + parity > MAX_RS_SHARDS) return 0
        return parity
    }

    fun splitPayload(au: ByteArray, maxPayload: Int = MAX_PAYLOAD): List<ByteArray> {
        require(maxPayload > 0)
        if (au.isEmpty()) return listOf(ByteArray(0))
        val shards = ArrayList<ByteArray>((au.size + maxPayload - 1) / maxPayload)
        var off = 0
        while (off < au.size) {
            val len = minOf(maxPayload, au.size - off)
            shards.add(au.copyOfRange(off, off + len))
            off += len
        }
        return shards
    }

    private fun writeU16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(out: ByteArray, offset: Int, value: Long) {
        out[offset] = ((value ushr 24) and 0xFF).toByte()
        out[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeU64(out: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            out[offset + i] = ((value ushr ((7 - i) * 8)) and 0xFF).toByte()
        }
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readU32(data: ByteArray, offset: Int): Long =
        ((data[offset].toInt() and 0xFF).toLong() shl 24) or
            ((data[offset + 1].toInt() and 0xFF).toLong() shl 16) or
            ((data[offset + 2].toInt() and 0xFF).toLong() shl 8) or
            (data[offset + 3].toInt() and 0xFF).toLong()

    private fun readU64(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (data[offset + i].toInt() and 0xFF).toLong()
        }
        return v
    }
}
```

Mirror `WireProtocol.version = 3` and the four new `WireMessage` strings in `Android/.../wire/WireProtocol.kt`.

Ensure `project.yml` Mac target already includes `Shared/` (it does) so `UdpVideo.swift` compiles once header encode is filled in Task 3 — for this task, Swift file may only hold constants; full `buildDatagram` lands with the packager.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.UdpVideoProtocolTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add superpowers/specs/2026-07-28-sunshine-udp-transport.md \
  Shared/Protocol.swift Shared/UdpVideo.swift \
  Android/app/src/main/java/com/peetzweg/opendisplay/wire/WireProtocol.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/net/UdpVideoProtocol.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/net/UdpVideoProtocolTest.kt
git commit -m "$(cat <<'EOF'
feat(protocol): add Sunshine UDP packet header and pv=3 messages

EOF
)"
```

---

### Task 2: Reed–Solomon FEC + Frame Packager/Assembler

**Files:**
- Create: `Android/app/src/main/java/com/peetzweg/opendisplay/net/ReedSolomon.kt`
- Create: `Android/app/src/main/java/com/peetzweg/opendisplay/net/UdpVideoPackager.kt`
- Create: `Android/app/src/main/java/com/peetzweg/opendisplay/net/UdpFrameAssembler.kt`
- Extend: `Shared/UdpVideo.swift` with matching RS + packager
- Test: `Android/app/src/test/java/com/peetzweg/opendisplay/net/UdpVideoFecTest.kt`

**Interfaces:**
- Consumes: `UdpVideoProtocol.splitPayload`, `parityShardCount`, `buildDatagram`
- Produces:
  - `ReedSolomon.encode(dataShards, parityCount) -> parityShards`
  - `ReedSolomon.decode(shardsIncludingNulls, dataCount) -> dataShards?`
  - `UdpVideoPackager.packageFrame(au, frameId, startSeq, keyframe, fecPct, captureMs, sendMs)`
  - `UdpFrameAssembler.offer(datagram) -> AssembledFrame?`
  - `data class AssembledFrame(frameId, keyframe, annexB, captureMs, sendMs, recoveredByFec)`

- [ ] **Step 1: Write the failing FEC/assembler tests**

```kotlin
package com.peetzweg.opendisplay.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UdpVideoFecTest {
    @Test
    fun packageAndAssemble_roundTripWithoutLoss() {
        val au = ByteArray(2500) { i -> (i % 251).toByte() }
        val packaged = UdpVideoPackager.packageFrame(
            au = au,
            frameId = 7L,
            startSeq = 100,
            keyframe = true,
            fecPct = 20,
            captureMs = 50L,
            sendMs = 60L,
        )
        val assembler = UdpFrameAssembler()
        var out: UdpFrameAssembler.AssembledFrame? = null
        for (pkt in packaged.packets) {
            val got = assembler.offer(pkt.datagram)
            if (got != null) out = got
        }
        assertNotNull(out)
        assertArrayEquals(au, out!!.annexB)
        assertEquals(true, out.isKeyframe)
        assertEquals(false, out.recoveredByFec)
    }

    @Test
    fun assemble_recoversOneMissingDataShardViaFec() {
        val au = ByteArray(1800) { i -> (i % 199).toByte() }
        val packaged = UdpVideoPackager.packageFrame(
            au = au,
            frameId = 9L,
            startSeq = 1,
            keyframe = false,
            fecPct = 20,
            captureMs = 1L,
            sendMs = 2L,
        )
        val dataPackets = packaged.packets.filter { !it.isParity }
        val parityPackets = packaged.packets.filter { it.isParity }
        require(dataPackets.size >= 2 && parityPackets.isNotEmpty())

        val assembler = UdpFrameAssembler()
        var out: UdpFrameAssembler.AssembledFrame? = null
        // Drop data shard index 1; keep others + all parity.
        for ((idx, pkt) in dataPackets.withIndex()) {
            if (idx == 1) continue
            assembler.offer(pkt.datagram)?.let { out = it }
        }
        for (pkt in parityPackets) {
            assembler.offer(pkt.datagram)?.let { out = it }
        }
        assertNotNull(out)
        assertArrayEquals(au, out!!.annexB)
        assertEquals(true, out.recoveredByFec)
    }

    @Test
    fun assemble_returnsNullWhenTooManyShardsMissing() {
        val au = ByteArray(3000) { 7 }
        val packaged = UdpVideoPackager.packageFrame(
            au = au,
            frameId = 3L,
            startSeq = 0,
            keyframe = false,
            fecPct = 20,
            captureMs = 1L,
            sendMs = 2L,
        )
        val assembler = UdpFrameAssembler()
        // Offer only the first data shard — far below recoverability.
        assertNull(assembler.offer(packaged.packets.first().datagram))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.UdpVideoFecTest`

Expected: FAIL with unresolved references for `ReedSolomon` / `UdpVideoPackager` / `UdpFrameAssembler`.

- [ ] **Step 3: Implement RS + packager + assembler**

Port the GF(256) tables and encode/decode from `udp` branch `ReedSolomon.kt` / `Shared/UdpVideo.swift` `ReedSolomonFEC`, adjusting for the 32-byte header (payload region unchanged).

`UdpVideoPackager.packageFrame` must:

1. `splitPayload(au)` → data shard payloads (pad to equal length for RS).
2. `parity = ReedSolomon.encode(paddedData, parityShardCount)`.
3. Emit one datagram per data shard (`FLAG_START` on first, `FLAG_END` on last data shard if no need to mark parity), then parity datagrams with `FLAG_FEC_PARITY`.
4. Assign monotonically increasing `seq` across the frame; return `nextSeq`.

`UdpFrameAssembler.offer`:

1. Parse header; ignore wrong version / foreign `frameId` after advancing.
2. Store shard by `shardIndex` (data `0..<dataShardCount`, parity after).
3. When `receivedData + receivedParity >= dataShardCount`, attempt RS decode if any data missing.
4. Concatenate unpadded data payloads to Annex-B; clear frame state; return `AssembledFrame`.

Keep Mac `Shared/UdpVideo.swift` packager API identical so `UdpVideoSender` can call it without duplicating shard maths.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.UdpVideoFecTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add Shared/UdpVideo.swift \
  Android/app/src/main/java/com/peetzweg/opendisplay/net/ReedSolomon.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/net/UdpVideoPackager.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/net/UdpFrameAssembler.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/net/UdpVideoFecTest.kt
git commit -m "$(cat <<'EOF'
feat(udp): add Reed-Solomon FEC packager and frame assembler

EOF
)"
```

---

### Task 3: Negotiate Separate UDP Video Channel (TCP Control Stays)

**Files:**
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/session/ReceiverSession.kt`
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/MainActivity.kt` (only if hello construction lives there — prefer session)
- Modify: `Mac/MacSender.swift`
- Modify: `Android/app/src/test/java/com/peetzweg/opendisplay/session/ReceiverSessionTest.kt`
- Create: `Android/app/src/main/java/com/peetzweg/opendisplay/net/UdpVideoReceiver.kt` (socket bind + start/stop only)

**Interfaces:**
- Consumes: `WireMessage.transportOffer` / `transportSelected`
- Produces:
  - Hello field optional: `"video":["tcp","udp"]`, `"udpPort":9001` for Android/Chromebook WiFi only (omit on USB-only mode)
  - `ReceiverSession.onTransportOffer(map)` → bind UDP, reply `transportSelected`
  - `MacSender` after welcome: if peer offered UDP and preference allows, send `transportOffer` and dial UDP to peer host:`udpPort`
  - `fun startUdpVideo(port: Int, streamId: Int)` / `fun stopUdpVideo()`

- [ ] **Step 1: Write the failing negotiation tests**

```kotlin
@Test
fun helloJson_androidOffersUdpVideo() {
    val json = ReceiverSession.helloJson(
        wide = 1920, high = 1080, scale = 1.0,
        device = "Chromebook", id = "xyz", pv = 3,
        videoTransports = listOf("tcp", "udp"),
        udpPort = 9001,
    )
    val obj = org.json.JSONObject(json)
    assertEquals(3, obj.getInt("pv"))
    assertEquals(9001, obj.getInt("udpPort"))
    val video = obj.getJSONArray("video")
    assertEquals("tcp", video.getString(0))
    assertEquals("udp", video.getString(1))
}

@Test
fun selects_udp_when_transport_offer_arrives() {
    val sent = mutableListOf<Map<String, Any>>()
    val session = ReceiverSession(listener = noopListener)
    session.testHookSendControl = { sent += it }
    session.handleControlForTest(
        mapOf(
            "type" to WireMessage.transportOffer,
            "video" to listOf("udp", "tcp"),
            "control" to "tcp",
            "udpPort" to 9001,
            "streamId" to 7,
            "fecPct" to 20,
        )
    )
    val selected = sent.single { it["type"] == WireMessage.transportSelected }
    assertEquals("udp", selected["video"])
    assertEquals(7, selected["streamId"])
}
```

Add package-visible test hooks on `ReceiverSession`:

```kotlin
internal var testHookSendControl: ((Map<String, Any>) -> Unit)? = null

internal fun handleControlForTest(map: Map<String, Any>) = dispatchControl(map)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.ReceiverSessionTest`

Expected: FAIL on missing `videoTransports` / hooks / offer handling.

- [ ] **Step 3: Minimal negotiation + UDP bind**

Extend `helloJson` with optional `videoTransports` / `udpPort`.

On `transportOffer`: if `"udp"` is listed and preference is not forced-TCP, call `startUdpVideo(udpPort, streamId)` which constructs `UdpVideoReceiver(port)` bound to `0.0.0.0:udpPort`, then `sendControl(transportSelected video=udp)`.

On Mac (`MacSender`): after hello from Android/Chromebook with `udp` in `video`, if `UserDefaults videoTransport` is `udp` or (`auto` and feature flag `udpAutoEnabled` true — default **false** until Task 8 gate), allocate `streamId`, send `transportOffer` to the peer’s advertised `udpPort`, and construct `UdpVideoSender` toward the peer’s IP (strip IPv6 zone id). If preference is `tcp` or peer omitted `udp`, keep framed TCP video only.

`UdpVideoReceiver` in this task only binds and exposes `fun start(onDatagram: (ByteArray) -> Unit)` / `fun stop()` — assembly wiring is Task 4/5.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.ReceiverSessionTest`

Expected: PASS (existing tests still pass; new ones pass).

- [ ] **Step 5: Commit**

```bash
git add Mac/MacSender.swift \
  Android/app/src/main/java/com/peetzweg/opendisplay/session/ReceiverSession.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/net/UdpVideoReceiver.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/MainActivity.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/session/ReceiverSessionTest.kt
git commit -m "$(cat <<'EOF'
feat(transport): negotiate UDP video while keeping TCP control

EOF
)"
```

---

### Task 4: Paced Mac UDP Sender + Retransmit Cache

**Files:**
- Create: `Mac/UdpVideoSender.swift`
- Modify: `Mac/MacSender.swift` (route encoded AUs to UDP when selected)
- Extend: `Shared/UdpVideo.swift` if packager lives there
- Test: Android packager already covers wire bytes; add a small Swift-free check via documenting expected datagram count in `UdpVideoFecTest` (`packaged.packets.size >= data + parity`)

**Interfaces:**
- Consumes: packaged datagrams from `UdpVideoPackager` / Shared equivalent
- Produces:
  - `UdpVideoSender.connect(host:port:)`
  - `sendFrame(au:keyframe:captureMs:) -> Bool` (false = net-drop this frame — disposable)
  - `handleNack(missing: [UInt16])` retransmit from cache
  - `pendingCount` for MacSender backpressure (like `pendingSends`)
  - Pacing: batch ≤12 datagrams, delay so batch bytes stay within `wireBps = encodeBitrate * (1 + fecPct/100)` budget

- [ ] **Step 1: Write the failing disposable-frame / backpressure test (Kotlin mirror of sender policy)**

Because Mac XCTest is not in the Android CI path, encode the pacing/drop policy as a pure Kotlin helper used conceptually by docs and tested here:

```kotlin
// Android/.../net/UdpSendBudgetTest.kt
package com.peetzweg.opendisplay.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpSendBudgetTest {
    @Test
    fun shouldDropFrame_whenPendingAtCap() {
        assertTrue(UdpSendBudget.shouldNetDrop(pendingDatagrams = 64, maxPending = 64))
        assertFalse(UdpSendBudget.shouldNetDrop(pendingDatagrams = 10, maxPending = 64))
    }

    @Test
    fun paceDelayMs_scalesWithBatchBytes() {
        // 12_000 bytes at 3_000 bytes/ms budget → 4ms
        assertEquals(4.0, UdpSendBudget.paceDelayMs(batchBytes = 12_000, bytesPerMs = 3_000), 0.01)
    }
}
```

```kotlin
object UdpSendBudget {
    fun shouldNetDrop(pendingDatagrams: Int, maxPending: Int): Boolean =
        pendingDatagrams >= maxPending

    fun paceDelayMs(batchBytes: Int, bytesPerMs: Int): Double {
        val budget = maxOf(1, bytesPerMs)
        return (batchBytes.toDouble() / budget.toDouble()).coerceIn(0.05, 8.0)
    }

    fun bytesPerMs(encodeBitrate: Int, fecPct: Int): Int {
        val wireBps = encodeBitrate.toDouble() * (1.0 + fecPct.toDouble() / 100.0)
        return maxOf(1, (wireBps / 8.0 / 1000.0).toInt())
    }
}
```

Mac `UdpVideoSender` must call the same formulas (duplicate constants in Swift matching these numbers: `maxPendingDatagrams = 64`, batch size 12, delay clamp 0.05…8ms).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.UdpSendBudgetTest`

Expected: FAIL unresolved `UdpSendBudget`.

- [ ] **Step 3: Implement budget helper + Mac paced sender**

Implement `UdpSendBudget` as above.

Implement `Mac/UdpVideoSender.swift` following the `udp` branch structure, updated for 32-byte headers and timestamps:

```swift
@available(macOS 14.0, *)
final class UdpVideoSender {
    private let queue: DispatchQueue
    private var connection: NWConnection?
    private var nextSeq: UInt16 = 0
    private var nextFrameId: UInt32 = 1
    private var pendingDatagrams = 0
    private let maxPendingDatagrams = 64
    private var encodeBitrate: Int = 18_000_000
    private var fecPct: Int = UdpVideoProtocol.defaultFecPct
    private var retransmitCache: [UInt16: Data] = [:]
    private let retransmitCacheLimit = 2048
    private(set) var isReady = false

    var pendingCount: Int { pendingDatagrams }
    var onReady: (() -> Void)?
    var onFailed: ((String) -> Void)?

    func updateBitrate(_ bitrate: Int) { encodeBitrate = max(1_000_000, bitrate) }
    func updateFecPct(_ pct: Int) { fecPct = max(0, min(50, pct)) }

    @discardableResult
    func sendFrame(au: Data, keyframe: Bool, captureMs: UInt64) -> Bool {
        guard isReady, let connection else { return false }
        if pendingDatagrams >= maxPendingDatagrams { return false }
        let sendMs = UInt64(Date().timeIntervalSince1970 * 1000)
        let packaged = UdpVideoPackager.packageFrame(
            au: au, frameId: nextFrameId, startSeq: nextSeq,
            keyframe: keyframe, fecPct: fecPct,
            captureMs: captureMs, sendMs: sendMs
        )
        nextFrameId &+= 1
        nextSeq = packaged.nextSeq
        for pkt in packaged.packets {
            cache(seq: pkt.seq, datagram: pkt.datagram)
        }
        paceAndSend(packaged.packets, on: connection)
        return true
    }

    func handleNack(missing: [UInt16]) {
        guard let connection, isReady else { return }
        for seq in missing {
            if let dgram = retransmitCache[seq] {
                pendingDatagrams += 1
                connection.send(content: dgram, completion: .contentProcessed { [weak self] _ in
                    self?.queue.async { self?.pendingDatagrams = max(0, (self?.pendingDatagrams ?? 1) - 1) }
                })
            }
        }
    }
}
```

Wire `MacSender` encode completion: if UDP transport selected and ready, `udpSender.sendFrame(...)`; on `false`, count as `netDrops` (do **not** force IDR — disposable frame, reference chain intact only if encoder also skipped; if AU was already encoded, prefer sending and only drop when pending cap hit before encode — mirror existing `shouldDropFrame("pending_sends")` using `udpSender.pendingCount`).

- [ ] **Step 4: Run tests + Mac compile**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.UdpSendBudgetTest`

Expected: PASS

Run: `make mac`

Expected: `** BUILD SUCCEEDED **`

- [ ] **Step 5: Commit**

```bash
git add Mac/UdpVideoSender.swift Mac/MacSender.swift Shared/UdpVideo.swift \
  Android/app/src/main/java/com/peetzweg/opendisplay/net/UdpSendBudget.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/net/UdpSendBudgetTest.kt
git commit -m "$(cat <<'EOF'
feat(udp): pace datagrams and cache packets for NACK retransmit

EOF
)"
```

---

### Task 5: Jitter Buffer, Late-Frame Drops, Loss Detection, NACK

**Files:**
- Create: `Android/app/src/main/java/com/peetzweg/opendisplay/net/JitterBuffer.kt`
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/net/UdpVideoReceiver.kt`
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/session/ReceiverSession.kt`
- Modify: `Mac/MacSender.swift` / `Mac/UdpVideoSender.swift` (handle `nack`)
- Test: `Android/app/src/test/java/com/peetzweg/opendisplay/net/JitterBufferTest.kt`

**Interfaces:**
- Consumes: `UdpFrameAssembler.AssembledFrame`, incomplete-frame gap signals
- Produces:
  - `JitterBuffer(targetDelayMs=20, maxDelayMs=40)`
  - `onRelease(frame)` → decoder
  - `onDropLate(frameId)` / `onIncomplete(frameId, missingSeqs)`
  - NACK JSON: `{"type":"nack","streamId":7,"missing":[9002,9003]}`
  - Policy: incomplete after `maxDelayMs` → drop + NACK once + request `kf` if keyframe gap

- [ ] **Step 1: Write the failing jitter-buffer tests**

```kotlin
package com.peetzweg.opendisplay.net

import org.junit.Assert.assertEquals
import org.junit.Test

class JitterBufferTest {
    private var now = 0L

    @Test
    fun releases_in_order_after_target_delay() {
        val released = mutableListOf<Long>()
        val buffer = JitterBuffer(
            targetDelayMs = 20,
            maxDelayMs = 40,
            nowMs = { now },
            onRelease = { released += it.frameId },
            onDropLate = {},
            onIncomplete = { _, _ -> },
        )
        buffer.offer(
            JitterBuffer.Frame(
                frameId = 2, complete = true, captureMs = 20,
                sendMs = 21, annexB = byteArrayOf(2), isKeyframe = false, seqs = intArrayOf(2),
            )
        )
        buffer.offer(
            JitterBuffer.Frame(
                frameId = 1, complete = true, captureMs = 10,
                sendMs = 11, annexB = byteArrayOf(1), isKeyframe = true, seqs = intArrayOf(1),
            )
        )
        now = 30
        buffer.drain()
        assertEquals(listOf(1L, 2L), released)
    }

    @Test
    fun drops_incomplete_after_max_delay_and_reports_missing() {
        val incomplete = mutableListOf<Pair<Long, IntArray>>()
        val buffer = JitterBuffer(
            targetDelayMs = 20,
            maxDelayMs = 40,
            nowMs = { now },
            onRelease = {},
            onDropLate = {},
            onIncomplete = { id, missing -> incomplete += id to missing },
        )
        buffer.offerPartial(frameId = 5, missingSeqs = intArrayOf(50, 51), firstSeenMs = 0)
        now = 41
        buffer.drain()
        assertEquals(5L, incomplete.single().first)
        assertEquals(listOf(50, 51), incomplete.single().second.toList())
    }

    @Test
    fun drops_complete_frame_that_arrives_too_late() {
        val dropped = mutableListOf<Long>()
        val released = mutableListOf<Long>()
        val buffer = JitterBuffer(
            targetDelayMs = 20,
            maxDelayMs = 40,
            nowMs = { now },
            onRelease = { released += it.frameId },
            onDropLate = { dropped += it },
            onIncomplete = { _, _ -> },
        )
        // Playout head already at capture 100; a complete frame with capture 10 is late.
        buffer.advancePlayoutHeadForTest(100)
        buffer.offer(
            JitterBuffer.Frame(
                frameId = 1, complete = true, captureMs = 10,
                sendMs = 11, annexB = byteArrayOf(1), isKeyframe = false, seqs = intArrayOf(1),
            )
        )
        now = 200
        buffer.drain()
        assertEquals(listOf(1L), dropped)
        assertEquals(emptyList<Long>(), released)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.JitterBufferTest`

Expected: FAIL unresolved `JitterBuffer`.

- [ ] **Step 3: Implement jitter buffer + wire NACK**

```kotlin
class JitterBuffer(
    private val targetDelayMs: Long = 20,
    private val maxDelayMs: Long = 40,
    private val nowMs: () -> Long,
    private val onRelease: (Frame) -> Unit,
    private val onDropLate: (Long) -> Unit,
    private val onIncomplete: (Long, IntArray) -> Unit,
) {
    data class Frame(
        val frameId: Long,
        val complete: Boolean,
        val captureMs: Long,
        val sendMs: Long,
        val annexB: ByteArray,
        val isKeyframe: Boolean,
        val seqs: IntArray,
    )

    fun offer(frame: Frame) { /* enqueue by frameId */ }
    fun offerPartial(frameId: Long, missingSeqs: IntArray, firstSeenMs: Long) { /* track */ }
    fun drain() {
        // Release complete frames whose captureMs + targetDelayMs <= now,
        // in frameId order without gaps when possible.
        // If head is incomplete and now - firstSeen >= maxDelayMs: onIncomplete + drop.
        // If complete but captureMs + maxDelayMs < playoutHead: onDropLate.
    }
}
```

`UdpVideoReceiver` loop: datagram → `assembler.offer` → complete frames to `jitter.offer`; track missing seq gaps via assembler hooks → `jitter.offerPartial`; scheduled `drain()` every 5ms; on incomplete → `ReceiverSession.sendControl(nack)` + optionally `kf`.

`MacSender` control demux: on `type==nack`, `udpSender.handleNack(missing)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.JitterBufferTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/peetzweg/opendisplay/net/JitterBuffer.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/net/UdpVideoReceiver.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/session/ReceiverSession.kt \
  Mac/MacSender.swift Mac/UdpVideoSender.swift \
  Android/app/src/test/java/com/peetzweg/opendisplay/net/JitterBufferTest.kt
git commit -m "$(cat <<'EOF'
feat(udp): add jitter buffer, late drops, and NACK recovery

EOF
)"
```

---

### Task 6: Adaptive Bitrate, QoS Feedback, UDP Keyframe Policy

**Files:**
- Create: `Mac/VideoRateController.swift`
- Create: `Android/app/src/main/java/com/peetzweg/opendisplay/session/UdpHealthPolicy.kt`
- Modify: `Mac/MacSender.swift` (`setupEncoder` UDP branch: shorter IDR interval)
- Modify: `Android/.../session/ReceiverSession.kt` (emit `qos`)
- Modify: `Android/.../video/VideoDecoder.kt` / `MainActivity.kt` (existing `kf` path unchanged)
- Test: `Android/app/src/test/java/com/peetzweg/opendisplay/session/UdpHealthPolicyTest.kt`

**Interfaces:**
- Consumes: jitter/loss counters from Task 5
- Produces:
  - `qos` every ~500ms: `lossPct`, `jitterMs`, `nackRate`, `lateFrames`, `fecRecoveries`
  - `VideoRateController.next(lossPct,jitterMs,nackRate) -> RateAction(bitrateDeltaMbps, forceKeyframe, fallbackTcp)`
  - UDP encoder: `MaxKeyFrameIntervalDuration = 5` seconds (fast recovery); TCP keeps current 60s
  - `UdpHealthPolicy.shouldRequestKeyframe(...)` / `shouldFallbackToTcp(...)`

- [ ] **Step 1: Write the failing health-policy tests**

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
    fun qos_map_contains_required_keys() {
        val map = UdpHealthPolicy.qosMap(
            lossPct = 4.0, jitterMs = 12.0, nackRate = 0.05,
            lateFrames = 1, fecRecoveries = 3,
        )
        assertEquals("qos", map["type"])
        assertEquals(4.0, map["lossPct"])
        assertEquals(12.0, map["jitterMs"])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.UdpHealthPolicyTest`

Expected: FAIL unresolved `UdpHealthPolicy`.

- [ ] **Step 3: Implement policy + Mac rate controller + encoder UDP tuning**

```kotlin
object UdpHealthPolicy {
    private const val LOSS_FALLBACK_PCT = 20.0
    private const val BAD_WINDOWS_FOR_FALLBACK = 3

    fun shouldRequestKeyframe(lateFrames: Int, incompleteFrames: Int, decodeErrors: Int): Boolean =
        lateFrames > 0 || incompleteFrames > 0 || decodeErrors > 0

    fun shouldFallbackToTcp(lossPct: Double, consecutiveBadWindows: Int): Boolean =
        lossPct >= LOSS_FALLBACK_PCT && consecutiveBadWindows >= BAD_WINDOWS_FOR_FALLBACK

    fun qosMap(
        lossPct: Double,
        jitterMs: Double,
        nackRate: Double,
        lateFrames: Int,
        fecRecoveries: Int,
    ): Map<String, Any> = mapOf(
        "type" to "qos",
        "lossPct" to lossPct,
        "jitterMs" to jitterMs,
        "nackRate" to nackRate,
        "lateFrames" to lateFrames,
        "fecRecoveries" to fecRecoveries,
    )
}
```

```swift
struct RateAction {
    var bitrate: Int?
    var forceKeyframe: Bool
    var preferTcpNextSession: Bool
}

final class VideoRateController {
    private var bitrate: Int
    init(initialBitrate: Int) { self.bitrate = initialBitrate }

    func next(lossPct: Double, jitterMs: Double, nackRate: Double) -> RateAction {
        if lossPct >= 20 {
            return RateAction(bitrate: nil, forceKeyframe: true, preferTcpNextSession: true)
        }
        if lossPct > 5 || jitterMs > 25 {
            bitrate = max(2_000_000, bitrate - 4_000_000)
            return RateAction(bitrate: bitrate, forceKeyframe: false, preferTcpNextSession: false)
        }
        if nackRate > 0.10 {
            return RateAction(bitrate: nil, forceKeyframe: true, preferTcpNextSession: false)
        }
        if lossPct < 1 && jitterMs < 10 {
            bitrate = min(bitrate + 1_000_000, 40_000_000)
            return RateAction(bitrate: bitrate, forceKeyframe: false, preferTcpNextSession: false)
        }
        return RateAction(bitrate: nil, forceKeyframe: false, preferTcpNextSession: false)
    }
}
```

In `MacSender.setupEncoder`, when `videoTransport == .udp`:

```swift
VTSessionSetProperty(encoder, key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, value: 5 as CFNumber)
VTSessionSetProperty(encoder, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: 300 as CFNumber)
```

Keep existing 60s / 3600 interval for TCP. On `qos`, call `VideoRateController.next` and apply bitrate / force keyframe / remember TCP preference for next reconnect.

ReceiverSession: every 500ms while UDP active, compute loss from seq gaps, send `qos` map; if `shouldRequestKeyframe`, send existing `kf` control; if `shouldFallbackToTcp`, stop UDP and set session preference so next `transportSelected` is `tcp` (Task 7 completes mid-session switch if feasible; minimum bar is next reconnect).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.UdpHealthPolicyTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add Mac/VideoRateController.swift Mac/MacSender.swift \
  Android/app/src/main/java/com/peetzweg/opendisplay/session/UdpHealthPolicy.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/session/ReceiverSession.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/video/VideoDecoder.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/MainActivity.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/session/UdpHealthPolicyTest.kt
git commit -m "$(cat <<'EOF'
feat(udp): adapt bitrate and keyframes from receiver qos

EOF
)"
```

---

### Task 7: Decoder Path Integration + Chromebook Edge Hardening

**Files:**
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/MainActivity.kt`
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/video/VideoDecoder.kt`
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/session/ChromebookRecoverPolicy.kt` (only if UDP-specific recover differs)
- Modify: `Android/app/src/main/java/com/peetzweg/opendisplay/session/PerfStats.kt` / `ui/PerfOverlay.kt` (show `udp` + loss/fec)
- Test: `Android/app/src/test/java/com/peetzweg/opendisplay/session/ChromebookRecoverPolicyTest.kt` (extend)

**Interfaces:**
- Consumes: jitter `onRelease` Annex-B
- Produces: same `VideoDecoder.queue` path as TCP frames; perf overlay fields `transport=udp`, `lossPct`, `fecRecoveries`
- Chromebook: keep existing in-place recover (no TCP tear) when UDP loss causes decode errors — request `kf`, do not `bye` the control socket

- [ ] **Step 1: Write the failing recover-policy assertion for UDP decode errors**

```kotlin
@Test
fun udpDecodeError_requestsKeyframeWithoutTearingTcp() {
    val action = ChromebookRecoverPolicy.onDecodeError(transport = "udp", consecutiveErrors = 2)
    assertEquals(ChromebookRecoverPolicy.Action.RequestKeyframe, action)
}
```

If `ChromebookRecoverPolicy` has no transport parameter yet, add:

```kotlin
enum class Action { RequestKeyframe, RebuildCodec, TearSession }

fun onDecodeError(transport: String, consecutiveErrors: Int): Action {
    // UDP: never TearSession for decode errors — control must stay up.
    if (transport == "udp") {
        return if (consecutiveErrors >= 5) Action.RebuildCodec else Action.RequestKeyframe
    }
    // existing TCP behavior unchanged
    return existingTcpBehavior(consecutiveErrors)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.ChromebookRecoverPolicyTest`

Expected: FAIL on missing overload / enum case.

- [ ] **Step 3: Wire receiver → decoder + overlay**

In `MainActivity` / session listener: when UDP releases a frame, call the same path as `onVideoFrame` (telemetry already in header `captureMs`/`sendMs` — feed `DecodeTimings` / e2e stats from header instead of TCP JSON prefix).

Ensure `FLAG_KEEP_SCREEN_ON`, foreground service, and Chromebook cursor paths are unchanged.

Update `PerfStats` with optional `lossPct: Double?`, `fecRecoveries: Int`, `videoTransport: String`.

- [ ] **Step 4: Run tests**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.ChromebookRecoverPolicyTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add Android/app/src/main/java/com/peetzweg/opendisplay/MainActivity.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/video/VideoDecoder.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/session/ChromebookRecoverPolicy.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/session/PerfStats.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/ui/PerfOverlay.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/session/ChromebookRecoverPolicyTest.kt
git commit -m "$(cat <<'EOF'
feat(android): feed UDP frames to decoder with Chromebook-safe recover

EOF
)"
```

---

### Task 8: Fallback, Settings, Docs, Baseline Gate

**Files:**
- Modify: `Mac/OpenSidecarMacApp.swift` (optional menu: WiFi video Auto/TCP/UDP)
- Modify: `Mac/MacSender.swift` / Android settings if exposing transport
- Restore/update: `superpowers/plans/2026-07-26-android-udp-latency-baseline.md`
- Modify: `COMPATIBILITY.md`, `README.md`, `Android/README.md`
- Modify: `docs/superpowers/plans/2026-07-28-android-wifi-udp-transport.md` → add one-line supersession pointer to this plan
- Test: fallback unit test in `ReceiverSessionTest` / `UdpHealthPolicyTest`

**Interfaces:**
- Consumes: `UdpHealthPolicy.shouldFallbackToTcp`, Mac `videoTransport` defaults
- Produces:
  - `defaults` key `videoTransport` = `tcp|udp|auto` (default `auto`→TCP until gate)
  - On fallback: stop UDP sender/receiver; continue video on TCP frames for the rest of the session **or** reconnect with `transportSelected=tcp` — prefer mid-session switch to TCP video on the existing control socket if `MacSender` still has the TCP path warm; otherwise end video and wait for receiver `hello` to renegotiate TCP-only
  - Docs describing Sunshine machinery and the keep-UDP gate

- [ ] **Step 1: Write the failing fallback test**

```kotlin
@Test
fun falls_back_after_three_bad_qos_windows() {
    val policy = UdpHealthPolicy.FallbackTracker()
    repeat(3) { policy.noteWindow(lossPct = 30.0) }
    assertEquals("tcp", policy.preferredVideoTransport())
}
```

```kotlin
class FallbackTracker {
    private var bad = 0
    private var preferred = "udp"
    fun noteWindow(lossPct: Double) {
        if (UdpHealthPolicy.shouldFallbackToTcp(lossPct, bad + 1)) {
            bad += 1
            if (bad >= 3) preferred = "tcp"
        } else if (lossPct < 5.0) {
            bad = 0
        } else {
            bad += 1
        }
    }
    fun preferredVideoTransport(): String = preferred
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.UdpHealthPolicyTest`

Expected: FAIL until `FallbackTracker` exists.

- [ ] **Step 3: Implement fallback + docs + preference**

Wire tracker into `ReceiverSession` qos loop. Document in `COMPATIBILITY.md`:

```markdown
- **Protocol 3 (additive):** Android/Chromebook WiFi may negotiate **UDP video**
  with ~20% Reed–Solomon FEC, NACK retransmit, paced sends, jitter buffer,
  and qos-driven bitrate. Control JSON stays on TCP. Default `videoTransport`
  auto selects TCP until the Chromebook keep-UDP gate passes; opt in with
  `defaults write … videoTransport udp`. USB and iOS remain TCP video.
```

Copy baseline checklist from `udp` branch into `superpowers/plans/2026-07-26-android-udp-latency-baseline.md` if absent.

At top of `docs/superpowers/plans/2026-07-28-android-wifi-udp-transport.md` add:

```markdown
> **Superseded** by `superpowers/plans/2026-07-28-sunshine-udp-android-chromebook.md`
> (adds FEC, pacing budget tests, late-frame drops, qos AIMD, Chromebook harden).
```

- [ ] **Step 4: Full verification**

Run:

```bash
cd Android && ./gradlew :app:testDebugUnitTest
make android
make mac
```

Expected: all unit tests PASS; both builds SUCCESS.

Manual A/B (engineer): follow baseline checklist on Chromebook WiFi with `videoTransport=udp`; fill the A/B table; only then consider flipping `auto`→UDP.

- [ ] **Step 5: Commit**

```bash
git add Mac/OpenSidecarMacApp.swift Mac/MacSender.swift \
  Android/app/src/main/java/com/peetzweg/opendisplay/session/UdpHealthPolicy.kt \
  Android/app/src/main/java/com/peetzweg/opendisplay/session/ReceiverSession.kt \
  Android/app/src/test/java/com/peetzweg/opendisplay/session/UdpHealthPolicyTest.kt \
  COMPATIBILITY.md README.md Android/README.md \
  superpowers/plans/2026-07-26-android-udp-latency-baseline.md \
  docs/superpowers/plans/2026-07-28-android-wifi-udp-transport.md
git commit -m "$(cat <<'EOF'
feat(wifi): UDP fallback, transport preference, and Sunshine docs

EOF
)"
```

---

## Self-Review

### Spec coverage (`SUNSHINE_UDP_NOTES.md`)

| Big Reason | Task |
|---|---|
| Disposable late frames / forward progress | Task 4 net-drop + Task 5 late/incomplete drops |
| Selective reliability (seq, timestamps, boundaries, loss, retransmit, FEC, IDR) | Tasks 1–2, 5–6 |
| Separate control vs bulk video | Task 3 |
| Bitrate + packet pacing / congestion | Tasks 4 + 6 |
| Low-latency encoder + fast keyframe recovery | Task 6 UDP encoder intervals + `kf` |
| Small jitter buffer | Task 5 |
| Optimized decoder/renderer path | Task 7 |
| Real-world edge hardening + fallback | Tasks 7–8 |

### Placeholder scan

- No TBD/TODO/“implement later” left in task steps.
- FEC is in Task 2 (not deferred).
- Test code uses JUnit to match the repo.

### Type consistency

- Control types: `transportOffer`, `transportSelected`, `nack`, `qos` everywhere.
- Header: 32-byte layout with `captureMs`/`sendMs` on both ends.
- `WireProtocol.version = 3`, `minSupportedPeer = 1`.
- Default `videoTransport`: `auto` → TCP until gate.

### Lesson from `2fee387`

Ship FEC+pacing **with** NACK, jitter, late drops, and qos AIMD before any Chromebook default-on. The keep-UDP gate remains mandatory.

---

## Execution Handoff

Plan complete and saved to `superpowers/plans/2026-07-28-sunshine-udp-android-chromebook.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
