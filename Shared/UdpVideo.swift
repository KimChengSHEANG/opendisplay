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

    static func buildDatagram(
        flags: UInt8,
        seq: UInt16,
        frameId: UInt32,
        shardIndex: UInt16,
        dataShardCount: UInt16,
        fecPct: UInt8,
        captureMs: UInt64,
        sendMs: UInt64,
        payload: Data
    ) -> Data {
        var datagram = Data(capacity: headerSize + payload.count)
        datagram.append(version)
        datagram.append(flags)
        appendBigEndian(seq, to: &datagram)
        appendBigEndian(frameId, to: &datagram)
        appendBigEndian(shardIndex, to: &datagram)
        appendBigEndian(dataShardCount, to: &datagram)
        datagram.append(fecPct)
        datagram.append(contentsOf: [0, 0, 0])
        appendBigEndian(captureMs, to: &datagram)
        appendBigEndian(sendMs, to: &datagram)
        datagram.append(payload)
        return datagram
    }

    static func splitPayload(_ au: Data, maxPayload: Int = maxPayload) -> [Data] {
        precondition(maxPayload > 0)
        guard !au.isEmpty else { return [Data()] }
        return stride(from: 0, to: au.count, by: maxPayload).map { offset in
            au.subdata(in: offset..<min(offset + maxPayload, au.count))
        }
    }

    private static func appendBigEndian<T: FixedWidthInteger>(_ value: T, to data: inout Data) {
        var bigEndian = value.bigEndian
        withUnsafeBytes(of: &bigEndian) { data.append(contentsOf: $0) }
    }
}

enum ReedSolomonFEC {
    private static let tables: (log: [Int], exp: [Int]) = {
        var log = [Int](repeating: 0, count: 256)
        var exp = [Int](repeating: 0, count: 512)
        var x = 1
        for index in 0..<255 {
            exp[index] = x
            exp[index + 255] = x
            log[x] = index
            x <<= 1
            if x & 0x100 != 0 { x ^= 0x11D }
        }
        return (log, exp)
    }()

    static func encode(dataShards: [Data], parityCount: Int) -> [Data] {
        precondition(!dataShards.isEmpty)
        precondition(parityCount >= 0)
        precondition(dataShards.count + parityCount <= UdpVideoProtocol.maxRsShards)
        let shardSize = dataShards[0].count
        precondition(dataShards.allSatisfy { $0.count == shardSize })

        return (0..<parityCount).map { parityIndex in
            var parity = Data(count: shardSize)
            for dataIndex in dataShards.indices {
                let coefficient = cauchyCoefficient(
                    dataIndex: dataIndex,
                    parityShardIndex: dataShards.count + parityIndex
                )
                for byteIndex in 0..<shardSize {
                    parity[byteIndex] ^= gfMultiply(dataShards[dataIndex][byteIndex], coefficient)
                }
            }
            return parity
        }
    }

    static func padDataShards(_ payloads: [Data]) -> [Data] {
        precondition(!payloads.isEmpty)
        let shardSize = max(payloads.map(\.count).max() ?? 0, 1)
        return payloads.map { payload in
            guard payload.count < shardSize else { return payload }
            var padded = Data(count: shardSize)
            padded.replaceSubrange(0..<payload.count, with: payload)
            return padded
        }
    }

    private static func gfMultiply(_ left: UInt8, _ right: UInt8) -> UInt8 {
        guard left != 0, right != 0 else { return 0 }
        return UInt8(tables.exp[tables.log[Int(left)] + tables.log[Int(right)]])
    }

    private static func cauchyCoefficient(
        dataIndex: Int,
        parityShardIndex: Int
    ) -> UInt8 {
        let value = dataIndex ^ parityShardIndex
        precondition(value != 0)
        return UInt8(tables.exp[255 - tables.log[value]])
    }
}

enum UdpVideoPackager {
    struct Packet {
        let datagram: Data
        let isParity: Bool
        let seq: UInt16
    }

    static func packageFrame(
        au: Data,
        frameId: UInt32,
        startSeq: UInt16,
        keyframe: Bool,
        fecPct: Int = UdpVideoProtocol.defaultFecPct,
        captureMs: UInt64,
        sendMs: UInt64
    ) -> (packets: [Packet], nextSeq: UInt16) {
        precondition(au.count <= Int(UInt32.max))
        var framedAu = Data(capacity: MemoryLayout<UInt32>.size + au.count)
        var auSize = UInt32(au.count).bigEndian
        withUnsafeBytes(of: &auSize) { framedAu.append(contentsOf: $0) }
        framedAu.append(au)

        let payloads = UdpVideoProtocol.splitPayload(framedAu)
        let dataShards = ReedSolomonFEC.padDataShards(payloads)
        let parityCount = UdpVideoProtocol.parityShardCount(
            dataShardCount: dataShards.count,
            fecPct: fecPct
        )
        let effectiveFecPct = parityCount == 0 ? 0 : fecPct
        let parityShards = ReedSolomonFEC.encode(
            dataShards: dataShards,
            parityCount: parityCount
        )

        var packets: [Packet] = []
        var seq = startSeq
        for index in dataShards.indices {
            var flags: UInt8 = keyframe ? UdpVideoProtocol.flagKeyframe : 0
            if index == dataShards.startIndex { flags |= UdpVideoProtocol.flagStart }
            if index == dataShards.index(before: dataShards.endIndex), parityShards.isEmpty {
                flags |= UdpVideoProtocol.flagEnd
            }
            packets.append(Packet(
                datagram: UdpVideoProtocol.buildDatagram(
                    flags: flags,
                    seq: seq,
                    frameId: frameId,
                    shardIndex: UInt16(index),
                    dataShardCount: UInt16(dataShards.count),
                    fecPct: UInt8(effectiveFecPct),
                    captureMs: captureMs,
                    sendMs: sendMs,
                    payload: payloads[index]
                ),
                isParity: false,
                seq: seq
            ))
            seq &+= 1
        }
        for index in parityShards.indices {
            var flags = UdpVideoProtocol.flagFecParity
            if keyframe { flags |= UdpVideoProtocol.flagKeyframe }
            if index == parityShards.index(before: parityShards.endIndex) {
                flags |= UdpVideoProtocol.flagEnd
            }
            packets.append(Packet(
                datagram: UdpVideoProtocol.buildDatagram(
                    flags: flags,
                    seq: seq,
                    frameId: frameId,
                    shardIndex: UInt16(dataShards.count + index),
                    dataShardCount: UInt16(dataShards.count),
                    fecPct: UInt8(effectiveFecPct),
                    captureMs: captureMs,
                    sendMs: sendMs,
                    payload: parityShards[index]
                ),
                isParity: true,
                seq: seq
            ))
            seq &+= 1
        }
        return (packets, seq)
    }
}
