# Mac Host Sleep Disconnect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the Mac display sleeps or the screen locks, announce `hostSleeping`, tear down sessions, and reconnect only when the Mac is awake and unlocked; the phone enters the same sleep path as a local lock so its screen can turn off.

**Architecture:** Additive Mac→phone control message `WireMessage.hostSleeping`. `SenderController` owns host dormancy (`screensAsleep || screenLocked`), announces then ends sessions into `pendingWakeTargets`, and dials with `awaitingWake: true` only on the usable edge. Phone handles the message via existing `enterSleep()` and restores the idle timer so iOS can blank the display.

**Tech Stack:** Swift, AppKit (`NSWorkspace` screen sleep), Distributed Notifications (screen lock), Network.framework control frames, existing `MacSender` / `PhoneReceiver` / `SenderController` session lifecycle.

## Global Constraints

- Additive wire only — do **not** bump `WireProtocol.version` or `minSupportedPeer`.
- Older phones ignore unknown types; Mac must still end sessions on dormancy.
- Phone must **not** send `sleeping` back when handling `hostSleeping`.
- Reconnect only when **both** screens awake **and** screen unlocked.
- Do not dial while `hostDormant` (including `autoConnect`).
- Spec: `superpowers/specs/2026-07-23-mac-host-sleep-design.md` (repo uses `superpowers/` because `docs/` is the GitHub Pages build output).
- No unit-test target in this repo — verify with `make mac` / `make ios` and the manual checklist at the end.
- Before long `xcodebuild` / `make` runs in a DevSwarm child workspace, ask the parent via `hivecontrol workspace message-parent` and wait on `monitor`.

---

## File map

| File | Role |
|---|---|
| `Shared/Protocol.swift` | Add `WireMessage.hostSleeping` |
| `COMPATIBILITY.md` | Document optional Mac→phone message |
| `Mac/HostSleepObserver.swift` | **Create** — lock/sleep notifications → dormant edges |
| `Mac/MacSender.swift` | Announce `hostSleeping` with completion + timeout |
| `Mac/OpenSidecarMacApp.swift` | `SenderController` dormancy state + reconnect gating |
| `iOS/PhoneReceiver.swift` | Handle `hostSleeping` → `enterSleep` |
| `iOS/OpenSidecarPhoneApp.swift` | Idle timer tied to connection / sleep |

---

### Task 1: Wire constant + compatibility note

**Files:**
- Modify: `Shared/Protocol.swift`
- Modify: `COMPATIBILITY.md`

**Interfaces:**
- Produces: `WireMessage.hostSleeping` = `"hostSleeping"`

- [ ] **Step 1: Add the wire constant**

In `Shared/Protocol.swift`, next to `sleeping` / `closing`:

```swift
static let hostSleeping = "hostSleeping"        // Mac -> phone: Mac display asleep/locked, reconnect when usable
```

- [ ] **Step 2: Document in COMPATIBILITY.md**

In section 3 (compatibility matrix) or a short “Optional control messages” note near message-flow, add:

> **`hostSleeping` (Mac → phone, optional):** Mac display sleep or screen lock. New phones call `enterSleep()` (same as local lock). Old phones ignore the type; the Mac still ends the session. Does **not** require a `pv` bump — presence of the message is the feature gate.

- [ ] **Step 3: Commit**

```bash
git add Shared/Protocol.swift COMPATIBILITY.md
git commit -m "$(cat <<'EOF'
feat: add hostSleeping wire message for Mac display sleep

EOF
)"
```

---

### Task 2: Host sleep observer (Mac)

**Files:**
- Create: `Mac/HostSleepObserver.swift`

**Interfaces:**
- Produces:
  - `final class HostSleepObserver`
  - `var onDormantChange: ((Bool) -> Void)?` — called on main with `hostDormant`
  - `var isDormant: Bool { get }` — `screensAsleep || screenLocked`
  - `func start()` / `func stop()`
- Consumes: AppKit + DistributedNotificationCenter

