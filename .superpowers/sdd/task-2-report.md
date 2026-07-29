# Task 2 Report: Emit connect stages on Mac + Android

## Status: DONE

## Commit
- `a2179d2` feat(timing): log Mac and Chromebook connect TTFF stages

## Changes

### Mac (`MacSender.swift`)
- Added `connectTiming` field alongside session state.
- Reset + `dialStart` mark at TCP/USB dial entry (`connectTCP`, `connectUSB`).
- `tcpReady` in `becomeReady`.
- `helloReceived` when `lastHello` is set in hello handler.
- `virtualDisplayReady` after `virtualDisplay = vd` in `setupExtend`.
- `captureStarted` after `stream.startCapture()` in `startCapture`.
- First successful annex-B encode: `firstEncoded` + `Log.info(connectTiming.summaryLine())`.

### Android (`ReceiverSession.kt`)
- `connectedAtWallMs` / `firstVideoAtWallMs` with private setters.
- `connectedAtWallMs` set in `handleClient` before `onConnected()`.
- `firstVideoAtWallMs` set once in `noteVideoFrame`.
- Both reset in `resetStreamState` and `closeClient`.

### Android (`MainActivity.kt`)
- Import `ConnectTimingPolicy`.
- On first `hasRendered` flip: log `ttff=…ms slow=…` using `session.connectedAtWallMs`.

## Tests
- `./gradlew :app:testDebugUnitTest --tests com.peetzweg.opendisplay.session.ConnectTimingPolicyTest` — **PASS** (BUILD SUCCESSFUL)

## Manual smoke (Step 3)
**SKIPPED** — no Mac + Chromebook hardware in this environment. Wiring committed; on-device baseline capture deferred.

## Self-review

| Check | Result |
|-------|--------|
| Mark sites match brief | OK |
| Field names match brief | OK |
| Mac log once per connect (first encode) | OK — guarded by `firstEncoded == nil` |
| Android TTFF once per connection | OK — guarded by `!paintedThisConnection` |
| Timing reset on reconnect | OK — dial start resets Mac timing; ReceiverSession resets on accept/close |
| No secrets / unrelated diffs | OK |

### Concerns (minor)
1. **Mac `helloReceived` on rotation/reconnect hellos** — mark fires on every hello, not only the first connect. Stage deltas after the first hello may be misleading on orientation change; acceptable for initial TTFF diagnosis.
2. **`resetStreamState` before `connectedAtWallMs` in `handleClient`** — intentional: reset clears stale values, then connect time is set immediately before `onConnected()`.
3. **Manual smoke not run** — baseline A/B numbers not recorded.

## Expected log samples (when run on device)
- Mac: `connectTiming dial→ready=…ms ready→hello=…ms hello→vd=…ms vd→capture=…ms capture→encode=…ms dial→encode=…ms`
- Chromebook: `MainActivity: ttff=1234ms slow=false`
