package com.peetzweg.opendisplay.input

/**
 * Normalizes touch/scroll input and builds the control JSON sent to the Mac
 * via `ReceiverSession.sendControl`, matching iOS's `VideoView` (see
 * `OpenSidecarPhoneApp.swift`):
 * `{"type":"touch","phase":"began|moved|ended|cancelled","x":0…1,"y":0…1}`
 * `{"type":"scroll","dx":…,"dy":…}` (deltas in video pixels).
 *
 * `StreamingScreen`'s video view fills its container with no letterboxing
 * — the `hello` handshake already advertises the device's own pixel size, so
 * the Mac captures 1:1 — so normalization is a plain view-pixel ratio here,
 * simpler than iOS's aspect-fit math against a negotiated video size.
 *
 * Pure Kotlin (no `android.*` types) so it's unit-testable on the JVM.
 * `StreamingScreen` extracts pointer data from `MotionEvent` and calls in:
 * one finger drives [down]/[move]/[up]/[cancel]; a second finger joining
 * switches to two-finger scroll via [secondPointerDown]/[twoFingerMove]/
 * [secondPointerUp], mirroring iOS's `UIPanGestureRecognizer(minimum/maximum
 * NumberOfTouches = 2)`.
 *
 * Move/hover batches mirror iOS `coalescedTouches` — every historical sample
 * is forwarded so the Mac gets full-rate trackpad/mouse injection.
 */
class InputForwarder(private val send: (Map<String, Any>) -> Unit) {

    private var twoFingerActive = false
    private var multiTouchOccurred = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastNormX = 0.5
    private var lastNormY = 0.5
    private var lastMoveX = Float.NaN
    private var lastMoveY = Float.NaN

    /** First finger touches down. */
    fun down(x: Float, y: Float, width: Int, height: Int) {
        twoFingerActive = false
        multiTouchOccurred = false
        lastMoveX = Float.NaN
        lastMoveY = Float.NaN
        touch("began", x, y, width, height)
    }

    /** The single tracked finger moved. Ignored once a second finger has joined. */
    fun move(x: Float, y: Float, width: Int, height: Int) {
        if (twoFingerActive || multiTouchOccurred) return
        movedSamples(listOf(x to y), width, height)
    }

    /**
     * iOS coalesced-touch analogue: forward every sample in order (historical
     * + current), then one predicted point from the last delta (~1 frame).
     */
    fun movedSamples(samples: List<Pair<Float, Float>>, width: Int, height: Int) {
        if (twoFingerActive || multiTouchOccurred) return
        if (samples.isEmpty()) return
        for ((x, y) in samples) {
            touch("moved", x, y, width, height)
        }
        val last = samples.last()
        val prev = if (samples.size >= 2) {
            samples[samples.size - 2]
        } else if (!lastMoveX.isNaN() && !lastMoveY.isNaN()) {
            lastMoveX to lastMoveY
        } else {
            null
        }
        if (prev != null) {
            val px = last.first + (last.first - prev.first) * PREDICT_FACTOR
            val py = last.second + (last.second - prev.second) * PREDICT_FACTOR
            if (px != last.first || py != last.second) {
                touch("moved", px, py, width, height)
            }
        }
        lastMoveX = last.first
        lastMoveY = last.second
    }

    /**
     * Mouse/trackpad hover (no button): Mac treats touch `moved` while up as
     * `mouseMoved`. Prefer [movedSamples] with MotionEvent history.
     */
    fun hover(x: Float, y: Float, width: Int, height: Int) {
        movedSamples(listOf(x to y), width, height)
    }

    /** Mouse-wheel / trackpad scroll → Mac `scroll` (video pixels). */
    fun wheel(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        send(mapOf("type" to "scroll", "dx" to dx.toDouble(), "dy" to dy.toDouble()))
    }

    /** The single tracked finger lifted. Ignored once a second finger has joined this gesture. */
    fun up(x: Float, y: Float, width: Int, height: Int) {
        if (twoFingerActive || multiTouchOccurred) {
            twoFingerActive = false
            multiTouchOccurred = false
            return
        }
        lastMoveX = Float.NaN
        lastMoveY = Float.NaN
        touch("ended", x, y, width, height)
    }

    /** The gesture was cancelled by the system (e.g. window lost focus). */
    fun cancel(x: Float, y: Float, width: Int, height: Int) {
        twoFingerActive = false
        multiTouchOccurred = false
        lastMoveX = Float.NaN
        lastMoveY = Float.NaN
        touch("cancelled", x, y, width, height)
    }

    /**
     * A second finger joined mid-press: cancels the in-flight single-finger
     * touch (if any) and starts two-finger scroll tracking from [focusX]/[focusY]
     * (e.g. the average position of the two active pointers, in view pixels).
     */
    fun secondPointerDown(focusX: Float, focusY: Float) {
        if (!twoFingerActive) {
            send(mapOf("type" to "touch", "phase" to "cancelled", "x" to lastNormX, "y" to lastNormY))
        }
        twoFingerActive = true
        multiTouchOccurred = true
        lastFocusX = focusX
        lastFocusY = focusY
        lastMoveX = Float.NaN
        lastMoveY = Float.NaN
    }

    /**
     * Two fingers moved: sends `scroll` with the delta (in view/video pixels,
     * 1:1 per this class's docs above) since the last call. No-op once the
     * gesture has dropped back to one (or zero) fingers.
     */
    fun twoFingerMove(focusX: Float, focusY: Float) {
        if (!twoFingerActive) return
        val dx = (focusX - lastFocusX).toDouble()
        val dy = (focusY - lastFocusY).toDouble()
        lastFocusX = focusX
        lastFocusY = focusY
        if (dx == 0.0 && dy == 0.0) return
        send(mapOf("type" to "scroll", "dx" to dx, "dy" to dy))
    }

    /** One of the two fingers lifted, dropping back to a single pointer (or zero). */
    fun secondPointerUp() {
        twoFingerActive = false
    }

    private fun touch(phase: String, x: Float, y: Float, width: Int, height: Int) {
        val (nx, ny) = normalize(x, y, width, height)
        lastNormX = nx
        lastNormY = ny
        send(mapOf("type" to "touch", "phase" to phase, "x" to nx, "y" to ny))
    }

    companion object {
        /** Fraction of last delta to project (~half frame; iOS predictedTouches). */
        private const val PREDICT_FACTOR = 0.5f

        /** View-pixel `(x, y)` within a `width`×`height` view, clamped to `[0,1]`. */
        fun normalize(x: Float, y: Float, width: Int, height: Int): Pair<Double, Double> {
            if (width <= 0 || height <= 0) return 0.5 to 0.5
            val nx = (x.toDouble() / width).coerceIn(0.0, 1.0)
            val ny = (y.toDouble() / height).coerceIn(0.0, 1.0)
            return nx to ny
        }
    }
}
