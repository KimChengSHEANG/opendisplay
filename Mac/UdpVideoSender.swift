import Foundation
import Network

/// Paced UDP video sender with retransmit cache for NACK recovery.
@available(macOS 14.0, *)
final class UdpVideoSender {
    private let queue: DispatchQueue
    private var connection: NWConnection?
    private var nextSeq: UInt16 = 0
    private var nextFrameId: UInt32 = 1
    private var pendingDatagrams = 0
    private let maxPendingDatagrams = 64
    private let batchSize = 12
    private let minPaceDelayMs = 0.05
    private let maxPaceDelayMs = 8.0
    private var encodeBitrate: Int = 18_000_000
    private var fecPct: Int = UdpVideoProtocol.defaultFecPct
    private var retransmitCache: [UInt16: Data] = [:]
    private var retransmitCacheOrder: [UInt16] = []
    private let retransmitCacheLimit = 2048
    private(set) var isReady = false

    var pendingCount: Int { pendingDatagrams }
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
        encodeBitrate = max(1_000_000, bitrate)
    }

    func updateFecPct(_ pct: Int) {
        fecPct = max(0, min(50, pct))
    }

    func connect(host: NWEndpoint.Host, port: NWEndpoint.Port) {
        disconnect()
        let params = NWParameters.udp
        params.allowLocalEndpointReuse = true
        let conn = NWConnection(host: host, port: port, using: params)
        connection = conn
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            self.queue.async {
                switch state {
                case .ready:
                    self.isReady = true
                    Log.info("UDP video ready → \(host.debugDescription):\(port.rawValue)")
                    self.onReady?()
                case .failed(let err):
                    self.isReady = false
                    Log.info("UDP video failed: \(err)")
                    self.onFailed?(String(describing: err))
                case .cancelled:
                    self.isReady = false
                default:
                    break
                }
            }
        }
        conn.start(queue: queue)
    }

    func stop() {
        disconnect()
    }

    func disconnect() {
        isReady = false
        connection?.cancel()
        connection = nil
        pendingDatagrams = 0
        nextSeq = 0
        nextFrameId = 1
        retransmitCache.removeAll()
        retransmitCacheOrder.removeAll()
    }

    @discardableResult
    func sendFrame(au: Data, keyframe: Bool, captureMs: UInt64) -> Bool {
        guard isReady, let connection else { return false }
        if pendingDatagrams >= maxPendingDatagrams { return false }
        let sendMs = UInt64(Date().timeIntervalSince1970 * 1000)
        let packaged = UdpVideoPackager.packageFrame(
            au: au,
            frameId: nextFrameId,
            startSeq: nextSeq,
            keyframe: keyframe,
            fecPct: fecPct,
            captureMs: captureMs,
            sendMs: sendMs
        )
        nextFrameId &+= 1
        nextSeq = packaged.nextSeq
        for pkt in packaged.packets {
            cache(seq: pkt.seq, datagram: pkt.datagram)
        }
        paceAndSend(packaged.packets, on: connection)
        return true
    }

    func handleNack(missing: [UInt16]) {
        guard let connection, isReady else { return }
        for seq in missing {
            guard let dgram = retransmitCache[seq] else { continue }
            pendingDatagrams += 1
            connection.send(content: dgram, completion: .contentProcessed { [weak self] _ in
                guard let self else { return }
                self.queue.async {
                    self.pendingDatagrams = max(0, self.pendingDatagrams - 1)
                }
            })
        }
    }

    private func bytesPerMs() -> Int {
        let wireBps = Double(encodeBitrate) * (1.0 + Double(fecPct) / 100.0)
        return max(1, Int(wireBps / 8.0 / 1000.0))
    }

    private func paceDelayMs(batchBytes: Int) -> Double {
        let budget = max(1, bytesPerMs())
        return min(maxPaceDelayMs, max(minPaceDelayMs, Double(batchBytes) / Double(budget)))
    }

    private func cache(seq: UInt16, datagram: Data) {
        if retransmitCache[seq] == nil {
            retransmitCacheOrder.append(seq)
        }
        retransmitCache[seq] = datagram
        while retransmitCacheOrder.count > retransmitCacheLimit {
            let evicted = retransmitCacheOrder.removeFirst()
            retransmitCache.removeValue(forKey: evicted)
        }
    }

    private func paceAndSend(_ packets: [UdpVideoPackager.Packet], on connection: NWConnection) {
        var index = 0
        func sendNextBatch() {
            guard index < packets.count else { return }
            let end = min(index + batchSize, packets.count)
            var batchBytes = 0
            for i in index..<end {
                let dgram = packets[i].datagram
                batchBytes += dgram.count
                pendingDatagrams += 1
                connection.send(content: dgram, completion: .contentProcessed { [weak self] _ in
                    guard let self else { return }
                    self.queue.async {
                        self.pendingDatagrams = max(0, self.pendingDatagrams - 1)
                    }
                })
            }
            index = end
            guard index < packets.count else { return }
            let delayMs = paceDelayMs(batchBytes: batchBytes)
            let ns = UInt64(delayMs * 1_000_000)
            queue.asyncAfter(deadline: .now() + .nanoseconds(Int(ns))) {
                sendNextBatch()
            }
        }
        sendNextBatch()
    }
}
