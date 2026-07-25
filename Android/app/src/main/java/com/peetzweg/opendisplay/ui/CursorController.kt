package com.peetzweg.opendisplay.ui

import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Local Mac cursor overlay that bypasses Compose.
 *
 * iOS updates a `CALayer` directly (no implicit animations). We do the same
 * with an [ImageView]: size/sprite via layout params only when they change,
 * position via `translationX`/`translationY`.
 *
 * Chromebook mouse: hover→Mac→echo is a full RTT and feels laggy. While the
 * local pointer is moving we paint immediately ([moveLocal]) — with a short
 * velocity lead so the sprite stays ahead of the trackpad sample — and ignore
 * Mac echo positions for a window long enough to cover USB/WiFi RTT.
 *
 * Chromebook also keeps the view attached (alpha 0 when idle) so the first
 * move never pays a GONE→VISIBLE + layout hitch — that hitch was the main
 * "choppy when it first starts moving" feel with `showsCursor=false`.
 */
class CursorController(private val chromebook: Boolean = false) {
    @Volatile private var host: View? = null
    @Volatile private var view: ImageView? = null

    private var bitmap: Bitmap? = null
    private var normW: Float = 0f
    private var normH: Float = 0f
    private var anchorX: Float = 0f
    private var anchorY: Float = 0f
    private var x: Float = 0.5f
    private var y: Float = 0.5f
    private var visible: Boolean = false
    private var laidOutW: Int = -1
    private var laidOutH: Int = -1
    /** Uptime deadline: prefer local hover/touch position over Mac echo. */
    @Volatile private var localDriveUntilMs: Long = 0L
    private var lastLocalX: Float = Float.NaN
    private var lastLocalY: Float = Float.NaN
    private var lastLocalAtMs: Long = 0L

    fun attach(host: View, cursorView: ImageView) {
        this.host = host
        this.view = cursorView
        if (chromebook) {
            // Hardware-compose the sprite so it isn't stuck behind SurfaceView
            // hole-punch / software blending (a common ARC mouse-feel killer).
            cursorView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            cursorView.alpha = 0f
            // Stay in the tree so the first move only updates translation.
            cursorView.visibility = View.VISIBLE
        }
        runOnMain {
            // Pre-size with the placeholder so moveLocal never triggers a
            // layout pass on the first trackpad sample.
            if (chromebook) ensureLaidOut(cursorView)
            applyAll()
        }
    }

    fun detach() {
        host = null
        view = null
        laidOutW = -1
        laidOutH = -1
        localDriveUntilMs = 0L
        lastLocalX = Float.NaN
        lastLocalY = Float.NaN
        lastLocalAtMs = 0L
    }

