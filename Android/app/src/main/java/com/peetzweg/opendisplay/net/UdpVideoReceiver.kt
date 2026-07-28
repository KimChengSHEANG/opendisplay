package com.peetzweg.opendisplay.net

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class UdpVideoReceiver(port: Int) {
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress("0.0.0.0", port))
    }
    @Volatile private var running = false
    private var receiveThread: Thread? = null

    fun start(onDatagram: (ByteArray) -> Unit) {
        if (running) return
        running = true
        receiveThread = Thread({
            val buffer = ByteArray(MAX_DATAGRAM_SIZE)
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    onDatagram(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
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
        socket.close()
        receiveThread?.interrupt()
        receiveThread = null
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
    }
}
