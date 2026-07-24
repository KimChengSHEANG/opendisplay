import SwiftUI
import Network
import Combine
import Sparkle

/// How the app presents itself. One bundle, switched at runtime via the
/// activation policy — like Raycast/Hammerspoon style background agents.
enum AppPresentation: String, CaseIterable {
    case menuBar, dock, background

    var label: String {
        switch self {
        case .menuBar: return "Menu bar"
        case .dock: return "Dock"
        case .background: return "Background only"
        }
    }
}

@main
struct OpenSidecarMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var controller = SenderController.shared

    var body: some Scene {
        MenuBarExtra(isInserted: Binding(
            get: { controller.presentation == .menuBar },
            set: { _ in }
        )) {
            ContentView(controller: controller, updater: appDelegate.updater)
        } label: {
            Image(systemName: controller.running
                  ? "rectangle.on.rectangle.fill" : "rectangle.on.rectangle")
        }
        .menuBarExtraStyle(.window)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    // Sparkle's standard updater. `startingUpdater: true` boots the updater
    // immediately so scheduled background checks (SUEnableAutomaticChecks)
    // run; the menu item drives manual "Check for Updates…". Held for the
    // app's lifetime here so every window (menu bar + control window) shares
    // one updater instance.
    let updater = SPUStandardUpdaterController(
        startingUpdater: true, updaterDelegate: nil, userDriverDelegate: nil)

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Hand the updater to the control window, which is built outside the
        // SwiftUI App scene (NSHostingView), so it can offer the same button.
        MainWindow.updater = updater
        let presentation = SenderController.shared.presentation
        NSApp.setActivationPolicy(presentation == .dock ? .regular : .accessory)
        if presentation != .menuBar {
            MainWindow.show()
        }
    }

    // Background/Dock modes: opening the app again (Spotlight, Finder, Dock
    // click) brings up the control window — Hammerspoon-style.
    func applicationShouldHandleReopen(_ sender: NSApplication,
                                       hasVisibleWindows: Bool) -> Bool {
        MainWindow.show()
        return false
    }
}

/// The control panel as a regular window, for Dock/background presentation.
@MainActor
enum MainWindow {
    private static var window: NSWindow?
    // Set once at launch by AppDelegate so the control window can share the
    // app's single Sparkle updater.
    static var updater: SPUStandardUpdaterController?

    static func show() {
        if window == nil {
            let w = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 440, height: 540),
                styleMask: [.titled, .closable, .miniaturizable],
                backing: .buffered, defer: false)
            w.title = "OpenDisplay"
            w.contentView = NSHostingView(
                rootView: ContentView(controller: SenderController.shared,
                                      updater: updater))
            w.isReleasedWhenClosed = false
            w.center()
            window = w
        }
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }
}

enum ConnectionTarget: Hashable {
    case usb(udid: String?)           // wired via built-in usbmuxd; nil = first device
    case wifi(NWBrowser.Result)       // discovered via Bonjour
    case androidUsb(serial: String)   // wired via `adb forward` → local TCP

    /// Stable identity for sessions and persistence — survives Bonjour
    /// re-discovery (fresh NWBrowser.Result) and USB replugs (new DeviceID).
    var sessionID: String {
        switch self {
        case .usb(let udid): return "usb:\(udid ?? "first")"
        case .wifi(let result):
            if case .service(let name, _, _, _) = result.endpoint { return "wifi:\(name)" }
            return "wifi:unknown"
        case .androidUsb(let serial): return "adb:\(serial)"
        }
    }
}

/// One connected (or connecting) device: its target, its sender pipeline,
/// and the per-device status the UI shows. Each session owns a full pipeline
/// — virtual display, capture, encoder, socket — so devices are independent:
/// one disconnecting never stalls the others.
@MainActor
final class DeviceSession: ObservableObject, Identifiable {
    nonisolated let id: String
    let target: ConnectionTarget
    let name: String
    let sender: MacSender

    @Published var status = "Starting…"
    @Published var framesSent = 0
    @Published var mbps = 0.0
    // Receiver's per-install identity (from hello) — the key for recognizing
    // the same physical device across USB and WiFi.
    var deviceID: String?
    // "iPhone" / "iPad" from hello — naming fallback while (or in case)
    // lockdown hasn't resolved the device's real name.
    var deviceKind: String?
    // `target` names the identity the session was created for; the live
    // transport can migrate (cable-in upgrade, unplug failover) — these
    // track where the sender actually is right now.
    @Published var onUSB: Bool
    // The udid the session is (or was last) cabled through, so a usbmuxd
    // detach can be matched back to this session for failover.
    var usbUDID: String?
    // ADB serial when this session rides `adb forward` (androidUsb target, or
    // a WiFi session migrated onto the cable). Used for detach/failover the
    // same way `usbUDID` is for usbmux.
    var adbSerial: String?
    // The Bonjour service name this session was started from or failed over
    // to. Kept because browse results routinely arrive without their TXT
    // record (no install id to match on) and the USB device is detached
    // after a failover — the name is then the only link between the session
    // and its service row.
    var wifiServiceName: String?

    var transportLabel: String { onUSB ? "USB" : "WiFi" }

    init(id: String, target: ConnectionTarget, name: String, sender: MacSender) {
        self.id = id
        self.target = target
        self.name = name
        self.sender = sender
        if case .usb(let udid) = target {
            onUSB = true
            usbUDID = udid
        } else if case .androidUsb(let serial) = target {
            // Forward-tunnelled over the cable — a USB transport, but tracked
            // separately (no usbmux DeviceID, so failover uses `adbSerial`).
            onUSB = true
            adbSerial = serial
        } else {
            onUSB = false
        }
    }
}

@MainActor
final class SenderController: ObservableObject {
    static let shared = SenderController()

    @Published var presentation = AppPresentation(
        rawValue: UserDefaults.standard.string(forKey: "presentation") ?? "") ?? .menuBar {
        didSet {
            UserDefaults.standard.set(presentation.rawValue, forKey: "presentation")
            NSApp.setActivationPolicy(presentation == .dock ? .regular : .accessory)
            // Never strand the user without UI: leaving menu-bar mode opens
            // the window immediately.
            if presentation != .menuBar { MainWindow.show() }
        }
    }

