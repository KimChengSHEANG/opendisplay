package com.peetzweg.opendisplay.net

class UdpFrameAssembler {
    data class AssembledFrame(
        val frameId: Long,
        val isKeyframe: Boolean,
        val annexB: ByteArray,
        val captureMs: Long,
        val sendMs: Long,
        val recoveredByFec: Boolean,
    )

    private data class FrameState(
        val frameId: Long,
        val dataCount: Int,
        val parityCount: Int,
        val isKeyframe: Boolean,
        val captureMs: Long,
        val sendMs: Long,
        val shards: Array<ByteArray?>,
    )

    private var state: FrameState? = null
    private var latestFrameId: Long? = null

    fun offer(datagram: ByteArray): AssembledFrame? {
        val header = UdpVideoProtocol.decodeHeader(datagram) ?: return null
        if (header.dataShardCount <= 0) return null
        val parityCount = UdpVideoProtocol.parityShardCount(header.dataShardCount, header.fecPct)
        val totalCount = header.dataShardCount + parityCount
        if (header.shardIndex !in 0 until totalCount) return null
        if (header.isParity != (header.shardIndex >= header.dataShardCount)) return null

        var current = state
        if (current == null) {
            val latest = latestFrameId
            if (latest != null && !isNewerFrame(header.frameId, latest)) return null
            current = newState(header, parityCount)
            state = current
            latestFrameId = header.frameId
        } else if (header.frameId != current.frameId) {
            if (!isNewerFrame(header.frameId, current.frameId)) return null
            current = newState(header, parityCount)
            state = current
            latestFrameId = header.frameId
        } else if (
            current.dataCount != header.dataShardCount ||
            current.parityCount != parityCount ||
            current.isKeyframe != header.isKeyframe ||
            current.captureMs != header.captureMs ||
            current.sendMs != header.sendMs
        ) {
            return null
        }

        val payload = datagram.copyOfRange(UdpVideoProtocol.HEADER_SIZE, datagram.size)
        current.shards[header.shardIndex] = payload
        if (current.shards.count { it != null } < current.dataCount) return null

        val missingData = (0 until current.dataCount).any { current.shards[it] == null }
        val dataShards = if (missingData) {
            val shardSize = current.shards.maxOfOrNull { it?.size ?: 0 } ?: return null
            if (shardSize == 0) return null
            val padded = Array<ByteArray?>(current.shards.size) { index ->
                current.shards[index]?.copyOf(shardSize)
            }
            ReedSolomon.decode(padded, current.dataCount) ?: return null
        } else {
            Array(current.dataCount) { current.shards[it]!! }
        }

        val framedAu = concatenate(dataShards)
        if (framedAu.size < Int.SIZE_BYTES) return null
        val auSize = readU32(framedAu)
        if (auSize < 0 || auSize > framedAu.size - Int.SIZE_BYTES) return null
        val assembled = AssembledFrame(
            frameId = current.frameId,
            isKeyframe = current.isKeyframe,
            annexB = framedAu.copyOfRange(Int.SIZE_BYTES, Int.SIZE_BYTES + auSize),
            captureMs = current.captureMs,
            sendMs = current.sendMs,
            recoveredByFec = missingData,
        )
        state = null
        return assembled
    }

    private fun newState(
        header: UdpVideoProtocol.Header,
        parityCount: Int,
    ): FrameState = FrameState(
        frameId = header.frameId,
        dataCount = header.dataShardCount,
        parityCount = parityCount,
        isKeyframe = header.isKeyframe,
        captureMs = header.captureMs,
        sendMs = header.sendMs,
        shards = arrayOfNulls(header.dataShardCount + parityCount),
    )

    private fun isNewerFrame(candidate: Long, current: Long): Boolean {
        val difference = (candidate - current) and 0xFFFF_FFFFL
        return difference in 1..0x7FFF_FFFFL
    }

    private fun concatenate(shards: Array<ByteArray>): ByteArray {
        val result = ByteArray(shards.sumOf { it.size })
        var offset = 0
        for (shard in shards) {
            System.arraycopy(shard, 0, result, offset, shard.size)
            offset += shard.size
        }
        return result
    }

    private fun readU32(source: ByteArray): Int =
        ((source[0].toInt() and 0xFF) shl 24) or
            ((source[1].toInt() and 0xFF) shl 16) or
            ((source[2].toInt() and 0xFF) shl 8) or
            (source[3].toInt() and 0xFF)
}
