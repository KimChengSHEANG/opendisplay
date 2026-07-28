# Task 2 Report — qos `incompleteRate` + health policy tests

**Status:** DONE  
**Branch:** sunshine-udp-android  
**Commit:** 6e11628 — feat(udp): report incompleteRate in qos instead of nackRate

## Summary

Replaced qos `nackRate` with `incompleteRate` in `UdpHealthPolicy` and updated unit tests per TDD. Added minimal compile fix in `ReceiverSession.publishQosWindow` so the project builds with the new `qosMap` signature.

## TDD steps

| Step | Result |
|------|--------|
| 1. Write failing tests | Added `incomplete_rate_is_incomplete_over_frames`, replaced `qos_map_contains_required_keys` with `qos_map_uses_incomplete_rate_not_nack_rate` |
| 2. Run tests (expect fail) | FAIL — unresolved `incompleteRate`, wrong `qosMap` signature |
| 3. Implement policy | Added `incompleteRate()` helper; `qosMap` now emits `"incompleteRate"` (no `"nackRate"`) |
| 4. Run tests (expect pass) | PASS — 5/5 tests in `UdpHealthPolicyTest` |
| 5. Commit | 6e11628 |

## Files changed

| File | Change |
|------|--------|
| `Android/.../UdpHealthPolicy.kt` | `incompleteRate()` helper; `qosMap(..., incompleteRate, ...)` |
| `Android/.../UdpHealthPolicyTest.kt` | New/updated tests per brief |
| `Android/.../ReceiverSession.kt` | **Compile fix only:** `publishQosWindow` passes `incompleteRate = UdpHealthPolicy.incompleteRate(window.incompleteFrames, window.frames)` |

## Test command & output

```bash
cd Android && ./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.UdpHealthPolicyTest
# BUILD SUCCESSFUL — 5 tests passed
```

## Self-review

- **Spec alignment:** `incompleteRate = incompleteFrames / frames` (0 when frames == 0); qos map keys match spec (`type`, `lossPct`, `jitterMs`, `incompleteRate`, `lateFrames`, `fecRecoveries`).
- **Scope:** Did not remove NACK sending or `WireMessage.nack` — deferred to Task 3 as instructed.
- **ReceiverSession:** Only the `publishQosWindow` call-site changed; NACK window counters (`qosNacksWindow`) remain untouched.
- **Existing behavior preserved:** `shouldRequestKeyframe`, `shouldFallbackToTcp`, and `FallbackTracker` unchanged.
- **Concerns:** None. Mac-side still reads `nackRate` until Task 5.

## Out of scope (later tasks)

- Task 3: Remove NACK control messages from receiver
- Task 5: Mac `VideoRateController` / `MacSender` switch to `incompleteRate`
