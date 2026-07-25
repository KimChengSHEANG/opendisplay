package com.peetzweg.opendisplay.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.PointerIcon
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Local Mac cursor.
 *
 * **Phones:** [ImageView] overlay from Mac echo / touch.
 *
 * **Chromebook:** prefer the OS cursor plane via [PointerIcon] on a normal
 * (non-SurfaceView) hit layer — that matches a 60Hz monitor's pointer feel.
 * SurfaceView itself cannot host a visible PointerIcon on ARC. Software
 * [ImageView] remains as fallback if the native icon cannot be installed.
 */
class CursorController(private val chromebook: Boolean = false) {
    @Volatile private var host: View? = null
    @Volatile private var view: ImageView? = null
    @Volatile private var pointerTarget: View? = null

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
    private var pointerIconBitmap: Bitmap? = null
    /** True once a PointerIcon is live on [pointerTarget] (native speed path). */
    @Volatile private var nativePointerLive: Boolean = false

    val usesNativePointer: Boolean get() = chromebook && nativePointerLive

    fun attach(host: View, cursorView: ImageView, pointerTarget: View? = null) {
        this.host = host
        this.view = cursorView
        this.pointerTarget = pointerTarget
        if (chromebook) {
            cursorView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            // Try native pointer first — overlay stays ready as fallback.
            cursorView.alpha = 0f
            cursorView.visibility = View.VISIBLE
            runOnMain {
                ensureLaidOut(cursorView)
                if (!installNativePointer(preferCustom = false)) {
                    // Keep overlay path armed with placeholder.
                    applyAll()
                }
            }
        } else {
            runOnMain { applyAll() }
        }
    }

    fun detach() {
        host = null
        view = null
        pointerTarget = null
        laidOutW = -1
        laidOutH = -1
        localDriveUntilMs = 0L
        lastLocalX = Float.NaN
        lastLocalY = Float.NaN
        lastLocalAtMs = 0L
        nativePointerLive = false
        pointerIconBitmap?.takeIf { it !== bitmap }?.recycle()
        pointerIconBitmap = null
    }

    fun moveLocal(x: Float, y: Float) {
        // Native OS cursor already sits on the sample — nothing to paint.
        if (usesNativePointer) {
            localDriveUntilMs = SystemClock.uptimeMillis() + LOCAL_DRIVE_MS
            return
        }
        val now = SystemClock.uptimeMillis()
        var drawX = x
        var drawY = y
        if (!lastLocalX.isNaN() && !lastLocalY.isNaN() && now - lastLocalAtMs < 50) {
            // Lead harder than a half-frame so the sprite matches monitor feel;
            // capped so sparse samples after a pause don't overshoot.
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
        if (usesNativePointer) return
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
        runOnMain {
            if (chromebook && installNativePointer(preferCustom = true)) {
                hideOverlay()
            } else {
                nativePointerLive = false
                applyAll()
            }
        }
    }

    fun hide() {
        visible = false
        localDriveUntilMs = 0L
        lastLocalX = Float.NaN
        lastLocalY = Float.NaN
        lastLocalAtMs = 0L
        runOnMain {
            if (usesNativePointer) {
                installNativePointer(preferCustom = false)
            } else {
                val v = view ?: return@runOnMain
                if (chromebook) {
                    v.alpha = 0f
                    v.visibility = View.VISIBLE
                } else {
                    v.visibility = View.GONE
                }
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
        runOnMain {
            if (chromebook && installNativePointer(preferCustom = bitmap != null)) {
                hideOverlay()
            } else {
                applyAll()
            }
        }
    }

    /**
     * Install a PointerIcon on the non-SurfaceView hit layer.
     * @return true if a visible native icon is now active
     */
    private fun installNativePointer(preferCustom: Boolean): Boolean {
        if (!chromebook || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val target = pointerTarget ?: return false
        val ctx = target.context
        val icon: PointerIcon = if (preferCustom) {
            customPointerIcon() ?: PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_ARROW)
        } else {
            PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_ARROW)
        }
        target.pointerIcon = icon
        host?.pointerIcon = icon
        nativePointerLive = true
        Log.i(TAG, "native PointerIcon active custom=${preferCustom && bitmap != null}")
        return true
    }

    private fun customPointerIcon(): PointerIcon? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val host = host ?: return null
        val bmp = bitmap ?: return null
        if (bmp.isRecycled || host.width <= 0 || host.height <= 0) return null
        if (normW <= 0f || normH <= 0f) return null
        var w = (normW * host.width * CURSOR_BOOST).toInt().coerceAtLeast(1)
        var h = (normH * host.height * CURSOR_BOOST).toInt().coerceAtLeast(1)
        val density = host.resources.displayMetrics.density
        val minLong = (28f * density).toInt().coerceAtLeast(32)
        val maxLong = (88f * density).toInt().coerceAtLeast(96)
        if (maxOf(w, h) < minLong) {
            val s = minLong.toFloat() / maxOf(w, h).toFloat()
            w = (w * s).toInt().coerceAtLeast(1)
            h = (h * s).toInt().coerceAtLeast(1)
        }
        if (maxOf(w, h) > maxLong) {
            val s = maxLong.toFloat() / maxOf(w, h).toFloat()
            w = (w * s).toInt().coerceAtLeast(1)
            h = (h * s).toInt().coerceAtLeast(1)
        }
        val scaled = try {
            Bitmap.createScaledBitmap(bmp, w, h, true)
        } catch (_: Exception) {
            return null
        }
        pointerIconBitmap?.takeIf { it !== bmp && it !== scaled }?.recycle()
        pointerIconBitmap = scaled
        val hotX = (anchorX * w).coerceIn(0f, (w - 1).toFloat())
        val hotY = (anchorY * h).coerceIn(0f, (h - 1).toFloat())
        return try {
            PointerIcon.create(scaled, hotX, hotY)
        } catch (e: Exception) {
            Log.w(TAG, "PointerIcon.create failed: ${e.message}")
            null
        }
    }

    private fun hideOverlay() {
        view?.let {
            it.alpha = 0f
            it.visibility = View.GONE
        }
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
        val d = (24f * v.resources.displayMetrics.density).toInt().coerceAtLeast(24)
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
        if (usesNativePointer) {
            hideOverlay()
            return
        }
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
        private const val TAG = "CursorController"
        private const val LOCAL_DRIVE_MS = 280L
        private const val LOCAL_HANDOFF_MS = 40L
        /** Software-fallback lead; native path needs none. */
        private const val LOCAL_PREDICT = 0.9f
        private const val CURSOR_BOOST = 1.35f
    }
}