- [ ] **Step 1: Create `Mac/HostSleepObserver.swift`**

```swift
import AppKit

/// Tracks Mac display sleep and screen lock. Host is dormant when either is true;
/// usable only when both are clear. Fires `onDormantChange` on edges only.
@MainActor
final class HostSleepObserver {
    var onDormantChange: ((Bool) -> Void)?

    private(set) var screensAsleep = false
    private(set) var screenLocked = false
    private var started = false

    var isDormant: Bool { screensAsleep || screenLocked }

    func start() {
        guard !started else { return }
        started = true

        let workspace = NSWorkspace.shared.notificationCenter
        workspace.addObserver(self, selector: #selector(screensDidSleep),
                              name: NSWorkspace.screensDidSleepNotification, object: nil)
        workspace.addObserver(self, selector: #selector(screensDidWake),
                              name: NSWorkspace.screensDidWakeNotification, object: nil)

        let dnc = DistributedNotificationCenter.default()
        dnc.addObserver(self, selector: #selector(screenIsLocked),
                        name: Notification.Name("com.apple.screenIsLocked"), object: nil)
        dnc.addObserver(self, selector: #selector(screenIsUnlocked),
                        name: Notification.Name("com.apple.screenIsUnlocked"), object: nil)

        // Best-effort initial lock state; assume screens awake until notified.
        if let dict = CGSessionCopyCurrentDictionary() as? [String: Any],
           let locked = dict["CGSSessionScreenIsLocked"] as? Bool {
            screenLocked = locked
        }
        Log.info("host sleep observer started — dormant=\(isDormant) locked=\(screenLocked)")
    }

    func stop() {
        guard started else { return }
        started = false
        NSWorkspace.shared.notificationCenter.removeObserver(self)
        DistributedNotificationCenter.default().removeObserver(self)
    }

    @objc private func screensDidSleep() { setScreensAsleep(true) }
    @objc private func screensDidWake() { setScreensAsleep(false) }
    @objc private func screenIsLocked() { setScreenLocked(true) }
    @objc private func screenIsUnlocked() { setScreenLocked(false) }

    private func setScreensAsleep(_ value: Bool) {
        guard screensAsleep != value else { return }
        let was = isDormant
        screensAsleep = value
        emitIfChanged(from: was)
    }

    private func setScreenLocked(_ value: Bool) {
        guard screenLocked != value else { return }
        let was = isDormant
        screenLocked = value
        emitIfChanged(from: was)
    }

    private func emitIfChanged(from wasDormant: Bool) {
        let now = isDormant
        guard wasDormant != now else { return }
        Log.info("host dormancy \(wasDormant) → \(now) (asleep=\(screensAsleep) locked=\(screenLocked))")
        onDormantChange?(now)
    }
}
```

Note: `CGSessionCopyCurrentDictionary` needs CoreGraphics (pulled in via AppKit on macOS). If the compiler complains, add `import CoreGraphics`.

- [ ] **Step 2: Build Mac target to verify the new file compiles**

```bash
make mac
```

Expected: build succeeds (project.yml already includes all of `Mac/`).

- [ ] **Step 3: Commit**

```bash
git add Mac/HostSleepObserver.swift
git commit -m "$(cat <<'EOF'
feat(mac): observe display sleep and screen lock for host dormancy

EOF
)"
```

---

### Task 3: Announce `hostSleeping` from MacSender

**Files:**
- Modify: `Mac/MacSender.swift`

**Interfaces:**
- Produces: `func announceHostSleeping(completion: @escaping () -> Void)` — hops to `queue`, sends framed JSON, calls `completion` on main (or after 1s timeout). Safe if not connected.
- Consumes: `WireMessage.hostSleeping`, existing framing

- [ ] **Step 1: Add announce helper near the wire-framing section**

