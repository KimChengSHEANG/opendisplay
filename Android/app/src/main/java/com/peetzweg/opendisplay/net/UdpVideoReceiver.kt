package com.peetzweg.opendisplay.net

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class UdpVideoReceiver(
    port: Int,
    private val targetDelayMs: Long = UdpJitterTiming.TARGET_DELAY_MS,
    private val maxDelayMs: Long = UdpJitterTiming.MAX_DELAY_MS,
) {
    interface Callbacks {
        fun onVideoFrame(annexB: ByteArray, captureMs: Long, sendMs: Long, isKeyframe: Boolean)
        fun onIncomplete(frameId: Long, missingSeqs: IntArray, isKeyframe: Boolean)
        fun onLateDrop(frameId: Long)
    }

    data class QosSnapshot(
        val lossPct: Double,
        val jitterMs: Double,
        val fecRecoveries: Int,
    )

    private class QosWindowStats {
        private var lastSeq: Int? = null
        private var datagramsReceived = 0
        private var seqGaps = 0
        private var fecRecoveries = 0
        private var lastArrivalMs = 0L
        private val interArrivalMs = ArrayList<Double>(32)

        fun noteDatagram(seq: Int, nowMs: Long) {
            datagramsReceived++
            val previous = lastSeq
            if (previous != null) {
                val expected = (previous + 1) and 0xFFFF
                if (seq != expected) {
                    val forward = (seq - expected) and 0xFFFF
                    if (forward in 1..0x7FFF) seqGaps += forward
                }
            }
            lastSeq = seq
            if (lastArrivalMs > 0L) {
                interArrivalMs.add((nowMs - lastArrivalMs).toDouble())
            }
            lastArrivalMs = nowMs
        }

        fun noteFecRecovery() {
            fecRecoveries++
        }

        fun snapshotAndReset(): QosSnapshot {
            val total = datagramsReceived + seqGaps
            val lossPct = if (total > 0) seqGaps * 100.0 / total else 0.0
            val jitterMs = if (interArrivalMs.isEmpty()) {
                0.0
            } else {
                val mean = interArrivalMs.sum() / interArrivalMs.size
                interArrivalMs.sumOf { kotlin.math.abs(it - mean) } / interArrivalMs.size
            }
            val snapshot = QosSnapshot(lossPct = lossPct, jitterMs = jitterMs, fecRecoveries = fecRecoveries)
            datagramsReceived = 0
            seqGaps = 0
            fecRecoveries = 0
            interArrivalMs.clear()
            return snapshot
        }

        fun reset() {
            lastSeq = null
            datagramsReceived = 0
            seqGaps = 0
            fecRecoveries = 0
            lastArrivalMs = 0L
            interArrivalMs.clear()
        }
    }

    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        // Prefer :: so IPv6 peers (Chromebook ARC WiFi) deliver datagrams.
        // 0.0.0.0 is IPv4-only — Mac sending to the TCP remote IPv6 address
        // never reaches that bind, which blacks the panel until reconnect.
        var bound = false
        var lastError: IOException? = null
        for (host in UdpBindAddress.bindHostCandidates()) {
            try {
                bind(InetSocketAddress(InetAddress.getByName(host), port))
                bound = true
                break
            } catch (error: IOException) {
                lastError = error
            }
        }
        if (!bound) {
            throw lastError ?: IOException("UDP bind failed for port $port")
        }
    }
    @Volatile private var running = false
    private var receiveThread: Thread? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "UdpVideoReceiver-drain").apply { isDaemon = true }
    }
    private var drainFuture: ScheduledFuture<*>? = null
    private val assembler = UdpFrameAssembler()
    private var jitter: JitterBuffer? = null
    private val stateLock = Any()
    private val partialKeyframe = HashMap<Long, Boolean>()
    private val qosStats = QosWindowStats()

    fun start(callbacks: Callbacks) {
        if (running) return
        running = true
        jitter = JitterBuffer(
            targetDelayMs = targetDelayMs,
            maxDelayMs = maxDelayMs,
            nowMs = { System.currentTimeMillis() },
            onRelease = { frame ->
                callbacks.onVideoFrame(
                    frame.annexB,
                    frame.captureMs,
                    frame.sendMs,
                    frame.isKeyframe,
                )
            },
            onDropLate = callbacks::onLateDrop,
            onIncomplete = { frameId, missingSeqs ->
                val isKeyframe = synchronized(stateLock) {
                    partialKeyframe.remove(frameId) ?: false
                }
                callbacks.onIncomplete(frameId, missingSeqs, isKeyframe)
            },
        )
        assembler.onIncompleteFrame = { incomplete ->
            synchronized(stateLock) {
                trackPartial(incomplete)
                jitter?.offerPartial(
                    frameId = incomplete.frameId,
                    missingSeqs = incomplete.missingSeqs,
                    firstSeenMs = incomplete.firstSeenMs,
                )
            }
        }
        drainFuture = scheduler.scheduleAtFixedRate(
            { synchronized(stateLock) { jitter?.drain() } },
            DRAIN_INTERVAL_MS,
            DRAIN_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        receiveThread = Thread({
            val buffer = ByteArray(MAX_DATAGRAM_SIZE)
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val datagram = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    handleDatagram(datagram, callbacks)
                } catch (error: IOException) {
                    if (running) {
                        logW("receive failed: ${error.message}")
                    }
                    break
                }
            }
        }, "UdpVideoReceiver").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        drainFuture?.cancel(false)
        drainFuture = null
        scheduler.shutdownNow()
        socket.close()
        receiveThread?.interrupt()
        receiveThread = null
        synchronized(stateLock) {
            jitter = null
            partialKeyframe.clear()
            qosStats.reset()
        }
        assembler.onIncompleteFrame = null
    }

    fun snapshotQosAndReset(): QosSnapshot = synchronized(stateLock) { qosStats.snapshotAndReset() }

    private fun trackPartial(incomplete: UdpFrameAssembler.IncompleteFrame) {
        partialKeyframe[incomplete.frameId] = incomplete.isKeyframe
    }

    private fun handleDatagram(datagram: ByteArray, callbacks: Callbacks) {
        val now = System.currentTimeMillis()
        val header = UdpVideoProtocol.decodeHeader(datagram)
        synchronized(stateLock) {
            if (header != null) qosStats.noteDatagram(header.seq, now)
        }
        val assembled = assembler.offer(datagram, nowMs = now)
        synchronized(stateLock) {
            if (assembled != null) {
                if (assembled.recoveredByFec) qosStats.noteFecRecovery()
                partialKeyframe.remove(assembled.frameId)
                jitter?.offer(
                    JitterBuffer.Frame(
                        frameId = assembled.frameId,
                        complete = true,
                        captureMs = assembled.captureMs,
                        sendMs = assembled.sendMs,
                        annexB = assembled.annexB,
                        isKeyframe = assembled.isKeyframe,
                        seqs = assembled.seqs,
                        readyAtMs = now,
                    ),
                )
                return
            }
            assembler.currentIncomplete()?.let { incomplete ->
                trackPartial(incomplete)
                jitter?.offerPartial(
                    frameId = incomplete.frameId,
                    missingSeqs = incomplete.missingSeqs,
                    firstSeenMs = incomplete.firstSeenMs,
                )
            }
        }
    }

    private fun logW(message: String) {
        try {
            android.util.Log.w(TAG, message)
        } catch (_: RuntimeException) {
        }
    }

    private companion object {
        const val TAG = "UdpVideoReceiver"
        const val MAX_DATAGRAM_SIZE = 65_535
        const val DRAIN_INTERVAL_MS = 5L
    }
}
