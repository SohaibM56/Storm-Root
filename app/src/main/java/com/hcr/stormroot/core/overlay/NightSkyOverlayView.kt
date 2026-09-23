package com.hcr.stormroot.core.overlay

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.withClip
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class NightSkyOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    private class Star {
        var x = 0f
        var y = 0f
        var radius = 0f
        var baseAlpha = 0
        var twinklePhase = 0f
        var twinkleSpeed = 0f
        var isBright = false

        fun init(width: Int, height: Int) {
            x = Random.nextFloat() * width
            y = Random.nextFloat() * height * 0.85f
            isBright = Random.nextFloat() < 0.12f
            radius = if (isBright) 1.6f + Random.nextFloat() * 1.2f else 0.6f + Random.nextFloat() * 1.0f
            baseAlpha = if (isBright) 200 + Random.nextInt(55) else 90 + Random.nextInt(120)
            twinklePhase = Random.nextFloat() * Math.PI.toFloat() * 2f
            twinkleSpeed = 0.01f + Random.nextFloat() * 0.025f
        }
    }

    private class ShootingStar {
        var startX = 0f
        var startY = 0f
        var length = 0f
        var angleRad = 0f
        var progress = 0f
        var speed = 0f
        var active = false

        fun launch(width: Int, height: Int) {
            startX = Random.nextFloat() * width * 0.6f
            startY = Random.nextFloat() * height * 0.3f
            length = width * (0.12f + Random.nextFloat() * 0.1f)
            angleRad = Math.toRadians((25 + Random.nextFloat() * 20).toDouble()).toFloat()
            progress = 0f
            speed = 0.02f + Random.nextFloat() * 0.015f
            active = true
        }

        fun update(deltaFactor: Float) {
            progress += speed * deltaFactor
            if (progress >= 1.4f) active = false
        }
    }

    // Fixed offsets from moon center as a fraction of radius, generated once so the moon has a
    // stable "face" instead of a flat disc — a dense field of small dark/light blotches mimics
    // the mottled maria/highlands texture of a real full moon photo at negligible draw cost.
    private class Blotch(val dxFraction: Float, val dyFraction: Float, val sizeFraction: Float, val alpha: Int, val isDark: Boolean)

    private val stars = ArrayList<Star>()
    private val moonTexture = ArrayList<Blotch>()
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val moonClipPath = Path()
    private val shootingStarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f
    }
    private val shootingStar = ShootingStar()
    private var isAnimating = false
    private var lastFrameNanos = 0L

    // Overlays draw on top of whatever's on screen (wallpaper, apps) with no dark backdrop of
    // their own, so a translucent white glow can vanish against a bright background — this
    // darkens the sky immediately around the moon first so the glow always has contrast to pop
    // against, regardless of what's underneath.
    private var moonVignetteShader: RadialGradient? = null
    private var moonVignetteKeyRadius = -1f
    private var moonGlowShader: RadialGradient? = null
    private var moonGlowKeyW = -1
    private var moonGlowKeyH = -1
    private var moonRimGlowShader: RadialGradient? = null
    private var moonRimGlowKeyRadius = -1f
    private var moonDiscShader: RadialGradient? = null
    private var moonDiscKeyRadius = -1f
    // Real maria/craters have soft, blended edges — plain filled circles read as flat gray coins
    // stuck onto the disc. A single shared blur filter (scaled to moon size, rebuilt only on
    // resize) softens every texture blotch at effectively no extra per-frame cost.
    private var moonTextureBlur: BlurMaskFilter? = null
    private var moonTextureBlurKeyRadius = -1f
    private var moonX = 0f
    private var moonY = 0f
    private var moonRadius = 0f

    var intensity: Float = 0.7f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (isAnimating) adjustStarCount(targetCount())
        }

    private fun targetCount(): Int = (60 + 140 * intensity).toInt()

    fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        lastFrameNanos = 0L
        setupStarsIfReady()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopAnimation() {
        isAnimating = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun adjustStarCount(target: Int) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        while (stars.size < target) {
            val s = Star()
            s.init(w, h)
            stars.add(s)
        }
        while (stars.size > target) {
            stars.removeAt(stars.size - 1)
        }
    }

    private fun setupStarsIfReady() {
        val w = width
        val h = height
        if (w == 0 || h == 0 || !isAnimating) return
        if (stars.isEmpty()) {
            repeat(targetCount()) {
                val s = Star()
                s.init(w, h)
                stars.add(s)
            }
        } else {
            adjustStarCount(targetCount())
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        moonX = w * 0.78f
        moonY = h * 0.18f
        moonRadius = minOf(w, h) * 0.09f
        moonClipPath.reset()
        moonClipPath.addCircle(moonX, moonY, moonRadius, Path.Direction.CW)
        if (moonTexture.isEmpty()) rebuildMoonTexture()
        setupStarsIfReady()
    }

    private fun rebuildMoonTexture() {
        // Large dark maria patches first (drawn first so the smaller crater speckling below
        // layers on top of them) — this is what gives the moon actual visible texture instead of
        // a faint, barely-there speckle. Placed with minimum spacing (rejection sampling) so a
        // handful of patches can't randomly land on top of each other and read as one big blob.
        val mariaCenters = ArrayList<Pair<Float, Float>>()
        val mariaCount = 4 + Random.nextInt(3)
        repeat(mariaCount) {
            var dx = 0f
            var dy = 0f
            var size = 0f
            for (attempt in 0 until 12) {
                val angle = Random.nextFloat() * (Math.PI * 2).toFloat()
                val dist = sqrt(Random.nextFloat()) * 0.65f
                val candidateX = cos(angle) * dist
                val candidateY = sin(angle) * dist
                val candidateSize = 0.11f + Random.nextFloat() * 0.09f
                val tooClose = mariaCenters.any { (cx, cy) ->
                    val ddx = cx - candidateX
                    val ddy = cy - candidateY
                    sqrt(ddx * ddx + ddy * ddy) < (candidateSize + size) * 0.9f + 0.12f
                }
                dx = candidateX
                dy = candidateY
                size = candidateSize
                if (!tooClose) break
            }
            mariaCenters.add(dx to dy)
            moonTexture.add(
                Blotch(
                    dxFraction = dx,
                    dyFraction = dy,
                    sizeFraction = size,
                    alpha = 40 + Random.nextInt(25),
                    isDark = true
                )
            )
        }

        val blotchCount = 55 + Random.nextInt(20)
        repeat(blotchCount) {
            val angle = Random.nextFloat() * (Math.PI * 2).toFloat()
            // sqrt of a uniform random spreads points evenly by area instead of clustering at the center.
            val dist = sqrt(Random.nextFloat()) * 0.88f
            val isDark = Random.nextFloat() < 0.55f
            moonTexture.add(
                Blotch(
                    dxFraction = cos(angle) * dist,
                    dyFraction = sin(angle) * dist,
                    sizeFraction = 0.025f + Random.nextFloat() * 0.06f,
                    alpha = if (isDark) 25 + Random.nextInt(35) else 20 + Random.nextInt(35),
                    isDark = isDark
                )
            )
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAnimating) return
        val w = width
        val h = height
        if (w > 0 && h > 0) {
            if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos
            val deltaFactor = ((frameTimeNanos - lastFrameNanos) / (1_000_000_000f / 60f)).coerceIn(0.25f, 4f)
            lastFrameNanos = frameTimeNanos

            for (i in stars.indices) {
                stars[i].twinklePhase += stars[i].twinkleSpeed * deltaFactor
            }

            if (shootingStar.active) {
                shootingStar.update(deltaFactor)
            } else if (Random.nextFloat() < 0.0025f * intensity * deltaFactor) {
                shootingStar.launch(w, h)
            }

            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    // These three shaders describe the moon's look at full brightness only — they no longer bake
    // `intensity` in at all, so they're stable across intensity changes and only ever rebuilt on
    // resize. Actual visible brightness (seekbar-set strength, or ramp progress fading the moon
    // in over time) is applied uniformly via moonPaint.alpha at draw time in onDraw — a genuine
    // multiplier over these full-brightness colors, so intensity 0 is truly invisible and 1 is
    // exactly this shader's color, instead of a floor-clamped blend baked into the gradient itself.
    private fun moonVignetteFor(): RadialGradient {
        if (moonVignetteShader == null || moonVignetteKeyRadius != moonRadius) {
            moonVignetteKeyRadius = moonRadius
            moonVignetteShader = RadialGradient(
                moonX, moonY, moonRadius * 3f,
                intArrayOf(
                    Color.argb(180, 6, 10, 24),
                    Color.argb(70, 6, 10, 24),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        return moonVignetteShader!!
    }

    // Wide, soft outer haze.
    private fun moonGlowFor(): RadialGradient {
        if (moonGlowShader == null || moonGlowKeyW != width || moonGlowKeyH != height) {
            moonGlowKeyW = width
            moonGlowKeyH = height
            moonGlowShader = RadialGradient(
                moonX, moonY, moonRadius * 2.2f,
                intArrayOf(Color.argb(250, 235, 242, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
        }
        return moonGlowShader!!
    }

    // Tight, bright rim right at the edge of the disc — this is what actually reads as the moon
    // "glowing" rather than just being a bright disc with a faint haze around it.
    private fun moonRimGlowFor(): RadialGradient {
        if (moonRimGlowShader == null || moonRimGlowKeyRadius != moonRadius) {
            moonRimGlowKeyRadius = moonRadius
            moonRimGlowShader = RadialGradient(
                moonX, moonY, moonRadius * 1.5f,
                intArrayOf(Color.argb(255, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0.6f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        return moonRimGlowShader!!
    }

    private fun moonTextureBlurFor(): BlurMaskFilter {
        if (moonTextureBlur == null || moonTextureBlurKeyRadius != moonRadius) {
            moonTextureBlurKeyRadius = moonRadius
            moonTextureBlur = BlurMaskFilter((moonRadius * 0.06f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        }
        return moonTextureBlur!!
    }

    // Uniform, evenly-lit disc (a subtle center-to-edge falloff only, no directional highlight)
    // so it reads as a flat-on full moon photo rather than a shaded 3D sphere.
    private fun moonDiscFor(): RadialGradient {
        if (moonDiscShader == null || moonDiscKeyRadius != moonRadius) {
            moonDiscKeyRadius = moonRadius
            moonDiscShader = RadialGradient(
                moonX, moonY, moonRadius,
                intArrayOf(
                    Color.rgb(255, 255, 255),
                    Color.rgb(248, 249, 250),
                    Color.rgb(230, 233, 238)
                ),
                floatArrayOf(0f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        return moonDiscShader!!
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        // Single multiplier applied to every moon layer: at intensity 0 the whole moon is
        // invisible, at 1 it's exactly the shaders' full-brightness colors. This is what lets the
        // moon start faint and grow brighter over time when driven by a module's ramp progress,
        // and what makes the Overlays screen's strength seekbar actually control how bright it is.
        val moonAlpha = (255 * intensity).toInt().coerceIn(0, 255)

        moonPaint.shader = moonVignetteFor()
        moonPaint.alpha = moonAlpha
        canvas.drawCircle(moonX, moonY, moonRadius * 3f, moonPaint)
        moonPaint.shader = null

        moonPaint.shader = moonGlowFor()
        moonPaint.alpha = moonAlpha
        canvas.drawCircle(moonX, moonY, moonRadius * 2.2f, moonPaint)
        moonPaint.shader = null

        moonPaint.shader = moonRimGlowFor()
        moonPaint.alpha = moonAlpha
        canvas.drawCircle(moonX, moonY, moonRadius * 1.5f, moonPaint)
        moonPaint.shader = null

        moonPaint.shader = moonDiscFor()
        moonPaint.alpha = moonAlpha
        canvas.drawCircle(moonX, moonY, moonRadius, moonPaint)
        moonPaint.shader = null

        canvas.withClip(moonClipPath) {
            moonPaint.maskFilter = moonTextureBlurFor()
            for (i in moonTexture.indices) {
                val b = moonTexture[i]
                val combinedAlpha = (b.alpha * intensity).toInt().coerceIn(0, 255)
                moonPaint.color = if (b.isDark) {
                    Color.argb(combinedAlpha, 165, 168, 175)
                } else {
                    Color.argb(combinedAlpha, 255, 255, 255)
                }
                drawCircle(moonX + b.dxFraction * moonRadius, moonY + b.dyFraction * moonRadius, b.sizeFraction * moonRadius, moonPaint)
            }
            moonPaint.maskFilter = null
        }

        for (i in stars.indices) {
            val s = stars[i]
            val twinkle = (sin(s.twinklePhase.toDouble()).toFloat() + 1f) / 2f
            starPaint.color = if (s.isBright) Color.WHITE else Color.rgb(225, 232, 250)
            starPaint.alpha = (s.baseAlpha * (0.5f + 0.5f * twinkle)).toInt().coerceIn(0, 255)
            canvas.drawCircle(s.x, s.y, s.radius, starPaint)
        }

        if (shootingStar.active) {
            val t = shootingStar.progress.coerceIn(0f, 1f)
            val headX = shootingStar.startX + cos(shootingStar.angleRad) * shootingStar.length * t
            val headY = shootingStar.startY + sin(shootingStar.angleRad) * shootingStar.length * t
            val tailFactor = (t - 0.3f).coerceAtLeast(0f) / 0.7f
            val tailX = shootingStar.startX + cos(shootingStar.angleRad) * shootingStar.length * tailFactor
            val tailY = shootingStar.startY + sin(shootingStar.angleRad) * shootingStar.length * tailFactor
            val fadeOut = (1f - ((shootingStar.progress - 1f).coerceAtLeast(0f) / 0.4f)).coerceIn(0f, 1f)
            shootingStarPaint.color = Color.argb((220 * fadeOut).toInt(), 255, 255, 255)
            canvas.drawLine(tailX, tailY, headX, headY, shootingStarPaint)
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}
