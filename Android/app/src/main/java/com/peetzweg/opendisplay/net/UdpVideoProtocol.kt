package com.peetzweg.opendisplay.net

object UdpVideoProtocol {
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 32
    const val MAX_DATAGRAM: Int = 1200
    const val MAX_PAYLOAD: Int = MAX_DATAGRAM - HEADER_SIZE
    const val DEFAULT_FEC_PCT: Int = 20
    const val MAX_RS_SHARDS: Int = 255

    const val FLAG_KEYFRAME: Int = 0x01
    const val FLAG_FEC_PARITY: Int = 0x02
    const val FLAG_START: Int = 0x04
    const val FLAG_END: Int = 0x08

    data class Header(
        val version: Int,
        val flags: Int,
        val seq: Int,
        val frameId: Long,
        val shardIndex: Int,
        val dataShardCount: Int,
        val fecPct: Int,
        val captureMs: Long,
        val sendMs: Long,
    ) {
        val isKeyframe: Boolean get() = flags and FLAG_KEYFRAME != 0
        val isParity: Boolean get() = flags and FLAG_FEC_PARITY != 0
        val isStart: Boolean get() = flags and FLAG_START != 0
        val isEnd: Boolean get() = flags and FLAG_END != 0
    }

    fun buildDatagram(
        flags: Int,
        seq: Int,
        frameId: Long,
        shardIndex: Int,
        dataShardCount: Int,
        fecPct: Int,
        captureMs: Long,
        sendMs: Long,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLen: Int = payload.size,
    ): ByteArray {
        val out = ByteArray(HEADER_SIZE + payloadLen)
        encodeHeader(flags, seq, frameId, shardIndex, dataShardCount, fecPct, captureMs, sendMs, out)
        System.arraycopy(payload, payloadOffset, out, HEADER_SIZE, payloadLen)
        return out
    }

    fun encodeHeader(
        flags: Int,
        seq: Int,
        frameId: Long,
        shardIndex: Int,
        dataShardCount: Int,
        fecPct: Int,
        captureMs: Long,
        sendMs: Long,
        out: ByteArray,
        offset: Int = 0,
    ) {
        require(out.size >= offset + HEADER_SIZE)
        out[offset] = VERSION.toByte()
        out[offset + 1] = flags.toByte()
        writeU16(out, offset + 2, seq)
        writeU32(out, offset + 4, frameId)
        writeU16(out, offset + 8, shardIndex)
        writeU16(out, offset + 10, dataShardCount)
        out[offset + 12] = fecPct.toByte()
        out[offset + 13] = 0
        writeU16(out, offset + 14, 0)
        writeU64(out, offset + 16, captureMs)
        writeU64(out, offset + 24, sendMs)
    }

    fun decodeHeader(data: ByteArray, offset: Int = 0): Header? {
        if (data.size < offset + HEADER_SIZE) return null
        val version = data[offset].toInt() and 0xFF
        if (version != VERSION) return null
        return Header(
            version = version,
            flags = data[offset + 1].toInt() and 0xFF,
            seq = readU16(data, offset + 2),
            frameId = readU32(data, offset + 4),
            shardIndex = readU16(data, offset + 8),
            dataShardCount = readU16(data, offset + 10),
            fecPct = data[offset + 12].toInt() and 0xFF,
            captureMs = readU64(data, offset + 16),
            sendMs = readU64(data, offset + 24),
        )
    }

    fun parityShardCount(dataShardCount: Int, fecPct: Int): Int {
        if (fecPct <= 0 || dataShardCount <= 0) return 0
        val parity = (dataShardCount * fecPct + 99) / 100
        if (dataShardCount + parity > MAX_RS_SHARDS) return 0
        return parity
    }

    fun splitPayload(au: ByteArray, maxPayload: Int = MAX_PAYLOAD): List<ByteArray> {
        require(maxPayload > 0)
        if (au.isEmpty()) return listOf(ByteArray(0))
        val shards = ArrayList<ByteArray>((au.size + maxPayload - 1) / maxPayload)
        var off = 0
        while (off < au.size) {
            val len = minOf(maxPayload, au.size - off)
            shards.add(au.copyOfRange(off, off + len))
            off += len
        }
        return shards
    }

    private fun writeU16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(out: ByteArray, offset: Int, value: Long) {
        out[offset] = ((value ushr 24) and 0xFF).toByte()
        out[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeU64(out: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            out[offset + i] = ((value ushr ((7 - i) * 8)) and 0xFF).toByte()
        }
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readU32(data: ByteArray, offset: Int): Long =
        ((data[offset].toInt() and 0xFF).toLong() shl 24) or
            ((data[offset + 1].toInt() and 0xFF).toLong() shl 16) or
            ((data[offset + 2].toInt() and 0xFF).toLong() shl 8) or
            (data[offset + 3].toInt() and 0xFF).toLong()

    private fun readU64(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (data[offset + i].toInt() and 0xFF).toLong()
        }
        return v
    }
}