    /**
     * Immediate local position from Chromebook hover/touch (UI thread).
     * Does not wait for the Mac echo — mirrors a native OS pointer.
     */
    fun moveLocal(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        var drawX = x
        var drawY = y
        if (!lastLocalX.isNaN() && !lastLocalY.isNaN() && now - lastLocalAtMs < 80) {
            // Lead the sprite by a fraction of the last delta (~½ frame) so
            // ARC's input→draw path feels closer to a native 60/120Hz pointer.
            // Skip prediction after a pause — leading a cold start overshoots.
            drawX = (x + (x - lastLocalX) * LOCAL_PREDICT).coerceIn(0f, 1f)
            drawY = (y + (y - lastLocalY) * LOCAL_PREDICT).coerceIn(0f, 1f)
        }
        lastLocalX = x
        lastLocalY = y
        lastLocalAtMs = now
        this.x = drawX
        this.y = drawY
        this.visible = true
        localDriveUntilMs = now + LOCAL_DRIVE_MS
        val v = view ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyPosition(v)
        } else {
            val posted = v.handler?.postAtFrontOfQueue { applyPosition(v) } == true
            if (!posted) v.post { applyPosition(v) }
        }
    }

    fun move(x: Float, y: Float, visible: Boolean) {
        // While local pointer is driving, Mac echo is one RTT stale — skip
        // position so we don't tug the cursor backward every 8ms.
        if (visible && SystemClock.uptimeMillis() < localDriveUntilMs) return
        this.x = x
        this.y = y
        this.visible = visible
        val v = view ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyPosition(v)
        } else {
            val posted = v.handler?.postAtFrontOfQueue { applyPosition(v) } == true
            if (!posted) v.post { applyPosition(v) }
        }
    }

    fun setSprite(
        bitmap: Bitmap,
        normW: Float,
        normH: Float,
        anchorX: Float,
        anchorY: Float,
    ) {
        this.bitmap = bitmap
        this.normW = normW
        this.normH = normH
        this.anchorX = anchorX
        this.anchorY = anchorY
        laidOutW = -1
        laidOutH = -1
        runOnMain { applyAll() }
    }

    fun hide() {
        visible = false
        localDriveUntilMs = 0L
        lastLocalX = Float.NaN
        lastLocalY = Float.NaN
        lastLocalAtMs = 0L
        runOnMain {
            val v = view ?: return@runOnMain
            if (chromebook) {
                v.alpha = 0f
                v.visibility = View.VISIBLE
            } else {
                v.visibility = View.GONE
            }
        }
    }

    /** Hover left the surface — let Mac echo resume after a short grace. */
    fun endLocalDrive() {
        localDriveUntilMs = SystemClock.uptimeMillis() + LOCAL_HANDOFF_MS
        lastLocalX = Float.NaN
        lastLocalY = Float.NaN
        lastLocalAtMs = 0L
    }

    /** Host size changed (rotation / window resize) — recompute pixel size + translation. */
    fun relayout() {
        laidOutW = -1
        laidOutH = -1
        runOnMain { applyAll() }
    }

    private fun applyAll() {
        val v = view ?: return
        val bmp = bitmap
        if (bmp != null &&
            (v.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== bmp
        ) {
            v.setImageBitmap(bmp)
        }
        applyPosition(v)
    }

    private fun applyPosition(v: ImageView) {
        val host = host ?: return
        val pw = host.width
        val ph = host.height
        if (pw <= 0 || ph <= 0) return
        val bmp = bitmap
        // Local hover can show before the first sprite arrives — use a
        // density-sized placeholder arrow box so motion still feels native.
        val canShow = (bmp != null || chromebook) &&
            ((normW > 0f && normH > 0f) || chromebook)
        if (!canShow) {
            if (chromebook) {
                v.alpha = 0f
                v.visibility = View.VISIBLE
            } else {
                v.visibility = View.GONE
            }
            return
        }
        ensureLaidOut(v)
        val w = laidOutW
        val h = laidOutH
        if (w <= 0 || h <= 0) return
        v.translationX = x * pw - anchorX * w
        v.translationY = y * ph - anchorY * h
        if (chromebook) {
            v.visibility = View.VISIBLE
            val target = if (visible) 1f else 0f
            if (v.alpha != target) v.alpha = target
        } else {
            v.visibility = if (visible) View.VISIBLE else View.GONE
            v.alpha = 1f
        }
    }

    /** Size the ImageView once; subsequent moves only touch translation/alpha. */
    private fun ensureLaidOut(v: ImageView) {
        val host = host ?: return
        val pw = host.width
        val ph = host.height
        if (pw <= 0 || ph <= 0) return
        var w: Int
        var h: Int
        if (normW > 0f && normH > 0f) {
            w = (normW * pw).toInt().coerceAtLeast(1)
            h = (normH * ph).toInt().coerceAtLeast(1)
        } else {
            val d = host.resources.displayMetrics.density
            w = (24f * d).toInt().coerceAtLeast(24)
            h = (24f * d).toInt().coerceAtLeast(24)
        }
        if (chromebook && normW > 0f) {
            val density = host.resources.displayMetrics.density
            val minLong = (32f * density).toInt().coerceAtLeast(48)
            val boost = 1.6f
            w = (w * boost).toInt().coerceAtLeast(1)
            h = (h * boost).toInt().coerceAtLeast(1)
            if (maxOf(w, h) < minLong) {
                val s = minLong.toFloat() / maxOf(w, h).toFloat()
                w = (w * s).toInt().coerceAtLeast(1)
                h = (h * s).toInt().coerceAtLeast(1)
            }
        }
        if (w == laidOutW && h == laidOutH) return
        val lp = (v.layoutParams as FrameLayout.LayoutParams).apply {
            width = w
            height = h
            leftMargin = 0
            topMargin = 0
            gravity = Gravity.TOP or Gravity.START
        }
        v.layoutParams = lp
        laidOutW = w
        laidOutH = h
    }

    private fun runOnMain(block: () -> Unit) {
        val v = view
        if (v == null) {
            if (Looper.myLooper() == Looper.getMainLooper()) block()
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else v.post(block)
    }

    companion object {
        /** Cover a typical USB/WiFi RTT so Mac echo can't tug the sprite back. */
        private const val LOCAL_DRIVE_MS = 280L
        /** After hover exit, brief grace before Mac echo can tug position. */
        private const val LOCAL_HANDOFF_MS = 40L
        /** Fraction of last delta to lead the local sprite (iOS predictedTouches). */
        private const val LOCAL_PREDICT = 0.5f
    }
}