    @Published var sessions: [DeviceSession] = []
    @Published var discovered: [NWBrowser.Result] = []
    @Published var usbDevices: [UsbmuxDevice] = []
    @Published var androidDevices: [AdbDevice] = []
    // `-host x.x.x.x` / `-port n` bypass usbmuxd with a manual TCP endpoint
    // (debugging escape hatch, e.g. an iproxy or SSH tunnel).
    @Published var host = UserDefaults.standard.string(forKey: "host") ?? "127.0.0.1"
    @Published var port = UserDefaults.standard.string(forKey: "port") ?? "9000"
    // `-mode mirror` / `-mode extend` launch argument also works.
    @Published var mode = CaptureMode(rawValue: UserDefaults.standard.string(forKey: "mode") ?? "") ?? .extend
    @Published var quality = StreamQuality(rawValue: UserDefaults.standard.string(forKey: "quality") ?? "") ?? .best {
        didSet { UserDefaults.standard.set(quality.rawValue, forKey: "quality") }
    }

    var running: Bool { !sessions.isEmpty }

    private var browser: NWBrowser?
    private var usbWatcher: UsbmuxDeviceWatcher?
    private var adbWatcher: AdbDeviceWatcher?

    // Connection policy — one session per physical device, and the cable
    // wins whenever it's available (lower, steadier latency than WiFi):
    //
    //  - USB devices connect on attach ("plug in and go") unless the user
    //    explicitly disconnected them once (usbDisabled / adbDisabled).
    //  - Plugging the cable in while the device streams over WiFi migrates
    //    the live session onto USB; unplugging it fails over to WiFi when
    //    the device's service is visible — otherwise the session ends after
    //    the usual grace. Migrations swap only the socket (switchTransport):
    //    the virtual display survives, so no screen flash, no window
    //    reshuffle — the earlier no-switching policy existed because
    //    migration used to mean destroying and recreating the session.
    //  - WiFi devices auto-connect when their Bonjour service appears
    //    (receiver app open = intent to use, same as plugging USB) unless
    //    the user explicitly disconnected them (wifiDisabled). A short
    //    post-launch arm delay still prefers the cable for dual-transport
    //    devices before WiFi is dialed.
    // `-autostart NO` disables all auto-connecting, including migrations.
    private var usbDisabled = Set(UserDefaults.standard.stringArray(forKey: "usbDisabled") ?? []) {
        didSet { UserDefaults.standard.set(Array(usbDisabled), forKey: "usbDisabled") }
    }
    // Android forward-USB counterpart of usbDisabled: serials the user
    // explicitly disconnected, so they don't auto-reconnect on the next poll.
    private var adbDisabled = Set(UserDefaults.standard.stringArray(forKey: "adbDisabled") ?? []) {
        didSet { UserDefaults.standard.set(Array(adbDisabled), forKey: "adbDisabled") }
    }
    // WiFi counterparts: session IDs the user explicitly disconnected.
    private var wifiDisabled = Set(UserDefaults.standard.stringArray(forKey: "wifiDisabled") ?? []) {
        didSet { UserDefaults.standard.set(Array(wifiDisabled), forKey: "wifiDisabled") }
    }
    // Legacy: previously gated WiFi auto-connect to "connected once + Mac
    // launch window". Still updated on connect for older builds / debugging,
    // but no longer required for auto-connect (see wifiDisabled).
    private var wifiRemembered = Set(UserDefaults.standard.stringArray(forKey: "wifiRemembered") ?? []) {
        didSet { UserDefaults.standard.set(Array(wifiRemembered), forKey: "wifiRemembered") }
    }
    // Install id learned from each USB device's hello, persisted, so the
    // same hardware is recognized across transports even when the user
    // renamed the advertised service. @Published so the device list regroups
    // the moment an identity is learned.
    @Published private var installIDByUDID: [String: String] =
        UserDefaults.standard.dictionary(forKey: "installIDByUDID") as? [String: String] ?? [:] {
        didSet { UserDefaults.standard.set(installIDByUDID, forKey: "installIDByUDID") }
    }
    // Same map for Android ADB serials — pairs a cable/`adb connect` peer with
    // its Bonjour service so WiFi↔USB upgrade and failover work like usbmux.
    @Published private var installIDByAdbSerial: [String: String] =
        UserDefaults.standard.dictionary(forKey: "installIDByAdbSerial") as? [String: String] ?? [:] {
        didSet { UserDefaults.standard.set(installIDByAdbSerial, forKey: "installIDByAdbSerial") }
    }
    // Receiver kind ("Android", "Chromebook", …) learned from hello — keyed by
    // install id, Bonjour service name, or ADB serial so WiFi rows can show
    // Android vs Chromebook before the user connects again.
    @Published private var knownReceiverKinds: [String: String] =
        UserDefaults.standard.dictionary(forKey: "knownReceiverKinds") as? [String: String] ?? [:] {
        didSet { UserDefaults.standard.set(knownReceiverKinds, forKey: "knownReceiverKinds") }
    }

    /// False when platform-tools `adb` is missing — Android USB is disabled.
    var adbInstalled: Bool { Adb.findAdb() != nil }
    private let autoConnectEnabled = UserDefaults.standard.object(forKey: "autostart") == nil
        || UserDefaults.standard.bool(forKey: "autostart")

    // Bonjour usually reports devices before usbmuxd does — WiFi auto-connect
    // waits out this arm delay so a cabled device is dialed over USB first.
    private var wifiAutoConnectArmed = false

    // The Mac is asleep or locked: sessions are parked (announced + ended)
    // rather than left to time out, and reconnecting is deferred until the
    // host is usable again. Edge-driven by `hostSleepObserver`.
    private var hostDormant = false
    private var pendingWakeTargets: [ConnectionTarget] = []
    private let hostSleepObserver = HostSleepObserver()

