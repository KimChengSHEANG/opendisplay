package com.peetzweg.opendisplay.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.peetzweg.opendisplay.input.InputForwarder
import com.peetzweg.opendisplay.video.VideoDecoder
import java.util.Base64

/**
 * Local cursor echo from the Mac (`cursor` / `cursorImg` control messages).
 * Position and size are normalized to the video frame [0,1], same as iOS.
 */
data class CursorState(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val visible: Boolean = false,
    val bitmap: Bitmap? = null,
    val anchorX: Float = 0f,
    val anchorY: Float = 0f,
    val normW: Float = 0f,
    val normH: Float = 0f,
)

/**
 * Fullscreen black surface the Mac's decoded video is rendered onto, with a
 * sibling [ImageView] for the local cursor sprite (SurfaceView punches a hole
 * in the window, so Compose overlays above it won't show — the cursor must
 * live in the same FrameLayout as the surface, matching iOS's CALayer).
 */
@Composable
fun StreamingScreen(
    cursor: CursorState,
    onSurfaceReady: (VideoDecoder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onControl: (Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val forwarder = remember(onControl) { InputForwarder(onControl) }
    AndroidView(
        modifier = modifier.fillMaxSize().background(Color.Black),
        factory = { context ->
            val root = FrameLayout(context)
            val surface = SurfaceView(context)
            val cursorView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                visibility = android.view.View.GONE
                // Don't steal touches from the surface under us.
                isClickable = false
                isFocusable = false
            }
            root.addView(
                surface,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            root.addView(
                cursorView,
                FrameLayout.LayoutParams(0, 0).apply { gravity = Gravity.TOP or Gravity.START },
            )
            root.tag = cursorView

            surface.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    onSurfaceReady(VideoDecoder(holder.surface))
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    onSurfaceDestroyed()
                }
            })
            surface.setOnTouchListener { view, event ->
                handleTouch(forwarder, view.width, view.height, event)
            }
            root
        },
        update = { root ->
            val cursorView = root.tag as? ImageView ?: return@AndroidView
            applyCursor(cursorView, root.width, root.height, cursor)
        },
    )
}

/** Decode a Mac `cursorImg` PNG payload (base64). */
fun decodeCursorPng(base64: String): Bitmap? {
    return try {
        val bytes = Base64.getDecoder().decode(base64)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun applyCursor(view: ImageView, parentW: Int, parentH: Int, cursor: CursorState) {
    if (parentW <= 0 || parentH <= 0) return
    val bmp = cursor.bitmap
    val show = cursor.visible && bmp != null && cursor.normW > 0f && cursor.normH > 0f
    if (!show) {
        view.visibility = android.view.View.GONE
        return
    }
    val w = (cursor.normW * parentW).toInt().coerceAtLeast(1)
    val h = (cursor.normH * parentH).toInt().coerceAtLeast(1)
    // Position is the hotspot (anchor) in video space — same as iOS CALayer.
    val left = (cursor.x * parentW - cursor.anchorX * w).toInt()
    val top = (cursor.y * parentH - cursor.anchorY * h).toInt()
    if (view.drawable == null || (view.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== bmp) {
        view.setImageBitmap(bmp)
    }
    val lp = (view.layoutParams as FrameLayout.LayoutParams).apply {
        width = w
        height = h
        leftMargin = left
        topMargin = top
        gravity = Gravity.TOP or Gravity.START
    }
    view.layoutParams = lp
    view.visibility = android.view.View.VISIBLE
}

/**
 * Dispatches a [MotionEvent] to [forwarder]. One finger drives touch
 * began/moved/ended/cancelled; a second finger joining switches to
 * two-finger scroll tracked on the average position of pointers 0 and 1,
 * matching iOS's `UIPanGestureRecognizer(minimum/maximumNumberOfTouches = 2)`.
 */
private fun handleTouch(forwarder: InputForwarder, width: Int, height: Int, event: MotionEvent): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> forwarder.down(event.getX(0), event.getY(0), width, height)
        MotionEvent.ACTION_POINTER_DOWN -> {
            if (event.pointerCount == 2) forwarder.secondPointerDown(focusX(event), focusY(event))
        }
        MotionEvent.ACTION_MOVE -> {
            if (event.pointerCount >= 2) {
                forwarder.twoFingerMove(focusX(event), focusY(event))
            } else {
                forwarder.move(event.getX(0), event.getY(0), width, height)
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

private fun focusX(event: MotionEvent): Float = (event.getX(0) + event.getX(1)) / 2f
private fun focusY(event: MotionEvent): Float = (event.getY(0) + event.getY(1)) / 2f
