package com.peetzweg.opendisplay

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.peetzweg.opendisplay.net.DiscoveryAdvertiser
import com.peetzweg.opendisplay.session.InstallId
import com.peetzweg.opendisplay.session.ReceiverSession
import com.peetzweg.opendisplay.sleep.HostSleepController
import com.peetzweg.opendisplay.ui.StreamingScreen
import com.peetzweg.opendisplay.video.VideoDecoder
import com.peetzweg.opendisplay.wire.WireMessage

class MainActivity : ComponentActivity() {
    private var connected by mutableStateOf(false)
    private var hostDisplayOff by mutableStateOf(false)
    private var session: ReceiverSession? = null
    private var decoder: VideoDecoder? = null
    private var advertiser: DiscoveryAdvertiser? = null
    private var savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var lockReceiverRegistered = false

    /** Wires Android window/lock/session APIs to the pure sleep state machine — see `HostSleepController`. */
    private val hostSleep = HostSleepController(
        sendControl = { session?.sendControl(it) },
        setBrightness = { value -> setWindowBrightness(value) },
        setKeepScreenOn = { keep ->
            if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        },
        stopAccepting = {
            session?.stop()
            connected = false
        },
        resumeAccepting = { session?.start() },
    )

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    if (km?.isDeviceSecure == true) hostSleep.onDeviceWillLock()
                }
                Intent.ACTION_USER_PRESENT -> hostSleep.onDeviceUnlocked()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenDisplayApp(
                connected = connected,
                hostDisplayOff = hostDisplayOff,
                onWake = {
                    hostSleep.wake()
                    hostDisplayOff = false
                },
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
        if (!lockReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) }
            ContextCompat.registerReceiver(this, lockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            lockReceiverRegistered = true
        }
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

    override fun onStop() {
        super.onStop()
        if (lockReceiverRegistered) {
            unregisterReceiver(lockReceiver)
            lockReceiverRegistered = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hostSleep.onAppQuitting()
        session?.stop()
        session = null
        advertiser?.stop()
        advertiser = null
        decoder?.release()
        decoder = null
    }

    /** Rotation: keep the session alive, just tell the Mac about the new panel — see `ReceiverSession.updatePanel`. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = resources.displayMetrics
        session?.updatePanel(metrics.widthPixels, metrics.heightPixels, metrics.density.toDouble())
    }

    private fun setWindowBrightness(value: Float?) {
        val attrs = window.attributes
        if (value == null) {
            attrs.screenBrightness = savedBrightness
        } else {
            savedBrightness = attrs.screenBrightness
            attrs.screenBrightness = value
        }
        window.attributes = attrs
    }

    private inner class ReceiverListener : ReceiverSession.Listener {
        override fun onConnected() {
            runOnUiThread {
                connected = true
                hostSleep.onConnected()
                hostDisplayOff = hostSleep.hostDisplayOff
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

        override fun onControl(map: Map<String, Any>) {
            if (map["type"] == WireMessage.hostSleeping) {
                runOnUiThread {
                    hostSleep.onHostSleeping()
                    hostDisplayOff = true
                }
            }
        }

        override fun onStatus(status: String) {}
    }
}

@Composable
fun OpenDisplayApp(
    connected: Boolean,
    hostDisplayOff: Boolean,
    onWake: () -> Unit,
    onSurfaceReady: (VideoDecoder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onControl: (Map<String, Any>) -> Unit,
) {
    MaterialTheme {
        if (hostDisplayOff) {
            // Mac is asleep — blank the panel too; tap restores brightness
            // without ending the wait for it to wake. Mirrors iOS's
            // `hostDisplayOff` overlay in `OpenSidecarPhoneApp.swift`.
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black)
                    .clickable(onClick = onWake),
            )
        } else if (connected) {
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
