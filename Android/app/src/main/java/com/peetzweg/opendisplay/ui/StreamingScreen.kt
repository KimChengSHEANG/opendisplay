package com.peetzweg.opendisplay.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.PointerIcon
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.peetzweg.opendisplay.input.InputForwarder
import com.peetzweg.opendisplay.session.ReceiverSession
import com.peetzweg.opendisplay.video.VideoDecoder
import java.util.Base64

/**
 * Fullscreen video surface the Mac's stream is decoded onto, with a sibling
 * [ImageView] for the local cursor sprite.
 *
 * Every device gets [SurfaceView]: ChromeOS ARC pairs it with the hardware
 * `c2.vda.avc.decoder` (full panel) — SurfaceView is the BufferQueue path VDA
 * expects. Phones and tablets get SurfaceView too, so SurfaceFlinger can
 * promote it to a hardware overlay; they keep their own hardware AVC decoder
 * via `MediaCodec.createDecoderByType`.
 * Cursor position is applied by [cursorController] directly (not Compose).
 */
@Composable
fun StreamingScreen(
    cursorController: CursorController,
    onSurfaceReady: (VideoDecoder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onGreenScreen: () -> Unit = {},
    onControl: (Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val forwarder = remember(onControl) { InputForwarder(onControl) }
    DisposableEffect(cursorController) {
        onDispose { cursorController.detach() }
    }
    AndroidView(
        modifier = modifier.fillMaxSize().background(Color.Black),
        factory = { context ->
            val chromebook = ReceiverSession.deviceKind(context) == "Chromebook"
            val root = FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                if (chromebook && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Hide the ARC system pointer so only the Mac sprite shows
                    // (otherwise two cursors, and ChromeOS's is tiny).
                    pointerIcon = PointerIcon.getSystemIcon(
                        context,
                        PointerIcon.TYPE_NULL,
                    )
                }
            }
            val cursorView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                // Chromebook: stay VISIBLE at alpha 0 so the first move never
                // pays a GONE→VISIBLE layout hitch (showsCursor is off, so any
                // hitch = invisible/choppy pointer).
                if (chromebook) {
                    visibility = android.view.View.VISIBLE
                    alpha = 0f
                } else {
                    visibility = android.view.View.GONE
                }
                isClickable = false
                isFocusable = false
            }
            // SurfaceView on every device: it is the BufferQueue path the ARC
            // VDA decoder expects AND the one SurfaceFlinger can hand a
            // hardware overlay — Android's nearest equivalent to the dedicated
            // video plane iOS gives AVSampleBufferDisplayLayer. TextureView
            // (the old phone/tablet path) is always GPU-composited through the
            // View tree, costing about a frame, and sized its buffer from the
            // view at first layout — which on connect is the pre-immersive
            // window, quietly downscaling a native-resolution stream.
            val video: View = SurfaceView(context).also { surfaceView ->
                // Default z-order (hole-punch): the sibling ImageView draws
                // above the surface. Media-overlay / on-top would hide the cursor.
                surfaceView.holder.setFormat(PixelFormat.OPAQUE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    surfaceView.pointerIcon = PointerIcon.getSystemIcon(
                        context,
                        PointerIcon.TYPE_NULL,
                    )
                }
                var started = false
                var greenWatch: Runnable? = null
                val handler = Handler(Looper.getMainLooper())
                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {}

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        // Wait for a real size before binding the decoder — a
                        // 0×0 surface is a common ARC green-screen trigger.
                        if (started || width <= 0 || height <= 0) return
                        started = true
                        onSurfaceReady(
                            VideoDecoder(
                                holder.surface,
                                preferSoftware = false,
                                preferHardwareAvc = chromebook,
                            ),
                        )
                        if (chromebook) {
                            greenWatch?.let { handler.removeCallbacks(it) }
                            var checks = 0
                            var greenHits = 0
                            lateinit var watch: Runnable
                            watch = Runnable {
                                if (!started) return@Runnable
                                checks++
                                sampleGreenScreen(surfaceView) { green ->
                                    if (!started) return@sampleGreenScreen
                                    if (green) {
                                        greenHits++
                                        // Two consecutive green samples (~1.5s)
                                        // → force Mac reconnect (quality unchanged).
                                        if (greenHits >= 2) {
                                            Log.w(TAG, "green screen detected — requesting reconnect")
                                            onGreenScreen()
                                            return@sampleGreenScreen
                                        }
                                    } else {
                                        greenHits = 0
                                    }
                                    if (checks < 8) handler.postDelayed(watch, 750)
                                }
                            }
                            greenWatch = watch
                            // First paint can lag the IDR; start sampling after 1s.
                            handler.postDelayed(watch, 1_000)
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        started = false
                        greenWatch?.let { handler.removeCallbacks(it) }
                        greenWatch = null
                        onSurfaceDestroyed()
                    }
                })
            }
            root.addView(
                video,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            root.addView(
                cursorView,
                FrameLayout.LayoutParams(0, 0).apply { gravity = Gravity.TOP or Gravity.START },
            )
            cursorController.attach(root, cursorView)
            // Chromebook trackpad/mouse: hover moves the Mac cursor without a
            // finger-down. Touch path still covers phones/tablets.
            // Local overlay follows hover immediately (iOS-style); Mac echo is
            // fallback only — see CursorController.moveLocal.
            video.isFocusable = true
            video.isFocusableInTouchMode = false
            // Cap Chromebook hover→Mac at ~125Hz (latest sample only). ChromeOS
            // batches many historical points per event; flooding TCP made Mac
            // injection (and desktop hover UI) trail the local sprite.
            val hoverMac = if (chromebook) HoverMacThrottle(forwarder) else null
            video.setOnTouchListener { view, event ->
                if (chromebook && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    view.requestUnbufferedDispatch(event)
                }
                handleTouch(forwarder, cursorController, chromebook, view.width, view.height, event)
            }
            video.setOnHoverListener { view, event ->
                if (chromebook && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    view.requestUnbufferedDispatch(event)
                }
                handleHover(
                    forwarder, cursorController, hoverMac,
                    view.width, view.height, event,
                )
            }
            // Mouse wheel / precision scroll (Chromebook) → Mac scroll.
            video.setOnGenericMotionListener { view, event ->
                handleGenericMotion(forwarder, view.width, view.height, event)
            }
            root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                cursorController.relayout()
            }
            root
        },
    )
}

