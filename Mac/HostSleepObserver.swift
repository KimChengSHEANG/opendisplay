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

    @objc private func screensDidSleep() {
        Task { @MainActor [weak self] in self?.setScreensAsleep(true) }
    }

    @objc private func screensDidWake() {
        Task { @MainActor [weak self] in self?.setScreensAsleep(false) }
    }

    @objc private func screenIsLocked() {
        Task { @MainActor [weak self] in self?.setScreenLocked(true) }
    }

    @objc private func screenIsUnlocked() {
        Task { @MainActor [weak self] in self?.setScreenLocked(false) }
    }

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
