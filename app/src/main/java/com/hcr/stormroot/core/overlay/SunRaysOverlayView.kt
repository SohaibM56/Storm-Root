package com.hcr.stormroot.core.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.random.Random
import androidx.core.graphics.withTranslation

class SunRaysOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    private class Beam {
        var baseAngle = 0f
        var swayPhase = 0f
        var swaySpeed = 0f
        var swayAmplitude = 0f
        var shimmerPhase = 0f
        var shimmerSpeed = 0f
        var topHalfWidth = 0f
        var bottomHalfWidth = 0f
        var baseAlpha = 0
        var tintColor = Color.WHITE

        // Length/width fade shaders only depend on values fixed at init() (tintColor,
        // bottomHalfWidth) plus beamLength (fixed until resize) — cache them instead of
        // allocating 2 gradients + 1 ComposeShader per beam on every single frame.
        private var cachedBeamLength = -1f
        var composeShader: ComposeShader? = null

        fun ensureShaders(beamLength: Float) {
            if (cachedBeamLength == beamLength && composeShader != null) return
            cachedBeamLength = beamLength
            val r = Color.red(tintColor)
            val g = Color.green(tintColor)
            val b = Color.blue(tintColor)
            // Holds near-full brightness for most of the beam's length instead of a straight
            // linear fade — a plain 2-stop fade was already down to ~13% opacity by the time it
            // reached the actual screen corner (the beam overshoots it by design), which made the
            // rays look like they only existed right next to the source.
            val lengthFade = LinearGradient(
                0f, 0f, 0f, beamLength,
                intArrayOf(
                    Color.argb(255, r, g, b),
                    Color.argb(230, r, g, b),
                    Color.argb(120, r, g, b),
                    Color.argb(0, r, g, b)
                ),
                floatArrayOf(0f, 0.75f, 0.92f, 1f),
                Shader.TileMode.CLAMP
            )
            val widthFade = LinearGradient(
                -bottomHalfWidth, 0f, bottomHalfWidth, 0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(180, 255, 255, 255),
                    Color.argb(255, 255, 255, 255),
                    Color.argb(180, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            composeShader = ComposeShader(lengthFade, widthFade, PorterDuff.Mode.MULTIPLY)
        }

        fun init(index: Int, count: Int, centerAngle: Float) {
            // Tight fan around the true top-left-to-bottom-right corner angle (passed in, not a
            // fixed 45° — a portrait screen's actual diagonal is closer to 20-25° from vertical)
            // so the rays stay aimed at that corner instead of spreading toward the edges.
            val spread = 22f
            baseAngle = centerAngle - spread / 2f + (spread * index / (count - 1).coerceAtLeast(1)) + (Random.nextFloat() - 0.5f) * 4f
            swayPhase = Random.nextFloat() * Math.PI.toFloat() * 2f
            swaySpeed = 0.003f + Random.nextFloat() * 0.004f
            swayAmplitude = 2.5f + Random.nextFloat() * 2.5f
            shimmerPhase = Random.nextFloat() * Math.PI.toFloat() * 2f
            shimmerSpeed = 0.006f + Random.nextFloat() * 0.01f
            topHalfWidth = 6f + Random.nextFloat() * 10f
            bottomHalfWidth = 90f + Random.nextFloat() * 140f
            baseAlpha = 90 + Random.nextInt(60)
            tintColor = Color.rgb(255, 244 + Random.nextInt(11), 210 + Random.nextInt(30))
        }

        fun update() {
            swayPhase += swaySpeed
            shimmerPhase += shimmerSpeed
        }

        fun currentAngle(): Float = baseAngle + sin(swayPhase.toDouble()).toFloat() * swayAmplitude
        fun shimmerFactor(): Float {
            val normalized = (sin(shimmerPhase.toDouble()).toFloat() + 1f) / 2f
            return 0.55f + 0.45f * normalized
        }
    }

    private class DustMote {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var radius = 0f
        var alpha = 0
        var twinklePhase = 0f
        var twinkleSpeed = 0f

        fun init(width: Int, height: Int, spawnAnywhere: Boolean = true) {
            x = Random.nextFloat() * width
            y = if (spawnAnywhere) Random.nextFloat() * height else height + 10f
            vx = (Random.nextFloat() - 0.5f) * 0.25f
            vy = -0.15f - Random.nextFloat() * 0.35f
            radius = 0.8f + Random.nextFloat() * 1.8f
            alpha = 60 + Random.nextInt(110)
            twinklePhase = Random.nextFloat() * Math.PI.toFloat() * 2f
            twinkleSpeed = 0.02f + Random.nextFloat() * 0.05f
        }

        fun update(width: Int, height: Int) {
            x += vx
            y += vy
            twinklePhase += twinkleSpeed
            if (y < -10f || x < -10f || x > width + 10f) init(width, height, spawnAnywhere = false)
        }
    }

    private val beams = ArrayList<Beam>()
    private val motes = ArrayList<DustMote>()
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val beamPath = Path()
    private var isAnimating = false

    private var sourceXFraction = 0f
    private var sourceY = -100f
    private var diagonalAngle = 45f

    // Cached by radius bucket so onDraw doesn't allocate a RadialGradient per dust mote per frame.
    private val moteGradients = HashMap<Int, RadialGradient>()

    private fun moteGradientFor(radius: Float): RadialGradient {
        val key = (radius * 4f).toInt()
        return moteGradients.getOrPut(key) {
            val r = key / 4f
            RadialGradient(
                0f, 0f, r * 2.2f,
                intArrayOf(Color.WHITE, Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    // Ambient glow only depends on source position, height, and intensity — rebuilt only when
    // one of those actually changes instead of once per frame.
    private var ambientGlowShader: RadialGradient? = null
    private var ambientGlowKeyW = -1
    private var ambientGlowKeyH = -1
    private var ambientGlowKeyIntensity = -1f

    private fun ambientGlowFor(sourceX: Float, h: Int): RadialGradient {
        if (ambientGlowShader == null || ambientGlowKeyW != width || ambientGlowKeyH != h || ambientGlowKeyIntensity != intensity) {
            ambientGlowKeyW = width
            ambientGlowKeyH = h
            ambientGlowKeyIntensity = intensity
            // Reaches the full screen diagonal instead of stopping at 0.6h, so the ambient wash
            // covers the whole view instead of just the area right around the source.
            val glowRadius = kotlin.math.hypot(width.toFloat(), h.toFloat())
            ambientGlowShader = RadialGradient(
                sourceX, sourceY, glowRadius,
                intArrayOf(
                    Color.argb((70 * intensity).toInt(), 255, 245, 220),
                    Color.argb((25 * intensity).toInt(), 255, 245, 220),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        return ambientGlowShader!!
    }

    var intensity: Float = 0.7f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (isAnimating) rebuildIfReady()
        }

    private fun beamCount(): Int = (6 + (6 * intensity)).toInt()
    private fun moteCount(): Int = (18 + 50 * intensity).toInt()

    fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        rebuildIfReady()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopAnimation() {
        isAnimating = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun rebuildIfReady() {
        val w = width
        val h = height
        if (w == 0 || h == 0 || !isAnimating) return

        // canvas.rotate(+angle) sweeps the local "straight down" direction toward the LEFT, not
        // the right (Android rotation is clockwise, and clockwise from 6 o'clock goes to 9, not
        // 3) — so a NEGATIVE angle is what actually swings the beam from the top-left source
        // toward the right, i.e. toward the bottom-right corner. On a portrait screen this
        // magnitude is well under 45°, since height exceeds width.
        diagonalAngle = -Math.toDegrees(atan2(w.toDouble(), h.toDouble())).toFloat()

        beams.clear()
        val count = beamCount()
        repeat(count) { i ->
            val b = Beam()
            b.init(i, count, diagonalAngle)
            beams.add(b)
        }

        val targetMotes = moteCount()
        while (motes.size < targetMotes) {
            val m = DustMote()
            m.init(w, h)
            motes.add(m)
        }
        while (motes.size > targetMotes) {
            motes.removeAt(motes.size - 1)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sourceY = 0f
        rebuildIfReady()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAnimating) return
        val w = width
        val h = height
        if (w > 0 && h > 0) {
            for (i in beams.indices) beams[i].update()
            for (i in motes.indices) motes[i].update(w, h)
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        val sourceX = w * sourceXFraction
        // Long enough to actually reach the bottom-right corner (the screen diagonal) instead of
        // fading out well before it, plus margin so the fade-to-transparent tail isn't cut short.
        val glowRadius = kotlin.math.hypot(w.toFloat(), h.toFloat())
        val beamLength = glowRadius * 1.15f
        motePaint.shader = ambientGlowFor(sourceX, h)
        canvas.drawCircle(sourceX, sourceY, glowRadius, motePaint)
        motePaint.shader = null

        for (i in beams.indices) {
            val b = beams[i]
            b.ensureShaders(beamLength)
            canvas.withTranslation(sourceX, sourceY) {
                rotate(b.currentAngle())

                beamPath.reset()
                beamPath.moveTo(-b.topHalfWidth, 0f)
                beamPath.lineTo(b.topHalfWidth, 0f)
                beamPath.lineTo(b.bottomHalfWidth, beamLength)
                beamPath.lineTo(-b.bottomHalfWidth, beamLength)
                beamPath.close()

                beamPaint.shader = b.composeShader
                beamPaint.alpha = (b.baseAlpha * b.shimmerFactor()).toInt().coerceIn(0, 255)
                drawPath(beamPath, beamPaint)
            }
        }
        beamPaint.shader = null

        for (i in motes.indices) {
            val m = motes[i]
            val twinkle = (sin(m.twinklePhase.toDouble()).toFloat() + 1f) / 2f

            // Negated x-difference to match the beam's rotation convention (see diagonalAngle
            // above) — otherwise this flags motes on the wrong side of the source as "in beam".
            val angleToSource = Math.toDegrees(atan2(-(m.x - sourceX).toDouble(), (m.y - sourceY).toDouble())).toFloat()
            
            val inBeamFactor = if (abs(angleToSource - diagonalAngle) < 22f) 1.6f else 0.3f
            
            motePaint.alpha = (m.alpha * (0.4f + 0.6f * twinkle) * inBeamFactor).toInt().coerceIn(0, 255)
            motePaint.shader = moteGradientFor(m.radius)
            canvas.withTranslation(m.x, m.y) {
                drawCircle(0f, 0f, m.radius * 2.2f, motePaint)
            }
        }
        motePaint.shader = null
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}
