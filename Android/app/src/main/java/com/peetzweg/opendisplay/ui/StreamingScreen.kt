package com.peetzweg.opendisplay.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.peetzweg.opendisplay.video.VideoDecoder

/**
 * Fullscreen black surface the Mac's decoded video is rendered onto.
 * [onSurfaceReady] fires once the underlying [SurfaceView]'s surface exists
 * (a fresh [VideoDecoder] bound to it); [onSurfaceDestroyed] fires right
 * before it goes away so the caller can release that decoder.
 */
@Composable
fun StreamingScreen(
    onSurfaceReady: (VideoDecoder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            }
        },
    )
}
