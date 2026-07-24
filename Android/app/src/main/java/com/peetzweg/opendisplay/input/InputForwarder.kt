package com.peetzweg.opendisplay.input

/**
 * Normalizes touch/scroll input and builds the control JSON sent to the Mac
 * via `ReceiverSession.sendControl`, matching iOS's `VideoView` (see
 * `OpenSidecarPhoneApp.swift`):
 * `{"type":"touch","phase":"began|moved|ended|cancelled","x":0…1,"y":0…1}`
 * `{"type":"scroll","dx":…,"dy":…}` (deltas in video pixels).
 *
 * `StreamingScreen`'s `SurfaceView` fills its container with no letterboxing
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
 */
class InputForwarder(private val send: (Map<String, Any>) -> Unit) {

    private var twoFingerActive = false
    private var multiTouchOccurred = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastNormX = 0.5
    private var lastNormY = 0.5

    /** First finger touches down. */
    fun down(x: Float, y: Float, width: Int, height: Int) {
        twoFingerActive = false
        multiTouchOccurred = false
        touch("began", x, y, width, height)
    }

    /** The single tracked finger moved. Ignored once a second finger has joined. */
    fun move(x: Float, y: Float, width: Int, height: Int) {
        if (twoFingerActive || multiTouchOccurred) return
        touch("moved", x, y, width, height)
    }

    /** The single tracked finger lifted. Ignored once a second finger has joined this gesture. */
    fun up(x: Float, y: Float, width: Int, height: Int) {
        if (twoFingerActive || multiTouchOccurred) {
            twoFingerActive = false
            multiTouchOccurred = false
            return
        }
        touch("ended", x, y, width, height)
    }

    /** The gesture was cancelled by the system (e.g. window lost focus). */
    fun cancel(x: Float, y: Float, width: Int, height: Int) {
        twoFingerActive = false
        multiTouchOccurred = false
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
        /** View-pixel `(x, y)` within a `width`×`height` view, clamped to `[0,1]`. */
        fun normalize(x: Float, y: Float, width: Int, height: Int): Pair<Double, Double> {
            if (width <= 0 || height <= 0) return 0.5 to 0.5
            val nx = (x.toDouble() / width).coerceIn(0.0, 1.0)
            val ny = (y.toDouble() / height).coerceIn(0.0, 1.0)
            return nx to ny
        }
    }
}
