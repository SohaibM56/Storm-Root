package com.hcr.stormroot.core.overlay

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

        private const val EXTRA_EFFECT = "com.hcr.stormroot.overlay.extra.EFFECT"
        private const val EXTRA_STRENGTH_MULTIPLIER = "com.hcr.stormroot.overlay.extra.STRENGTH_MULTIPLIER"

        private const val NOTIFICATION_CHANNEL_ID = "overlay_service"
        private const val NOTIFICATION_ID = 1001
        private const val LAYER_PREVIEW = "preview"
        private const val LAYER_BEDTIME = "bedtime"
        private const val LAYER_DOOMSCROLL = "doomscroll"
        private const val LAYER_ROOTS = "roots"
        private const val BEDTIME_TICK_MS = 60_000L
        private const val DOOMSCROLL_TICK_MS = 4_000L
        private const val ROOTS_TICK_MS = 60_000L
        private const val MOVEMENT_ACCEL_THRESHOLD = 2.2f

        /** Shows [effect] as a real system overlay over whatever's on screen right now —
         *  used by the Overlays tab's full-screen preview icon, distinct from the in-app
         *  preview rendered inside previewPanel. */
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

    private var doomscrollEffectView: View? = null
    private var doomscrollEffect: String? = null

    private var rootsEffectView: View? = null
    private var rootsEffect: String? = null
    private var rootsCurrentValue: Float = 0f
    private var rootsGrowthAnimator: ValueAnimator? = null
    private var previewRootsGrowthAnimator: ValueAnimator? = null
    private var lastMovementMillis: Long = 0L
    private var sensorManager: SensorManager? = null
    private var movementSensorListener: SensorEventListener? = null

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return resumeMonitorsAfterProcessRestart()
        }
        when (intent.action) {
            ACTION_STOP_MONITOR -> {
                isBedtimeMonitorActive = false
                handler.removeCallbacks(bedtimeTick)
                bedtimeNudgeStartMillis?.let { start ->
                    StatsStore.recordNudgeSession(this, StatsStore.MODULE_BEDTIME, start, System.currentTimeMillis())
                }
                bedtimeNudgeStartMillis = null
                windowController.setLayer(LAYER_BEDTIME, null)
                windowController.updateBlurBehind(0)
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
        doomscrollEffectView?.let { stopEffectView(it) }
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
            bedtimeNudgeStartMillis?.let { start ->
                StatsStore.recordNudgeSession(this, StatsStore.MODULE_BEDTIME, start, System.currentTimeMillis())
            }
            bedtimeNudgeStartMillis = null
            windowController.setLayer(LAYER_BEDTIME, null)
            windowController.updateBlurBehind(0)
            return
        }
        if (bedtimeNudgeStartMillis == null) {
            bedtimeNudgeStartMillis = System.currentTimeMillis()
        }

        val themedContext = ContextThemeWrapper(this, R.style.Theme_StormRoot)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_bedtime, null)
        val warmScrim = view.findViewById<View>(R.id.warmScrim)
        val vignetteScrim = view.findViewById<View>(R.id.vignetteScrim)
        val decorativeContainer = view.findViewById<android.widget.FrameLayout>(R.id.decorativeContainer)

        // The warm dimming (scrim/vignette/blur) is bedtime's own staged progression and
        // always applies; only the decorative particle layer underneath it is swappable —
        // it defaults to the storm visual but can be any of the 9 overlay effects.
        val decorativeView = createEffectView(themedContext, BedtimeDriftPrefs.getOverlayEffect(this))
        decorativeContainer.addView(decorativeView)
        startEffectView(decorativeView)

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

        windowController.setLayer(LAYER_BEDTIME, view)
    }

    private fun updateDoomscrollLayer() {
        if (!DoomscrollPrefs.isEnabled(this) ||
            !OverlayPermission.isGranted(this) ||
            !UsageAccessPermission.isGranted(this)
        ) {
            clearDoomscrollLayer()
            return
        }

        val targetPackages = DoomscrollPrefs.getTargetPackages(this)
        val foregroundPackage = UsageStatsHelper.currentForegroundPackage(this)
        if (foregroundPackage == null || foregroundPackage !in targetPackages) {
            clearDoomscrollLayer()
            return
        }

        val usedMinutes = UsageStatsHelper.todayUsageMinutes(this, targetPackages, DoomscrollPrefs.RESET_HOUR)
        val result = DoomscrollEngine.calculate(
            usedMinutes,
            DoomscrollPrefs.getDailyLimitMinutes(this),
            DoomscrollPrefs.getRampMinutes(this)
        )

        if (result.stage == DoomscrollStage.NONE) {
            clearDoomscrollLayer()
            return
        }

        val effect = DoomscrollPrefs.getOverlayEffect(this)
        if (doomscrollEffectView == null || doomscrollEffect != effect) {
            doomscrollEffectView?.let { stopEffectView(it) }
            val themedContext = ContextThemeWrapper(this, R.style.Theme_StormRoot)
            val effectView = createEffectView(themedContext, effect)
            startEffectView(effectView)
            doomscrollEffectView = effectView
            doomscrollEffect = effect
            windowController.setLayer(LAYER_DOOMSCROLL, effectView)
            doomscrollNudgeStartMillis = System.currentTimeMillis()
        }

        setEffectValue(doomscrollEffectView ?: return, result.rampProgress)
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
    }

    private fun registerMovementSensor() {
        if (sensorManager == null) {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        }
        val manager = sensorManager ?: return
        if (movementSensorListener != null) return

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
                            onMovementDetected()
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

        val effect = SittingRootsPrefs.getOverlayEffect(this)
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
        }

        animateRootsGrowthTo(targetGrowth)
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
    }

    /** Instantiates the view for a stored EFFECT_* choice, full-screen and ready to be
     *  added to any FrameLayout — used by the sitting-roots/doomscroll/bedtime layers to
     *  let each feature use whichever of the 9 overlay visuals the user picked. */
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
}