```swift
/// Tell the phone the Mac is going dormant, then call `completion` on the
/// main actor so the controller can tear the session down. Best-effort: if
/// there is no live link, complete immediately.
func announceHostSleeping(completion: @escaping () -> Void) {
    queue.async { [weak self] in
        guard let self else {
            DispatchQueue.main.async { completion() }
            return
        }
        guard let connection = self.connection, self.connectionReady else {
            DispatchQueue.main.async { completion() }
            return
        }
        let payload = Data("{\"type\":\"\(WireMessage.hostSleeping)\"}".utf8)
        var header = UInt32(payload.count).bigEndian
        var frame = Data(bytes: &header, count: 4)
        frame.append(payload)
        var finished = false
        let finish = {
            guard !finished else { return }
            finished = true
            DispatchQueue.main.async { completion() }
        }
        connection.send(content: frame, completion: .contentProcessed { _ in finish() })
        self.queue.asyncAfter(deadline: .now() + 1.0) { finish() }
    }
}
```

Place this as a public method on `MacSender` (near `forceReconnect` / lifecycle), not `private`.

- [ ] **Step 2: Build**

```bash
make mac
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add Mac/MacSender.swift
git commit -m "$(cat <<'EOF'
feat(mac): announce hostSleeping before tearing down sessions

EOF
)"
```

---

### Task 4: SenderController dormancy + deferred wake reconnect

**Files:**
- Modify: `Mac/OpenSidecarMacApp.swift` (`SenderController`)

**Interfaces:**
- Consumes: `HostSleepObserver`, `MacSender.announceHostSleeping`, `connect(to:awaitingWake:)`
- Produces:
  - `private var hostDormant = false`
  - `private var pendingWakeTargets: [ConnectionTarget] = []`
  - `private let hostSleepObserver = HostSleepObserver()`
  - `hostBecameDormant()` / `hostBecameUsable()`
  - `refreshed(_ target:) -> ConnectionTarget` for stale Bonjour results

- [ ] **Step 1: Add state + start observer in `SenderController.init()`**

After existing `usbWatcher` / wifi auto-connect Task setup:

```swift
private var hostDormant = false
private var pendingWakeTargets: [ConnectionTarget] = []
private let hostSleepObserver = HostSleepObserver()
```

In `init()`, after starting browsing / USB watcher:

```swift
hostSleepObserver.onDormantChange = { [weak self] dormant in
    guard let self else { return }
    if dormant { self.hostBecameDormant() } else { self.hostBecameUsable() }
}
hostSleepObserver.start()
// If we launched already locked, treat as dormant with no sessions yet.
if hostSleepObserver.isDormant {
    hostDormant = true
}
```

- [ ] **Step 2: Gate `autoConnect()`**

At the top of `autoConnect()`:

```swift
guard !hostDormant else { return }
```

- [ ] **Step 3: Implement dormancy handlers**

```swift
private func hostBecameDormant() {
    hostDormant = true
    let active = sessions
    guard !active.isEmpty else {
        Log.info("host dormant — no sessions to park")
        return
    }
    Log.info("host dormant — parking \(active.count) session(s)")
    // Snapshot targets before ending; announce then end each.
    for session in active {
        let target = session.target
        if !pendingWakeTargets.contains(where: { $0.sessionID == target.sessionID }) {
            pendingWakeTargets.append(target)
        }
        session.sender.announceHostSleeping { [weak self] in
            guard let self else { return }
            // Session may already be gone if peer disconnected mid-announce.
            if self.sessions.contains(where: { $0.id == session.id }) {
                self.end(session)
            }
        }
    }
}

private func hostBecameUsable() {
    hostDormant = false
    let targets = pendingWakeTargets
    pendingWakeTargets.removeAll()
    guard !targets.isEmpty else {
        Log.info("host usable — nothing pending")
        return
    }
    Log.info("host usable — reconnecting \(targets.count) session(s)")
    for target in targets {
        connect(to: refreshed(target), awaitingWake: true)
    }
}

/// Prefer a live Bonjour result after a long sleep; USB targets are stable.
private func refreshed(_ target: ConnectionTarget) -> ConnectionTarget {
    switch target {
    case .usb:
        return target
    case .wifi:
        let id = target.sessionID
        if let fresh = discovered.first(where: { ConnectionTarget.wifi($0).sessionID == id }) {
            return .wifi(fresh)
        }
        return target
    }
}
```

