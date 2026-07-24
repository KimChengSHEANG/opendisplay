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

    /**
     * Accumulates bytes across [push] calls and emits complete length-prefixed
     * frames. Backed by a growable byte buffer with a read cursor (mirrors
     * `PhoneReceiver.drainFrames` on iOS): each batch is scanned once and the
     * buffer is compacted at most once per call — O(n) in bytes received, not
     * the O(n²) of the old per-byte `ArrayList` + `removeAt(0)`.
     */
    class Deframer {
        private var buf = ByteArray(INITIAL_CAPACITY)
        private var size = 0

        fun push(data: ByteArray): List<ByteArray> {
            append(data)
            val frames = ArrayList<ByteArray>()
            var cursor = 0
            while (size - cursor >= 4) {
                val len = ((buf[cursor].toInt() and 0xFF) shl 24) or
                    ((buf[cursor + 1].toInt() and 0xFF) shl 16) or
                    ((buf[cursor + 2].toInt() and 0xFF) shl 8) or
                    (buf[cursor + 3].toInt() and 0xFF)
                // Garbage length (negative top bit or absurdly large): the
                // stream is desynced — drop everything buffered and resync.
                if (len < 0 || len > MAX_FRAME) {
                    cursor = size
                    break
                }
                if (size - cursor < 4 + len) break
                frames.add(buf.copyOfRange(cursor + 4, cursor + 4 + len))
                cursor += 4 + len
            }
            compact(cursor)
            return frames
        }

        private fun append(data: ByteArray) {
            if (size + data.size > buf.size) {
                var newCapacity = buf.size
                while (newCapacity < size + data.size) newCapacity *= 2
                buf = buf.copyOf(newCapacity)
            }
            System.arraycopy(data, 0, buf, size, data.size)
            size += data.size
        }

        /** Drop the first [consumed] bytes, keeping any trailing partial frame. */
        private fun compact(consumed: Int) {
            if (consumed <= 0) return
            val remaining = size - consumed
            if (remaining > 0) System.arraycopy(buf, consumed, buf, 0, remaining)
            size = remaining
        }
    }

    private const val INITIAL_CAPACITY = 64 * 1024
    private const val MAX_FRAME = 1 shl 22
}
