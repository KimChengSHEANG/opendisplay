package com.peetzweg.opendisplay.ui

import android.graphics.Bitmap
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Local Mac cursor overlay that bypasses Compose.
 *
 * Position packets arrive at up to ~120Hz. Routing them through
 * `mutableStateOf` → recomposition → `AndroidView.update` was the Chromebook
 * lag source (full Compose passes + `layoutParams` writes every move). iOS
 * updates a `CALayer` directly; we do the same with an [ImageView]:
 * size/sprite via layout params only when they change, position via
 * `translationX`/`translationY` (no layout).
 *
 * Chromebook: Mac normalizes the sprite against VD points, which reads tiny
 * on 240dpi Cheets panels — enforce a density-based floor and boost.
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

    fun attach(host: View, cursorView: ImageView) {
        this.host = host
        this.view = cursorView
        runOnMain { applyAll() }
    }

    fun detach() {
        host = null
        view = null
        laidOutW = -1
        laidOutH = -1
    }

    fun move(x: Float, y: Float, visible: Boolean) {
        this.x = x
        this.y = y
        this.visible = visible
        val v = view ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyPosition(v)
        } else {
            // Front of queue so cursor beats Compose/layout work on ARC.
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
        runOnMain {
            view?.visibility = View.GONE
        }
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
        val show = visible && bmp != null && normW > 0f && normH > 0f
        if (!show) {
            v.visibility = View.GONE
            return
        }
        var w = (normW * pw).toInt().coerceAtLeast(1)
        var h = (normH * ph).toInt().coerceAtLeast(1)
        if (chromebook) {
            // ~32dp min long edge at the panel density; also 1.6× boost so
            // Mac-point sprites match finger/trackpad expectations on Cheets.
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
        if (w != laidOutW || h != laidOutH) {
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
        v.translationX = x * pw - anchorX * w
        v.translationY = y * ph - anchorY * h
        if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
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
}
