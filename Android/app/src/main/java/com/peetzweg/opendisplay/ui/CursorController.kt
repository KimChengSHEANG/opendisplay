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
 * iOS updates a `CALayer` directly. We do the same with an [ImageView].
 *
 * Chromebook: always use the software overlay. ARC does not reliably draw
 * [android.view.PointerIcon] (on SurfaceView or a sibling hit layer), which
 * left the pointer invisible. Hover paints immediately via [moveLocal]; Mac
 * echo is ignored while driving locally. The view stays attached at alpha 0
 * so the first move never pays a GONE→VISIBLE hitch.
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
    @Volatile private var localDriveUntilMs: Long = 0L
    private var lastLocalX: Float = Float.NaN
    private var lastLocalY: Float = Float.NaN
    private var lastLocalAtMs: Long = 0L

    /** ARC PointerIcon is unreliable — always false. */
    val usesNativePointer: Boolean get() = false

    fun attach(host: View, cursorView: ImageView, pointerTarget: View? = null) {
        this.host = host
        this.view = cursorView
        if (chromebook) {
            cursorView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            cursorView.alpha = 0f
            cursorView.visibility = View.VISIBLE
        }
        runOnMain {
            if (chromebook) {
                ensureLaidOut(cursorView)
                if (cursorView.drawable == null) {
                    cursorView.setImageDrawable(placeholderArrow(cursorView))
                }
            }
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

    fun moveLocal(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        var drawX = x
        var drawY = y
        if (!lastLocalX.isNaN() && !lastLocalY.isNaN() && now - lastLocalAtMs < 50) {
            val dt = (now - lastLocalAtMs).toFloat().coerceIn(4f, 24f)
            val lead = LOCAL_PREDICT * (16f / dt)
            drawX = (x + (x - lastLocalX) * lead).coerceIn(0f, 1f)
            drawY = (y + (y - lastLocalY) * lead).coerceIn(0f, 1f)
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

    fun endLocalDrive() {
        localDriveUntilMs = SystemClock.uptimeMillis() + LOCAL_HANDOFF_MS
        lastLocalX = Float.NaN
        lastLocalY = Float.NaN
        lastLocalAtMs = 0L
    }

    fun relayout() {
        laidOutW = -1
        laidOutH = -1
        runOnMain { applyAll() }
    }

    private fun applyAll() {
        val v = view ?: return
        val bmp = bitmap
        if (bmp != null) {
            if ((v.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== bmp) {
                v.setImageBitmap(bmp)
            }
        } else if (chromebook && v.drawable == null) {
            v.setImageDrawable(placeholderArrow(v))
        }
        applyPosition(v)
    }

    private fun placeholderArrow(v: ImageView): android.graphics.drawable.Drawable {
        val d = (28f * v.resources.displayMetrics.density).toInt().coerceAtLeast(28)
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.FILL
        }
        val path = android.graphics.Path().apply {
            moveTo(d * 0.15f, d * 0.10f)
            lineTo(d * 0.15f, d * 0.85f)
            lineTo(d * 0.40f, d * 0.62f)
            lineTo(d * 0.58f, d * 0.90f)
            lineTo(d * 0.70f, d * 0.82f)
            lineTo(d * 0.48f, d * 0.55f)
            lineTo(d * 0.75f, d * 0.55f)
            close()
        }
        val shadow = android.graphics.Paint(p).apply {
            color = android.graphics.Color.BLACK
            alpha = 90
        }
        c.save()
        c.translate(d * 0.06f, d * 0.06f)
        c.drawPath(path, shadow)
        c.restore()
        c.drawPath(path, p)
        p.style = android.graphics.Paint.Style.STROKE
        p.color = android.graphics.Color.BLACK
        p.strokeWidth = d * 0.06f
        c.drawPath(path, p)
        return android.graphics.drawable.BitmapDrawable(v.resources, bmp)
    }

    private fun applyPosition(v: ImageView) {
        val host = host ?: return
        val pw = host.width
        val ph = host.height
        if (pw <= 0 || ph <= 0) return
        val canShow = (bitmap != null || chromebook) &&
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
        // Placeholder has top-left hotspot; Mac sprites use anchorX/Y.
        val ax = if (bitmap != null) anchorX else 0.15f
        val ay = if (bitmap != null) anchorY else 0.10f
        v.translationX = x * pw - ax * w
        v.translationY = y * ph - ay * h
        if (chromebook) {
            v.visibility = View.VISIBLE
            val target = if (visible) 1f else 0f
            if (v.alpha != target) v.alpha = target
        } else {
            v.visibility = if (visible) View.VISIBLE else View.GONE
            v.alpha = 1f
        }
    }

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
            w = (28f * d).toInt().coerceAtLeast(28)
            h = (28f * d).toInt().coerceAtLeast(28)
        }
        if (chromebook && normW > 0f) {
            val density = host.resources.displayMetrics.density
            val minLong = (32f * density).toInt().coerceAtLeast(48)
            w = (w * 1.6f).toInt().coerceAtLeast(1)
            h = (h * 1.6f).toInt().coerceAtLeast(1)
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
        private const val LOCAL_DRIVE_MS = 280L
        private const val LOCAL_HANDOFF_MS = 40L
        private const val LOCAL_PREDICT = 0.9f
    }
}
