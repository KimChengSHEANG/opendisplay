package com.peetzweg.opendisplay.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.PointerIcon
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Local Mac cursor.
 *
 * **Phones / iPad-style:** [ImageView] overlay updated from Mac echo + local
 * touch (same idea as iOS `CALayer`).
 *
 * **Chromebook:** the overlay can never match a real 60Hz monitor — it is
 * composited with the video SurfaceView and always trails the OS pointer
 * plane. Instead we install the Mac sprite as a [PointerIcon] on the video
 * view so ChromeOS draws it on the hardware cursor plane at the real
 * trackpad/mouse position (native poll rate). Position updates are free;
 * only the sprite bitmap is swapped when the Mac sends `cursorImg`.
 */
class CursorController(private val chromebook: Boolean = false) {
    @Volatile private var host: View? = null
    @Volatile private var view: ImageView? = null
    /** View that receives [PointerIcon] (Chromebook video surface). */
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
    /** Uptime deadline: prefer local hover/touch position over Mac echo. */
    @Volatile private var localDriveUntilMs: Long = 0L
    private var lastLocalX: Float = Float.NaN
    private var lastLocalY: Float = Float.NaN
    private var lastLocalAtMs: Long = 0L
    private var pointerIconBitmap: Bitmap? = null

    /** Chromebook uses the OS cursor plane — no software position follow. */
    val usesNativePointer: Boolean get() = chromebook

    fun attach(host: View, cursorView: ImageView, pointerTarget: View? = null) {
        this.host = host
        this.view = cursorView
        this.pointerTarget = pointerTarget
        if (chromebook) {
            // Software overlay stays hidden — OS PointerIcon owns the sprite.
            cursorView.visibility = View.GONE
            cursorView.alpha = 0f
            applyNativePointerIcon()
        }
        runOnMain { applyAll() }
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
        pointerIconBitmap?.takeIf { it !== bitmap }?.recycle()
        pointerIconBitmap = null
    }

    /**
     * Immediate local position from touch (UI thread). No-op on Chromebook
     * hover — the OS pointer is already at the sample. Still used for rare
     * finger-drag on a Chromebook touchscreen.
     */
    fun moveLocal(x: Float, y: Float) {
        if (usesNativePointer) {
            // Native pointer already tracks the sample; only mark local-drive
            // so a late Mac echo can't fight us after a touch gesture.
            localDriveUntilMs = SystemClock.uptimeMillis() + LOCAL_DRIVE_MS
            return
        }
        val now = SystemClock.uptimeMillis()
        var drawX = x
        var drawY = y
        if (!lastLocalX.isNaN() && !lastLocalY.isNaN() && now - lastLocalAtMs < 80) {
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
        if (usesNativePointer) return // OS pointer owns position
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
            if (usesNativePointer) applyNativePointerIcon()
            else applyAll()
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
                // Keep a default arrow so the panel never goes cursor-less.
                applyDefaultNativePointer()
            } else {
                view?.visibility = View.GONE
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
            if (usesNativePointer) applyNativePointerIcon()
            else applyAll()
        }
    }

    private fun applyNativePointerIcon() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            applyDefaultNativePointer()
            return
        }
        val host = host
        val bmp = bitmap
        val target = pointerTarget
        if (host == null || target == null || bmp == null || bmp.isRecycled ||
            host.width <= 0 || host.height <= 0 || normW <= 0f || normH <= 0f
        ) {
            applyDefaultNativePointer()
            return
        }
        var w = (normW * host.width).toInt().coerceAtLeast(1)
        var h = (normH * host.height).toInt().coerceAtLeast(1)
        val density = host.resources.displayMetrics.density
        val minLong = (28f * density).toInt().coerceAtLeast(32)
        // Mild boost — Mac sprites are sized for Retina points; ARC pixels are denser.
        val boost = 1.35f
        w = (w * boost).toInt().coerceAtLeast(1)
        h = (h * boost).toInt().coerceAtLeast(1)
        if (maxOf(w, h) < minLong) {
            val s = minLong.toFloat() / maxOf(w, h).toFloat()
            w = (w * s).toInt().coerceAtLeast(1)
            h = (h * s).toInt().coerceAtLeast(1)
        }
        // Cap so a huge sprite doesn't dominate the panel.
        val maxLong = (96f * density).toInt().coerceAtLeast(128)
        if (maxOf(w, h) > maxLong) {
            val s = maxLong.toFloat() / maxOf(w, h).toFloat()
            w = (w * s).toInt().coerceAtLeast(1)
            h = (h * s).toInt().coerceAtLeast(1)
        }
        val scaled = try {
            Bitmap.createScaledBitmap(bmp, w, h, true)
        } catch (_: Exception) {
            applyDefaultNativePointer()
            return
        }
        pointerIconBitmap?.takeIf { it !== bmp && it !== scaled }?.recycle()
        pointerIconBitmap = scaled
        val hotX = (anchorX * w).coerceIn(0f, (w - 1).toFloat())
        val hotY = (anchorY * h).coerceIn(0f, (h - 1).toFloat())
        val icon = try {
            PointerIcon.create(scaled, hotX, hotY)
        } catch (_: Exception) {
            applyDefaultNativePointer()
            return
        }
        target.pointerIcon = icon
        host.pointerIcon = icon
    }

    private fun applyDefaultNativePointer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val ctx = host?.context ?: pointerTarget?.context ?: return
        val arrow = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_ARROW)
        pointerTarget?.pointerIcon = arrow
        host?.pointerIcon = arrow
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
        if (usesNativePointer) {
            v.visibility = View.GONE
            return
        }
        val host = host ?: return
        val pw = host.width
        val ph = host.height
        if (pw <= 0 || ph <= 0) return
        val bmp = bitmap
        val canShow = bmp != null && normW > 0f && normH > 0f
        if (!canShow || !visible) {
            v.visibility = View.GONE
            return
        }
        ensureLaidOut(v)
        val w = laidOutW
        val h = laidOutH
        if (w <= 0 || h <= 0) return
        v.translationX = x * pw - anchorX * w
        v.translationY = y * ph - anchorY * h
        v.alpha = 1f
        if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
    }

    private fun ensureLaidOut(v: ImageView) {
        val host = host ?: return
        val pw = host.width
        val ph = host.height
        if (pw <= 0 || ph <= 0) return
        val w = (normW * pw).toInt().coerceAtLeast(1)
        val h = (normH * ph).toInt().coerceAtLeast(1)
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
        private const val LOCAL_PREDICT = 0.5f
    }
}
