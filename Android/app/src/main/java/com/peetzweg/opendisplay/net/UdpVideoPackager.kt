package com.peetzweg.opendisplay.net

object UdpVideoPackager {
    data class Packet(val datagram: ByteArray, val isParity: Boolean)
    data class PackagedFrame(val packets: List<Packet>, val nextSeq: Int)

    fun packageFrame(
        au: ByteArray,
        frameId: Long,
        startSeq: Int,
        keyframe: Boolean,
        fecPct: Int = UdpVideoProtocol.DEFAULT_FEC_PCT,
        captureMs: Long,
        sendMs: Long,
    ): PackagedFrame {
        require(au.size.toLong() <= 0xFFFF_FFFFL)
        val framedAu = ByteArray(Int.SIZE_BYTES + au.size)
        writeU32(framedAu, au.size)
        System.arraycopy(au, 0, framedAu, Int.SIZE_BYTES, au.size)

        val payloads = UdpVideoProtocol.splitPayload(framedAu)
        val shardSize = payloads.maxOf { it.size }.coerceAtLeast(1)
        val dataShards = Array(payloads.size) { index -> payloads[index].copyOf(shardSize) }
        val requestedParity = UdpVideoProtocol.parityShardCount(dataShards.size, fecPct)
        val effectiveFecPct = if (requestedParity == 0) 0 else fecPct
        val parityShards = ReedSolomon.encode(dataShards, requestedParity)

        val packets = ArrayList<Packet>(dataShards.size + parityShards.size)
        var seq = startSeq and 0xFFFF
        for (index in dataShards.indices) {
            var flags = if (keyframe) UdpVideoProtocol.FLAG_KEYFRAME else 0
            if (index == 0) flags = flags or UdpVideoProtocol.FLAG_START
            if (index == dataShards.lastIndex && parityShards.isEmpty()) {
                flags = flags or UdpVideoProtocol.FLAG_END
            }
            packets += Packet(
                datagram = UdpVideoProtocol.buildDatagram(
                    flags = flags,
                    seq = seq,
                    frameId = frameId,
                    shardIndex = index,
                    dataShardCount = dataShards.size,
                    fecPct = effectiveFecPct,
                    captureMs = captureMs,
                    sendMs = sendMs,
                    payload = payloads[index],
                ),
                isParity = false,
            )
            seq = (seq + 1) and 0xFFFF
        }
        for (index in parityShards.indices) {
            var flags = UdpVideoProtocol.FLAG_FEC_PARITY
            if (keyframe) flags = flags or UdpVideoProtocol.FLAG_KEYFRAME
            if (index == parityShards.lastIndex) flags = flags or UdpVideoProtocol.FLAG_END
            packets += Packet(
                datagram = UdpVideoProtocol.buildDatagram(
                    flags = flags,
                    seq = seq,
                    frameId = frameId,
                    shardIndex = dataShards.size + index,
                    dataShardCount = dataShards.size,
                    fecPct = effectiveFecPct,
                    captureMs = captureMs,
                    sendMs = sendMs,
                    payload = parityShards[index],
                ),
                isParity = true,
            )
            seq = (seq + 1) and 0xFFFF
        }
        return PackagedFrame(packets, seq)
    }

    private fun writeU32(destination: ByteArray, value: Int) {
        destination[0] = (value ushr 24).toByte()
        destination[1] = (value ushr 16).toByte()
        destination[2] = (value ushr 8).toByte()
        destination[3] = value.toByte()
    }
}
