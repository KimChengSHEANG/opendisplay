package com.peetzweg.opendisplay.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
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
import com.peetzweg.opendisplay.session.ReceiverSession
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
 * Fullscreen video surface the Mac's stream is decoded onto, with a sibling
 * [ImageView] for the local cursor sprite.
 *
 * ChromeOS ARC: [SurfaceView] + software AVC. TextureView + hardware VDA
 * commonly paints a solid green buffer even after frames decode. Phones/tablets
 * keep [TextureView] (composites cleanly with overlays; black until the first
 * frame instead of SurfaceView's uninitialized green).
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
            val chromebook = ReceiverSession.deviceKind(context) == "Chromebook"
            val root = FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            val cursorView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                visibility = android.view.View.GONE
                isClickable = false
                isFocusable = false
            }
            val video: View = if (chromebook) {
                SurfaceView(context).also { surfaceView ->
                    // Default z-order (hole-punch): sibling ImageView draws above
                    // the surface. Media-overlay / on-top would hide the cursor.
                    surfaceView.holder.setFormat(PixelFormat.OPAQUE)
                    surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            onSurfaceReady(
                                VideoDecoder(
                                    holder.surface,
                                    preferSoftware = true,
                                ),
                            )
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            onSurfaceDestroyed()
                        }
                    })
                }
            } else {
                TextureView(context).also { texture ->
                    texture.isOpaque = true
                    var codecSurface: Surface? = null
                    texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            st: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            val w = width.coerceAtLeast(1280)
                            val h = height.coerceAtLeast(720)
                            st.setDefaultBufferSize(w, h)
                            codecSurface?.release()
                            val surface = Surface(st)
                            codecSurface = surface
                            onSurfaceReady(VideoDecoder(surface, preferSoftware = false))
                        }

                        override fun onSurfaceTextureSizeChanged(
                            st: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            if (width > 0 && height > 0) st.setDefaultBufferSize(width, height)
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            onSurfaceDestroyed()
                            codecSurface?.release()
                            codecSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
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
            root.tag = cursorView
            video.setOnTouchListener { view, event ->
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
