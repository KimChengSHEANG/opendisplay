package com.peetzweg.opendisplay.ui

import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.peetzweg.opendisplay.input.InputForwarder
import com.peetzweg.opendisplay.video.VideoDecoder

/**
 * Fullscreen black surface the Mac's decoded video is rendered onto.
 * [onSurfaceReady] fires once the underlying [SurfaceView]'s surface exists
 * (a fresh [VideoDecoder] bound to it); [onSurfaceDestroyed] fires right
 * before it goes away so the caller can release that decoder. Touches on the
 * surface are forwarded to the Mac as touch/scroll control messages via
 * [onControl] — see [InputForwarder].
 */
@Composable
fun StreamingScreen(
    onSurfaceReady: (VideoDecoder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onControl: (Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val forwarder = remember(onControl) { InputForwarder(onControl) }
    AndroidView(
        modifier = modifier.fillMaxSize().background(Color.Black),
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceReady(VideoDecoder(holder.surface))
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceDestroyed()
                    }
                })
                setOnTouchListener { view, event -> handleTouch(forwarder, view.width, view.height, event) }
            }
        },
    )
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
