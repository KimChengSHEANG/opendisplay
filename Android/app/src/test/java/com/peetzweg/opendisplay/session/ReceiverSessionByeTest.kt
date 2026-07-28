package com.peetzweg.opendisplay.session

import com.peetzweg.opendisplay.wire.WireMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Live-socket check that intentional closes announce `bye` with a reason
 * before tearing TCP — the Mac-side breadcrumb for Chromebook mid-session drops.
 */
class ReceiverSessionByeTest {
    @Test
    fun forcePeerReconnect_sendsByeWithReasonBeforeClose() {
        val port = ServerSocket(0).use { it.localPort }
        val bye = AtomicReference<Map<String, Any>?>(null)
        val disconnected = CountDownLatch(1)
        val session = ReceiverSession(
            port = port,
            listener = object : ReceiverSession.Listener {
                override fun onConnected() {}
                override fun onDisconnected() { disconnected.countDown() }
                override fun onVideoFrame(data: ByteArray) {}
                override fun onControl(map: Map<String, Any>) {}
                override fun onStatus(status: String) {}
            },
        )
        session.start()
        try {
            val client = Socket("127.0.0.1", port)
            // Wait until accept has wired the socket (hello arrives).
            val helloFrame = readOneFrame(client)
            assertTrue(String(helloFrame).contains("\"hello\""))

            // Drain concurrent hello/pings in background while we wait for bye.
            val readerDone = CountDownLatch(1)
            Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val payload = readOneFrame(client)
                        val text = String(payload)
                        if (text.startsWith("{")) {
                            val obj = JSONObject(text)
                            if (obj.optString("type") == WireMessage.bye) {
                                val map = LinkedHashMap<String, Any>()
                                val keys = obj.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    map[k] = obj.get(k)
                                }
                                bye.set(map)
                                break
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    readerDone.countDown()
                }
            }, "bye-reader").also { it.isDaemon = true; it.start() }

            session.forcePeerReconnect("unit-test-tear")
            assertTrue("expected onDisconnected", disconnected.await(3, TimeUnit.SECONDS))
            assertTrue("expected bye frame", readerDone.await(3, TimeUnit.SECONDS))
            val msg = bye.get()
            requireNotNull(msg) { "bye not received" }
            assertEquals(WireMessage.bye, msg["type"])
            assertEquals("forcePeerReconnect:unit-test-tear", msg["reason"])
            client.close()
        } finally {
            session.stop(reason = "testCleanup")
        }
    }

    private fun readOneFrame(socket: Socket): ByteArray {
        val input = socket.getInputStream()
        val header = ByteArray(4)
        var got = 0
        while (got < 4) {
            val n = input.read(header, got, 4 - got)
            require(n >= 0) { "EOF reading length" }
            got += n
        }
        val len = ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)
        val payload = ByteArray(len)
        got = 0
        while (got < len) {
            val n = input.read(payload, got, len - got)
            require(n >= 0) { "EOF reading payload" }
            got += n
        }
        return payload
    }
}
