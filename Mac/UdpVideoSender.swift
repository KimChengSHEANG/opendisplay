import Foundation
import Network

/// Paced UDP video sender with retransmit cache for NACK recovery.
///
/// State is guarded by `stateLock` — never `queue.sync`. SCK delivers frames on
/// `sender.video`, and VT often emits the encode callback while that queue is
/// still inside `VTCompressionSessionEncodeFrame`. Syncing back into the same
/// queue from `isReady`/`sendFrame` deadlocks the Mac (black Chromebook + hung UI).
@available(macOS 14.0, *)
final class UdpVideoSender {
    private let queue: DispatchQueue
    private let stateLock = NSLock()
    private var connection: NWConnection?
    private var nextSeq: UInt16 = 0
    private var nextFrameId: UInt32 = 1
    private var pendingDatagrams = 0
    /// Enough for one full RS block (255) plus paced headroom. A hard 64
    /// rejected typical 1080p IDRs even on an empty queue → Chromebook black.
    private let maxPendingDatagrams = 256
    private let maxFrameDatagrams = 255
    private let batchSize = 12
    private let minPaceDelayMs = 0.05
    private let maxPaceDelayMs = 8.0
    private var encodeBitrate: Int = 18_000_000
    private var fecPct: Int = UdpVideoProtocol.defaultFecPct
    private var retransmitCache: [UInt16: Data] = [:]
    private var retransmitCacheOrder: [UInt16] = []
    private let retransmitCacheLimit = 2048
    private var ready = false

    var pendingCount: Int {
        stateLock.lock(); defer { stateLock.unlock() }
        return pendingDatagrams
    }

    var isReady: Bool {
        stateLock.lock(); defer { stateLock.unlock() }
        return ready
    }

    var onReady: (() -> Void)?
    var onFailed: ((String) -> Void)?

    init(queue: DispatchQueue) {
        self.queue = queue
    }

    convenience init(host: NWEndpoint.Host, port: NWEndpoint.Port, queue: DispatchQueue) {
        self.init(queue: queue)
        connect(host: host, port: port)
    }

    func updateBitrate(_ bitrate: Int) {
        stateLock.lock()
        encodeBitrate = max(1_000_000, bitrate)
        stateLock.unlock()
    }

    func updateFecPct(_ pct: Int) {
        stateLock.lock()
        fecPct = max(0, min(50, pct))
        stateLock.unlock()
    }

