# Mac host sleep → disconnect / iOS sleep

**Date:** 2026-07-23  
**Status:** Approved for implementation  
**Approach:** Symmetric `hostSleeping` wire message (Mac → phone)

## Problem

When the Mac display sleeps, the lid closes, or the screen locks, OpenDisplay keeps streaming to the phone. The virtual display can strand the cursor and the phone stays awake showing a dead or stale stream.

Today the reverse already exists: when the **phone** locks, it sends `sleeping`, the Mac tears down the session and arms an `awaitingWake` reconnect.

## Goals

1. **Mac:** Disconnect sessions when the computer screen is off (display sleep **or** screen lock, including lid close). Reconnect when the screen is on **and** unlocked.
2. **iOS:** On Mac dormancy, behave like a local device lock — enter sleep (tear down session / stop listening), then accept reconnect when both sides are awake.

## Non-goals

- User preference to disable host-sleep behavior
- Brightness / black-overlay hacks on iOS (no programmatic hardware lock)
- Changing phone→Mac `sleeping` / `closing` semantics
- Bumping wire protocol version or `minPeer` (additive optional message)

## Protocol

### New message

| Direction | `type` | Meaning |
|---|---|---|
| Mac → phone | `hostSleeping` | Mac display asleep and/or screen locked; phone should `enterSleep`; Mac will redial when host is usable again |

Defined in `Shared/Protocol.swift` as `WireMessage.hostSleeping`.

### Compatibility

Additive. Older phones ignore unknown control types; Mac still ends sessions on dormancy and the phone sees a normal disconnect. Older Macs never send the message — no behavior change. Document in `COMPATIBILITY.md` as an optional feature gated by message presence, not by raising `minPeer`. Do **not** bump `WireProtocol.version` solely for this.

### State machine (Mac)

```
hostDormant = screensAsleep || screenLocked
hostUsable  = !screensAsleep && !screenLocked
```

- **Enter dormant** (usable → dormant): announce `hostSleeping`, end sessions, remember targets; **do not dial** while dormant.
- **Exit dormant** (dormant → usable): `connect(to:target, awaitingWake: true)` for remembered targets.

Phone does **not** reply with `sleeping` when handling `hostSleeping` — the Mac already owns teardown.

## Mac design

**Owner:** session `Controller` in `Mac/OpenSidecarMacApp.swift`.

**Observer:** listen for:

- `NSWorkspace.screensDidSleepNotification` / `screensDidWakeNotification`
- Distributed notifications `com.apple.screenIsLocked` / `com.apple.screenIsUnlocked`

Maintain `screensAsleep` and `screenLocked`; on `hostDormant` edges call `hostBecameDormant()` / `hostBecameUsable()`.

**Initial state:** Prefer querying current lock/sleep if available; otherwise assume usable until the first sleep/lock notification (avoid false reconnect on launch).

### `hostBecameDormant()`

1. Snapshot active session targets into `pendingWakeTargets`.
2. For each session: best-effort send `hostSleeping` (short timeout), then `end(session)` (virtual display + capture torn down).
3. Set `hostDormant` so `autoConnect` / normal dials do not fight the dormant state.
4. Do **not** call `connect(..., awaitingWake: true)` yet.

### `hostBecameUsable()`

1. Clear `hostDormant`.
2. For each pending target: `connect(to:target, awaitingWake: true)`.
3. Clear `pendingWakeTargets`.

### `MacSender`

- Add announce helper analogous to the phone’s announce-then-close (send control then complete).
- Existing capture-recovery on mid-stream display sleep remains a safety net; primary path is full session end via the controller.

## iOS design

**Receive:** In `PhoneReceiver` control handling, on `WireMessage.hostSleeping` call `enterSleep()` (or shared close path).

**Status copy (optional polish):** e.g. `"Mac asleep — resumes when Mac wakes"` so logs/UI distinguish from local lock (`"Asleep — resumes on wake"`).

**Idle timer:** Today `isIdleTimerDisabled = true` on appear and stays on. On sleep/disconnect, set `isIdleTimerDisabled = false` so the phone can screen-off after the Mac goes dark; set it back to `true` when a session connects again.

**Unlock / return:** Unchanged — `ensureListening()` on scene activate; Mac’s `awaitingWake` dials reconnect when the host is usable.

## Files likely touched

- `Shared/Protocol.swift` — `WireMessage.hostSleeping`
- `COMPATIBILITY.md` — optional feature note
- `Mac/OpenSidecarMacApp.swift` — host dormancy observer + controller hooks
- `Mac/MacSender.swift` — send `hostSleeping`
- `iOS/PhoneReceiver.swift` — handle `hostSleeping` → `enterSleep`
- `iOS/OpenSidecarPhoneApp.swift` — idle timer on connect/disconnect (if not already)

## Testing (manual)

1. Stream to phone; put Mac display to sleep → phone enters sleep; Mac sessions end; no endless dial while asleep.
2. Wake Mac display (unlocked) → sessions reconnect with `awaitingWake`.
3. Lock Mac screen (display still on) → same as (1); unlock → reconnect.
4. Lid close / open (laptop) → same dormancy / usable edges.
5. Phone local lock still sends `sleeping` and Mac still arms wake reconnect (regression).
6. Old phone build (ignore unknown): Mac ends session on dormancy without crashing either side.
