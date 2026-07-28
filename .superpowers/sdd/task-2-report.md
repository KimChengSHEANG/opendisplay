# Task 2 Report: Reed–Solomon FEC + Frame Packager/Assembler

## Status

DONE

## Commit

- `04b000a feat(udp): add Reed-Solomon FEC packager and frame assembler`

## Implementation

- Added Kotlin GF(256) systematic Reed–Solomon parity encoding and data-shard recovery.
- Added the Android frame packager with monotonically increasing 16-bit sequence numbers, keyframe/start/end/parity flags, Task 1's 32-byte header, capture/send timestamps, and FEC parity.
- Added the Android frame assembler with frame advancement, stale-frame rejection, shard validation, direct assembly, FEC recovery, AU-length restoration, and state clearing after delivery.
- Added matching Swift datagram construction, payload splitting, Reed–Solomon parity encoding, and frame packaging for the later Mac sender task.
- Added the brief's Android tests verbatim.

The packagers prepend a four-byte big-endian AU length before sharding. This is required to remove zero padding after recovery, including when the missing shard is the final data shard. The prefix is removed by the Android assembler before returning `annexB`.

## TDD Evidence

1. Added `UdpVideoFecTest.kt` before production code.
2. Ran the focused test and observed the expected compile failure from unresolved `UdpVideoPackager` and `UdpFrameAssembler` references.
3. Implemented the minimum RS, packager, and assembler APIs.
4. Re-ran the focused test; after correcting `UdpFrameAssembler` from an object to the required constructible class, all three tests passed.

## Verification

- `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.UdpVideoFecTest` — passed.
- `cd Android && ./gradlew :app:testDebugUnitTest` — passed.
- `swiftc -typecheck Shared/UdpVideo.swift` — passed.
- `git diff --check` — passed before commit.

## Self-review

- Confirmed data shard indices occupy `0..<dataShardCount` and parity indices follow them.
- Confirmed FEC recovery pads received short shards only for decoding, then uses the recovered AU length to return exact bytes.
- Confirmed wrong protocol versions, invalid shard metadata, stale frame IDs, and inconsistent headers are ignored.
- Confirmed frame IDs and sequence numbers use their protocol-width wraparound behavior.
- Confirmed no negotiation, socket, jitter, or sender runtime code was added.

## Concerns

None. The pre-existing modification to `.superpowers/sdd/progress.md` was intentionally left unstaged and uncommitted.

## Review Fixes

- Replaced the non-MDS identity-plus-Vandermonde parity rows in Kotlin and Swift with a systematic Cauchy generator, so every set of `dataShardCount` received shards is invertible.
- Kept the assembler's latest advanced frame ID after state clearing, preventing delayed or duplicate packets from reopening an already delivered frame.
- Added coverage for the 16+4 review counterexample, multiple data erasures with mixed parity loss, reordered packet delivery, and stale packets after delivery.

### Fix Verification

- RED: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.UdpVideoFecTest` — failed as expected: 6 tests completed, 2 failed (16+4 recovery and delayed-packet rejection).
- GREEN: `cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.net.UdpVideoFecTest` — `BUILD SUCCESSFUL in 1s`.
- Full suite: `cd Android && ./gradlew :app:testDebugUnitTest` — `BUILD SUCCESSFUL in 955ms`.
- Swift: `swiftc -typecheck Shared/UdpVideo.swift` — passed with exit code 0.