    func connect(host: NWEndpoint.Host, port: NWEndpoint.Port) {
        queue.async { [weak self] in
            guard let self else { return }
            self.disconnectLocked()
            let params = NWParameters.udp
            params.allowLocalEndpointReuse = true
            let conn = NWConnection(host: host, port: port, using: params)
            self.stateLock.lock()
            self.connection = conn
            self.stateLock.unlock()
            conn.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                self.queue.async {
                    switch state {
                    case .ready:
                        self.stateLock.lock()
                        self.ready = true
                        self.stateLock.unlock()
                        Log.info("UDP video ready → \(host.debugDescription):\(port.rawValue)")
                        self.onReady?()
                    case .failed(let err):
                        self.stateLock.lock()
                        self.ready = false
                        self.stateLock.unlock()
                        Log.info("UDP video failed: \(err)")
                        self.onFailed?(String(describing: err))
                    case .cancelled:
                        self.stateLock.lock()
                        self.ready = false
                        self.stateLock.unlock()
                    default:
                        break
                    }
                }
            }
            conn.start(queue: self.queue)
        }
    }

    /// Non-blocking: safe from main / VT callback. Cancels on the NW queue.
    func stop() {
        queue.async { [weak self] in self?.disconnectLocked() }
    }

    func disconnect() {
        stop()
    }

    /// Packages under the lock, paces on `queue` without syncing back. Safe to
    /// call from the VT encode callback while SCK still owns `sender.video`.
    @discardableResult
    func sendFrame(au: Data, keyframe: Bool, captureMs: UInt64) -> Bool {
        stateLock.lock()
        guard ready, let connection else {
            stateLock.unlock()
            return false
        }
        let fecPct = self.fecPct
        let frameId = nextFrameId
        let startSeq = nextSeq
        let pending = pendingDatagrams
        stateLock.unlock()

        let sendMs = UInt64(Date().timeIntervalSince1970 * 1000)
        let packaged = UdpVideoPackager.packageFrame(
            au: au,
            frameId: frameId,
            startSeq: startSeq,
            keyframe: keyframe,
            fecPct: fecPct,
            captureMs: captureMs,
            sendMs: sendMs
        )
        let datagramCount = packaged.packets.count

        stateLock.lock()
        guard ready, self.connection === connection else {
            stateLock.unlock()
            return false
        }
        guard shouldAdmitFrame(
            pending: pendingDatagrams,
            frameDatagrams: datagramCount,
            keyframe: keyframe
        ) else {
            stateLock.unlock()
            return false
        }
        nextFrameId = frameId &+ 1
        nextSeq = packaged.nextSeq
        for pkt in packaged.packets {
            cacheLocked(seq: pkt.seq, datagram: pkt.datagram)
        }
        pendingDatagrams += datagramCount
        let bitrate = encodeBitrate
        let fec = self.fecPct
        stateLock.unlock()

        let datagrams = packaged.packets.map(\.datagram)
        queue.async { [weak self] in
            self?.paceDatagrams(datagrams, on: connection, bitrate: bitrate, fecPct: fec)
        }
        return true
    }

    func handleNack(missing: [UInt16]) {
        stateLock.lock()
        guard let connection, ready else {
            stateLock.unlock()
            return
        }
        var datagrams: [Data] = []
        for seq in missing {
            if let dgram = retransmitCache[seq] {
                datagrams.append(dgram)
            }
        }
        guard !datagrams.isEmpty else {
            stateLock.unlock()
            return
        }
        pendingDatagrams += datagrams.count
        let bitrate = encodeBitrate
        let fec = fecPct
        stateLock.unlock()
        queue.async { [weak self] in
            self?.paceDatagrams(datagrams, on: connection, bitrate: bitrate, fecPct: fec)
        }
    }

    // MARK: - Internals

    /// Atomic whole-frame admission (mirrors Android `UdpSendBudget.shouldAdmitFrame`).
    private func shouldAdmitFrame(pending: Int, frameDatagrams: Int, keyframe: Bool) -> Bool {
        if frameDatagrams <= 0 || frameDatagrams > maxFrameDatagrams { return false }
        if pending == 0 { return true }
        if keyframe { return true }
        return pending + frameDatagrams <= maxPendingDatagrams
    }

    private func disconnectLocked() {
        stateLock.lock()
        ready = false
        let conn = connection
        connection = nil
        pendingDatagrams = 0
        nextSeq = 0
        nextFrameId = 1
        retransmitCache.removeAll()
        retransmitCacheOrder.removeAll()
        stateLock.unlock()
        conn?.cancel()
    }

    private func cacheLocked(seq: UInt16, datagram: Data) {
        if retransmitCache[seq] == nil {
            retransmitCacheOrder.append(seq)
        }
        retransmitCache[seq] = datagram
        while retransmitCacheOrder.count > retransmitCacheLimit {
            let evicted = retransmitCacheOrder.removeFirst()
            retransmitCache.removeValue(forKey: evicted)
        }
    }

    private func bytesPerMs(bitrate: Int, fecPct: Int) -> Int {
        let wireBps = Double(bitrate) * (1.0 + Double(fecPct) / 100.0)
        return max(1, Int(wireBps / 8.0 / 1000.0))
    }

    private func paceDelayMs(batchBytes: Int, bitrate: Int, fecPct: Int) -> Double {
        let budget = max(1, bytesPerMs(bitrate: bitrate, fecPct: fecPct))
        return min(maxPaceDelayMs, max(minPaceDelayMs, Double(batchBytes) / Double(budget)))
    }

    private func paceDatagrams(
        _ datagrams: [Data],
        on connection: NWConnection,
        bitrate: Int,
        fecPct: Int
    ) {
        var index = 0
        func sendNextBatch() {
            guard index < datagrams.count else { return }
            stateLock.lock()
            let stillLive = self.connection === connection && ready
            stateLock.unlock()
            guard stillLive else { return }

            let end = min(index + batchSize, datagrams.count)
            var batchBytes = 0
            for i in index..<end {
                let dgram = datagrams[i]
                batchBytes += dgram.count
                connection.send(content: dgram, completion: .contentProcessed { [weak self] _ in
                    guard let self else { return }
                    self.stateLock.lock()
                    self.pendingDatagrams = max(0, self.pendingDatagrams - 1)
                    self.stateLock.unlock()
                })
            }
            index = end
            guard index < datagrams.count else { return }
            let delayMs = paceDelayMs(batchBytes: batchBytes, bitrate: bitrate, fecPct: fecPct)
            let ns = UInt64(delayMs * 1_000_000)
            queue.asyncAfter(deadline: .now() + .nanoseconds(Int(ns))) {
                sendNextBatch()
            }
        }
        sendNextBatch()
    }
}