    init() {
        startBrowsing()
        usbWatcher = UsbmuxDeviceWatcher { [weak self] devices in
            guard let self else { return }
            let detached = Set(self.usbDevices.map(\.udid)).subtracting(devices.map(\.udid))
            self.usbDevices = devices
            self.failover(detachedUDIDs: detached)
            self.autoConnect()
        }
        adbWatcher = AdbDeviceWatcher { [weak self] devices in
            guard let self else { return }
            let detached = Set(self.androidDevices.map(\.serial)).subtracting(devices.map(\.serial))
            self.androidDevices = devices
            self.androidDetached(detached)
            self.autoConnect()
        }
        adbWatcher?.start()
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(2))
            self.wifiAutoConnectArmed = true
            self.autoConnect()
        }

        hostSleepObserver.onDormantChange = { [weak self] dormant in
            guard let self else { return }
            if dormant { self.hostBecameDormant() } else { self.hostBecameUsable() }
        }
        hostSleepObserver.start()
        // If we launched already locked, treat as dormant with no sessions
        // yet — `start()` only reports the initial state, it doesn't fire
        // the edge-only callback for it.
        if hostSleepObserver.isDormant {
            hostDormant = true
        }
    }

    private func startBrowsing() {
        // TXT records carry the receiver's install id (new receivers).
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_opensidecar._tcp", domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                self.discovered = Array(results)
                self.endSessionsWhoseServiceVanished()
                self.autoConnect()
            }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    // MARK: - Physical-device identity

    private func serviceName(of result: NWBrowser.Result) -> String? {
        if case .service(let name, _, _, _) = result.endpoint { return name }
        return nil
    }

    private func txtID(of result: NWBrowser.Result) -> String? {
        if case .bonjour(let txt) = result.metadata { return txt["id"] }
        return nil
    }

    private func txtDeviceKind(of result: NWBrowser.Result) -> String? {
        if case .bonjour(let txt) = result.metadata { return txt["device"] }
        return nil
    }

    /// Android/Chromebook kind for a WiFi peer, when known from TXT, a past
    /// hello, or the live session.
    private func androidKindHint(forWiFi result: NWBrowser.Result) -> String? {
        if let session = activeSession(coveringWiFi: result), let kind = session.deviceKind,
           kind == "Android" || kind == "Chromebook" { return kind }
        if let kind = txtDeviceKind(of: result),
           kind == "Android" || kind == "Chromebook" { return kind }
        if let name = serviceName(of: result), let kind = knownReceiverKinds["wifi:\(name)"],
           kind == "Android" || kind == "Chromebook" { return kind }
        if let id = txtID(of: result), let kind = knownReceiverKinds[id],
           kind == "Android" || kind == "Chromebook" { return kind }
        return nil
    }

    private func rememberReceiverKind(_ kind: String, installID: String?,
                                      target: ConnectionTarget) {
        if let installID { knownReceiverKinds[installID] = kind }
        switch target {
        case .wifi(let result):
            if let name = serviceName(of: result) {
                knownReceiverKinds["wifi:\(name)"] = kind
                // Pair any ADB serial already known for this install id.
                if let installID,
                   let serial = installIDByAdbSerial.first(where: { $0.value == installID })?.key {
                    knownReceiverKinds["adb:\(serial):wifiName"] = name
                }
            }
        case .androidUsb(let serial):
            knownReceiverKinds["adb:\(serial)"] = kind
        default:
            break
        }
    }

    /// Same hardware? Strong match: the service's install id equals the id
    /// this USB device announced in a (past or present) hello. Fallback for
    /// old receivers: lockdown device name equals the service name.
    private func sameDevice(_ result: NWBrowser.Result, _ device: UsbmuxDevice) -> Bool {
        if let id = txtID(of: result), installIDByUDID[device.udid] == id { return true }
        if let name = serviceName(of: result), let usbName = device.name,
           usbName == name { return true }
        return false
    }

    /// WiFi Bonjour peer and an ADB serial are the same Android/Chromebook.
    private func sameAndroidDevice(_ result: NWBrowser.Result, serial: String) -> Bool {
        if let id = txtID(of: result), installIDByAdbSerial[serial] == id { return true }
        // Service name previously paired to this serial (from hello on either side).
        if let name = serviceName(of: result),
           knownReceiverKinds["adb:\(serial):wifiName"] == name {
            return true
        }
        return false
    }

    /// An attached, auto-connectable USB / ADB device is (about to be) dialed
    /// over the cable — its WiFi service must not be grabbed in the launch race.
    private func cabled(_ result: NWBrowser.Result) -> Bool {
        if usbDevices.contains(where: {
            sameDevice(result, $0) && !usbDisabled.contains("usb:\($0.udid)")
        }) { return true }
        return androidDevices.contains(where: {
            $0.authorized
                && !adbDisabled.contains(ConnectionTarget.androidUsb(serial: $0.serial).sessionID)
                && sameAndroidDevice(result, serial: $0.serial)
        })
    }

    /// The session (over either transport) already serving this USB device.
    private func activeSession(coveringUSB device: UsbmuxDevice) -> DeviceSession? {
        if let direct = session(for: "usb:\(device.udid)") { return direct }
        return sessions.first { s in
            guard case .wifi(let result) = s.target else { return false }
            if let id = installIDByUDID[device.udid],
               s.deviceID == id || txtID(of: result) == id { return true }
            return serviceName(of: result) != nil && device.name == serviceName(of: result)
        }
    }

    /// The session (over either transport) already serving this WiFi service.
    private func activeSession(coveringWiFi result: NWBrowser.Result) -> DeviceSession? {
        if let name = serviceName(of: result), let direct = session(for: "wifi:\(name)") {
            return direct
        }
        return sessions.first { s in
            if case .usb(let udid) = s.target {
                if let id = txtID(of: result), s.deviceID == id { return true }
                if let udid, let device = usbDevices.first(where: { $0.udid == udid }),
                   sameDevice(result, device) { return true }
                let name = serviceName(of: result)
                return name != nil && (name == s.wifiServiceName || name == s.name)
            }
            if case .androidUsb(let serial) = s.target {
                return sameAndroidDevice(result, serial: serial)
                    || (txtID(of: result).map { installIDByAdbSerial[serial] == $0 } ?? false)
                    || (s.deviceID != nil && s.deviceID == txtID(of: result))
            }
            // WiFi-origin session migrated onto ADB — still covers this service.
            if let serial = s.adbSerial, s.onUSB {
                return sameAndroidDevice(result, serial: serial)
                    || (s.deviceID != nil && s.deviceID == txtID(of: result))
            }
            return false
        }
    }

    /// The session already serving this ADB serial (androidUsb target or a
    /// WiFi session migrated onto the forward tunnel).
    private func activeSession(coveringAndroid serial: String) -> DeviceSession? {
        if let direct = session(for: ConnectionTarget.androidUsb(serial: serial).sessionID) {
            return direct
        }
        if let id = installIDByAdbSerial[serial],
           let byID = sessions.first(where: { $0.deviceID == id }) {
            return byID
        }
        return sessions.first { $0.adbSerial == serial }
            ?? discovered.first(where: { sameAndroidDevice($0, serial: serial) })
                .flatMap { activeSession(coveringWiFi: $0) }
    }

    // MARK: - Connection policy

    private func autoConnect() {
        guard autoConnectEnabled else { return }
        guard !hostDormant else { return }
        dedupeSessions()
        // The -host/-port escape hatch is an explicit choice — dial it like
        // the wired devices (it joins them, not replaces them).
        if UserDefaults.standard.object(forKey: "host") != nil,
           !usbDisabled.contains("usb:first"), session(for: "usb:first") == nil {
            connect(to: .usb(udid: nil))
        }
        for device in usbDevices {
            if let covering = activeSession(coveringUSB: device) {
                // usbDisabled gates auto-connecting a device, not the
                // transport of a session the user deliberately has running —
                // however it was started, the cable is better: take it.
                upgradeToUSB(covering, device: device)
            } else if !usbDisabled.contains("usb:\(device.udid)") {
                connect(to: .usb(udid: device.udid))
            }
        }
        // Android over ADB (USB cable or `adb connect`): prefer migrating a
        // live WiFi session onto the forward tunnel (iOS cable-upgrade parity);
        // otherwise auto-connect authorized serials the user hasn't opted out of.
        for device in androidDevices where device.authorized {
            let target = ConnectionTarget.androidUsb(serial: device.serial)
            if let covering = activeSession(coveringAndroid: device.serial) {
                upgradeToAndroidUSB(covering, serial: device.serial)
            } else if !adbDisabled.contains(target.sessionID),
                      session(for: target.sessionID) == nil {
                connect(to: target)
            }
        }
        guard wifiAutoConnectArmed else { return }
        // OpenDisplay only advertises while the receiver app is open — a
        // Bonjour appearance is the WiFi analogue of plugging in USB. Dial
        // every visible service the user hasn't opted out of; the arm delay
        // above still lets dual-transport devices take the cable first.
        for result in discovered {
            let target = ConnectionTarget.wifi(result)
            if !wifiDisabled.contains(target.sessionID),
               activeSession(coveringWiFi: result) == nil,
               !cabled(result) {
                connect(to: target)
            }
        }
    }

    // MARK: - Host dormancy (sleep/lock)

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
            session.sender.announceHostSleeping { [weak self, weak session] in
                guard let self, let session else { return }
                // Session may already be gone if peer disconnected mid-announce,
                // or replaced by a fresh reconnect if the host woke up first —
                // only end it if it's still the exact same session object.
                if let current = self.sessions.first(where: { $0.id == session.id }), current === session {
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
            // If the announce-then-end from `hostBecameDormant` hasn't landed
            // yet, the old session is still parked in `sessions` and would
            // make `connect` no-op below — force-end it first.
            if let lingering = session(for: target.sessionID) {
                Log.info("host usable — ending lingering session \(lingering.id) before wake reconnect")
                end(lingering)
            }
            connect(to: refreshed(target), awaitingWake: true)
        }
    }

    /// Prefer a live Bonjour result after a long sleep; USB targets are stable.
    private func refreshed(_ target: ConnectionTarget) -> ConnectionTarget {
        switch target {
        case .usb, .androidUsb:
            return target
        case .wifi:
            let id = target.sessionID
            if let fresh = discovered.first(where: { ConnectionTarget.wifi($0).sessionID == id }) {
                return .wifi(fresh)
            }
            return target
        }
    }

    /// Cable plugged in while the device streams over WiFi: migrate the live
    /// session onto USB. No-op when the session is already cabled.
    private func upgradeToUSB(_ session: DeviceSession, device: UsbmuxDevice) {
        guard !session.onUSB, let portNum = UInt16(port) else { return }
        Log.info("cable attached for \(session.id) — migrating to USB")
        session.onUSB = true
        session.usbUDID = device.udid
        // The match may have been by name only — pin the strong identity so
        // future matching (and the next launch) recognizes the pair.
        if let id = session.deviceID { installIDByUDID[device.udid] = id }
        session.sender.switchTransport(to: .usb(udid: device.udid, port: portNum))
    }

    /// ADB appears while the device streams over WiFi: migrate onto
    /// `adb forward` (phones over USB cable, or Chromebook via `adb connect`).
    private func upgradeToAndroidUSB(_ session: DeviceSession, serial: String) {
        // Already on this ADB tunnel — nothing to do.
        if session.onUSB, session.adbSerial == serial { return }
        guard let portNum = UInt16(port) else { return }
        Log.info("adb attached for \(session.id) — migrating to Android USB (\(serial))")
        do {
            try Adb.forward(serial: serial, port: portNum)
        } catch {
            Log.info("adb forward failed for \(serial): \(error)")
            return
        }
        session.onUSB = true
        session.adbSerial = serial
        if let id = session.deviceID { installIDByAdbSerial[serial] = id }
        let wifiName = session.wifiServiceName
            ?? discovered.first(where: { sameAndroidDevice($0, serial: serial) }).flatMap(serviceName)
        if let wifiName {
            knownReceiverKinds["adb:\(serial):wifiName"] = wifiName
            session.wifiServiceName = wifiName
        }
        session.sender.switchTransport(to: .tcp(.hostPort(
            host: "127.0.0.1",
            port: NWEndpoint.Port(rawValue: portNum)!)))
    }

    /// Cable unplugged under a live session: fail over to the device's WiFi
    /// service if one is visible. Without one the session keeps its normal
    /// fate — retry over USB through the grace period, then end.
    private func failover(detachedUDIDs: Set<String>) {
        guard autoConnectEnabled, !detachedUDIDs.isEmpty else { return }
        for session in sessions where session.onUSB {
            guard let udid = session.usbUDID, detachedUDIDs.contains(udid),
                  let result = wifiService(for: session) else { continue }
            Log.info("cable detached for \(session.id) — failing over to WiFi")
            session.onUSB = false
            session.wifiServiceName = serviceName(of: result)
            session.sender.switchTransport(to: .tcp(result.endpoint))
        }
    }

    /// ADB serial gone (`adb devices` no longer lists it): fail over to the
    /// device's Bonjour service when visible (iOS unplug parity). Otherwise
    /// end the session and clear the dangling forward.
    private func androidDetached(_ detachedSerials: Set<String>) {
        guard !detachedSerials.isEmpty else { return }
        for serial in detachedSerials {
            try? Adb.clearForward(serial: serial)
            guard let session = activeSession(coveringAndroid: serial),
                  session.adbSerial == serial || session.id == ConnectionTarget.androidUsb(serial: serial).sessionID
            else { continue }
            if let result = wifiService(for: session) {
                Log.info("adb device \(serial) detached — failing over to WiFi")
                session.onUSB = false
                session.adbSerial = nil
                session.wifiServiceName = serviceName(of: result)
                session.sender.switchTransport(to: .tcp(result.endpoint))
            } else {
                Log.info("adb device \(serial) detached — ending session \(session.id)")
                end(session)
            }
        }
    }

    /// A quit receiver app loses its Bonjour advertisement within ~1s, far
    /// faster than WiFi dial timeouts can notice (dials to a withdrawn
    /// service stall rather than getting refused). Report the withdrawal to
    /// each live WiFi session's sender; it only acts if its connection is
    /// already down too, which together proves the app is gone. Debounced
    /// 3s: an mDNS record can drop briefly during a WiFi roam — only a
    /// withdrawal that persists counts. One-shot, guarded re-check, so
    /// overlapping browse events at worst repeat an idempotent call.
    private func endSessionsWhoseServiceVanished() {
        for session in sessions where !session.onUSB {
            guard wifiService(for: session) == nil else { continue }
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self, weak session] in
                guard let self, let session,
                      self.sessions.contains(where: { $0 === session }),
                      self.wifiService(for: session) == nil else { return }
                session.sender.peerServiceWithdrawn()
            }
        }
    }

    /// The discovered WiFi service belonging to this session's device.
    private func wifiService(for session: DeviceSession) -> NWBrowser.Result? {
        discovered.first { result in
            if let id = txtID(of: result), let deviceID = session.deviceID {
                return id == deviceID
            }
            let name = serviceName(of: result)
            return name != nil && (name == session.wifiServiceName || name == session.name)
        }
    }

    /// Safety net, not a feature: if identity was learned too late (old
    /// receiver, renamed service) and one physical device ended up with two
    /// sessions, the transports steal the receiver's single connection from
    /// each other forever. Keep the cable, drop the WiFi twin.
    private func dedupeSessions() {
        let usbSessionIDs = Set(sessions.compactMap { s -> String? in
            if case .usb = s.target { return s.deviceID }
            return nil
        })
        let cabledNames = Set(usbDevices.compactMap { device in
            session(for: "usb:\(device.udid)") != nil ? device.name : nil
        })
        // Install ids currently served over the Android forward-USB cable.
        // Same rule as iPhone/iPad usbmux: the cable wins over WiFi for the
        // same physical device (the receiver holds one connection, so a WiFi
        // twin would fight the cable for it). Matched on the install id from
        // hello, so it fires the moment the androidUsb session identifies.
        let androidCabledInstallIDs = Set(sessions.compactMap { s -> String? in
            if case .androidUsb = s.target { return s.deviceID }
            return nil
        })
        for s in sessions {
            guard case .wifi(let result) = s.target else { continue }
            let duplicate = (s.deviceID.map { usbSessionIDs.contains($0) } ?? false)
                || (txtID(of: result).map { usbSessionIDs.contains($0) } ?? false)
                || (serviceName(of: result).map { cabledNames.contains($0) } ?? false)
                || (s.deviceID.map { androidCabledInstallIDs.contains($0) } ?? false)
                || (txtID(of: result).map { androidCabledInstallIDs.contains($0) } ?? false)
            if duplicate {
                Log.info("two sessions for one device — keeping the cable, dropping \(s.id)")
                end(s)
            }
        }
    }

    /// Human-readable device name for a target (no transport suffix — the
    /// UI shows transports separately).
    func label(for target: ConnectionTarget) -> String {
        switch target {
        case .usb(let udid):
            if let device = usbDevices.first(where: { $0.udid == udid }), let name = device.name {
                return name
            }
            return udid == nil ? "Manual (\(host):\(port))" : "iPhone / iPad"
        case .wifi(let result):
            return serviceName(of: result) ?? "WiFi device"
        case .androidUsb:
            return "Android (USB)"
        }
    }

    func session(for id: String) -> DeviceSession? {
        sessions.first { $0.id == id }
    }

    /// Derive a stable, per-device display serial from the session identity.
    /// FNV-1a over the id string; macOS keys saved display arrangement on
    /// vendor/product/serial, so each device keeps its screen position.
    private static func displaySerial(for id: String) -> UInt32 {
        var hash: UInt32 = 2_166_136_261
        for byte in id.utf8 { hash = (hash ^ UInt32(byte)) &* 16_777_619 }
        return hash == 0 ? 1 : hash
    }

    func connect(to target: ConnectionTarget, userInitiated: Bool = false,
                 awaitingWake: Bool = false) {
        // While the Mac is asleep/locked, don't start sessions — the display
        // is invisible either way. `hostBecameUsable` clears the flag before
        // calling back in, so genuine wake-reconnects still go through.
        if hostDormant {
            let id = target.sessionID
            if !pendingWakeTargets.contains(where: { $0.sessionID == id }) {
                pendingWakeTargets.append(target)
            }
            Log.info("connect(\(id)) deferred — host dormant")
            return
        }

        let id = target.sessionID
        guard session(for: id) == nil else { return }

        // Never create a second session for the same physical device — the
        // receiver holds one connection, so a twin would steal it. But an
        // explicit user click overrides: e.g. right after unplugging the
        // cable, the dying USB session sits in its 10s reconnect grace and
        // would otherwise swallow the tap on the WiFi row.
        let covering: DeviceSession?
        switch target {
        case .usb(let udid?):
            covering = usbDevices.first(where: { $0.udid == udid })
                .flatMap { activeSession(coveringUSB: $0) }
        case .wifi(let result):
            covering = activeSession(coveringWiFi: result)
        default:
            covering = nil
        }
        if let covering {
            guard userInitiated else { return }
            Log.info("user chose \(id) — taking over from \(covering.id)")
            end(covering)
        }

        // Connecting a device clears its "don't auto-connect" state.
        switch target {
        case .usb: usbDisabled.remove(id)
        case .wifi:
            wifiDisabled.remove(id)
            wifiRemembered.insert(id)
        case .androidUsb: adbDisabled.remove(id)
        }

        let transport: SenderTransport
        switch target {
        case .usb(let udid):
            guard let portNum = UInt16(port) else { return }
            if UserDefaults.standard.object(forKey: "host") != nil, udid == nil {
                // Manual override: dial a plain TCP endpoint instead of usbmuxd.
                transport = .tcp(.hostPort(host: NWEndpoint.Host(host),
                                           port: NWEndpoint.Port(rawValue: portNum)!))
            } else {
                transport = .usb(udid: udid, port: portNum)
            }
        case .wifi(let result):
            transport = .tcp(result.endpoint)
        case .androidUsb(let serial):
            guard let portNum = UInt16(port) else { return }
            // Open the forward tunnel before dialing: this Mac's
            // localhost:port now routes to the phone's, where the receiver
            // listens, so the plain TCP sender reaches it at 127.0.0.1 as if
            // it were local.
            do {
                try Adb.forward(serial: serial, port: portNum)
            } catch {
                Log.info("adb forward failed for \(serial): \(error)")
                return
            }
            transport = .tcp(.hostPort(host: "127.0.0.1",
                                       port: NWEndpoint.Port(rawValue: portNum)!))
        }

        let name = label(for: target)
        let sender = MacSender(transport: transport, name: name, mode: mode,
                               quality: quality, displaySerial: Self.displaySerial(for: id),
                               awaitingWake: awaitingWake)
        let session = DeviceSession(id: id, target: target, name: name, sender: sender)
        if case .wifi(let result) = target {
            session.wifiServiceName = serviceName(of: result)
        }
        sender.onStatus = { [weak session] text in
            session?.status = text
            Log.info("status[\(id)]: \(text)")
        }
        sender.onHello = { [weak self, weak session] info in
            guard let self, let session else { return }
            session.deviceID = info.id
            session.deviceKind = info.device
            if let kind = info.device {
                self.rememberReceiverKind(kind, installID: info.id, target: session.target)
            }
            if case .usb(let udid?) = session.target, let installID = info.id {
                self.installIDByUDID[udid] = installID
            }
            if let serial = session.adbSerial, let installID = info.id {
                self.installIDByAdbSerial[serial] = installID
            }
            if case .androidUsb(let serial) = session.target, let installID = info.id {
                self.installIDByAdbSerial[serial] = installID
            }
            // Pair ADB serial ↔ Bonjour name once we know the install id.
            if let installID = info.id, let name = session.wifiServiceName {
                for (serial, id) in self.installIDByAdbSerial where id == installID {
                    self.knownReceiverKinds["adb:\(serial):wifiName"] = name
                }
            }
            self.dedupeSessions()
            // The learned identity may reveal that this WiFi session's device
            // is cabled — take the upgrade opportunity right away.
            self.autoConnect()
        }
        sender.onStats = { [weak session] frames, mbps in
            session?.framesSent = frames
            session?.mbps = mbps
        }
        sender.onDisconnected = { [weak self, weak session] in
            // iOS keeps the TCP link alive across an app switch, so this path
            // is "device really gone". Android freezes the process when the
            // user leaves the receiver, so the link dies — treat that like
            // sleep and keep dialing until they return (ensureListening).
            guard let self, let session else { return }
            let target = session.target
            let androidPeer: Bool = {
                if case .androidUsb = target { return true }
                if let kind = session.deviceKind,
                   kind == "Android" || kind == "Chromebook" { return true }
                return false
            }()
            if androidPeer {
                Log.info("android peer disconnected — arming wake reconnect for \(session.id)")
                self.end(session)
                self.connect(to: target, awaitingWake: true)
            } else {
                Log.info("device disconnected — session \(session.id) stopped")
                self.end(session)
            }
        }
        sender.onPeerSleeping = { [weak self, weak session] in
            // The device locked. Unlike a plain disconnect this is a
            // known-temporary state announced by the receiver, so ending
            // the session (which frees the cursor from the now-invisible
            // display) is paired with a replacement session that dials
            // patiently until the device wakes and accepts again.
            guard let self, let session else { return }
            let target = session.target
            Log.info("session \(session.id) asleep — display down, waiting for wake")
            self.end(session)
            self.connect(to: target, awaitingWake: true)
        }
        sender.onPeerClosed = { [weak self, weak session] in
            // The receiver app quit — a deliberate goodbye, so no reconnect
            // waits around. Reopening the app is a fresh start handled by
            // the normal discovery/auto-connect paths.
            guard let self, let session else { return }
            Log.info("session \(session.id) closed by the receiver — ending")
            self.end(session)
        }
        sessions.append(session)
        Task {
            do {
                try await sender.start()
            } catch is CancellationError {
                // stopped by the user while waiting — nothing to report
            } catch {
                Log.info("sender failed to start: \(error)")
                session.status = "Failed: \(error.localizedDescription)"
            }
        }
    }

    /// User-initiated disconnect: also opt the device out of auto-connect.
    func disconnect(_ session: DeviceSession) {
        switch session.target {
        case .usb: usbDisabled.insert(session.id)
        case .wifi:
            wifiDisabled.insert(session.id)
            wifiRemembered.remove(session.id)
        case .androidUsb(let serial):
            adbDisabled.insert(session.id)
            try? Adb.clearForward(serial: serial)
        }
        // A migrated session is also reachable the other way — opt that side
        // out too, or auto-connect resurrects the device moments later.
        if session.onUSB, let udid = session.usbUDID { usbDisabled.insert("usb:\(udid)") }
        if let serial = session.adbSerial {
            adbDisabled.insert(ConnectionTarget.androidUsb(serial: serial).sessionID)
            try? Adb.clearForward(serial: serial)
        }
        if let name = session.wifiServiceName {
            wifiDisabled.insert("wifi:\(name)")
            wifiRemembered.remove("wifi:\(name)")
        }
        end(session)
    }

    func disconnectAll() {
        sessions.forEach { disconnect($0) }
    }

    private func end(_ session: DeviceSession) {
        session.sender.stop()
        sessions.removeAll { $0.id == session.id }
    }

    /// Mode/quality apply per-pipeline at construction — rebuild every session.
    func restartAll() {
        guard running else { return }
        let targets = sessions.map(\.target)
        sessions.forEach { $0.sender.stop() }
        sessions.removeAll()
        targets.forEach { connect(to: $0) }
        autoConnect()   // a rebuilt WiFi session may deserve its cable back
    }

    // MARK: - Device list (one row per physical device)

    struct DeviceEntry: Identifiable {
        let id: String
        let name: String
        let usbTarget: ConnectionTarget?
        let wifiTarget: ConnectionTarget?
        /// "Android" or "Chromebook" when known — shown in the transport line.
        let kindHint: String?

        init(id: String, name: String, usbTarget: ConnectionTarget?,
             wifiTarget: ConnectionTarget?, kindHint: String? = nil) {
            self.id = id
            self.name = name
            self.usbTarget = usbTarget
            self.wifiTarget = wifiTarget
            self.kindHint = kindHint
        }

        var transportLabel: String {
            let base: String
            switch (usbTarget != nil, wifiTarget != nil) {
            case (true, true): base = "USB · WiFi"
            case (true, false): base = "USB"
            case (false, true): base = "WiFi"
            default: base = ""
            }
            if let kindHint, kindHint == "Android" || kindHint == "Chromebook" {
                return base.isEmpty ? kindHint : "\(base) · \(kindHint)"
            }
            return base
        }
        /// Lowest latency first.
        var preferredTarget: ConnectionTarget? { usbTarget ?? wifiTarget }
    }

    var deviceEntries: [DeviceEntry] {
        var entries: [DeviceEntry] = []
        var mergedServices = Set<String>()
        var coveredSessionIDs = Set<String>()

        for device in usbDevices {
            // A discovered WiFi service for the same hardware folds into
            // this row instead of appearing as a second device.
            let twin = discovered.first { sameDevice($0, device) }
            if let twin, let name = serviceName(of: twin) { mergedServices.insert(name) }
            let usbTarget = ConnectionTarget.usb(udid: device.udid)
            coveredSessionIDs.insert(usbTarget.sessionID)
            if let twin { coveredSessionIDs.insert(ConnectionTarget.wifi(twin).sessionID) }
            // A WiFi-identity session migrated onto this cable serves the
            // device even when its service is no longer advertised.
            if let covering = activeSession(coveringUSB: device) {
                coveredSessionIDs.insert(covering.id)
            }
            entries.append(DeviceEntry(
                id: "device:\(device.udid)",
                name: device.name
                    ?? twin.flatMap(serviceName)
                    ?? session(for: usbTarget.sessionID)?.deviceKind
                    ?? "iPhone / iPad",
                usbTarget: usbTarget,
                wifiTarget: twin.map { .wifi($0) }))
        }
        if UserDefaults.standard.object(forKey: "host") != nil {
            let target = ConnectionTarget.usb(udid: nil)
            coveredSessionIDs.insert(target.sessionID)
            entries.append(DeviceEntry(id: target.sessionID, name: label(for: target),
                                       usbTarget: target, wifiTarget: nil))
        }
        for device in androidDevices {
            let target = ConnectionTarget.androidUsb(serial: device.serial)
            coveredSessionIDs.insert(target.sessionID)
            // Fold the matching Bonjour service into this row (iOS USB+WiFi
            // twin behavior) so Chromebooks/`adb connect` don't show twice.
            let twin = discovered.first { sameAndroidDevice($0, serial: device.serial) }
            if let twin, let name = serviceName(of: twin) { mergedServices.insert(name) }
            if let twin { coveredSessionIDs.insert(ConnectionTarget.wifi(twin).sessionID) }
            if let covering = activeSession(coveringAndroid: device.serial) {
                coveredSessionIDs.insert(covering.id)
            }
            let kind = knownReceiverKinds["adb:\(device.serial)"]
                ?? twin.flatMap { androidKindHint(forWiFi: $0) }
                ?? "Android"
            let displayName = twin.flatMap(serviceName)
                ?? (device.authorized ? device.label : "\(device.label) — tap Allow on phone")
            entries.append(DeviceEntry(
                id: "android:\(device.serial)",
                name: displayName,
                usbTarget: device.authorized ? target : nil,
                wifiTarget: twin.map { .wifi($0) },
                kindHint: kind == "Chromebook" ? kind : "Android"))
        }
        for result in discovered {
            guard let name = serviceName(of: result), !mergedServices.contains(name)
            else { continue }
            let target = ConnectionTarget.wifi(result)
            coveredSessionIDs.insert(target.sessionID)
            // A USB-identity session that failed over to WiFi serves this
            // service — claim it, or it would dangle as a second row and
            // this one would offer a Connect that steals the receiver.
            if let covering = activeSession(coveringWiFi: result) {
                coveredSessionIDs.insert(covering.id)
            }
            entries.append(DeviceEntry(
                id: "service:\(name)", name: name,
                usbTarget: nil, wifiTarget: target,
                kindHint: androidKindHint(forWiFi: result)))
        }
        // Sessions whose device vanished from discovery (e.g. Bonjour record
        // gone while the stream is still alive) keep a row to disconnect.
        for session in sessions where !coveredSessionIDs.contains(session.id) {
            entries.append(DeviceEntry(id: session.id, name: session.name,
                                       usbTarget: nil, wifiTarget: nil))
        }
        return entries
    }

    func session(for entry: DeviceEntry) -> DeviceSession? {
        if let target = entry.usbTarget {
            if let s = session(for: target.sessionID) { return s }
            if case .usb(let udid?) = target,
               let device = usbDevices.first(where: { $0.udid == udid }),
               let s = activeSession(coveringUSB: device) { return s }
        }
        if let target = entry.wifiTarget {
            if let s = session(for: target.sessionID) { return s }
            // Transport-migrated sessions keep their original identity — a
            // USB-identity session failed over to WiFi still owns this row.
            if case .wifi(let result) = target,
               let s = activeSession(coveringWiFi: result) { return s }
        }
        return session(for: entry.id)   // dangling-session rows
    }
}

