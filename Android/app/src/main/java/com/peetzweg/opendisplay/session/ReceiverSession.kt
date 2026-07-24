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
        if (payload.isNotEmpty() && payload[0] == '{'.code.toByte()) {
            val map = parseControl(payload) ?: return
            if (map["type"] == "ping") sendControl(pongFor(map["t"]))
            listener.onControl(map)
        } else {
            listener.onVideoFrame(payload)
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
    }
}
