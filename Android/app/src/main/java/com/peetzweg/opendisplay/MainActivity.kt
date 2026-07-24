package com.peetzweg.opendisplay

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.peetzweg.opendisplay.net.DiscoveryAdvertiser
import com.peetzweg.opendisplay.session.InstallId
import com.peetzweg.opendisplay.session.ReceiverSession
import com.peetzweg.opendisplay.settings.AppSettings
import com.peetzweg.opendisplay.sleep.HostSleepController
import com.peetzweg.opendisplay.ui.IdleScreen
import com.peetzweg.opendisplay.ui.PerfOverlay
import com.peetzweg.opendisplay.ui.SettingsScreen
import com.peetzweg.opendisplay.ui.StreamingScreen
import com.peetzweg.opendisplay.ui.UpdateRequiredScreen
import com.peetzweg.opendisplay.version.VersionGate
import com.peetzweg.opendisplay.video.VideoDecoder
import com.peetzweg.opendisplay.wire.WireMessage

class MainActivity : ComponentActivity() {
    private var connected by mutableStateOf(false)
    private var hostDisplayOff by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var deviceName by mutableStateOf("")
    private var showAnalytics by mutableStateOf(false)
    private var fps by mutableStateOf(0)
    private var updateRequired by mutableStateOf<VersionGate.Update?>(null)
    private var recommendedUpdate by mutableStateOf<VersionGate.Update?>(null)
    private val versionGate = VersionGate()
    private var session: ReceiverSession? = null
    private var decoder: VideoDecoder? = null
    private var advertiser: DiscoveryAdvertiser? = null
    private var savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var lockReceiverRegistered = false
    /** True between [onStart] and [onStop]; gates [resumeAccepting] while backgrounded. */
    private var activityStarted = false
    /** Set when unlock arrives before the activity is visible again. */
    private var pendingResumeAccepting = false

    private var frameCount = 0
    private val fpsHandler = Handler(Looper.getMainLooper())
    private val fpsTicker: Runnable = object : Runnable {
        override fun run() {
            fps = frameCount
            frameCount = 0
            fpsHandler.postDelayed(this, 1000)
        }
    }

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
        resumeAccepting = {
            if (activityStarted) {
                session?.start()
                pendingResumeAccepting = false
            } else {
                pendingResumeAccepting = true
            }
        },
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        deviceName = DiscoveryAdvertiser.deviceName(this)
        showAnalytics = AppSettings.showAnalytics(this)
        if (!lockReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) }
            ContextCompat.registerReceiver(this, lockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            lockReceiverRegistered = true
        }
        setContent {
            OpenDisplayApp(
                connected = connected,
                hostDisplayOff = hostDisplayOff,
                showSettings = showSettings,
                deviceName = deviceName,
                showAnalytics = showAnalytics,
                fps = fps,
                updateRequired = updateRequired,
                recommendedUpdate = recommendedUpdate,
                onWake = {
                    hostSleep.wake()
                    hostDisplayOff = false
                    applyImmersive()
                },
                onSurfaceReady = { decoder = it },
                onSurfaceDestroyed = {
                    decoder?.release()
                    decoder = null
                },
                onControl = { session?.sendControl(it) },
                onOpenSettings = { showSettings = true },
                onCloseSettings = { showSettings = false },
                onDeviceNameChange = { name ->
                    deviceName = name
                    DiscoveryAdvertiser.setSavedName(this, name)
                    advertiser?.updateServiceName(DiscoveryAdvertiser.deviceName(this))
                },
                onShowAnalyticsChange = { value ->
                    showAnalytics = value
                    AppSettings.setShowAnalytics(this, value)
                },
                onOpenProjectSite = { openUrl(VersionGate.PROJECT_SITE_URL) },
                onUpdate = { url -> openUrl(url) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        if (session == null) {
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
        } else if (pendingResumeAccepting) {
            session?.start()
            pendingResumeAccepting = false
        }
    }

    override fun onStop() {
        super.onStop()
        activityStarted = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (lockReceiverRegistered) {
            unregisterReceiver(lockReceiver)
            lockReceiverRegistered = false
        }
        hostSleep.onAppQuitting()
        session?.stop()
        session = null
        advertiser?.stop()
        advertiser = null
        decoder?.release()
        decoder = null
        fpsHandler.removeCallbacks(fpsTicker)
    }

    /** Rotation: keep the session alive, just tell the Mac about the new panel — see `ReceiverSession.updatePanel`. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = resources.displayMetrics
        session?.updatePanel(metrics.widthPixels, metrics.heightPixels, metrics.density.toDouble())
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // No browser/Play Store to handle it — nothing more we can do.
        }
    }

    /**
     * Edge-to-edge immersive mode while a session owns the panel (streaming or
     * host-display-off): hide the status/navigation bars so the mirrored
     * display fills the screen, matching the iOS receiver. Bars come back on a
     * swipe (transient) and whenever we return to idle/settings.
     */
    private fun applyImmersive() {
        val immersive = connected || hostDisplayOff
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The system restores the bars on focus loss (e.g. a dialog); reassert
        // immersive when we regain focus and still own the panel.
        if (hasFocus) applyImmersive()
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
                applyImmersive()
                frameCount = 0
                fpsHandler.removeCallbacks(fpsTicker)
                fpsHandler.postDelayed(fpsTicker, 1000)
            }
        }

        override fun onDisconnected() {
            runOnUiThread {
                connected = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                applyImmersive()
                fpsHandler.removeCallbacks(fpsTicker)
                fps = 0
            }
        }

        override fun onVideoFrame(data: ByteArray) {
            frameCount++
            decoder?.feedAnnexB(data)
        }

        override fun onControl(map: Map<String, Any>) {
            when (map["type"]) {
                WireMessage.hostSleeping -> runOnUiThread {
                    hostSleep.onHostSleeping()
                    hostDisplayOff = true
                    applyImmersive()
                }
                // welcome/updateRequired: peer-driven update signals — see `VersionGate`.
                WireMessage.welcome, WireMessage.updateRequired -> {
                    versionGate.onControl(map)
                    runOnUiThread {
                        val status = versionGate.status
                        updateRequired = (status as? VersionGate.Status.Required)?.update
                        recommendedUpdate = (status as? VersionGate.Status.Recommended)?.update
                    }
                }
            }
        }

        override fun onStatus(status: String) {}
    }
}