private const val TAG = "StreamingScreen"

/**
 * Sample a tiny downscale of the SurfaceView. Uninitialized / desynced VDA
 * output is typically solid green (Y=0 UV≈0 → green in RGB).
 */
private fun sampleGreenScreen(surfaceView: SurfaceView, done: (Boolean) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        done(false)
        return
    }
    if (!surfaceView.holder.surface.isValid) {
        done(false)
        return
    }
    val bmp = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(surfaceView, bmp, { result ->
            if (result != PixelCopy.SUCCESS) {
                done(false)
                return@request
            }
            done(isMostlyGreen(bmp))
            bmp.recycle()
        }, Handler(Looper.getMainLooper()))
    } catch (e: Exception) {
        Log.w(TAG, "PixelCopy failed: ${e.message}")
        bmp.recycle()
        done(false)
    }
}

/** True when ≥85% of samples look like solid VDA-green (high G, low R/B). */
internal fun isMostlyGreen(bitmap: Bitmap): Boolean {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return false
    var green = 0
    var total = 0
    // Sparse grid — enough to spot a full-frame green panel.
    val stepX = maxOf(1, w / 12)
    val stepY = maxOf(1, h / 8)
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val c = bitmap.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (isVdaGreenPixel(r, g, b)) green++
            total++
            x += stepX
        }
        y += stepY
    }
    return total > 0 && green * 100 / total >= 85
}

/** Solid green / dark-green from zeroed YUV — not typical desktop content. */
internal fun isVdaGreenPixel(r: Int, g: Int, b: Int): Boolean =
    g >= 60 && g > r + 40 && g > b + 40 && r < 90 && b < 90

/** Decode a Mac `cursorImg` PNG payload (base64). */
fun decodeCursorPng(base64: String): Bitmap? {
    return try {
        val bytes = Base64.getDecoder().decode(base64)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Dispatches a [MotionEvent] to [forwarder]. One finger drives touch
 * began/moved/ended/cancelled; a second finger joining switches to
 * two-finger scroll tracked on the average position of pointers 0 and 1,
 * matching iOS's `UIPanGestureRecognizer(minimum/maximumNumberOfTouches = 2)`.
 */
private fun handleTouch(
    forwarder: InputForwarder,
    cursor: CursorController,
    chromebook: Boolean,
    width: Int,
    height: Int,
    event: MotionEvent,
): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            if (chromebook) moveLocalCursor(cursor, event.getX(0), event.getY(0), width, height)
            forwarder.down(event.getX(0), event.getY(0), width, height)
        }
        MotionEvent.ACTION_POINTER_DOWN -> {
            if (event.pointerCount == 2) forwarder.secondPointerDown(focusX(event), focusY(event))
        }
        MotionEvent.ACTION_MOVE -> {
            if (event.pointerCount >= 2) {
                forwarder.twoFingerMove(focusX(event), focusY(event))
            } else {
                // iOS coalescedTouches: every batched sample, then current.
                val samples = pointerSamples(event, pointerIndex = 0)
                if (chromebook && samples.isNotEmpty()) {
                    val last = samples.last()
                    moveLocalCursor(cursor, last.first, last.second, width, height)
                }
                forwarder.movedSamples(samples, width, height)
            }
        }
        MotionEvent.ACTION_POINTER_UP -> {
            if (event.pointerCount == 2) forwarder.secondPointerUp()
        }
        MotionEvent.ACTION_UP -> forwarder.up(event.getX(0), event.getY(0), width, height)
        MotionEvent.ACTION_CANCEL -> forwarder.cancel(event.getX(0), event.getY(0), width, height)
    }
    return true
}

