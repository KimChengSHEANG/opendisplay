package com.peetzweg.opendisplay.net

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class UdpVideoReceiver(port: Int) {
    interface Callbacks {
        fun onVideoFrame(annexB: ByteArray, captureMs: Long, sendMs: Long, isKeyframe: Boolean)
        fun onIncomplete(frameId: Long, missingSeqs: IntArray, isKeyframe: Boolean)
        fun onLateDrop(frameId: Long)
    }

    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress("0.0.0.0", port))
    }
    @Volatile private var running = false
    private var receiveThread: Thread? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "UdpVideoReceiver-drain").apply { isDaemon = true }
    }
    private var drainFuture: ScheduledFuture<*>? = null
    private val assembler = UdpFrameAssembler()
    private var jitter: JitterBuffer? = null
    private val partialKeyframe = HashMap<Long, Boolean>()

    fun start(callbacks: Callbacks) {
        if (running) return
        running = true
        jitter = JitterBuffer(
            targetDelayMs = TARGET_DELAY_MS,
            maxDelayMs = MAX_DELAY_MS,
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
                val isKeyframe = partialKeyframe.remove(frameId) ?: false
                callbacks.onIncomplete(frameId, missingSeqs, isKeyframe)
            },
        )
        assembler.onIncompleteFrame = { incomplete ->
            trackPartial(incomplete)
            jitter?.offerPartial(
                frameId = incomplete.frameId,
                missingSeqs = incomplete.missingSeqs,
                firstSeenMs = incomplete.firstSeenMs,
            )
        }
        drainFuture = scheduler.scheduleAtFixedRate(
            { jitter?.drain() },
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
        jitter = null
        assembler.onIncompleteFrame = null
        partialKeyframe.clear()
    }

    private fun trackPartial(incomplete: UdpFrameAssembler.IncompleteFrame) {
        partialKeyframe[incomplete.frameId] = incomplete.isKeyframe
    }

    private fun handleDatagram(datagram: ByteArray, callbacks: Callbacks) {
        val now = System.currentTimeMillis()
        val assembled = assembler.offer(datagram, nowMs = now)
        if (assembled != null) {
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

    private fun logW(message: String) {
        try {
            android.util.Log.w(TAG, message)
        } catch (_: RuntimeException) {
        }
    }

    private companion object {
        const val TAG = "UdpVideoReceiver"
        const val MAX_DATAGRAM_SIZE = 65_535
        const val TARGET_DELAY_MS = 20L
        const val MAX_DELAY_MS = 40L
        const val DRAIN_INTERVAL_MS = 5L
    }
}