/// Polls the permission states the app depends on so the UI can surface
/// exactly what's missing instead of failing silently.
@MainActor
final class PermissionMonitor: ObservableObject {
    @Published var screenRecording = false
    @Published var accessibility = false
    private var timer: Timer?

    init() {
        refresh()
        timer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { _ in
            Task { @MainActor in self.refresh() }
        }
    }

    func refresh() {
        screenRecording = CGPreflightScreenCaptureAccess()
        accessibility = AXIsProcessTrusted()
    }

    /// Fire the system permission dialog on demand. macOS only shows each
    /// dialog once per reset — after that the call just (re)registers the
    /// app in System Settings, so the row exists to toggle manually.
    func requestScreenRecording() {
        CGRequestScreenCaptureAccess()
        refresh()
    }

    func requestAccessibility() {
        _ = InputInjector.ensureAccessibilityPermission()
        refresh()
    }

    static func openPrivacyPane(_ anchor: String) {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?\(anchor)") {
            NSWorkspace.shared.open(url)
        }
    }
}

struct ContentView: View {
    @ObservedObject var controller: SenderController
    @StateObject private var permissions = PermissionMonitor()
    // Optional so the view still compiles/previews without an updater (e.g.
    // if Sparkle ever fails to start); the button just disables itself then.
    let updater: SPUStandardUpdaterController?

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack(spacing: 12) {
                Image(nsImage: NSApp.applicationIconImage)
                    .resizable()
                    .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text("OpenDisplay")
                        .font(.title3.bold())
                    Text("Your iPads and iPhones as extra displays")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if controller.running {
                    Button("Disconnect All") { controller.disconnectAll() }
                        .controlSize(.large)
                }
            }
            .padding(16)