Also guard user-visible `connect(to:)` when dormant unless we are in `hostBecameUsable` (which clears the flag first). Add at the start of `connect(to:userInitiated:awaitingWake:)`:

```swift
// While the Mac is asleep/locked, only wake-reconnects (called after
// clearing hostDormant) and explicit user taps should start sessions.
// autoConnect is already gated; block accidental reconnects from other paths.
if hostDormant && !userInitiated {
    Log.info("connect(\(target.sessionID)) skipped — host dormant")
    // Still remember so usable edge can dial.
    if !pendingWakeTargets.contains(where: { $0.sessionID == target.sessionID }) {
        pendingWakeTargets.append(target)
    }
    return
}
```

Wait — `hostBecameUsable` calls `connect` after clearing `hostDormant`, so the guard is fine. User tap while dormant: `userInitiated: true` should either queue or no-op. Prefer: if dormant and userInitiated, append to pendingWakeTargets and return (can't usefully stream while locked). Adjust:

```swift
if hostDormant {
    if !pendingWakeTargets.contains(where: { $0.sessionID == target.sessionID }) {
        pendingWakeTargets.append(target)
    }
    Log.info("connect(\(target.sessionID)) deferred — host dormant")
    return
}
```

- [ ] **Step 4: Build**

```bash
make mac
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Mac/OpenSidecarMacApp.swift
git commit -m "$(cat <<'EOF'
feat(mac): disconnect on host sleep/lock and reconnect when usable

EOF
)"
```

---

### Task 5: Phone handles `hostSleeping` + idle timer

**Files:**
- Modify: `iOS/PhoneReceiver.swift`
- Modify: `iOS/OpenSidecarPhoneApp.swift`

**Interfaces:**
- Consumes: `WireMessage.hostSleeping`, `enterSleep()`
- Produces: status `"Mac asleep — resumes when Mac wakes"`; idle timer false when disconnected/asleep, true when connected

- [ ] **Step 1: Handle the control message in `PhoneReceiver`**

In the control `switch` (with `welcome` / `updateRequired`), before `default`:

```swift
case WireMessage.hostSleeping:
    // Mac display asleep or locked — same teardown as a local lock, but
    // do not announce sleeping back (Mac already owns reconnect).
    Log.info("Mac host sleeping — entering sleep")
    enterSleep()
    setStatus("Mac asleep — resumes when Mac wakes")
```

`enterSleep()` already sets status to `"Asleep — resumes on wake"` via `closeSession`. Either:
- add an optional status parameter to `enterSleep` / `closeSession`, or
- call `closeSession(announcing: WireMessage.hostSleeping, …)` — **wrong**, that would announce to Mac.

Best: extend `enterSleep`:

```swift
func enterSleep(status: String = "Asleep — resumes on wake",
                completion: (() -> Void)? = nil) {
    closeSession(announcing: WireMessage.sleeping,
                 status: status, completion: completion)
}
```

But for host sleep we must **not** announce `sleeping`. Add:

```swift
func enterSleep(announceToMac: Bool = true,
                status: String = "Asleep — resumes on wake",
                completion: (() -> Void)? = nil) {
    if announceToMac {
        closeSession(announcing: WireMessage.sleeping, status: status, completion: completion)
    } else {
        closeSession(announcing: nil, status: status, completion: completion)
    }
}
```

And teach `closeSession` to skip send when `announcing` is nil:

```swift
private func closeSession(announcing type: String?, status: String,
                          completion: (() -> Void)?) {
    queue.async {
        var finished = false
        let finish = { [weak self] in
            guard let self, !finished else { return }
            finished = true
            self.connection?.cancel()
            self.connection = nil
            self.listener?.cancel()
            self.listener = nil
            self.listenerHealthy = false
            self.setConnected(false)
            self.setStatus(status)
            completion?()
        }
        guard let type,
              let conn = self.connection, conn.state == .ready else {
            Log.info("closing session (\(type ?? "silent")) — no announce or no live connection")
            finish()
            return
        }
        Log.info("closing session — announcing \(type) to the Mac")
        self.sendControl(["type": type], on: conn) {
            self.queue.async { finish() }
        }
        self.queue.asyncAfter(deadline: .now() + 1) { finish() }
    }
}
```

Then host handler:

```swift
case WireMessage.hostSleeping:
    Log.info("Mac host sleeping — entering sleep (no announce)")
    enterSleep(announceToMac: false, status: "Mac asleep — resumes when Mac wakes")
```

Local lock paths keep default `announceToMac: true`.

- [ ] **Step 2: Idle timer on connect / disconnect**

In `OpenSidecarPhoneApp.swift` `ReceiverScreen`, replace always-on idle disable with connection-driven behavior:

On appear, keep current start, but also:

```swift
.onValueChange(of: model.receiver.connected) { isConnected in
    if isConnected {
        hasConnectedBefore = true
        showOnboarding = false
        UIApplication.shared.isIdleTimerDisabled = true
    } else {
        UIApplication.shared.isIdleTimerDisabled = false
    }
}
```

And change `.onAppear` so it does **not** force idle-disable forever — only disable while connected (or leave appear as-is and let the first disconnect clear it). Preferred appear:

```swift
.onAppear {
    model.start()
    if !hasConnectedBefore && !onboardingDismissed {
        showOnboarding = true
    }
}
```

Idle stays default until first connect.

- [ ] **Step 3: Build iOS**

```bash
make ios
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add iOS/PhoneReceiver.swift iOS/OpenSidecarPhoneApp.swift
git commit -m "$(cat <<'EOF'
feat(ios): enter sleep on Mac hostSleeping and allow screen idle

EOF
)"
```

---

### Task 6: Manual verification checklist

**Files:** none (device testing)

- [ ] **Step 1: Build both**

```bash
make all
```

Expected: both schemes succeed.

- [ ] **Step 2: Manual matrix** (on real Mac + device)

| # | Action | Expect |
|---|---|---|
| 1 | Stream; put Mac display to sleep | Phone status → Mac asleep; session ends; Mac does not spin-dial while asleep |
| 2 | Wake display (unlocked) | Session reconnects (`awaitingWake`) |
| 3 | Stream; lock Mac (⌃⌘Q) | Same as (1) |
| 4 | Unlock Mac | Reconnect |
| 5 | Lid close / open (laptop) | Dormant / usable edges |
| 6 | Phone lock while streaming | Still sends `sleeping`; Mac arms wake reconnect (regression) |
| 7 | After Mac sleep, phone screen can idle off | Idle timer re-enabled |

- [ ] **Step 3: Commit only if verification found fixes** — otherwise done; message parent with summary if in DevSwarm.

---

## Spec coverage (self-review)

| Spec requirement | Task |
|---|---|
| `hostSleeping` wire message | 1 |
| COMPATIBILITY optional note | 1 |
| Display sleep + screen lock triggers | 2 |
| `hostDormant` / usable gating | 4 |
| Announce then end; no dial while dormant | 3 + 4 |
| Reconnect with `awaitingWake` when usable | 4 |
| Refresh stale Bonjour targets | 4 |
| Phone `enterSleep` without announcing back | 5 |
| Idle timer so phone can screen-off | 5 |
| No `pv` bump | Global + Task 1 |
| Manual test matrix | 6 |

**Placeholder scan:** none intentional.  
**Type consistency:** `HostSleepObserver.onDormantChange: ((Bool) -> Void)?`, `announceHostSleeping(completion:)`, `enterSleep(announceToMac:status:completion:)`.
