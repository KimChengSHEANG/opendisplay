package com.peetzweg.opendisplay.session

import android.content.Context
import com.peetzweg.opendisplay.wire.FrameCodec
import com.peetzweg.opendisplay.wire.WireProtocol
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * TCP receiver session — Android counterpart of iOS `PhoneReceiver`.
 *
 * Owns connect/listen, demux, phone-initiated ping + NTP clock offset,
 * silence watchdog, frame `cap`/`snd` e2e telemetry, Mac health from pings,
 * and periodic `stats` / [PerfStats] publish. Decode and cursor stay in the
 * Activity (Chromebook SurfaceView/VDA + local cursor have no iOS twin).
 *
 * All socket writes go through [writeExecutor] so UI-thread callers never
 * hit `NetworkOnMainThreadException` under StrictMode (ChromeOS ARC).
 */
class ReceiverSession(private val port: Int = DEFAULT_PORT, private val listener: Listener) {

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        /** Pure Annex-B access unit (telemetry prefix already stripped). */
        fun onVideoFrame(data: ByteArray)
        fun onControl(map: Map<String, Any>)
        fun onStatus(status: String)
        /** ~1Hz pipeline health (iOS `@Published perf`). */
        fun onPerf(stats: PerfStats) {}
    }

    var pixelsWide: Int = 0
    var pixelsHigh: Int = 0
    var scale: Double = 1.0
    var device: String = "Android"
    var installId: String = ""

    @Volatile private var running = false
    /** True while the accept loop has an open ServerSocket. Mirrors iOS `listenerHealthy`. */
    @Volatile private var listenerHealthy = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var readThread: Thread? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var outputStream: OutputStream? = null
    private val writeLock = Any()
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ReceiverSession-write").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ReceiverSession-sched").apply { isDaemon = true }
    }
    private var pingFuture: ScheduledFuture<*>? = null
    private var watchdogFuture: ScheduledFuture<*>? = null

    // --- Liveness / clock sync (iOS PhoneReceiver) ---
    private val lastDataReceivedMs = AtomicLong(0L)
    @Volatile private var clockOffsetMs: Double? = null
    private val offsetSamples = ArrayList<SessionTelemetry.OffsetSample>(16)
    private val offsetLock = Any()
    @Volatile private var lastRttMs = 0.0
    @Volatile private var transport = "—"

    // Mac health piggybacked on Mac→device ping
    @Volatile private var macEncDrops = 0
    @Volatile private var macNetDrops = 0
    @Volatile private var macPending = 0
    @Volatile private var macInputP50 = 0.0
    @Volatile private var macInputP95 = 0.0
    @Volatile private var macCapFps = 0

    // Perf window
    private var framesThisWindow = 0
    private var bytesThisWindow = 0
    private var stallsThisWindow = 0
    private var fpsWindowStartMs = 0L
    private var lastFrameAtMs = 0L
    private val frameIntervals = ArrayList<Double>(MAX_SAMPLES)
    private val e2eWindow = ArrayList<Double>(MAX_SAMPLES)
    private val encodeWindow = ArrayList<Double>(MAX_SAMPLES)
    private val perfLock = Any()
    private var statsReportCounter = 0

    val isConnected: Boolean get() = clientSocket != null

    fun start() {
        if (running) return
        running = true
        val thread = Thread({ acceptLoop() }, "ReceiverSession-accept")
        thread.isDaemon = true
        acceptThread = thread
        thread.start()
    }

    fun stop() {
        running = false
        listenerHealthy = false
        stopLivenessTimers()
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        closeClient(notify = false)
        acceptThread?.interrupt()
        acceptThread = null
        readThread?.interrupt()
        readThread = null
    }

    /**
     * Re-arm the TCP listener if it died while we were backgrounded — mirrors
     * iOS `PhoneReceiver.ensureListening()`.
     */
    fun ensureListening() {
        if (running && listenerHealthy) return
        listener.onStatus("listener not healthy — restarting")
        stop()
        start()
    }

    /** Send control JSON; auto-stamps touch with Mac-clock `t` when synced. */
    fun sendControl(map: Map<String, Any>) {
        val stamped = SessionTelemetry.stampTouch(map, nowMs(), clockOffsetMs)
        sendFrame(JSONObject(stamped).toString().toByteArray(Charsets.UTF_8))
    }

    fun updatePanel(wide: Int, high: Int, newScale: Double) {
        if (wide == pixelsWide && high == pixelsHigh && newScale == scale) return
        pixelsWide = wide
        pixelsHigh = high
        scale = newScale
        if (outputStream != null) sendHello()
    }

    private fun acceptLoop() {
        try {
            val server = ServerSocket(port)
            serverSocket = server
            listenerHealthy = true
            listener.onStatus("listening:$port")
            while (running) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    if (!running) return
                    listener.onStatus("accept-error:${e.message}")
                    continue
                }
                handleClient(socket)
            }
        } catch (e: IOException) {
            if (running) listener.onStatus("listen-error:${e.message}")
        } finally {
            listenerHealthy = false
        }
    }

    private fun handleClient(socket: Socket) {
        // Drop previous client without UI disconnect — old readLoop exits after
        // close; clientSocket already replaced so it won't double-notify.
        closeClient(notify = false)
        try {
            socket.tcpNoDelay = true
            socket.receiveBufferSize = 2 * 1024 * 1024
            socket.sendBufferSize = 256 * 1024
            socket.trafficClass = 0x10 // IPTOS_LOWDELAY
        } catch (_: Exception) {
        }
        transport = SessionTelemetry.inferTransport(socket.inetAddress?.hostAddress)
        resetStreamState()
        clientSocket = socket
        outputStream = socket.getOutputStream()
        lastDataReceivedMs.set(System.currentTimeMillis())
        startLivenessTimers()
        listener.onConnected()
        sendHello()
        val thread = Thread({ readLoop(socket) }, "ReceiverSession-read")
        thread.isDaemon = true
        readThread = thread
        thread.start()
    }

    private fun readLoop(socket: Socket) {
        val deframer = FrameCodec.Deframer()
        val buf = ByteArray(64 * 1024)
        try {
            val input = socket.getInputStream()
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                lastDataReceivedMs.set(System.currentTimeMillis())
                synchronized(perfLock) { bytesThisWindow += n }
                for (frame in deframer.push(buf.copyOf(n))) processFrame(frame)
            }
        } catch (_: IOException) {
        } finally {
            if (clientSocket === socket) {
                closeClient(notify = true)
            }
        }
    }

    private fun processFrame(payload: ByteArray) {
        if (isVideoFrame(payload)) {
            val meta = SessionTelemetry.parseFrameMeta(payload)
            noteVideoFrame(meta.captureMs, meta.sendMs)
            listener.onVideoFrame(meta.annexB)
            return
        }
        val map = parseControl(payload) ?: return
        when (map["type"]) {
            "pong" -> handlePong(map)
            "ping" -> {
                // Reply to Mac liveness + absorb send-side health.
                sendControl(pongFor(map["t"]))
                absorbMacPing(map)
            }
        }
        listener.onControl(map)
    }

    private fun handlePong(map: Map<String, Any>) {
        val t1 = (map["t"] as? Number)?.toDouble() ?: return
        val mt = (map["mt"] as? Number)?.toDouble() ?: return
        val sample = SessionTelemetry.offsetFromPong(t1, mt, nowMs()) ?: return
        synchronized(offsetLock) {
            offsetSamples.add(sample)
            if (offsetSamples.size > 15) offsetSamples.removeAt(0)
            clockOffsetMs = SessionTelemetry.bestOffset(offsetSamples)
        }
        lastRttMs = sample.rtt
    }

    private fun absorbMacPing(map: Map<String, Any>) {
        val enc = (map["encDrops"] as? Number)?.toInt()
            ?: (map["drops"] as? Number)?.toInt()
        if (enc != null) macEncDrops = enc
        (map["netDrops"] as? Number)?.toInt()?.let { macNetDrops = it }
        (map["pending"] as? Number)?.toInt()?.let { macPending = it }
        (map["inp50"] as? Number)?.toDouble()?.let { macInputP50 = it }
        (map["inp95"] as? Number)?.toDouble()?.let { macInputP95 = it }
        (map["capFps"] as? Number)?.toInt()?.let { macCapFps = it }
    }

    private fun noteVideoFrame(captureMs: Double?, sendMs: Double?) {
        val now = System.currentTimeMillis()
        var snapshot: PerfWindowResult? = null
        synchronized(perfLock) {
            if (lastFrameAtMs > 0) {
                val ms = (now - lastFrameAtMs).toDouble()
                frameIntervals.add(ms)
                if (frameIntervals.size > MAX_SAMPLES) frameIntervals.removeAt(0)
                if (ms > 50) stallsThisWindow++
            }
            lastFrameAtMs = now

            if (captureMs != null && sendMs != null) {
                encodeWindow.add(sendMs - captureMs)
                val offset = clockOffsetMs
                if (offset != null) {
                    val e2e = (nowMs() + offset) - captureMs
                    if (e2e > -50 && e2e < 5000) {
                        e2eWindow.add(e2e)
                        if (e2eWindow.size > MAX_SAMPLES) e2eWindow.removeAt(0)
                    }
                }
            }

            framesThisWindow++
            if (fpsWindowStartMs == 0L) fpsWindowStartMs = now
            val elapsed = (now - fpsWindowStartMs) / 1000.0
            if (elapsed >= 1.0) {
                snapshot = buildPerfWindow(elapsed, now)
            }
        }
        snapshot?.let {
            listener.onPerf(it.stats)
            if (it.sendReport) sendStatsReport(it.stats)
        }
    }

    private data class PerfWindowResult(val stats: PerfStats, val sendReport: Boolean)

    /** Caller must hold [perfLock]. */
    private fun buildPerfWindow(elapsed: Double, now: Long): PerfWindowResult {
        val fps = (framesThisWindow / elapsed).toInt()
        val mbps = bytesThisWindow * 8.0 / elapsed / 1_000_000.0
        val intervals = ArrayList(frameIntervals)
        val e2e = ArrayList(e2eWindow)
        val enc = ArrayList(encodeWindow)
        val stalls = stallsThisWindow
        framesThisWindow = 0
        bytesThisWindow = 0
        stallsThisWindow = 0
        fpsWindowStartMs = now

        val stats = PerfStats(
            fps = fps,
            mbps = mbps,
            avgFrameMs = if (intervals.isEmpty()) 0.0 else intervals.sum() / intervals.size,
            maxFrameMs = intervals.maxOrNull() ?: 0.0,
            stalls = stalls,
            e2eP50 = SessionTelemetry.percentile(e2e, 0.5),
            e2eP95 = SessionTelemetry.percentile(e2e, 0.95),
            encodeP50 = SessionTelemetry.percentile(enc, 0.5),
            rttMs = lastRttMs,
            transport = transport,
            macEncDrops = macEncDrops,
            macNetDrops = macNetDrops,
            macPending = macPending,
            inputP50 = macInputP50,
            inputP95 = macInputP95,
            capFps = macCapFps,
            offsetKnown = clockOffsetMs != null,
        )

        statsReportCounter++
        val sendReport = statsReportCounter >= 5
        if (sendReport) {
            statsReportCounter = 0
            e2eWindow.clear()
            encodeWindow.clear()
        }
        return PerfWindowResult(stats, sendReport)
    }

    private fun sendStatsReport(stats: PerfStats) {
        // Must not hold perfLock — write path is async but keep call order clear.
        sendControl(
            mapOf(
                "type" to "stats",
                "transport" to transport,
                "fps" to stats.fps,
                "mbps" to ((stats.mbps * 10).toInt() / 10.0),
                "e2e50" to stats.e2eP50.toLong().toDouble(),
                "e2e95" to stats.e2eP95.toLong().toDouble(),
                "enc50" to stats.encodeP50.toLong().toDouble(),
                "rtt" to lastRttMs.toLong().toDouble(),
                "stalls" to stats.stalls,
                "inp50" to macInputP50.toLong().toDouble(),
                "capFps" to macCapFps,
                "offsetKnown" to (clockOffsetMs != null),
            ),
        )
    }

    private fun startLivenessTimers() {
        stopLivenessTimers()
        // Phone-initiated ping every 2s (iOS schedulePing).
        pingFuture = scheduler.scheduleAtFixedRate({
            if (clientSocket != null) {
                sendControl(mapOf("type" to "ping", "t" to nowMs()))
            }
        }, 2, 2, TimeUnit.SECONDS)
        // Silence watchdog every 1s; drop if >5s with no data.
        watchdogFuture = scheduler.scheduleAtFixedRate({
            val last = lastDataReceivedMs.get()
            if (last > 0 && clientSocket != null &&
                System.currentTimeMillis() - last > WATCHDOG_MS
            ) {
                listener.onStatus("watchdog: nothing from Mac >5s — dropping")
                closeClient(notify = true)
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun stopLivenessTimers() {
        pingFuture?.cancel(false)
        pingFuture = null
        watchdogFuture?.cancel(false)
        watchdogFuture = null
    }

    private fun resetStreamState() {
        synchronized(offsetLock) {
            offsetSamples.clear()
            clockOffsetMs = null
        }
        lastRttMs = 0.0
        macEncDrops = 0
        macNetDrops = 0
        macPending = 0
        macInputP50 = 0.0
        macInputP95 = 0.0
        macCapFps = 0
        synchronized(perfLock) {
            framesThisWindow = 0
            bytesThisWindow = 0
            stallsThisWindow = 0
            fpsWindowStartMs = 0L
            lastFrameAtMs = 0L
            frameIntervals.clear()
            e2eWindow.clear()
            encodeWindow.clear()
            statsReportCounter = 0
        }
    }

    private fun pongFor(t: Any?): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        map["type"] = "pong"
        if (t != null) map["t"] = t
        map["mt"] = nowMs()
        return map
    }

    private fun parseControl(payload: ByteArray): Map<String, Any>? {
        return try {
            val json = JSONObject(String(payload, Charsets.UTF_8))
            val map = LinkedHashMap<String, Any>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.get(key)
            }
            map
        } catch (_: org.json.JSONException) {
            null
        }
    }

    private fun sendHello() {
        sendFrame(
            helloJson(pixelsWide, pixelsHigh, scale, device, installId, WireProtocol.version)
                .toByteArray(Charsets.UTF_8),
        )
    }

    private fun sendFrame(payload: ByteArray) {
        if (outputStream == null) return
        val framed = FrameCodec.encode(payload)
        writeExecutor.execute {
            synchronized(writeLock) {
                val stream = outputStream ?: return@synchronized
                try {
                    stream.write(framed)
                    stream.flush()
                } catch (e: IOException) {
                    listener.onStatus("send-error:${e.message}")
                }
            }
        }
    }

    private fun closeClient(notify: Boolean) {
        stopLivenessTimers()
        val had = clientSocket != null
        try {
            clientSocket?.close()
        } catch (_: IOException) {
        }
        clientSocket = null
        outputStream = null
        lastDataReceivedMs.set(0L)
        if (notify && had) listener.onDisconnected()
    }

    private fun nowMs(): Double = System.currentTimeMillis().toDouble()

    companion object {
        const val DEFAULT_PORT = 9000
        private const val MAX_SAMPLES = 120
        private const val WATCHDOG_MS = 5_000L

        fun helloJson(wide: Int, high: Int, scale: Double, device: String, id: String, pv: Int): String {
            val obj = JSONObject()
            obj.put("type", "hello")
            obj.put("pixelsWide", wide)
            obj.put("pixelsHigh", high)
            obj.put("scale", scale)
            obj.put("device", device)
            obj.put("id", id)
            obj.put("pv", pv)
            return obj.toString()
        }

        fun deviceKind(context: Context): String {
            val pm = context.packageManager
            return if (pm.hasSystemFeature("org.chromium.arc") ||
                pm.hasSystemFeature("org.chromium.arc.device_management")
            ) {
                "Chromebook"
            } else {
                "Android"
            }
        }

        fun isVideoFrame(payload: ByteArray): Boolean {
            if (payload.isEmpty()) return false
            if (payload[0] != '{'.code.toByte()) return true
            return payload.any { it == 0.toByte() }
        }

        fun stripTelemetryPrefix(payload: ByteArray): ByteArray =
            SessionTelemetry.parseFrameMeta(payload).annexB
    }
}
