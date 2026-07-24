package com.peetzweg.opendisplay.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
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
 * ChromeOS ARC: [SurfaceView] + hardware `c2.vda.avc.decoder` (full panel).
 * TextureView + VDA historically painted solid green on Cheets; SurfaceView
 * is the BufferQueue path VDA expects. Phones/tablets keep [TextureView] + HW.
 * Cursor position is applied by [cursorController] directly (not Compose).
 */
@Composable
fun StreamingScreen(
    cursorController: CursorController,
    onSurfaceReady: (VideoDecoder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
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
                visibility = android.view.View.GONE
                isClickable = false
                isFocusable = false
            }
            val video: View = if (chromebook) {
                SurfaceView(context).also { surfaceView ->
                    // Default z-order (hole-punch): sibling ImageView draws above
                    // the surface. Media-overlay / on-top would hide the cursor.
                    surfaceView.holder.setFormat(PixelFormat.OPAQUE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        surfaceView.pointerIcon = PointerIcon.getSystemIcon(
                            context,
                            PointerIcon.TYPE_NULL,
                        )
                    }
                    var started = false
                    surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {}

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            // Wait for a real size before binding VDA — a 0×0
                            // surface is a common ARC green-screen trigger.
                            if (started || width <= 0 || height <= 0) return
                            started = true
                            onSurfaceReady(
                                VideoDecoder(
                                    holder.surface,
                                    preferSoftware = false,
                                    preferHardwareAvc = true,
                                ),
                            )
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            started = false
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
            cursorController.attach(root, cursorView)
            // Chromebook trackpad/mouse: hover moves the Mac cursor without a
            // finger-down. Touch path still covers phones/tablets.
            video.isFocusable = true
            video.isFocusableInTouchMode = false
            video.setOnTouchListener { view, event ->
                handleTouch(forwarder, view.width, view.height, event)
            }
            video.setOnHoverListener { view, event ->
                handleHover(forwarder, view.width, view.height, event)
            }
            root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                cursorController.relayout()
            }
            root
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

/** Chromebook/mouse hover → Mac `mouseMoved` (touch phase `moved` while up). */
private fun handleHover(forwarder: InputForwarder, width: Int, height: Int, event: MotionEvent): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_HOVER_MOVE,
        MotionEvent.ACTION_HOVER_ENTER -> {
            forwarder.hover(event.x, event.y, width, height)
            return true
        }
        MotionEvent.ACTION_HOVER_EXIT -> return true
    }
    return false
}

private fun focusX(event: MotionEvent): Float = (event.getX(0) + event.getX(1)) / 2f
private fun focusY(event: MotionEvent): Float = (event.getY(0) + event.getY(1)) / 2f
