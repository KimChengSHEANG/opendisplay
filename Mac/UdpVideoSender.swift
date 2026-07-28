import Foundation
import Network

/// UDP socket lifecycle only. Packetization, pacing, and retransmission are
/// added in the following transport tasks.
@available(macOS 14.0, *)
final class UdpVideoSender {
    private let connection: NWConnection

    init(host: NWEndpoint.Host, port: NWEndpoint.Port, queue: DispatchQueue) {
        connection = NWConnection(host: host, port: port, using: .udp)
        connection.start(queue: queue)
    }

    func stop() {
        connection.cancel()
    }
}
