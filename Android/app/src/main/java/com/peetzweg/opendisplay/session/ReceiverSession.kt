package com.peetzweg.opendisplay.session

import android.content.Context
import com.peetzweg.opendisplay.wire.FrameCodec
import com.peetzweg.opendisplay.wire.WireProtocol
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP receiver session: listens for the Mac sender to connect, sends `hello`
 * on connect, demuxes control (`{...}`) vs. video frames, and answers `ping`
 * with `pong`. Mirrors the wire behavior of `iOS/PhoneReceiver.swift`.
 *
 * v1: a background accept thread + one background read thread per
 * connection. Only one client is served at a time — a new connection
 * replaces the previous one.
 */
class ReceiverSession(private val port: Int = DEFAULT_PORT, private val listener: Listener) {

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onVideoFrame(data: ByteArray)
        fun onControl(map: Map<String, Any>)
        fun onStatus(status: String)
    }

    var pixelsWide: Int = 0
    var pixelsHigh: Int = 0
    var scale: Double = 1.0
    var device: String = "Android"
    var installId: String = ""

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var readThread: Thread? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var outputStream: OutputStream? = null
    private val writeLock = Any()

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
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        closeClient()
        acceptThread?.interrupt()
        acceptThread = null
        readThread?.interrupt()
        readThread = null
    }

    /** Send an arbitrary control message (e.g. `pong`, app-level events) to the connected peer. */
    fun sendControl(map: Map<String, Any>) {
        sendFrame(JSONObject(map).toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * Panel metrics changed (rotation): update what the next `hello` reports
     * and, if a Mac is connected right now, resend `hello` immediately so it
     * rebuilds its virtual display without waiting for a reconnect — mirrors
     * `PhoneReceiver.setOrientation`'s mid-session `sendHello`.
     */
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
        }
    }

    private fun handleClient(socket: Socket) {
        closeClient()
        clientSocket = socket
        outputStream = socket.getOutputStream()
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
                for (frame in deframer.push(buf.copyOf(n))) processFrame(frame)
            }
        } catch (_: IOException) {
            // Peer dropped — fall through to disconnect notification below.
        } finally {
            if (clientSocket === socket) {
                closeClient()
                listener.onDisconnected()
            }
        }
    }

    private fun processFrame(payload: ByteArray) {
        if (isVideoFrame(payload)) {
            listener.onVideoFrame(stripTelemetryPrefix(payload))
        } else {
            val map = parseControl(payload) ?: return
            if (map["type"] == "ping") sendControl(pongFor(map["t"]))
            listener.onControl(map)
        }
    }

    private fun pongFor(t: Any?): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        map["type"] = "pong"
        if (t != null) map["t"] = t
        map["mt"] = System.currentTimeMillis().toDouble()
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
        sendFrame(helloJson(pixelsWide, pixelsHigh, scale, device, installId, WireProtocol.version)
            .toByteArray(Charsets.UTF_8))
    }

    private fun sendFrame(payload: ByteArray) {
        val out = outputStream ?: return
        val framed = FrameCodec.encode(payload)
        synchronized(writeLock) {
            try {
                out.write(framed)
                out.flush()
            } catch (e: IOException) {
                listener.onStatus("send-error:${e.message}")
            }
        }
    }

    private fun closeClient() {
        try {
            clientSocket?.close()
        } catch (_: IOException) {
        }
        clientSocket = null
        outputStream = null
    }

    companion object {
        const val DEFAULT_PORT = 9000

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

        /** "Chromebook" for ARC++/ChromeOS, "Android" otherwise. */
        fun deviceKind(context: Context): String {
            val pm = context.packageManager
            return if (pm.hasSystemFeature("org.chromium.arc") ||
                pm.hasSystemFeature("org.chromium.arc.device_management")
            ) "Chromebook" else "Android"
        }

        /**
         * True if `payload` is video, not control JSON. A payload starting with
         * `{` isn't necessarily control: the Mac stamps every video frame with a
         * `{"cap":...,"snd":...}` telemetry prefix directly ahead of the raw Annex
         * B bytes (see `MacSender.swift`'s `encode(_:pts:)` / `annexB(from:)`) with
         * no separating delimiter. Annex B start codes always contain a `0x00`
         * byte; control JSON (hello/ping/pong/cursor/...) never does — mirrors the
         * disambiguation `PhoneReceiver.swift`'s `handleAnnexB` uses.
         */
        fun isVideoFrame(payload: ByteArray): Boolean {
            if (payload.isEmpty()) return false
            if (payload[0] != '{'.code.toByte()) return true
            return payload.any { it == 0.toByte() }
        }

        /** Strips a `{"cap":...,"snd":...}` telemetry prefix (if present), leaving pure Annex B. */
        fun stripTelemetryPrefix(payload: ByteArray): ByteArray {
            if (payload.isEmpty() || payload[0] != '{'.code.toByte()) return payload
            val start = indexOfStartCode(payload)
            return if (start > 0) payload.copyOfRange(start, payload.size) else payload
        }

        private fun indexOfStartCode(data: ByteArray): Int {
            var i = 0
            while (i + 2 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    (data[i + 2] == 1.toByte() ||
                        (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()))
                ) {
                    return i
                }
                i++
            }
            return -1
        }
    }
}
