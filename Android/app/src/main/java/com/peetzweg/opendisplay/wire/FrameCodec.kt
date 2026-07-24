package com.peetzweg.opendisplay.wire

import java.nio.ByteBuffer
import java.nio.ByteOrder

object FrameCodec {
    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).putInt(payload.size)
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    class Deframer {
        private val buf = ArrayList<Byte>()
        fun push(data: ByteArray): List<ByteArray> {
            for (b in data) buf.add(b)
            val frames = ArrayList<ByteArray>()
            while (true) {
                if (buf.size < 4) break
                val len = ByteBuffer.wrap(byteArrayOf(buf[0], buf[1], buf[2], buf[3]))
                    .order(ByteOrder.BIG_ENDIAN).int
                if (len < 0 || len > 1 shl 22) { buf.clear(); break }
                if (buf.size < 4 + len) break
                val payload = ByteArray(len)
                for (i in 0 until len) payload[i] = buf[4 + i]
                repeat(4 + len) { buf.removeAt(0) }
                frames.add(payload)
            }
            return frames
        }
    }
}
