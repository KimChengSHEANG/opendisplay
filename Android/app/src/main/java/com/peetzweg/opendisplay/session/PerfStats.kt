package com.peetzweg.opendisplay.session

/**
 * One-second window of pipeline health — mirrors iOS `PerfStats` in
 * `PhoneReceiver.swift` (overlay + PHONE-STATS to the Mac).
 */
data class PerfStats(
    val fps: Int = 0,
    val mbps: Double = 0.0,
    val avgFrameMs: Double = 0.0,
    val maxFrameMs: Double = 0.0,
    val stalls: Int = 0,
    val e2eP50: Double = 0.0,
    val e2eP95: Double = 0.0,
    val encodeP50: Double = 0.0,
    val rttMs: Double = 0.0,
    val transport: String = "—",
    val macEncDrops: Int = 0,
    val macNetDrops: Int = 0,
    val macPending: Int = 0,
    val inputP50: Double = 0.0,
    val inputP95: Double = 0.0,
    val capFps: Int = 0,
    /** Queued→rendered inside the receiver (iOS `decodeP50`). */
    val decodeP50: Double = 0.0,
    val decodeP95: Double = 0.0,
    val offsetKnown: Boolean = false,
) {
    /**
     * Capture→glass estimate for the overlay: wire e2e (cap→receive) plus
     * decode→panel when both are known; otherwise e2e alone.
     */
    val totalLatencyP50: Double
        get() = when {
            e2eP50 > 0.0 && decodeP50 > 0.0 -> e2eP50 + decodeP50
            else -> e2eP50
        }
}

/**
 * Pure helpers shared by [ReceiverSession] and unit tests — clock sync,
 * telemetry prefix parse, percentiles (iOS PhoneReceiver maths).
 */
object SessionTelemetry {
    data class FrameMeta(
        val annexB: ByteArray,
        val captureMs: Double?,
        val sendMs: Double?,
    )

    data class OffsetSample(val rtt: Double, val offset: Double)

    /** NTP-style offset = macClock − phoneClock from a pong sample. */
    fun offsetFromPong(t1: Double, mt: Double, t2: Double): OffsetSample? {
        val rtt = t2 - t1
        if (rtt < 0 || rtt >= 2000) return null
        val offset = mt - (t1 + t2) / 2.0
        return OffsetSample(rtt, offset)
    }

    /** Keep the sample with the lowest RTT (least asymmetric path). */
    fun bestOffset(samples: List<OffsetSample>): Double? =
        samples.minByOrNull { it.rtt }?.offset

    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val idx = minOf(sorted.size - 1, (sorted.size * p).toInt())
        return sorted[idx]
    }

    /**
     * Split Mac `{"cap":…,"snd":…}` prefix from Annex-B. Returns the full
     * payload as annexB when there is no prefix.
     */
    fun parseFrameMeta(payload: ByteArray): FrameMeta {
        if (payload.isEmpty() || payload[0] != '{'.code.toByte()) {
            return FrameMeta(payload, null, null)
        }
        val start = indexOfStartCode(payload)
        if (start <= 0) return FrameMeta(payload, null, null)
        val annexB = payload.copyOfRange(start, payload.size)
        var cap: Double? = null
        var snd: Double? = null
        try {
            val json = org.json.JSONObject(String(payload, 0, start, Charsets.UTF_8))
            if (json.has("cap")) cap = json.getDouble("cap")
            if (json.has("snd")) snd = json.getDouble("snd")
        } catch (_: org.json.JSONException) {
            // Malformed prefix — still deliver Annex-B.
        }
        return FrameMeta(annexB, cap, snd)
    }

    fun inferTransport(peerHost: String?): String {
        if (peerHost.isNullOrEmpty()) return "—"
        val h = peerHost.lowercase()
        return if (
            h == "127.0.0.1" || h == "::1" || h == "localhost" ||
            h.startsWith("/127.") || h.contains("127.0.0.1")
        ) {
            "USB"
        } else {
            "WiFi"
        }
    }

    /**
     * Stamp touch with Mac-clock time when offset is known (iOS sendTouch).
     * Leaves other messages and already-stamped touches alone.
     */
    fun stampTouch(map: Map<String, Any>, nowMs: Double, clockOffsetMs: Double?): Map<String, Any> {
        if (map["type"] != "touch") return map
        if (map.containsKey("t")) return map
        val offset = clockOffsetMs ?: return map
        val out = LinkedHashMap(map)
        out["t"] = nowMs + offset
        return out
    }

    fun indexOfStartCode(data: ByteArray): Int {
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
