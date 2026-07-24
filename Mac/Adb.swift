// Adb — forward-USB transport for Android receivers. Android has no
// usbmuxd equivalent, so wired streaming rides on the Android Debug Bridge:
// the Android app listens on :9000, so `adb forward tcp:9000 tcp:9000`
// forwards connections to the Mac's localhost:9000 through to the phone's
// localhost:9000, and the existing TCP sender then dials 127.0.0.1:9000 as
// if the receiver were local. (`adb reverse` is the opposite direction —
// device connects back to host — and would be wrong here.) One external
// dependency — the `adb` binary that ships with Android platform-tools —
// located best-effort below.

import Foundation

/// A device reported by `adb devices`. `authorized` is false while the phone
/// still shows the "Allow USB debugging?" prompt (adb state `unauthorized`).
struct AdbDevice: Identifiable, Hashable {
    let serial: String
    var authorized: Bool

    var id: String { serial }
    /// USB cable serials look like hex; `adb connect` uses `host:port`.
    var label: String {
        serial.contains(":") ? "Android (ADB network)" : "Android (USB)"
    }
}

enum Adb {
    enum Failure: Error, LocalizedError {
        case notInstalled
        case command(String)

        var errorDescription: String? {
            switch self {
            case .notInstalled:
                return "adb not found — install Android platform-tools"
            case .command(let detail):
                return "adb error: \(detail)"
            }
        }
    }

    // MARK: - Locating the binary

    /// Resolve the `adb` executable: PATH first (`which`), then the default
    /// Android SDK and Homebrew locations. Cached — the path never moves
    /// mid-session.
    static func findAdb() -> URL? {
        if let cached = cachedAdb { return cached }
        let candidates = [
            which("adb"),
            URL(fileURLWithPath: NSHomeDirectory())
                .appendingPathComponent("Library/Android/sdk/platform-tools/adb"),
            URL(fileURLWithPath: "/opt/homebrew/bin/adb"),
            URL(fileURLWithPath: "/usr/local/bin/adb"),
        ].compactMap { $0 }
        let found = candidates.first { FileManager.default.isExecutableFile(atPath: $0.path) }
        cachedAdb = found
        return found
    }

    private static var cachedAdb: URL?

    private static func which(_ name: String) -> URL? {
        // `/usr/bin/env which` respects the caller's PATH without a shell.
        guard let out = try? shell(URL(fileURLWithPath: "/usr/bin/which"), [name]),
              case let line = out.trimmingCharacters(in: .whitespacesAndNewlines),
              !line.isEmpty else { return nil }
        return URL(fileURLWithPath: line)
    }

    // MARK: - Commands

    /// Parse `adb devices` into attached devices, keeping their authorization
    /// state so the UI can distinguish "tap Allow on the phone" from "ready".
    static func devices() throws -> [AdbDevice] {
        let out = try run(["devices"])
        var result: [AdbDevice] = []
        // Skip the "List of devices attached" header; each line is
        // "<serial>\t<state>". States other than device/unauthorized
        // (offline, no permissions) are transient — drop them.
        for line in out.split(separator: "\n").dropFirst() {
            let parts = line.split(whereSeparator: { $0 == "\t" || $0 == " " })
                .map(String.init).filter { !$0.isEmpty }
            guard parts.count >= 2 else { continue }
            let state = parts[1]
            guard state == "device" || state == "unauthorized" else { continue }
            result.append(AdbDevice(serial: parts[0], authorized: state == "device"))
        }
        return result
    }

    /// Forward this Mac's localhost:port to the phone's localhost:port,
    /// where the Android receiver is listening.
    static func forward(serial: String, port: UInt16) throws {
        _ = try run(["-s", serial, "forward", "tcp:\(port)", "tcp:\(port)"])
    }

    /// Tear down every forward tunnel for the device (best-effort on cleanup).
    static func clearForward(serial: String) throws {
        _ = try run(["-s", serial, "forward", "--remove-all"])
    }

    // MARK: - Process plumbing

    @discardableResult
    private static func run(_ args: [String]) throws -> String {
        guard let adb = findAdb() else { throw Failure.notInstalled }
        return try shell(adb, args)
    }

    private static func shell(_ executable: URL, _ args: [String]) throws -> String {
        let process = Process()
        process.executableURL = executable
        process.arguments = args
        let stdout = Pipe()
        let stderr = Pipe()
        process.standardOutput = stdout
        process.standardError = stderr
        try process.run()
        let outData = stdout.fileHandleForReading.readDataToEndOfFile()
        let errData = stderr.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            let message = String(data: errData, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? "exit \(process.terminationStatus)"
            throw Failure.command(message)
        }
        return String(data: outData, encoding: .utf8) ?? ""
    }
}

/// Polls `adb devices` on a background queue (adb has no push events for the
/// device list; `track-devices` needs a long-lived server socket, whereas a
/// 2s poll is trivial and robust). Publishes on the main actor only when the
/// set actually changes, mirroring `UsbmuxDeviceWatcher`.
@MainActor
final class AdbDeviceWatcher {
    private let onChange: ([AdbDevice]) -> Void
    private let queue = DispatchQueue(label: "adb.watcher")
    private var timer: DispatchSourceTimer?
    private var last: [AdbDevice] = []

    init(onChange: @escaping ([AdbDevice]) -> Void) {
        self.onChange = onChange
    }

    func start() {
        // No adb, no Android support — stay silent rather than spin a poller.
        guard Adb.findAdb() != nil else {
            Log.info("adb watcher: adb not found — Android USB disabled")
            return
        }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: .seconds(2))
        timer.setEventHandler { [weak self] in self?.poll() }
        timer.resume()
        self.timer = timer
    }

    func stop() {
        timer?.cancel()
        timer = nil
    }

    private func poll() {
        let devices = (try? Adb.devices()) ?? []
        Task { @MainActor [weak self] in
            guard let self, devices != self.last else { return }
            self.last = devices
            self.onChange(devices.sorted { $0.serial < $1.serial })
        }
    }
}
