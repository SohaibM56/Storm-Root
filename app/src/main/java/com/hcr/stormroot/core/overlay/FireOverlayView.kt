package com.hcr.stormroot.core.overlay

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.PowerManager
import android.provider.Settings
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import androidx.core.graphics.withTranslation
import androidx.core.graphics.withRotation

class FireOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    companion object {
        private const val ROLE_CORE = 0
        private const val ROLE_BODY = 1
        private const val ROLE_FRINGE = 2
        private const val SEGMENTS = 48
        // Every speed/probability constant below was tuned assuming ~60 updates/sec; deltaFactor
        // scales each increment so perceived speed stays correct regardless of how often doFrame
        // actually processes a frame. Fire is the heaviest overlay (per-tongue bezier paths, blur
        // mask filters, a full-screen saveLayer), so unlike the other effects it's throttled to
        // TARGET_FPS to bound CPU cost — 30 is used specifically because it divides evenly into
        // 60/90/120Hz vsync intervals (2/3/4 vsyncs per processed frame), giving a perfectly even
        // skip pattern instead of the judder a non-divisor value like 40 produces.
        private const val TARGET_FPS = 30L
        private const val IDEAL_FRAME_NANOS = 1_000_000_000L / 60L
        // Safety ceiling so flicker speed can never be tuned into photosensitivity-risk
        // territory (WCAG guidance: stay under ~3 flashes/sec). Current tuned range
        // (0.04-0.08/tick, ~0.4-0.8Hz) is well under this; the cap is a no-op today and only
        // guards against a future tuning change pushing it too high.
        private const val MAX_FLICKER_SPEED_PER_TICK = 0.3f // ~2.9Hz at an assumed 60 ticks/sec
    }

    private class Tongue {
        var phase = 0f
        var freqA = 0f
        var freqB = 0f
        var freqC = 0f
        var ampA = 0f
        var ampB = 0f
        var ampC = 0f
        var heightScale = 1f
        var widthScale = 1f
        var xJitter = 0f
        var baseXJitter = 0f
        var isCore = false
        var alpha = 0
        // Depends only on isCore/widthScale, both fixed after init, so built once instead of
        // allocating a new BlurMaskFilter every frame.
        var blurFilter: BlurMaskFilter? = null

        fun init(role: Int, clusterHalfWidth: Float) {
            phase = Random.nextFloat() * 6.28f
            freqA = 1.1f + Random.nextFloat() * 1.5f
            freqB = 2.8f + Random.nextFloat() * 3.2f
            freqC = 7f + Random.nextFloat() * 5f
            ampA = 9f + Random.nextFloat() * 15f
            ampB = 5f + Random.nextFloat() * 9f
            ampC = 2f + Random.nextFloat() * 4f
            isCore = role == ROLE_CORE
            when (role) {
                ROLE_CORE -> {
                    heightScale = 1.1f + Random.nextFloat() * 0.4f
                    widthScale = 0.2f
                    xJitter = 0f
                    baseXJitter = 0f
                    alpha = 250
                }
                ROLE_FRINGE -> {
                    // short, wide-spread licks along the base for a scalloped bottom edge
                    heightScale = 0.2f + Random.nextFloat() * 0.22f
                    widthScale = 0.22f + Random.nextFloat() * 0.2f
                    baseXJitter = (Random.nextFloat() - 0.5f) * clusterHalfWidth * 2.1f
                    xJitter = baseXJitter + (Random.nextFloat() - 0.5f) * clusterHalfWidth * 0.5f
                    alpha = 150 + Random.nextInt(90)
                }
                else -> {
                    heightScale = 0.55f + Random.nextFloat() * 0.85f
                    widthScale = 0.28f + Random.nextFloat() * 0.34f
                    baseXJitter = (Random.nextFloat() - 0.5f) * clusterHalfWidth * 0.6f
                    xJitter = (Random.nextFloat() - 0.5f) * clusterHalfWidth * 1.3f
                    alpha = 130 + Random.nextInt(90)
                }
            }
            val blurRadius = if (isCore) 12f else 22f
            blurFilter = BlurMaskFilter(blurRadius * widthScale, BlurMaskFilter.Blur.NORMAL)
        }
    }

    private class Log {
        var xOffset = 0f
        var w = 0f
        var h = 0f
        var rotation = 0f
        // Colors are fixed constants and geometry is fixed after init, so this is built once
        // instead of allocating a new LinearGradient every frame.
        var shader: LinearGradient? = null

        fun init(clusterHalfWidth: Float) {
            xOffset = (Random.nextFloat() - 0.5f) * clusterHalfWidth * 1.7f
            w = clusterHalfWidth * (0.4f + Random.nextFloat() * 0.3f)
            h = clusterHalfWidth * (0.16f + Random.nextFloat() * 0.12f)
            rotation = (Random.nextFloat() - 0.5f) * 26f
            shader = LinearGradient(
                0f, -h, 0f, 0f,
                intArrayOf(
                    Color.argb(255, 40, 15, 5),
                    Color.argb(255, 15, 5, 2)
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    private class Flame {
        var xFraction = 0f
        var baseHeight = 0f
        var baseHalfWidth = 0f
        var swayPhase = 0f
        var swaySpeed = 0f
        var swayAmplitude = 0f
        var flickerPhase = 0f
        var flickerSpeed = 0f
        var time = 0f
        var timeSpeed = 0f
        val tongues = ArrayList<Tongue>()
        val logs = ArrayList<Log>()
        // Base floor glow only depends on view size, flame x, and intensity, all fixed until
        // the next rebuild, so it's built once instead of every onDraw call.
        var baseGlowShader: RadialGradient? = null
        var glowCenterX = 0f
        // Thermal shimmer's cool-stop alpha tracks flicker every frame, but flicker changes
        // smoothly, so the shader is only rebuilt when it moves to a new (imperceptible) bucket
        // instead of every single frame.
        var shimmerShader: RadialGradient? = null
        var shimmerBucket: Int = -1

        fun init(w: Int, h: Int) {
            xFraction = 0.5f
            baseHeight = h * 0.55f
            baseHalfWidth = w * 0.42f
            swayPhase = Random.nextFloat() * 6.28f
            swaySpeed = 0.008f + Random.nextFloat() * 0.01f
            swayAmplitude = 8f + Random.nextFloat() * 12f
            flickerPhase = Random.nextFloat() * 6.28f
            flickerSpeed = (0.04f + Random.nextFloat() * 0.04f).coerceAtMost(MAX_FLICKER_SPEED_PER_TICK)
            time = Random.nextFloat() * 10f
            timeSpeed = 0.06f + Random.nextFloat() * 0.04f

            tongues.clear()

            val fringeCount = 12 + Random.nextInt(6)
            repeat(fringeCount) {
                val t = Tongue()
                t.init(ROLE_FRINGE, clusterHalfWidth = baseHalfWidth)
                tongues.add(t)
            }
            val bodyCount = 18 + Random.nextInt(10)
            repeat(bodyCount) {
                val t = Tongue()
                t.init(ROLE_BODY, clusterHalfWidth = baseHalfWidth)
                tongues.add(t)
            }
            val coreCount = 4 + Random.nextInt(4)
            repeat(coreCount) {
                val t = Tongue()
                t.init(ROLE_CORE, clusterHalfWidth = baseHalfWidth)
                tongues.add(t)
            }

            logs.clear()
            repeat(12 + Random.nextInt(6)) {
                val log = Log()
                log.init(baseHalfWidth)
                logs.add(log)
            }
        }

        fun update(deltaFactor: Float) {
            swayPhase += swaySpeed * deltaFactor
            flickerPhase += flickerSpeed * deltaFactor
            time += timeSpeed * deltaFactor
        }

        fun swayOffset(): Float = sin(swayPhase.toDouble()).toFloat() * swayAmplitude
        fun flickerFactor(): Float {
            val normalized = (sin(flickerPhase.toDouble()).toFloat() + 1f) / 2f
            return 0.72f + 0.28f * normalized
        }
    }

    private class Ember {
        var x = 0f
        var y = 0f
        var lastX = 0f
        var lastY = 0f
        var vx = 0f
        var vy = 0f
        var radius = 0f
        var alpha = 0
        var twinklePhase = 0f
        var twinkleSpeed = 0f
        var life = 1f
        var lifeSpeed = 0f

        fun init(width: Int, height: Int, spawnAnywhere: Boolean = true) {
            x = width * 0.5f + (Random.nextFloat() - 0.5f) * width * 0.55f
            y = if (spawnAnywhere) height - Random.nextFloat() * height * 0.8f else height + 10f
            lastX = x
            lastY = y
            vx = (Random.nextFloat() - 0.5f) * 1.8f
            vy = -1.5f - Random.nextFloat() * 3.5f
            radius = 1.0f + Random.nextFloat() * 2.2f
            alpha = 140 + Random.nextInt(115)
            twinklePhase = Random.nextFloat() * 6.28f
            twinkleSpeed = 0.06f + Random.nextFloat() * 0.12f
            life = 0.5f + Random.nextFloat() * 0.5f
            lifeSpeed = 0.005f + Random.nextFloat() * 0.01f
        }

        fun update(width: Int, height: Int, deltaFactor: Float) {
            lastX = x
            lastY = y
            x += vx * deltaFactor
            y += vy * deltaFactor

            val altitudeFactor = (height - y) / height.toFloat()
            vy -= 0.05f * altitudeFactor * deltaFactor

            vx += (Random.nextFloat() - 0.5f) * (0.12f + 0.2f * altitudeFactor) * deltaFactor
            vx *= 0.98f.pow(deltaFactor)
            vy *= 0.99f.pow(deltaFactor)

            twinklePhase += twinkleSpeed * deltaFactor
            life -= lifeSpeed * deltaFactor

            if (life <= 0f || y < -20f || x < -20f || x > width + 20f) {
                init(width, height, spawnAnywhere = false)
            }
        }
    }

    private class Smoke {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var size = 0f
        var maxSize = 0f
        var life = 1f
        var lifeSpeed = 0f
        var alpha = 0
        // size grows continuously via easing, so the blur is only rebuilt once it drifts more
        // than half a pixel from the cached value instead of every single frame.
        var blurFilter: BlurMaskFilter? = null
        var blurFilterSize: Float = -1f

        fun init(width: Int, height: Int) {
            x = width * 0.5f + (Random.nextFloat() - 0.5f) * width * 0.32f
            y = height * 0.12f + Random.nextFloat() * height * 0.22f
            vx = (Random.nextFloat() - 0.5f) * 0.7f
            vy = -0.35f - Random.nextFloat() * 0.7f
            size = 8f + Random.nextFloat() * 9f
            maxSize = size * (3f + Random.nextFloat() * 3f)
            life = 1f
            lifeSpeed = 0.006f + Random.nextFloat() * 0.007f
            alpha = 45 + Random.nextInt(35)
        }

        fun update(width: Int, height: Int, deltaFactor: Float) {
            x += vx * deltaFactor
            y += vy * deltaFactor
            vx += (sin((y * 0.02f).toDouble()).toFloat() * 0.02f) * deltaFactor
            size += (maxSize - size) * 0.02f * deltaFactor
            life -= lifeSpeed * deltaFactor

            if (life <= 0f || y < -maxSize) {
                init(width, height)
            }
        }
    }

    private class FlameBlob {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var size = 0f
        var life = 0f
        var lifeSpeed = 0f
        var phase = 0f
        // size shrinks continuously via decay, so the blur is only rebuilt once it drifts more
        // than half a pixel from the cached value instead of every single frame.
        var blurFilter: BlurMaskFilter? = null
        var blurFilterSize: Float = -1f

        fun init(spawnX: Float, spawnY: Float, baseWidth: Float) {
            x = spawnX + (Random.nextFloat() - 0.5f) * baseWidth * 0.5f
            y = spawnY
            vx = (Random.nextFloat() - 0.5f) * 2f
            vy = -2f - Random.nextFloat() * 4f
            size = 15f + Random.nextFloat() * 25f
            life = 1f
            lifeSpeed = 0.015f + Random.nextFloat() * 0.02f
            phase = Random.nextFloat() * 6.28f
        }

        fun update(deltaFactor: Float) {
            x += (vx + sin((y * 0.05f + phase).toDouble()).toFloat() * 1.5f) * deltaFactor
            y += vy * deltaFactor
            life -= lifeSpeed * deltaFactor
            size *= 0.97f.pow(deltaFactor)
        }
    }

    private val flames = ArrayList<Flame>()
    private val embers = ArrayList<Ember>()
    private val blobs = ArrayList<FlameBlob>()
    private val smokes = ArrayList<Smoke>()
    private val flamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val emberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val logPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // Deliberately NOT using SCREEN blend like flamePaint/emberPaint — smoke is dark/desaturated
    // and would just disappear under additive blending. Normal alpha compositing instead.
    private val smokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val flamePath = Path()
    private var isAnimating = false

    // Reused across buildTonguePath calls instead of allocating point lists every frame.
    private val leftX = FloatArray(SEGMENTS + 1)
    private val rightX = FloatArray(SEGMENTS + 1)
    private val tongueMatrix = Matrix()

    // Unit-height (y: 0 to -1) gradients shared by every tongue and stretched per-draw via a
    // local matrix, instead of allocating a new LinearGradient every frame per tongue.
    private val coreUnitShader = LinearGradient(
        0f, 0f, 0f, -1f,
        intArrayOf(
            Color.argb(255, 60, 80, 255),
            Color.argb(255, 255, 255, 255),
            Color.argb(255, 255, 250, 150),
            Color.argb(245, 255, 170, 0),
            Color.argb(0, 255, 40, 0)
        ),
        floatArrayOf(0f, 0.08f, 0.25f, 0.6f, 1f),
        Shader.TileMode.CLAMP
    )
    private val bodyUnitShader = LinearGradient(
        0f, 0f, 0f, -1f,
        intArrayOf(
            Color.argb(40, 0, 20, 100),
            Color.argb(230, 255, 200, 40),
            Color.argb(190, 255, 110, 0),
            Color.argb(130, 200, 30, 0),
            Color.argb(0, 120, 10, 0)
        ),
        floatArrayOf(0f, 0.15f, 0.45f, 0.75f, 1f),
        Shader.TileMode.CLAMP
    )
    // Single shared blur for the thin "filament" stroke pass (fixed 5f radius, no per-tongue
    // variation) instead of allocating a new BlurMaskFilter for every filament draw.
    private val filamentBlur = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)

    // Unit-length (0,0)-(1,0) gradient reused for every ember's motion-blur line via a local
    // matrix (rotate+scale+translate onto the actual lastPos->pos segment) instead of
    // allocating a new LinearGradient every frame per ember.
    private val emberUnitShader = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(Color.argb(255, 255, 60, 0), Color.argb(255, 255, 245, 200)),
        null, Shader.TileMode.CLAMP
    )
    private val emberMatrix = Matrix()

    // Unit-radius gradient for the fixed-color "ignition" glow, repositioned per-frame (it
    // follows the flame's sway) via a local matrix instead of a new RadialGradient every frame.
    private val ignitionUnitShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.argb(60, 100, 150, 255), Color.TRANSPARENT),
        null, Shader.TileMode.CLAMP
    )
    private val ignitionMatrix = Matrix()

    // Unit-radius gradient for smoke wisps, repositioned/rescaled per-frame via a local matrix
    // (position and size both change continuously) instead of a new RadialGradient every frame.
    private val smokeUnitShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.argb(255, 90, 90, 95), Color.argb(120, 70, 70, 78), Color.TRANSPARENT),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP
    )
    private val smokeMatrix = Matrix()

    private var lastProcessedFrameNanos = 0L
    // Gesture-nav/system-bar inset so the log pile and base fringe aren't clipped at the very
    // bottom of edge-to-edge overlay windows.
    private var bottomInset = 0

    var intensity: Float = 0.85f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (isAnimating) {
                rebuildIfReady()
                invalidate()
            }
        }

    private fun flameCount(): Int = 1
    private fun emberCount(): Int = (65 + 130 * intensity).toInt()
    private fun smokeCount(): Int = (8 + 14 * intensity).toInt()

    private fun shouldAnimate(): Boolean {
        val reducedMotion = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
        val powerSaving = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        return !reducedMotion && !powerSaving
    }

    fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        lastProcessedFrameNanos = 0L
        rebuildIfReady()
        // Respect the system's "disable animations" dev setting and Battery Saver: still render
        // one static frame so the effect is visible, just without the continuous redraw loop.
        if (shouldAnimate()) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            invalidate()
        }
    }

    fun stopAnimation() {
        isAnimating = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val newInset = insets.systemWindowInsetBottom
        if (newInset != bottomInset) {
            bottomInset = newInset
            if (isAnimating) rebuildIfReady()
        }
        return super.onApplyWindowInsets(insets)
    }

    private fun effectiveBaseY(h: Int): Float = (h - bottomInset).toFloat().coerceAtLeast(0f)

    private fun rebuildIfReady() {
        val w = width
        val h = height
        if (w == 0 || h == 0 || !isAnimating) return

        val baseY = effectiveBaseY(h)
        val glowRadius = h * 0.35f
        flames.clear()
        repeat(flameCount()) {
            val f = Flame()
            f.init(w, h)
            f.glowCenterX = w * f.xFraction
            f.baseGlowShader = RadialGradient(
                f.glowCenterX, baseY, glowRadius,
                intArrayOf(
                    Color.argb((110 * intensity).toInt(), 255, 140, 50),
                    Color.argb((40 * intensity).toInt(), 220, 70, 20),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            flames.add(f)
        }

        val targetEmbers = emberCount()
        while (embers.size < targetEmbers) {
            val e = Ember()
            e.init(w, h)
            embers.add(e)
        }
        while (embers.size > targetEmbers) {
            embers.removeAt(embers.size - 1)
        }

        val targetSmokes = smokeCount()
        while (smokes.size < targetSmokes) {
            val s = Smoke()
            s.init(w, h)
            smokes.add(s)
        }
        while (smokes.size > targetSmokes) {
            smokes.removeAt(smokes.size - 1)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildIfReady()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAnimating) return
        val w = width
        val h = height
        if (w > 0 && h > 0) {
            if (lastProcessedFrameNanos == 0L) {
                lastProcessedFrameNanos = frameTimeNanos
            }
            val elapsedNanos = frameTimeNanos - lastProcessedFrameNanos
            val targetIntervalNanos = 1_000_000_000L / TARGET_FPS
            if (elapsedNanos >= targetIntervalNanos) {
                val deltaFactor = (elapsedNanos.toFloat() / IDEAL_FRAME_NANOS.toFloat()).coerceIn(0.25f, 4f)
                lastProcessedFrameNanos = frameTimeNanos

                for (i in flames.indices) flames[i].update(deltaFactor)
                for (i in embers.indices) embers[i].update(w, h, deltaFactor)
                for (i in smokes.indices) smokes[i].update(w, h, deltaFactor)

                if (Random.nextFloat() < 0.15f * intensity * deltaFactor) {
                    val b = FlameBlob()
                    b.init(w * 0.5f, h * 0.5f, w * 0.2f)
                    blobs.add(b)
                }
                val it = blobs.iterator()
                while (it.hasNext()) {
                    val b = it.next()
                    b.update(deltaFactor)
                    if (b.life <= 0f) it.remove()
                }

                invalidate()
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun buildTonguePath(flame: Flame, tongue: Tongue, flameHeight: Float): Path {
        flamePath.reset()

        val height = flameHeight * tongue.heightScale
        val halfWidth = flame.baseHalfWidth * tongue.widthScale

        for (i in 0..SEGMENTS) {
            val t = i / SEGMENTS.toFloat()

            val tPos = t.pow(1.2f)
            val taper = (1f - tPos).pow(1.4f)

            val time = flame.time * 1.3f
            val curlScale = tPos.pow(1.5f)

            val wave1 = sin((tongue.freqA * (tPos * 4f - time) + tongue.phase).toDouble()).toFloat()
            val wave2 = sin((tongue.freqB * (tPos * 3f - time * 2f) + tongue.phase * 2.2f).toDouble()).toFloat()
            val wave3 = sin((tongue.freqC * (tPos * 6f - time * 3.5f) + tongue.phase * 0.8f).toDouble()).toFloat()

            val tremble = (sin((time * 15f + tPos * 20f).toDouble()).toFloat() * 2f) * tPos

            val whip = (tongue.ampA * wave1 + tongue.ampB * wave2 + tongue.ampC * 0.5f * wave3) * curlScale + tremble

            val flare = 1f + 0.25f * sin((12f * tPos - time * 2f + tongue.phase).toDouble()).toFloat().coerceAtLeast(0f)
            val waist = (0.85f + 0.3f * sin((4f * tPos + tongue.phase).toDouble()).toFloat().coerceAtLeast(0f)) * flare
            val w = halfWidth * taper * waist

            val cx = tongue.baseXJitter + (tongue.xJitter - tongue.baseXJitter) * tPos.pow(1.3f) + whip
            val y = -height * tPos

            leftX[i] = cx - w
            rightX[i] = cx + w
            if (i == 0) {
                flamePath.moveTo(leftX[i], y)
            } else {
                flamePath.lineTo(leftX[i], y)
            }
        }

        for (i in SEGMENTS downTo 0) {
            val t = i / SEGMENTS.toFloat()
            val tPos = t.pow(1.2f)
            val y = -height * tPos
            flamePath.lineTo(rightX[i], y)
        }
        flamePath.close()
        return flamePath
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        val baseY = effectiveBaseY(h)

        val saveCount = canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)

        for (i in flames.indices) {
            val f = flames[i]
            val shader = f.baseGlowShader ?: continue
            val glowRadius = h * 0.35f
            glowPaint.shader = shader
            canvas.drawRect(
                (f.glowCenterX - glowRadius).coerceAtLeast(0f), baseY - glowRadius,
                (f.glowCenterX + glowRadius).coerceAtMost(w.toFloat()), baseY,
                glowPaint
            )
        }

        for (i in flames.indices) {
            val f = flames[i]
            val x = w * f.xFraction
            val flicker = f.flickerFactor()
            // Widened low end so "Subtle" reads as a genuinely calm, small fire rather than
            // just a slightly-shorter bonfire — keeps the campfire-comfort framing consistent
            // with the app's other calm effects across the whole strength range.
            val flameHeight = f.baseHeight * flicker * (0.25f + 0.85f * intensity)

            canvas.withTranslation(x, baseY) {
                for (j in f.logs.indices) {
                    val log = f.logs[j]
                    withRotation(log.rotation, log.xOffset, -log.h * 0.3f) {
                        logPaint.shader = log.shader
                        drawRoundRect(
                            log.xOffset - log.w / 2f, -log.h,
                            log.xOffset + log.w / 2f, 0f,
                            log.h * 0.4f, log.h * 0.4f,
                            logPaint
                        )

                        val logGlowFactor = 0.5f + 0.5f * flicker
                        val glowAlpha = (160 * logGlowFactor).toInt()
                        logPaint.shader = null
                        logPaint.color = Color.argb(glowAlpha, 255, 60, 0)
                        drawRoundRect(
                            log.xOffset - log.w / 2.2f, -log.h * 0.25f,
                            log.xOffset + log.w / 2.2f, 0f,
                            log.h * 0.2f, log.h * 0.2f,
                            logPaint
                        )

                        logPaint.color = Color.argb((glowAlpha * 0.6f).toInt(), 255, 255, 180)
                        drawRoundRect(
                            log.xOffset - log.w / 3f, -log.h * 0.15f,
                            log.xOffset + log.w / 3f, 0f,
                            log.h * 0.1f, log.h * 0.1f,
                            logPaint
                        )

                        if (flicker > 0.9f) {
                            logPaint.color = Color.argb(80, 255, 255, 200)
                            drawCircle(log.xOffset + (Random.nextFloat() - 0.5f) * log.w * 0.5f, -log.h * 0.1f, 2f, logPaint)
                        }
                    }
                }
                logPaint.shader = null
            }

            val haze = sin((f.time * 5f).toDouble()).toFloat() * 2.5f
            canvas.withTranslation(x + f.swayOffset() + haze, baseY) {
                for (j in f.tongues.indices) {
                    val tongue = f.tongues[j]

                    val tSway = sin((f.time * 2f + tongue.phase).toDouble()).toFloat() * 3f
                    canvas.withTranslation(tSway, 0f) {
                        val path = buildTonguePath(f, tongue, flameHeight)

                        val tongueHeight = flameHeight * tongue.heightScale
                        flamePaint.maskFilter = tongue.blurFilter

                        val unitShader = if (tongue.isCore) coreUnitShader else bodyUnitShader
                        tongueMatrix.setScale(1f, tongueHeight)
                        unitShader.setLocalMatrix(tongueMatrix)
                        flamePaint.shader = unitShader
                        flamePaint.alpha = (tongue.alpha * flicker).toInt().coerceIn(0, 255)
                        drawPath(path, flamePaint)

                        if (tongue.isCore || Random.nextFloat() < 0.4f) {
                            flamePaint.style = Paint.Style.STROKE
                            flamePaint.strokeWidth = 2f + Random.nextFloat() * 3f
                            flamePaint.maskFilter = filamentBlur
                            flamePaint.alpha = (255 * flicker * 0.7f).toInt()
                            drawPath(path, flamePaint)
                            flamePaint.style = Paint.Style.FILL
                        }
                    }
                }
                flamePaint.maskFilter = null
            }
        }
        flamePaint.shader = null

        for (i in smokes.indices) {
            val s = smokes[i]
            // life decays 1 -> 0 over the particle's lifetime; this envelope fades it in, holds,
            // then fades it out instead of popping in/out abruptly.
            val envelope = (1f - abs(s.life - 0.5f) * 2f).coerceIn(0f, 1f)
            smokePaint.alpha = (s.alpha * envelope * (0.3f + 0.7f * intensity)).toInt().coerceIn(0, 255)
            if (s.blurFilter == null || abs(s.size - s.blurFilterSize) > 0.5f) {
                s.blurFilterSize = s.size
                s.blurFilter = BlurMaskFilter(s.size * 0.5f, BlurMaskFilter.Blur.NORMAL)
            }
            smokePaint.maskFilter = s.blurFilter
            smokeMatrix.setScale(s.size, s.size)
            smokeMatrix.postTranslate(s.x, s.y)
            smokeUnitShader.setLocalMatrix(smokeMatrix)
            smokePaint.shader = smokeUnitShader
            canvas.drawCircle(s.x, s.y, s.size, smokePaint)
        }
        smokePaint.shader = null
        smokePaint.maskFilter = null

        for (i in flames.indices) {
            val f = flames[i]
            val x = w * f.xFraction
            val flicker = f.flickerFactor()
            // Quantize flicker into ~100 buckets (imperceptible steps) so the gradient is only
            // rebuilt when it actually moves to a new bucket, not on every single frame.
            val bucket = (flicker * 100).toInt()
            if (f.shimmerShader == null || f.shimmerBucket != bucket) {
                f.shimmerBucket = bucket
                f.shimmerShader = RadialGradient(
                    x, baseY - h * 0.5f, h * 0.4f,
                    intArrayOf(
                        Color.argb((40 * intensity * flicker).toInt(), 200, 210, 230),
                        Color.argb((20 * intensity).toInt(), 255, 180, 100),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            glowPaint.shader = f.shimmerShader
            canvas.drawCircle(x, baseY - h * 0.5f, h * 0.4f, glowPaint)
        }
        glowPaint.shader = null

        for (i in blobs.indices) {
            val b = blobs[i]
            flamePaint.alpha = (200 * b.life).toInt().coerceIn(0, 255)
            // size shrinks smoothly every frame; only rebuild the blur once it has drifted more
            // than half a pixel so this doesn't allocate on every single frame per blob.
            if (b.blurFilter == null || abs(b.size - b.blurFilterSize) > 0.5f) {
                b.blurFilterSize = b.size
                b.blurFilter = BlurMaskFilter(b.size * 0.6f, BlurMaskFilter.Blur.NORMAL)
            }
            flamePaint.maskFilter = b.blurFilter
            flamePaint.color = Color.argb(flamePaint.alpha, 255, 160, 20)
            canvas.drawCircle(b.x, b.y, b.size, flamePaint)
        }
        flamePaint.maskFilter = null

        for (i in flames.indices) {
            val f = flames[i]
            val x = w * f.xFraction
            val xOffset = x + f.swayOffset()
            val ignitionRadius = h * 0.08f
            ignitionMatrix.setScale(ignitionRadius, ignitionRadius)
            ignitionMatrix.postTranslate(xOffset, baseY)
            ignitionUnitShader.setLocalMatrix(ignitionMatrix)
            glowPaint.shader = ignitionUnitShader
            canvas.drawCircle(xOffset, baseY, ignitionRadius, glowPaint)
        }
        glowPaint.shader = null

        for (i in embers.indices) {
            val e = embers[i]
            val twinkle = (sin(e.twinklePhase.toDouble()).toFloat() + 1f) / 2f
            val intensityFactor = 0.4f + 0.6f * intensity
            val ageAlpha = e.life.coerceIn(0f, 1f)

            emberPaint.alpha = (e.alpha * (0.4f + 0.6f * twinkle) * intensityFactor * ageAlpha).toInt().coerceIn(0, 255)
            emberPaint.strokeWidth = e.radius * 2.2f

            // Motion blur: map the shared unit gradient onto the lastPos->pos segment instead
            // of allocating a new LinearGradient per ember per frame.
            val dx = e.x - e.lastX
            val dy = e.y - e.lastY
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
            val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            emberMatrix.reset()
            emberMatrix.postScale(len, 1f)
            emberMatrix.postRotate(angleDeg)
            emberMatrix.postTranslate(e.lastX, e.lastY)
            emberUnitShader.setLocalMatrix(emberMatrix)
            emberPaint.shader = emberUnitShader
            canvas.drawLine(e.lastX, e.lastY, e.x, e.y, emberPaint)
        }
        emberPaint.shader = null

        canvas.restoreToCount(saveCount)
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}