/**
 * Top-level navigation. Priority mirrors `ReceiverScreen` on iOS: a blocking
 * [VersionGate.Status.Required] gate wins over everything (even mid-stream —
 * the Mac has already refused this pairing); otherwise host-display-off,
 * streaming, settings, and idle are mutually exclusive full-screen states.
 */
@Composable
fun OpenDisplayApp(
    connected: Boolean,
    hostDisplayOff: Boolean,
    showSettings: Boolean,
    deviceName: String,
    showAnalytics: Boolean,
    fps: Int,
    updateRequired: VersionGate.Update?,
    recommendedUpdate: VersionGate.Update?,
    onWake: () -> Unit,
    onSurfaceReady: (VideoDecoder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onControl: (Map<String, Any>) -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onShowAnalyticsChange: (Boolean) -> Unit,
    onOpenProjectSite: () -> Unit,
    onUpdate: (String) -> Unit,
) {
    MaterialTheme {
        if (updateRequired != null) {
            UpdateRequiredScreen(update = updateRequired, onUpdate = { onUpdate(updateRequired.url) })
        } else if (hostDisplayOff) {
            // Mac is asleep — blank the panel too; tap restores brightness
            // without ending the wait for it to wake. Mirrors iOS's
            // `hostDisplayOff` overlay in `OpenSidecarPhoneApp.swift`.
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black)
                    .clickable(onClick = onWake),
            )
        } else if (connected) {
            Box(modifier = Modifier.fillMaxSize()) {
                StreamingScreen(
                    onSurfaceReady = onSurfaceReady,
                    onSurfaceDestroyed = onSurfaceDestroyed,
                    onControl = onControl,
                )
                if (showAnalytics) {
                    PerfOverlay(fps = fps, modifier = Modifier.fillMaxSize())
                }
            }
        } else if (showSettings) {
            SettingsScreen(
                deviceName = deviceName,
                onDeviceNameChange = onDeviceNameChange,
                connectionStatus = if (connected) "Connected" else "Waiting for Mac",
                showAnalytics = showAnalytics,
                onShowAnalyticsChange = onShowAnalyticsChange,
                onOpenProjectSite = onOpenProjectSite,
                onClose = onCloseSettings,
            )
        } else {
            IdleScreen(
                status = if (connected) "connected" else "waiting for Mac",
                recommendedUpdate = recommendedUpdate,
                onUpdateRecommended = { recommendedUpdate?.let { onUpdate(it.url) } },
                onOpenSettings = onOpenSettings,
            )
        }
    }
}
