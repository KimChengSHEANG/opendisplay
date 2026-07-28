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

    private val lock = Any()
    private val completeFrames = sortedMapOf<Long, Frame>()
    private val partialFrames = sortedMapOf<Long, PartialEntry>()
    private var playoutHeadCaptureMs = Long.MIN_VALUE

    fun offer(frame: Frame) {
        val lateFrameId = synchronized(lock) {
            if (!frame.complete) return
            if (frame.captureMs + maxDelayMs < playoutHeadCaptureMs) {
                return@synchronized frame.frameId
            }
            completeFrames[frame.frameId] = frame
            null
        }
        if (lateFrameId != null) onDropLate(lateFrameId)
    }

    fun offerPartial(frameId: Long, missingSeqs: IntArray, firstSeenMs: Long) {
        synchronized(lock) {
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
    }

    fun drain() {
        val actions = synchronized(lock) { collectDrainActions(nowMs()) }
        for (action in actions) {
            when (action) {
                is DrainAction.Release -> {
                    // Catch-up: release every consecutive complete frame at the playout head.
                    onRelease(action.frame)
                }
                is DrainAction.DropLate -> onDropLate(action.frameId)
                is DrainAction.Incomplete -> onIncomplete(action.frameId, action.missingSeqs)
            }
        }
    }

    internal fun advancePlayoutHeadForTest(captureMs: Long) {
        synchronized(lock) {
            playoutHeadCaptureMs = captureMs
        }
    }

    private sealed class DrainAction {
        data class Release(val frame: Frame) : DrainAction()
        data class DropLate(val frameId: Long) : DrainAction()
        data class Incomplete(val frameId: Long, val missingSeqs: IntArray) : DrainAction()
    }

    private fun collectDrainActions(now: Long): List<DrainAction> {
        val actions = mutableListOf<DrainAction>()
        dropLateCompleteFrames(actions)
        while (true) {
            val headId = nextHeadFrameId() ?: break
            val partial = partialFrames[headId]
            if (partial != null) {
                if (now - partial.firstSeenMs >= maxDelayMs) {
                    actions += DrainAction.Incomplete(headId, partial.missingSeqs.copyOf())
                    partialFrames.remove(headId)
                    continue
                }
                break
            }
            val head = completeFrames[headId] ?: break
            if (head.captureMs + targetDelayMs > now) break
            releaseConsecutiveFrom(headId, actions)
        }
        return actions
    }

    private fun dropLateCompleteFrames(actions: MutableList<DrainAction>) {
        val iterator = completeFrames.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.captureMs + maxDelayMs < playoutHeadCaptureMs) {
                actions += DrainAction.DropLate(entry.key)
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

    private fun releaseConsecutiveFrom(startId: Long, actions: MutableList<DrainAction>) {
        var id = startId
        while (true) {
            if (partialFrames.containsKey(id)) break
            val frame = completeFrames.remove(id) ?: break
            actions += DrainAction.Release(frame)
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
