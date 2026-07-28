package com.peetzweg.opendisplay.net

import java.util.SortedMap

class JitterBuffer(
    private val targetDelayMs: Long = 20,
    private val maxDelayMs: Long = 40,
    private val nowMs: () -> Long,
    private val onRelease: (Frame) -> Unit,
    private val onDropLate: (Long) -> Unit,
    private val onIncomplete: (Long, IntArray) -> Unit,
) {
    data class Frame(
        val frameId: Long,
        val complete: Boolean,
        val captureMs: Long,
        val sendMs: Long,
        val annexB: ByteArray,
        val isKeyframe: Boolean,
        val seqs: IntArray,
    )

    private data class PartialEntry(
        val missingSeqs: IntArray,
        val firstSeenMs: Long,
    )

    private val completeFrames = sortedMapOf<Long, Frame>()
    private val partialFrames = sortedMapOf<Long, PartialEntry>()
    private var playoutHeadCaptureMs = Long.MIN_VALUE

    fun offer(frame: Frame) {
        if (!frame.complete) return
        if (frame.captureMs + maxDelayMs < playoutHeadCaptureMs) {
            onDropLate(frame.frameId)
            return
        }
        completeFrames[frame.frameId] = frame
    }

    fun offerPartial(frameId: Long, missingSeqs: IntArray, firstSeenMs: Long) {
        if (missingSeqs.isEmpty()) return
        val existing = partialFrames[frameId]
        partialFrames[frameId] = if (existing == null) {
            PartialEntry(missingSeqs.copyOf(), firstSeenMs)
        } else {
            PartialEntry(
                missingSeqs = mergeMissing(existing.missingSeqs, missingSeqs),
                firstSeenMs = minOf(existing.firstSeenMs, firstSeenMs),
            )
        }
        completeFrames.remove(frameId)
    }

    fun drain() {
        val now = nowMs()
        dropLateCompleteFrames()
        while (true) {
            val headId = nextHeadFrameId() ?: break
            val partial = partialFrames[headId]
            if (partial != null) {
                if (now - partial.firstSeenMs >= maxDelayMs) {
                    onIncomplete(headId, partial.missingSeqs)
                    partialFrames.remove(headId)
                    continue
                }
                break
            }
            val head = completeFrames[headId] ?: break
            if (head.captureMs + targetDelayMs > now) break
            releaseConsecutiveFrom(headId)
        }
    }

    internal fun advancePlayoutHeadForTest(captureMs: Long) {
        playoutHeadCaptureMs = captureMs
    }

    private fun dropLateCompleteFrames() {
        val iterator = completeFrames.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.captureMs + maxDelayMs < playoutHeadCaptureMs) {
                onDropLate(entry.key)
                iterator.remove()
            }
        }
    }

    private fun nextHeadFrameId(): Long? {
        val completeHead = completeFrames.firstKeyOrNull()
        val partialHead = partialFrames.firstKeyOrNull()
        return when {
            completeHead == null -> partialHead
            partialHead == null -> completeHead
            else -> kotlin.math.min(completeHead, partialHead)
        }
    }

    private fun releaseConsecutiveFrom(startId: Long) {
        var id = startId
        while (true) {
            if (partialFrames.containsKey(id)) break
            val frame = completeFrames.remove(id) ?: break
            onRelease(frame)
            playoutHeadCaptureMs = maxOf(playoutHeadCaptureMs, frame.captureMs)
            id++
        }
    }

    private fun mergeMissing(existing: IntArray, incoming: IntArray): IntArray {
        val merged = (existing.toSet() + incoming.toSet()).sorted()
        return merged.toIntArray()
    }

    private fun <K> SortedMap<K, *>.firstKeyOrNull(): K? =
        if (isEmpty()) null else firstKey()
}
