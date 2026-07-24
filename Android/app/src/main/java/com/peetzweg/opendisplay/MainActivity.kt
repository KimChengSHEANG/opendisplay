package com.peetzweg.opendisplay

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.peetzweg.opendisplay.net.DiscoveryAdvertiser
import com.peetzweg.opendisplay.session.InstallId
import com.peetzweg.opendisplay.session.ReceiverSession
import com.peetzweg.opendisplay.ui.StreamingScreen
import com.peetzweg.opendisplay.video.VideoDecoder

class MainActivity : ComponentActivity() {
    private var connected by mutableStateOf(false)
    private var session: ReceiverSession? = null
    private var decoder: VideoDecoder? = null
    private var advertiser: DiscoveryAdvertiser? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenDisplayApp(
                connected = connected,
                onSurfaceReady = { decoder = it },
                onSurfaceDestroyed = {
                    decoder?.release()
                    decoder = null
                },
                onControl = { session?.sendControl(it) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (session != null) return
        val metrics = resources.displayMetrics
        val installId = InstallId.get(this)
        val s = ReceiverSession(listener = ReceiverListener())
        s.installId = installId
        s.device = ReceiverSession.deviceKind(this)
        s.pixelsWide = metrics.widthPixels
        s.pixelsHigh = metrics.heightPixels
        s.scale = metrics.density.toDouble()
        s.start()
        session = s

        val a = DiscoveryAdvertiser(this, DiscoveryAdvertiser.deviceName(this), installId)
        a.start(port = ReceiverSession.DEFAULT_PORT)
        advertiser = a
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.stop()
        session = null
        advertiser?.stop()
        advertiser = null
        decoder?.release()
        decoder = null
    }

    /** Only `onConnected`/`onDisconnected`/`onVideoFrame` matter until later tasks add control/status handling. */
    private inner class ReceiverListener : ReceiverSession.Listener {
        override fun onConnected() {
            runOnUiThread {
                connected = true
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        override fun onDisconnected() {
            runOnUiThread {
                connected = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        override fun onVideoFrame(data: ByteArray) {
            decoder?.feedAnnexB(data)
        }

        override fun onControl(map: Map<String, Any>) {}

        override fun onStatus(status: String) {}
    }
}

@Composable
fun OpenDisplayApp(
    connected: Boolean,
    onSurfaceReady: (VideoDecoder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onControl: (Map<String, Any>) -> Unit,
) {
    MaterialTheme {
        if (connected) {
            StreamingScreen(
                onSurfaceReady = onSurfaceReady,
                onSurfaceDestroyed = onSurfaceDestroyed,
                onControl = onControl,
            )
        } else {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("OpenDisplay — waiting for connection…")
                }
            }
        }
    }
}