            Divider()

            // Settings
            Form {
                Section("Devices") {
                    if !controller.adbInstalled {
                        Text("Android USB requires adb (Android platform-tools). Install with `brew install --cask android-platform-tools`, enable USB debugging on the phone, and tap Allow when prompted.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if controller.deviceEntries.isEmpty {
                        Text("No devices found — plug in an iPhone/iPad or Android phone via USB, or open OpenDisplay on a device on this WiFi network (Android phones, tablets, and Chromebooks over WiFi).")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(controller.deviceEntries) { entry in
                        if let session = controller.session(for: entry) {
                            // Title from the entry, not the session: the
                            // session name was snapshotted at connect time,
                            // often before lockdown resolved the real name.
                            SessionRow(title: entry.name, session: session,
                                       controller: controller)
                        } else {
                            HStack(alignment: .firstTextBaseline) {
                                Circle()
                                    .fill(.secondary.opacity(0.5))
                                    .frame(width: 9, height: 9)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(entry.name)
                                    Text(entry.transportLabel)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if let target = entry.preferredTarget {
                                    Button("Connect") {
                                        controller.connect(to: target, userInitiated: true)
                                    }
                                    .controlSize(.small)
                                }
                            }
                        }
                    }
                }

                Picker("Mode", selection: $controller.mode) {
                    Text("Extend").tag(CaptureMode.extend)
                    Text("Mirror").tag(CaptureMode.mirror)
                }
                .pickerStyle(.segmented)
                .onChange(of: controller.mode) { controller.restartAll() }

                VStack(alignment: .leading, spacing: 4) {
                    Picker("Quality", selection: $controller.quality) {
                        ForEach(StreamQuality.allCases, id: \.self) { q in
                            Text(q.label).tag(q)
                        }
                    }
                    .onChange(of: controller.quality) { controller.restartAll() }
                    Text(controller.quality.explanation)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                VStack(alignment: .leading, spacing: 4) {
                    Picker("Show app in", selection: $controller.presentation) {
                        ForEach(AppPresentation.allCases, id: \.self) { p in
                            Text(p.label).tag(p)
                        }
                    }
                    if controller.presentation == .background {
                        Text("No menu bar or Dock icon — streaming keeps running. Open the OpenDisplay app again (Spotlight/Finder) to show this window.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                LabeledContent("Display layout") {
                    Button("Arrange Displays…") {
                        if let url = URL(string: "x-apple.systempreferences:com.apple.Displays-Settings.extension") {
                            NSWorkspace.shared.open(url)
                        }
                    }
                    .controlSize(.small)
                }
                .help("Opens System Settings → Displays, where you can position the extended displays relative to your Mac screen (Arrange…). Each device shows up as its own display, named after the device.")

                Section("Permissions") {
                    permissionRow(
                        "Screen Recording",
                        granted: permissions.screenRecording,
                        help: "Required to capture the display.",
                        anchor: "Privacy_ScreenCapture",
                        request: { permissions.requestScreenRecording() }
                    )
                    permissionRow(
                        "Accessibility",
                        granted: permissions.accessibility,
                        help: "Required for touch input from the device.",
                        anchor: "Privacy_Accessibility",
                        request: { permissions.requestAccessibility() }
                    )
                    // macOS offers no API to query Local Network access, so
                    // infer from discovery results and let the user check.
                    permissionRow(
                        "Local Network",
                        granted: !controller.discovered.isEmpty,
                        uncertain: controller.discovered.isEmpty,
                        help: "Required for WiFi mode. If no device appears in the Devices list, allow OpenDisplay under Privacy & Security → Local Network on this Mac AND on the device — and keep the OpenDisplay app open there.",
                        anchor: "Privacy_LocalNetwork"
                    )
                }
            }
            .formStyle(.grouped)
            // Scrollable + fixed panel height: MenuBarExtra windows mis-measure
            // grouped Forms (clipping on small displays), so size explicitly
            // and let the form scroll when it doesn't fit.

            Divider()

            // Status bar
            HStack(spacing: 8) {
                Circle()
                    .fill(controller.running ? .green : .secondary.opacity(0.5))
                    .frame(width: 9, height: 9)
                Text(controller.running
                     ? "\(controller.sessions.count) device\(controller.sessions.count == 1 ? "" : "s") connected"
                     : "Idle")
                    .font(.callout)
                    .lineLimit(1)
                Spacer()
                if let updater {
                    CheckForUpdatesView(updater: updater)
                }
                Button("Quit") { NSApp.terminate(nil) }
                    .controlSize(.small)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .frame(width: 440, height: 540)
    }

    @ViewBuilder
    private func permissionRow(_ title: String, granted: Bool, uncertain: Bool = false,
                               help: String, anchor: String,
                               request: (() -> Void)? = nil) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Image(systemName: uncertain ? "questionmark.circle.fill"
                            : granted ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundStyle(uncertain ? .orange : granted ? .green : .red)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if uncertain || !granted {
                    Text(help)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            if uncertain || !granted {
                if let request {
                    Button("Grant…") { request() }
                        .controlSize(.small)
                        .help("Ask macOS for this permission. If the system dialog was already dismissed once, this registers the app under \(title) in System Settings — flip the toggle there.")
                }
                Button("Open Settings") {
                    PermissionMonitor.openPrivacyPane(anchor)
                }
                .controlSize(.small)
            }
        }
    }
}

/// "Check for Updates…" button wired to Sparkle. Follows Sparkle 2's
/// documented SwiftUI pattern: a small view model publishes the updater's
/// `canCheckForUpdates` so the button disables itself while a check is
/// already running (or the updater isn't ready).
@MainActor
final class CheckForUpdatesViewModel: ObservableObject {
    @Published var canCheckForUpdates = false

    init(updater: SPUUpdater) {
        updater.publisher(for: \.canCheckForUpdates)
            .assign(to: &$canCheckForUpdates)
    }
}

struct CheckForUpdatesView: View {
    @ObservedObject private var viewModel: CheckForUpdatesViewModel
    private let updater: SPUUpdater

    init(updater: SPUStandardUpdaterController) {
        self.updater = updater.updater
        self.viewModel = CheckForUpdatesViewModel(updater: updater.updater)
    }

    var body: some View {
        Button("Check for Updates…") { updater.checkForUpdates() }
            .controlSize(.small)
            .disabled(!viewModel.canCheckForUpdates)
    }
}

/// One connected device: live status, throughput, reconnect + disconnect.
struct SessionRow: View {
    let title: String
    @ObservedObject var session: DeviceSession
    let controller: SenderController

    private var statusColor: Color {
        if session.status.hasPrefix("Extending") || session.status.hasPrefix("Mirroring")
            || session.status.hasPrefix("Connected") {
            return .green
        }
        if session.status.hasPrefix("Failed") || session.status.contains("stopped") {
            return .red
        }
        return .orange
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Circle()
                .fill(statusColor)
                .frame(width: 9, height: 9)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text("\(session.transportLabel) · \(session.status)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer()
            if session.mbps > 0 {
                Text("\(String(format: "%.1f", session.mbps)) Mbit/s")
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            Button {
                session.sender.forceReconnect()
            } label: {
                Image(systemName: "arrow.clockwise")
            }
            .controlSize(.small)
            .help("Drop the connection and pair with the device again")
            Button("Disconnect") { controller.disconnect(session) }
                .controlSize(.small)
        }
    }
}
