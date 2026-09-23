package com.hcr.stormroot.core.overlay

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hcr.stormroot.R
import com.hcr.stormroot.core.bedtime.BedtimeDriftEngine
import com.hcr.stormroot.core.bedtime.BedtimeDriftPrefs
import com.hcr.stormroot.core.bedtime.DriftStage
import com.hcr.stormroot.core.doomscroll.DoomscrollEngine
import com.hcr.stormroot.core.doomscroll.DoomscrollPrefs
import com.hcr.stormroot.core.doomscroll.DoomscrollStage
import com.hcr.stormroot.core.doomscroll.UsageStatsHelper
import com.hcr.stormroot.core.permissions.ActivityRecognitionPermission
import com.hcr.stormroot.core.permissions.OverlayPermission
import com.hcr.stormroot.core.permissions.UsageAccessPermission
import com.hcr.stormroot.core.sitting.SittingRootsEngine
import com.hcr.stormroot.core.sitting.SittingRootsPrefs
import com.hcr.stormroot.core.stats.StatsStore
import com.hcr.stormroot.ui.dialogs.OverlayEffectOptions
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlin.math.sqrt

class OverlayService : Service() {

    companion object {
        const val ACTION_START_MONITOR = "com.hcr.stormroot.overlay.action.START_MONITOR"
        const val ACTION_STOP_MONITOR = "com.hcr.stormroot.overlay.action.STOP_MONITOR"
        const val ACTION_START_DOOMSCROLL_MONITOR = "com.hcr.stormroot.overlay.action.START_DOOMSCROLL_MONITOR"
        const val ACTION_STOP_DOOMSCROLL_MONITOR = "com.hcr.stormroot.overlay.action.STOP_DOOMSCROLL_MONITOR"
        const val ACTION_START_ROOTS_MONITOR = "com.hcr.stormroot.overlay.action.START_ROOTS_MONITOR"
        const val ACTION_STOP_ROOTS_MONITOR = "com.hcr.stormroot.overlay.action.STOP_ROOTS_MONITOR"
        const val ACTION_START_FULLSCREEN_PREVIEW = "com.hcr.stormroot.overlay.action.START_FULLSCREEN_PREVIEW"
        const val ACTION_STOP_FULLSCREEN_PREVIEW = "com.hcr.stormroot.overlay.action.STOP_FULLSCREEN_PREVIEW"

        const val EFFECT_SOFT_MIST = "SOFT_MIST"
        const val EFFECT_CALM_VINES = "CALM_VINES"
        const val EFFECT_WARM_GLOW = "WARM_GLOW"
        const val EFFECT_BUTTERFLIES = "BUTTERFLIES"
        const val EFFECT_FALLING_LEAVES = "FALLING_LEAVES"
        const val EFFECT_SNOWFALL = "SNOWFALL"
        const val EFFECT_SUN_RAYS = "SUN_RAYS"
        const val EFFECT_WATER_DROPLETS = "WATER_DROPLETS"
        const val EFFECT_DEW_WEB = "DEW_WEB"
        const val EFFECT_FIRE = "FIRE"
        const val EFFECT_STARRY_NIGHT = "STARRY_NIGHT"

        private const val EXTRA_EFFECT = "com.hcr.stormroot.overlay.extra.EFFECT"
        private const val EXTRA_STRENGTH_MULTIPLIER = "com.hcr.stormroot.overlay.extra.STRENGTH_MULTIPLIER"

        private const val NOTIFICATION_CHANNEL_ID = "overlay_service"
        private const val NOTIFICATION_ID = 1001
        private const val USAGE_ACCESS_WARNING_CHANNEL_ID = "usage_access_revoked"
        private const val USAGE_ACCESS_WARNING_NOTIFICATION_ID = 1002
        private const val SNOOZE_MINUTES = 15
        private const val LAYER_PREVIEW = "preview"
        private const val LAYER_BEDTIME = "bedtime"
        private const val LAYER_DOOMSCROLL = "doomscroll"
        private const val LAYER_ROOTS = "roots"
        private const val BEDTIME_TICK_MS = 60_000L
        private const val DOOMSCROLL_TICK_MS = 4_000L
        private const val ROOTS_TICK_MS = 60_000L
        private const val MOVEMENT_ACCEL_THRESHOLD = 2.2f
        // A single spike (a firm tap on the screen, a car bump) shouldn't reset the sedentary
        // timer — require several consecutive over-threshold accelerometer samples within a
        // short window before treating it as the user actually getting up and moving.
        private const val MOVEMENT_CONSECUTIVE_SAMPLES_REQUIRED = 3
        private const val MOVEMENT_SAMPLE_WINDOW_MS = 2_000L
        fun startFullscreenPreview(context: Context, effect: String, strengthMultiplier: Float) {
            if (!OverlayPermission.isGranted(context)) return
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_START_FULLSCREEN_PREVIEW)
                .putExtra(EXTRA_EFFECT, effect)
                .putExtra(EXTRA_STRENGTH_MULTIPLIER, strengthMultiplier)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopFullscreenPreview(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP_FULLSCREEN_PREVIEW))
        }

        fun startMonitor(context: Context) {
            if (!OverlayPermission.isGranted(context)) return
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java).setAction(ACTION_START_MONITOR))
        }

        fun stopMonitor(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP_MONITOR))
        }

        fun startDoomscrollMonitor(context: Context) {
            if (!OverlayPermission.isGranted(context) || !UsageAccessPermission.isGranted(context)) return
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java).setAction(ACTION_START_DOOMSCROLL_MONITOR))
        }

        fun stopDoomscrollMonitor(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP_DOOMSCROLL_MONITOR))
        }

        fun startRootsMonitor(context: Context) {
            if (!OverlayPermission.isGranted(context) || !ActivityRecognitionPermission.isGranted(context)) return
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java).setAction(ACTION_START_ROOTS_MONITOR))
        }

        fun stopRootsMonitor(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP_ROOTS_MONITOR))
        }
    }

    private lateinit var windowController: OverlayWindowController
    private val handler = Handler(Looper.getMainLooper())

    private var isBedtimeMonitorActive = false
    private var isDoomscrollMonitorActive = false
    private var isRootsMonitorActive = false
    private var isFullscreenPreviewActive = false
    private var previewFogView: FogOverlayView? = null
    private var previewRootsView: RootsOverlayView? = null
    private var previewStormView: StormOverlayView? = null
    private var previewButterflyView: ButterflyOverlayView? = null
    private var previewLeavesView: LeavesOverlayView? = null
    private var previewSnowView: SnowfallOverlayView? = null
    private var previewSunRaysView: SunRaysOverlayView? = null
    private var previewDropletsView: WaterDropletsOverlayView? = null
    private var previewDewWebView: DewSpiderWebOverlayView? = null
    private var previewFireView: FireOverlayView? = null
    private var previewNightSkyView: NightSkyOverlayView? = null

    private var doomscrollEffectView: View? = null
    private var doomscrollEffect: String? = null
    // UsageStatsManager queries (queryEvents over a 6h window, queryUsageStats) are genuinely
    // slow system calls. Running them on the main-looper handler every 4s (DOOMSCROLL_TICK_MS)
    // was blocking the main thread periodically, including UI taps in the host Activity since
    // this service has no android:process isolation and shares the same main thread.
    private val usageStatsExecutor = Executors.newSingleThreadExecutor()
    private var isQueryingDoomscrollUsage = false
    private var usageAccessRevokedNotified = false

    // Tracked so screen-off pausing can stop its Choreographer loop like the other layers;
    // bedtimeTick recreates this view fresh every tick regardless.
    private var bedtimeEffectView: View? = null
    private var screenStateReceiver: BroadcastReceiver? = null

    private var rootsEffectView: View? = null
    private var rootsEffect: String? = null
    private var rootsCurrentValue: Float = 0f
    private var rootsGrowthAnimator: ValueAnimator? = null
    private var previewRootsGrowthAnimator: ValueAnimator? = null
    private var lastMovementMillis: Long = 0L
    private var sensorManager: SensorManager? = null
    private var movementSensorListener: SensorEventListener? = null
    private var consecutiveAccelSpikes = 0
    private var firstAccelSpikeMillis: Long = 0L

    private var bedtimeNudgeStartMillis: Long? = null
    private var doomscrollNudgeStartMillis: Long? = null
    private var rootsNudgeStartMillis: Long? = null

    private val bedtimeTick = object : Runnable {
        override fun run() {
            updateBedtimeLayer()
            handler.postDelayed(this, BEDTIME_TICK_MS)
        }
    }

    private val doomscrollTick = object : Runnable {
        override fun run() {
            updateDoomscrollLayer()
            handler.postDelayed(this, DOOMSCROLL_TICK_MS)
        }
    }

    private val rootsTick = object : Runnable {
        override fun run() {
            updateRootsLayer()
            handler.postDelayed(this, ROOTS_TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowController = OverlayWindowController(this)
        registerScreenStateReceiver()
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> pauseForScreenOff()
                    Intent.ACTION_SCREEN_ON -> resumeForScreenOn()
                }
            }
        }
        screenStateReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterScreenStateReceiver() {
        screenStateReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenStateReceiver = null
    }

    // Nothing the user can see or act on while the screen is off: pause the periodic
    // re-evaluation ticks and halt any running Choreographer-driven animations without tearing
    // down layers or nudge-session bookkeeping, so everything resumes exactly where it left off.
    private fun pauseForScreenOff() {
        handler.removeCallbacks(bedtimeTick)
        handler.removeCallbacks(doomscrollTick)
        handler.removeCallbacks(rootsTick)

        doomscrollEffectView?.let { stopEffectView(it) }
        rootsEffectView?.let { stopEffectView(it) }
        bedtimeEffectView?.let { stopEffectView(it) }
        previewFogView?.stop()
        previewRootsView?.stop()
        previewStormView?.stop()
        previewButterflyView?.stopAnimation()
        previewLeavesView?.stopAnimation()
        previewSnowView?.stopAnimation()
        previewSunRaysView?.stopAnimation()
        previewDropletsView?.stopAnimation()
        previewDewWebView?.stopAnimation()
        previewFireView?.stopAnimation()
        previewNightSkyView?.stopAnimation()
    }

    private fun resumeForScreenOn() {
        doomscrollEffectView?.let { startEffectView(it) }
        rootsEffectView?.let { startEffectView(it) }
        bedtimeEffectView?.let { startEffectView(it) }
        previewFogView?.start()
        previewRootsView?.start()
        previewStormView?.start()
        previewButterflyView?.startAnimation()
        previewLeavesView?.startAnimation()
        previewSnowView?.startAnimation()
        previewSunRaysView?.startAnimation()
        previewDropletsView?.startAnimation()
        previewDewWebView?.startAnimation()
        previewFireView?.startAnimation()
        previewNightSkyView?.startAnimation()

        if (isBedtimeMonitorActive) {
            handler.removeCallbacks(bedtimeTick)
            handler.post(bedtimeTick)
        }
        if (isDoomscrollMonitorActive) {
            handler.removeCallbacks(doomscrollTick)
            handler.post(doomscrollTick)
        }
        if (isRootsMonitorActive) {
            handler.removeCallbacks(rootsTick)
            handler.post(rootsTick)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return resumeMonitorsAfterProcessRestart()
        }
        when (intent.action) {
            ACTION_STOP_MONITOR -> {
                isBedtimeMonitorActive = false
                handler.removeCallbacks(bedtimeTick)
                clearBedtimeLayer()
                stopServiceIfIdle()
            }
            ACTION_START_MONITOR -> {
                isBedtimeMonitorActive = true
                startForeground(NOTIFICATION_ID, buildNotification())
                handler.removeCallbacks(bedtimeTick)
                handler.post(bedtimeTick)
            }
            ACTION_STOP_DOOMSCROLL_MONITOR -> {
                isDoomscrollMonitorActive = false
                handler.removeCallbacks(doomscrollTick)
                clearDoomscrollLayer()
                stopServiceIfIdle()
            }
            ACTION_START_DOOMSCROLL_MONITOR -> {
                isDoomscrollMonitorActive = true
                startForeground(NOTIFICATION_ID, buildNotification())
                handler.removeCallbacks(doomscrollTick)
                handler.post(doomscrollTick)
            }
            ACTION_STOP_ROOTS_MONITOR -> {
                isRootsMonitorActive = false
                handler.removeCallbacks(rootsTick)
                unregisterMovementSensor()
                clearRootsLayer()
                stopServiceIfIdle()
            }
            ACTION_START_ROOTS_MONITOR -> {
                isRootsMonitorActive = true
                startForeground(NOTIFICATION_ID, buildNotification())
                lastMovementMillis = System.currentTimeMillis()
                registerMovementSensor()
                handler.removeCallbacks(rootsTick)
                handler.post(rootsTick)
            }
            ACTION_STOP_FULLSCREEN_PREVIEW -> {
                isFullscreenPreviewActive = false
                windowController.setLayer(LAYER_PREVIEW, null)
                windowController.updateBlurBehind(0)
                previewFogView?.stop()
                previewFogView = null
                previewRootsView?.stop()
                previewRootsView = null
                previewRootsGrowthAnimator?.cancel()
                previewRootsGrowthAnimator = null
                previewStormView?.stop()
                previewStormView = null
                previewButterflyView?.stopAnimation()
                previewButterflyView = null
                previewLeavesView?.stopAnimation()
                previewLeavesView = null
                previewSnowView?.stopAnimation()
                previewSnowView = null
                previewSunRaysView?.stopAnimation()
                previewSunRaysView = null
                previewDropletsView?.stopAnimation()
                previewDropletsView = null
                previewDewWebView?.stopAnimation()
                previewDewWebView = null
                previewFireView?.stopAnimation()
                previewFireView = null
                previewNightSkyView?.stopAnimation()
                previewNightSkyView = null
                stopServiceIfIdle()
            }
            ACTION_START_FULLSCREEN_PREVIEW -> {
                if (!OverlayPermission.isGranted(this)) {
                    stopServiceIfIdle()
                    return START_NOT_STICKY
                }
                isFullscreenPreviewActive = true
                startForeground(NOTIFICATION_ID, buildNotification())
                val effect = intent.getStringExtra(EXTRA_EFFECT) ?: EFFECT_SOFT_MIST
                val multiplier = intent.getFloatExtra(EXTRA_STRENGTH_MULTIPLIER, 0.7f)
                startPreviewLayer(effect, multiplier)
            }
        }
        return if (isBedtimeMonitorActive || isDoomscrollMonitorActive || isRootsMonitorActive || isFullscreenPreviewActive) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    private fun startPreviewLayer(effect: String, multiplier: Float) {
        previewFogView?.stop()
        previewFogView = null
        previewRootsView?.stop()
        previewRootsView = null
        previewStormView?.stop()
        previewStormView = null
        previewButterflyView?.stopAnimation()
        previewButterflyView = null
        previewLeavesView?.stopAnimation()
        previewLeavesView = null
        previewSnowView?.stopAnimation()
        previewSnowView = null
        previewSunRaysView?.stopAnimation()
        previewSunRaysView = null
        previewDropletsView?.stopAnimation()
        previewDropletsView = null
        previewDewWebView?.stopAnimation()
        previewDewWebView = null
        previewFireView?.stopAnimation()
        previewFireView = null
        previewNightSkyView?.stopAnimation()
        previewNightSkyView = null
        windowController.updateBlurBehind(0)

        val themedContext = ContextThemeWrapper(this, R.style.Theme_StormRoot)

        when (effect) {
            EFFECT_SOFT_MIST -> {
                val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_ambient, null)
                windowController.setLayer(LAYER_PREVIEW, view)
                val fog = view.findViewById<FogOverlayView>(R.id.fogView)
                fog.start()
                fog.intensity = multiplier
                previewFogView = fog
            }
            EFFECT_BUTTERFLIES -> {
                val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_ambient, null)
                windowController.setLayer(LAYER_PREVIEW, view)
                val fog = view.findViewById<FogOverlayView>(R.id.fogView)
                fog.visibility = View.GONE
                
                val butterflies = view.findViewById<ButterflyOverlayView>(R.id.butterflyView)
                butterflies.visibility = View.VISIBLE
                butterflies.intensity = multiplier
                butterflies.startAnimation()
                previewButterflyView = butterflies
            }
            EFFECT_FALLING_LEAVES -> {
                val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_ambient, null)
                windowController.setLayer(LAYER_PREVIEW, view)
                val fog = view.findViewById<FogOverlayView>(R.id.fogView)
                fog.visibility = View.GONE
                
                val leaves = view.findViewById<LeavesOverlayView>(R.id.leavesView)
                leaves.visibility = View.VISIBLE
                leaves.intensity = multiplier
                leaves.startAnimation()
                previewLeavesView = leaves
            }
            EFFECT_SNOWFALL -> {
                val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_ambient, null)
                windowController.setLayer(LAYER_PREVIEW, view)
                val fog = view.findViewById<FogOverlayView>(R.id.fogView)
                fog.visibility = View.GONE

                val snow = view.findViewById<SnowfallOverlayView>(R.id.snowView)
                snow.visibility = View.VISIBLE
                snow.intensity = multiplier
                snow.startAnimation()
                previewSnowView = snow
            }
            EFFECT_SUN_RAYS -> {
                val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_ambient, null)
                windowController.setLayer(LAYER_PREVIEW, view)
                val fog = view.findViewById<FogOverlayView>(R.id.fogView)
                fog.visibility = View.GONE

                val sunRays = view.findViewById<SunRaysOverlayView>(R.id.sunRaysView)
                sunRays.visibility = View.VISIBLE
                sunRays.intensity = multiplier
                sunRays.startAnimation()
                previewSunRaysView = sunRays
            }
            EFFECT_WATER_DROPLETS -> {
                val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_ambient, null)
                windowController.setLayer(LAYER_PREVIEW, view)
                val fog = view.findViewById<FogOverlayView>(R.id.fogView)
                fog.visibility = View.GONE

                val droplets = view.findViewById<WaterDropletsOverlayView>(R.id.dropletsView)
                droplets.visibility = View.VISIBLE
                droplets.intensity = multiplier
                droplets.startAnimation()
                previewDropletsView = droplets
            }
            EFFECT_DEW_WEB -> {
                val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_ambient, null)
                windowController.setLayer(LAYER_PREVIEW, view)
                val fog = view.findViewById<FogOverlayView>(R.id.fogView)
                fog.visibility = View.GONE

                val dewWeb = view.findViewById<DewSpiderWebOverlayView>(R.id.dewWebView)
                dewWeb.visibility = View.VISIBLE
                dewWeb.intensity = multiplier
                dewWeb.startAnimation()
                previewDewWebView = dewWeb
            }
            EFFECT_FIRE -> {
                val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_ambient, null)
                windowController.setLayer(LAYER_PREVIEW, view)
                val fog = view.findViewById<FogOverlayView>(R.id.fogView)
                fog.visibility = View.GONE

                val fire = view.findViewById<FireOverlayView>(R.id.fireView)
                fire.visibility = View.VISIBLE
                fire.intensity = multiplier
                fire.startAnimation()
                previewFireView = fire
            }
            EFFECT_STARRY_NIGHT -> {
                val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_ambient, null)
                windowController.setLayer(LAYER_PREVIEW, view)
                val fog = view.findViewById<FogOverlayView>(R.id.fogView)
                fog.visibility = View.GONE

                val nightSky = view.findViewById<NightSkyOverlayView>(R.id.nightSkyView)
                nightSky.visibility = View.VISIBLE
                nightSky.intensity = multiplier
                nightSky.startAnimation()
                previewNightSkyView = nightSky
            }
            EFFECT_CALM_VINES -> {
                val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_roots, null)
                windowController.setLayer(LAYER_PREVIEW, view)
                val roots = view.findViewById<RootsOverlayView>(R.id.rootsView)
                roots.start()
                
                previewRootsGrowthAnimator?.cancel()
                val currentGrowth = roots.growth
                val targetGrowth = 0.35f + 0.65f * multiplier
                previewRootsGrowthAnimator = ValueAnimator.ofFloat(currentGrowth, targetGrowth).apply {
                    duration = if (currentGrowth == 0f) 3500 else 1500
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { roots.growth = it.animatedValue as Float }
                    start()
                }
                previewRootsView = roots
            }
            EFFECT_WARM_GLOW -> {
                val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_bedtime, null)
                windowController.setLayer(LAYER_PREVIEW, view)
                val warmScrim = view.findViewById<View>(R.id.warmScrim)
                val decorativeContainer = view.findViewById<android.widget.FrameLayout>(R.id.decorativeContainer)
                val storm = StormOverlayView(themedContext)
                decorativeContainer.addView(storm)

                warmScrim.alpha = 0.1f + 0.2f * multiplier
                storm.start()
                storm.intensity = multiplier
                previewStormView = storm

                val density = resources.displayMetrics.density
                windowController.updateBlurBehind((8 * multiplier * density).toInt())
            }
        }
    }

    private fun resumeMonitorsAfterProcessRestart(): Int {
        if (BedtimeDriftPrefs.isEnabled(this) && OverlayPermission.isGranted(this)) {
            isBedtimeMonitorActive = true
        }
        if (DoomscrollPrefs.isEnabled(this) && OverlayPermission.isGranted(this) && UsageAccessPermission.isGranted(this)) {
            isDoomscrollMonitorActive = true
        }
        if (SittingRootsPrefs.isEnabled(this) && OverlayPermission.isGranted(this) && ActivityRecognitionPermission.isGranted(this)) {
            isRootsMonitorActive = true
        }

        if (!isBedtimeMonitorActive && !isDoomscrollMonitorActive && !isRootsMonitorActive) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        if (isBedtimeMonitorActive) {
            handler.removeCallbacks(bedtimeTick)
            handler.post(bedtimeTick)
        }
        if (isDoomscrollMonitorActive) {
            handler.removeCallbacks(doomscrollTick)
            handler.post(doomscrollTick)
        }
        if (isRootsMonitorActive) {
            lastMovementMillis = System.currentTimeMillis()
            registerMovementSensor()
            handler.removeCallbacks(rootsTick)
            handler.post(rootsTick)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterScreenStateReceiver()
        usageStatsExecutor.shutdown()
        handler.removeCallbacks(bedtimeTick)
        handler.removeCallbacks(doomscrollTick)
        handler.removeCallbacks(rootsTick)
        previewFogView?.stop()
        previewFogView = null
        previewRootsView?.stop()
        previewRootsView = null
        previewRootsGrowthAnimator?.cancel()
        previewRootsGrowthAnimator = null
        previewStormView?.stop()
        previewStormView = null
        previewButterflyView?.stopAnimation()
        previewButterflyView = null
        previewLeavesView?.stopAnimation()
        previewLeavesView = null
        previewSnowView?.stopAnimation()
        previewSnowView = null
        previewSunRaysView?.stopAnimation()
        previewSunRaysView = null
        previewDropletsView?.stopAnimation()
        previewDropletsView = null
        previewDewWebView?.stopAnimation()
        previewDewWebView = null
        previewFireView?.stopAnimation()
        previewFireView = null
        previewNightSkyView?.stopAnimation()
        previewNightSkyView = null
        doomscrollEffectView?.let { stopEffectView(it) }
        bedtimeEffectView = null
        rootsGrowthAnimator?.cancel()
        unregisterMovementSensor()
        flushNudgeSessions()
        windowController.hide()
        super.onDestroy()
    }

    private fun flushNudgeSessions() {
        val now = System.currentTimeMillis()
        bedtimeNudgeStartMillis?.let { StatsStore.recordNudgeSession(this, StatsStore.MODULE_BEDTIME, it, now) }
        bedtimeNudgeStartMillis = null
        doomscrollNudgeStartMillis?.let { StatsStore.recordNudgeSession(this, StatsStore.MODULE_DOOMSCROLL, it, now) }
        doomscrollNudgeStartMillis = null
        rootsNudgeStartMillis?.let { StatsStore.recordNudgeSession(this, StatsStore.MODULE_ROOTS, it, now) }
        rootsNudgeStartMillis = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopServiceIfIdle() {
        if (!isBedtimeMonitorActive && !isDoomscrollMonitorActive && !isRootsMonitorActive && !isFullscreenPreviewActive) {
            windowController.updateBlurBehind(0)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateBedtimeLayer() {
        if (!OverlayPermission.isGranted(this)) return

        val result = BedtimeDriftEngine.calculate(
            nowMillis = System.currentTimeMillis(),
            bedtimeHour = BedtimeDriftPrefs.getBedtimeHour(this),
            bedtimeMinute = BedtimeDriftPrefs.getBedtimeMinute(this),
            driftWindowMinutes = BedtimeDriftPrefs.getDriftWindowMinutes(this),
            snoozedUntilMillis = BedtimeDriftPrefs.getSnoozedUntil(this)
        )

        if (result.stage == DriftStage.NONE) {
            clearBedtimeLayer()
            return
        }
        val effect = OverlayEffectOptions.effectiveEffect(
            this,
            BedtimeDriftPrefs.getOverlayEffect(this),
            BedtimeDriftPrefs.DEFAULT_OVERLAY_EFFECT
        )
        if (bedtimeNudgeStartMillis == null) {
            bedtimeNudgeStartMillis = System.currentTimeMillis()
            StatsStore.recordEffectShown(this, StatsStore.MODULE_BEDTIME, effect)
        }

        val themedContext = ContextThemeWrapper(this, R.style.Theme_StormRoot)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_bedtime, null)
        val warmScrim = view.findViewById<View>(R.id.warmScrim)
        val vignetteScrim = view.findViewById<View>(R.id.vignetteScrim)
        val decorativeContainer = view.findViewById<android.widget.FrameLayout>(R.id.decorativeContainer)

        val decorativeView = createEffectView(themedContext, effect)
        decorativeContainer.addView(decorativeView)
        startEffectView(decorativeView)
        bedtimeEffectView = decorativeView

        val density = resources.displayMetrics.density
        val intensity = when (result.stage) {
            DriftStage.STAGE_1 -> {
                val progress = result.overallProgress.coerceIn(0f, 1f)
                warmScrim.alpha = 0.15f * progress
                vignetteScrim.alpha = 0f
                windowController.updateBlurBehind((4 * density).toInt())
                0.15f * progress
            }
            DriftStage.STAGE_2 -> {
                warmScrim.alpha = 0.15f + 0.10f * result.overallProgress
                vignetteScrim.alpha = 0.2f * result.overallProgress
                windowController.updateBlurBehind((10 * density).toInt())
                0.15f + 0.35f * result.overallProgress
            }
            DriftStage.STAGE_3 -> {
                warmScrim.alpha = 0.25f + 0.10f * result.overallProgress
                vignetteScrim.alpha = 0.2f + 0.20f * result.overallProgress
                windowController.updateBlurBehind((18 * density).toInt())
                0.5f + 0.5f * result.overallProgress
            }
            DriftStage.NONE -> 0f
        }
        setEffectValue(decorativeView, intensity)
        StatsStore.recordNudgeIntensityTick(this, StatsStore.MODULE_BEDTIME, intensity, BEDTIME_TICK_MS)

        windowController.setLayer(LAYER_BEDTIME, view)
        updateSnoozeChip()
    }

    private fun clearBedtimeLayer() {
        bedtimeNudgeStartMillis?.let { start ->
            StatsStore.recordNudgeSession(this, StatsStore.MODULE_BEDTIME, start, System.currentTimeMillis())
        }
        bedtimeNudgeStartMillis = null
        windowController.setLayer(LAYER_BEDTIME, null)
        windowController.updateBlurBehind(0)
        bedtimeEffectView = null
        updateSnoozeChip()
    }

    private fun updateDoomscrollLayer() {
        if (!DoomscrollPrefs.isEnabled(this)) {
            usageAccessRevokedNotified = false
            clearDoomscrollLayer()
            return
        }
        if (!OverlayPermission.isGranted(this)) {
            clearDoomscrollLayer()
            return
        }
        if (!UsageAccessPermission.isGranted(this)) {
            // The user turned this module on but Usage Access was revoked separately (e.g. from
            // system settings) — blocking is now silently inert unless we say something.
            notifyUsageAccessRevokedIfNeeded()
            clearDoomscrollLayer()
            return
        }
        usageAccessRevokedNotified = false

        if (System.currentTimeMillis() < DoomscrollPrefs.getSnoozedUntil(this)) {
            clearDoomscrollLayer()
            return
        }

        // Skip this tick if the previous query is still running rather than stacking up more
        // background work — the next tick (4s later) will pick up fresh data anyway.
        if (isQueryingDoomscrollUsage) return
        isQueryingDoomscrollUsage = true

        val targetPackages = DoomscrollPrefs.getTargetPackages(this)
        if (usageStatsExecutor.isShutdown) {
            isQueryingDoomscrollUsage = false
            return
        }
        try {
            usageStatsExecutor.execute {
                // The two UsageStatsManager queries below are genuinely slow system calls
                // (queryEvents scans a 6h window); run them off the main thread so they can't block
                // UI taps in the host Activity, which shares this thread since the service has no
                // android:process isolation.
                val foregroundPackage = UsageStatsHelper.currentForegroundPackage(this)
                val usedMinutes = UsageStatsHelper.todayUsageMinutes(this, targetPackages, DoomscrollPrefs.RESET_HOUR)
                handler.post {
                    isQueryingDoomscrollUsage = false
                    applyDoomscrollUsage(targetPackages, foregroundPackage, usedMinutes)
                }
            }
        } catch (e: RejectedExecutionException) {
            isQueryingDoomscrollUsage = false
        }
    }

    private fun applyDoomscrollUsage(targetPackages: Set<String>, foregroundPackage: String?, usedMinutes: Int) {
        // The monitor may have been disabled while the background query was in flight.
        if (!isDoomscrollMonitorActive) return

        if (foregroundPackage == null || foregroundPackage !in targetPackages) {
            clearDoomscrollLayer()
            return
        }

        val result = DoomscrollEngine.calculate(
            usedMinutes,
            DoomscrollPrefs.getDailyLimitMinutes(this),
            DoomscrollPrefs.getRampMinutes(this)
        )

        if (result.stage == DoomscrollStage.NONE) {
            clearDoomscrollLayer()
            return
        }

        val effect = OverlayEffectOptions.effectiveEffect(
            this,
            DoomscrollPrefs.getOverlayEffect(this),
            DoomscrollPrefs.DEFAULT_OVERLAY_EFFECT
        )
        if (doomscrollEffectView == null || doomscrollEffect != effect) {
            doomscrollEffectView?.let { stopEffectView(it) }
            val themedContext = ContextThemeWrapper(this, R.style.Theme_StormRoot)
            val effectView = createEffectView(themedContext, effect)
            startEffectView(effectView)
            doomscrollEffectView = effectView
            doomscrollEffect = effect
            windowController.setLayer(LAYER_DOOMSCROLL, effectView)
            doomscrollNudgeStartMillis = System.currentTimeMillis()
            StatsStore.recordEffectShown(this, StatsStore.MODULE_DOOMSCROLL, effect)
        }

        setEffectValue(doomscrollEffectView ?: return, result.rampProgress)
        StatsStore.recordNudgeIntensityTick(this, StatsStore.MODULE_DOOMSCROLL, result.rampProgress, DOOMSCROLL_TICK_MS)
        updateSnoozeChip()
    }

    private fun clearDoomscrollLayer() {
        if (doomscrollEffectView == null) return
        doomscrollNudgeStartMillis?.let { start ->
            StatsStore.recordNudgeSession(this, StatsStore.MODULE_DOOMSCROLL, start, System.currentTimeMillis())
        }
        doomscrollNudgeStartMillis = null
        doomscrollEffectView?.let { stopEffectView(it) }
        windowController.setLayer(LAYER_DOOMSCROLL, null)
        doomscrollEffectView = null
        doomscrollEffect = null
        updateSnoozeChip()
    }

    private fun registerMovementSensor() {
        if (sensorManager == null) {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        }
        val manager = sensorManager ?: return
        if (movementSensorListener != null) return
        consecutiveAccelSpikes = 0

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_STEP_DETECTOR -> onMovementDetected()
                    Sensor.TYPE_ACCELEROMETER -> {
                        val magnitude = sqrt(
                            event.values[0] * event.values[0] +
                                event.values[1] * event.values[1] +
                                event.values[2] * event.values[2]
                        )
                        if (abs(magnitude - SensorManager.GRAVITY_EARTH) > MOVEMENT_ACCEL_THRESHOLD) {
                            val now = System.currentTimeMillis()
                            if (consecutiveAccelSpikes == 0 || now - firstAccelSpikeMillis > MOVEMENT_SAMPLE_WINDOW_MS) {
                                consecutiveAccelSpikes = 1
                                firstAccelSpikeMillis = now
                            } else {
                                consecutiveAccelSpikes++
                            }
                            if (consecutiveAccelSpikes >= MOVEMENT_CONSECUTIVE_SAMPLES_REQUIRED) {
                                consecutiveAccelSpikes = 0
                                onMovementDetected()
                            }
                        } else {
                            consecutiveAccelSpikes = 0
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        movementSensorListener = listener

        val stepSensor = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (stepSensor != null) {
            manager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    private fun unregisterMovementSensor() {
        val manager = sensorManager ?: return
        movementSensorListener?.let { manager.unregisterListener(it) }
        movementSensorListener = null
    }

    private fun onMovementDetected() {
        lastMovementMillis = System.currentTimeMillis()
        if (isRootsMonitorActive) retreatRootsIfShowing()
    }

    private fun updateRootsLayer() {
        if (!SittingRootsPrefs.isEnabled(this) ||
            !OverlayPermission.isGranted(this) ||
            !ActivityRecognitionPermission.isGranted(this)
        ) {
            clearRootsLayer()
            return
        }
        if (System.currentTimeMillis() < SittingRootsPrefs.getSnoozedUntil(this)) {
            clearRootsLayer()
            return
        }

        val sedentaryMinutes = ((System.currentTimeMillis() - lastMovementMillis) / 60_000L).toInt()
        val targetGrowth = SittingRootsEngine.calculateGrowth(
            sedentaryMinutes = sedentaryMinutes,
            thresholdMinutes = SittingRootsPrefs.getSittingThresholdMinutes(this),
            rampMinutes = SittingRootsPrefs.getGrowthRampMinutes(this)
        )

        if (targetGrowth <= 0f) {
            clearRootsLayer()
            return
        }

        val effect = OverlayEffectOptions.effectiveEffect(
            this,
            SittingRootsPrefs.getOverlayEffect(this),
            SittingRootsPrefs.DEFAULT_OVERLAY_EFFECT
        )
        if (rootsEffectView == null || rootsEffect != effect) {
            rootsGrowthAnimator?.cancel()
            rootsEffectView?.let { stopEffectView(it) }
            val themedContext = ContextThemeWrapper(this, R.style.Theme_StormRoot)
            val effectView = createEffectView(themedContext, effect)
            startEffectView(effectView)
            rootsCurrentValue = 0f
            rootsEffectView = effectView
            rootsEffect = effect
            windowController.setLayer(LAYER_ROOTS, effectView)
            rootsNudgeStartMillis = System.currentTimeMillis()
            StatsStore.recordEffectShown(this, StatsStore.MODULE_ROOTS, effect)
        }

        animateRootsGrowthTo(targetGrowth)
        StatsStore.recordNudgeIntensityTick(this, StatsStore.MODULE_ROOTS, targetGrowth, ROOTS_TICK_MS)
        updateSnoozeChip()
    }

    private fun retreatRootsIfShowing() {
        val view = rootsEffectView ?: return
        if (rootsCurrentValue <= 0f) return
        rootsNudgeStartMillis?.let { start ->
            StatsStore.recordNudgeSession(this, StatsStore.MODULE_ROOTS, start, System.currentTimeMillis())
        }
        rootsNudgeStartMillis = null
        rootsGrowthAnimator?.cancel()
        rootsGrowthAnimator = ValueAnimator.ofFloat(rootsCurrentValue, 0f).apply {
            duration = 1200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                rootsCurrentValue = it.animatedValue as Float
                setEffectValue(view, rootsCurrentValue)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    clearRootsLayer()
                }
            })
            start()
        }
    }

    private fun animateRootsGrowthTo(target: Float) {
        val view = rootsEffectView ?: return
        rootsGrowthAnimator?.cancel()
        rootsGrowthAnimator = ValueAnimator.ofFloat(rootsCurrentValue, target).apply {
            duration = 3000
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                rootsCurrentValue = it.animatedValue as Float
                setEffectValue(view, rootsCurrentValue)
            }
            start()
        }
    }

    private fun clearRootsLayer() {
        if (rootsEffectView == null) return
        rootsNudgeStartMillis?.let { start ->
            StatsStore.recordNudgeSession(this, StatsStore.MODULE_ROOTS, start, System.currentTimeMillis())
        }
        rootsNudgeStartMillis = null
        rootsGrowthAnimator?.cancel()
        rootsEffectView?.let { stopEffectView(it) }
        windowController.setLayer(LAYER_ROOTS, null)
        rootsEffectView = null
        rootsEffect = null
        rootsCurrentValue = 0f
        updateSnoozeChip()
    }

    // Doomscroll and sitting-roots nudges previously had no way out short of complying (leaving
    // the app / getting up) — unlike bedtime, which already had snooze prefs wired up but no UI
    // ever called them. This surfaces one real "Not now" control for whichever nudge is active.
    // The main overlay window is FLAG_NOT_TOUCHABLE so this lives in its own small touchable window.
    private fun updateSnoozeChip() {
        val anyActive = bedtimeEffectView != null || doomscrollEffectView != null || rootsEffectView != null
        if (!anyActive) {
            windowController.hideSnoozeControl()
            return
        }
        val themedContext = ContextThemeWrapper(this, R.style.Theme_StormRoot)
        val chip = LayoutInflater.from(themedContext).inflate(R.layout.overlay_snooze_chip, null)
        chip.setOnClickListener { onSnoozeTapped() }
        windowController.showSnoozeControl(chip)
    }

    private fun onSnoozeTapped() {
        if (bedtimeEffectView != null) {
            BedtimeDriftPrefs.snoozeFor(this, SNOOZE_MINUTES)
            clearBedtimeLayer()
        }
        if (doomscrollEffectView != null) {
            DoomscrollPrefs.snoozeFor(this, SNOOZE_MINUTES)
            clearDoomscrollLayer()
        }
        if (rootsEffectView != null) {
            SittingRootsPrefs.snoozeFor(this, SNOOZE_MINUTES)
            clearRootsLayer()
        }
        updateSnoozeChip()
    }

    private fun createEffectView(themedContext: Context, effect: String): View {
        val view = when (effect) {
            EFFECT_CALM_VINES -> RootsOverlayView(themedContext)
            EFFECT_WARM_GLOW -> StormOverlayView(themedContext)
            EFFECT_BUTTERFLIES -> ButterflyOverlayView(themedContext)
            EFFECT_FALLING_LEAVES -> LeavesOverlayView(themedContext)
            EFFECT_SNOWFALL -> SnowfallOverlayView(themedContext)
            EFFECT_SUN_RAYS -> SunRaysOverlayView(themedContext)
            EFFECT_WATER_DROPLETS -> WaterDropletsOverlayView(themedContext)
            EFFECT_DEW_WEB -> DewSpiderWebOverlayView(themedContext)
            EFFECT_FIRE -> FireOverlayView(themedContext)
            EFFECT_STARRY_NIGHT -> NightSkyOverlayView(themedContext)
            else -> FogOverlayView(themedContext)
        }
        view.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
        return view
    }

    private fun startEffectView(view: View) {
        when (view) {
            is RootsOverlayView -> view.start()
            is FogOverlayView -> view.start()
            is StormOverlayView -> view.start()
            is ButterflyOverlayView -> view.startAnimation()
            is LeavesOverlayView -> view.startAnimation()
            is SnowfallOverlayView -> view.startAnimation()
            is SunRaysOverlayView -> view.startAnimation()
            is WaterDropletsOverlayView -> view.startAnimation()
            is DewSpiderWebOverlayView -> view.startAnimation()
            is FireOverlayView -> view.startAnimation()
            is NightSkyOverlayView -> view.startAnimation()
        }
    }

    private fun stopEffectView(view: View) {
        when (view) {
            is RootsOverlayView -> view.stop()
            is FogOverlayView -> view.stop()
            is StormOverlayView -> view.stop()
            is ButterflyOverlayView -> view.stopAnimation()
            is LeavesOverlayView -> view.stopAnimation()
            is SnowfallOverlayView -> view.stopAnimation()
            is SunRaysOverlayView -> view.stopAnimation()
            is WaterDropletsOverlayView -> view.stopAnimation()
            is DewSpiderWebOverlayView -> view.stopAnimation()
            is FireOverlayView -> view.stopAnimation()
            is NightSkyOverlayView -> view.stopAnimation()
        }
    }

    private fun setEffectValue(view: View, value: Float) {
        when (view) {
            is RootsOverlayView -> view.growth = value
            is FogOverlayView -> view.intensity = value
            is StormOverlayView -> view.intensity = value
            is ButterflyOverlayView -> view.intensity = value
            is LeavesOverlayView -> view.intensity = value
            is SnowfallOverlayView -> view.intensity = value
            is SunRaysOverlayView -> view.intensity = value
            is WaterDropletsOverlayView -> view.intensity = value
            is DewSpiderWebOverlayView -> view.intensity = value
            is FireOverlayView -> view.intensity = value
            is NightSkyOverlayView -> view.intensity = value
        }
    }

    private fun buildNotification(): android.app.Notification {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.overlay_notification_title),
                NotificationManager.IMPORTANCE_MIN
            )
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_body))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun notifyUsageAccessRevokedIfNeeded() {
        if (usageAccessRevokedNotified) return
        usageAccessRevokedNotified = true

        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                USAGE_ACCESS_WARNING_CHANNEL_ID,
                getString(R.string.usage_access_revoked_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val settingsIntent = UsageAccessPermission.requestIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, settingsIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, USAGE_ACCESS_WARNING_CHANNEL_ID)
            .setContentTitle(getString(R.string.usage_access_revoked_title))
            .setContentText(getString(R.string.usage_access_revoked_body))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(USAGE_ACCESS_WARNING_NOTIFICATION_ID, notification)
    }
}
