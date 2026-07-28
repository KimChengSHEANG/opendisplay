package com.peetzweg.opendisplay.net

object UdpSendBudget {
    fun shouldNetDrop(pendingDatagrams: Int, maxPending: Int): Boolean =
        pendingDatagrams >= maxPending

    fun paceDelayMs(batchBytes: Int, bytesPerMs: Int): Double {
        val budget = maxOf(1, bytesPerMs)
        return (batchBytes.toDouble() / budget.toDouble()).coerceIn(0.05, 8.0)
    }

    fun bytesPerMs(encodeBitrate: Int, fecPct: Int): Int {
        val wireBps = encodeBitrate.toDouble() * (1.0 + fecPct.toDouble() / 100.0)
        return maxOf(1, (wireBps / 8.0 / 1000.0).toInt())
    }
}
