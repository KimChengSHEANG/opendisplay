package com.peetzweg.opendisplay.session

object VirtualDisplayRetryPolicy {
    const val MAX_ATTEMPTS = 8

    fun sleepSecondsBeforeAttempt(attempt: Int): Double {
        require(attempt >= 0)
        if (attempt == 0) return 0.0
        return minOf(1.5, 0.25 * Math.pow(2.0, (attempt - 1).toDouble()))
    }
}
