import Foundation

enum UdpVideoProtocol {
    static let version: UInt8 = 1
    static let headerSize = 32
    static let maxDatagram = 1200
    static let maxPayload = maxDatagram - headerSize
    static let defaultFecPct = 20
    static let maxRsShards = 255

    static let flagKeyframe: UInt8 = 0x01
    static let flagFecParity: UInt8 = 0x02
    static let flagStart: UInt8 = 0x04
    static let flagEnd: UInt8 = 0x08

    static func parityShardCount(dataShardCount: Int, fecPct: Int) -> Int {
        guard fecPct > 0, dataShardCount > 0 else { return 0 }
        let parity = (dataShardCount * fecPct + 99) / 100
        if dataShardCount + parity > maxRsShards { return 0 }
        return parity
    }
}