/** Chromebook/mouse hover → local overlay + Mac `mouseMoved`. */
private fun handleHover(
    forwarder: InputForwarder,
    cursor: CursorController,
    hoverMac: HoverMacThrottle?,
    width: Int,
    height: Int,
    event: MotionEvent,
): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_HOVER_MOVE,
        MotionEvent.ACTION_HOVER_ENTER -> {
            val samples = pointerSamples(event, pointerIndex = 0)
            if (samples.isEmpty()) return true
            val last = samples.last()
            // Every sample paints locally; Mac only needs the tip of the path.
            moveLocalCursor(cursor, last.first, last.second, width, height)
            if (hoverMac != null) {
                hoverMac.onHover(last.first, last.second, width, height)
            } else {
                forwarder.movedSamples(samples, width, height)
            }
            return true
        }
        MotionEvent.ACTION_HOVER_EXIT -> {
            hoverMac?.flush()
            cursor.endLocalDrive()
            return true
        }
    }
    return false
}

/**
 * Latest-wins hover→Mac throttle. Local cursor stays full-rate; the wire is
 * capped so ChromeOS history bursts can't backlog `writeExecutor`.
 */
private class HoverMacThrottle(
    private val forwarder: InputForwarder,
    private val minIntervalMs: Long = 8L,
) {
    private var lastSendMs = 0L
    private var pendingX = Float.NaN
    private var pendingY = Float.NaN
    private var pendingW = 0
    private var pendingH = 0
    private var flushScheduled = false
    private val handler = Handler(Looper.getMainLooper())
    private val flushRunnable = Runnable {
        flushScheduled = false
        flush()
    }

    fun onHover(x: Float, y: Float, width: Int, height: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSendMs >= minIntervalMs) {
            lastSendMs = now
            pendingX = Float.NaN
            if (flushScheduled) {
                handler.removeCallbacks(flushRunnable)
                flushScheduled = false
            }
            forwarder.movedSamples(listOf(x to y), width, height)
            return
        }
        pendingX = x
        pendingY = y
        pendingW = width
        pendingH = height
        if (!flushScheduled) {
            flushScheduled = true
            val delay = (minIntervalMs - (now - lastSendMs)).coerceAtLeast(1L)
            handler.postDelayed(flushRunnable, delay)
        }
    }

    fun flush() {
        if (flushScheduled) {
            handler.removeCallbacks(flushRunnable)
            flushScheduled = false
        }
        if (pendingX.isNaN()) return
        val x = pendingX
        val y = pendingY
        val w = pendingW
        val h = pendingH
        pendingX = Float.NaN
        lastSendMs = SystemClock.uptimeMillis()
        forwarder.movedSamples(listOf(x to y), w, h)
    }
}

/** Mouse wheel → Mac pixel scroll (same wire as two-finger pan). */
private fun handleGenericMotion(
    forwarder: InputForwarder,
    width: Int,
    height: Int,
    event: MotionEvent,
): Boolean {
    if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
    val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
    val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
    if (v == 0f && h == 0f) return false
    // Axis units are typically ±1 per notch; scale to video pixels like a
    // short two-finger flick (~3–5% of the short edge).
    val unit = minOf(width, height).coerceAtLeast(1) * 0.04f
    forwarder.wheel(h * unit, -v * unit)
    return true
}

/**
 * Historical points + current — Android's analogue of UIKit coalesced touches.
 * ChromeOS often batches trackpad samples between vsyncs into one event.
 */
private fun pointerSamples(event: MotionEvent, pointerIndex: Int): List<Pair<Float, Float>> {
    val n = event.historySize
    if (n <= 0) {
        return listOf(event.getX(pointerIndex) to event.getY(pointerIndex))
    }
    val out = ArrayList<Pair<Float, Float>>(n + 1)
    for (i in 0 until n) {
        out.add(event.getHistoricalX(pointerIndex, i) to event.getHistoricalY(pointerIndex, i))
    }
    out.add(event.getX(pointerIndex) to event.getY(pointerIndex))
    return out
}

private fun moveLocalCursor(
    cursor: CursorController,
    x: Float,
    y: Float,
    width: Int,
    height: Int,
) {
    val (nx, ny) = InputForwarder.normalize(x, y, width, height)
    cursor.moveLocal(nx.toFloat(), ny.toFloat())
}

private fun focusX(event: MotionEvent): Float = (event.getX(0) + event.getX(1)) / 2f
private fun focusY(event: MotionEvent): Float = (event.getY(0) + event.getY(1)) / 2f
