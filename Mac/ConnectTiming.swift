import Foundation

/// Wall-clock stages for Mac→receiver connect (TTFF diagnosis).
struct ConnectTiming {
    var dialStart: Date?
    var tcpReady: Date?
    var helloReceived: Date?
    var virtualDisplayReady: Date?
    var captureStarted: Date?
    var firstEncoded: Date?

    mutating func mark(_ keyPath: WritableKeyPath<ConnectTiming, Date?>, at date: Date = Date()) {
        self[keyPath: keyPath] = date
    }

    func durationMs(from: KeyPath<ConnectTiming, Date?>, to: KeyPath<ConnectTiming, Date?>) -> Double? {
        guard let a = self[keyPath: from], let b = self[keyPath: to] else { return nil }
        return b.timeIntervalSince(a) * 1000
    }

    func summaryLine() -> String {
        func fmt(_ ms: Double?) -> String {
            guard let ms else { return "-" }
            return String(format: "%.0f", ms)
        }
        return "connectTiming dial→ready=\(fmt(durationMs(from: \.dialStart, to: \.tcpReady)))ms ready→hello=\(fmt(durationMs(from: \.tcpReady, to: \.helloReceived)))ms hello→vd=\(fmt(durationMs(from: \.helloReceived, to: \.virtualDisplayReady)))ms vd→capture=\(fmt(durationMs(from: \.virtualDisplayReady, to: \.captureStarted)))ms capture→encode=\(fmt(durationMs(from: \.captureStarted, to: \.firstEncoded)))ms dial→encode=\(fmt(durationMs(from: \.dialStart, to: \.firstEncoded)))ms"
    }
}
