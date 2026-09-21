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

/**
 * Soft light beams filtering down from a fixed source with gentle atmospheric depth — a slow
 * sway per beam, a slow shimmer (as if leaves overhead are moving), and drifting dust motes that
 * catch the light.
 */
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

        fun init(index: Int, count: Int) {
            // Beams spread from top-left towards the bottom-right
            val spread = 45f
            val centerAngle = 40f 
            baseAngle = centerAngle - spread / 2f + (spread * index / (count - 1).coerceAtLeast(1)) + (Random.nextFloat() - 0.5f) * 6f
            swayPhase = Random.nextFloat() * Math.PI.toFloat() * 2f
            swaySpeed = 0.003f + Random.nextFloat() * 0.004f
            swayAmplitude = 2.5f + Random.nextFloat() * 2.5f
            shimmerPhase = Random.nextFloat() * Math.PI.toFloat() * 2f
            shimmerSpeed = 0.006f + Random.nextFloat() * 0.01f
            topHalfWidth = 6f + Random.nextFloat() * 10f
            bottomHalfWidth = 90f + Random.nextFloat() * 140f
            baseAlpha = 40 + Random.nextInt(35)
            // Not every beam is the same warm-white — some lean golden, some cooler/paler.
            tintColor = Color.rgb(255, 244 + Random.nextInt(11), 210 + Random.nextInt(30))
        }

        fun update() {
            swayPhase += swaySpeed
            shimmerPhase += shimmerSpeed
        }

        fun currentAngle(): Float = baseAngle + sin(swayPhase.toDouble()).toFloat() * swayAmplitude
        fun shimmerFactor(): Float {
            val normalized = (sin(shimmerPhase.toDouble()).toFloat() + 1f) / 2f // 0..1
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

    /** A soft, drifting leaf/branch silhouette that dapples the beams — the "through trees" part. */
    private class CanopyPatch {
        var x = 0f
        var y = 0f
        var radiusX = 0f
        var radiusY = 0f
        var rotation = 0f
        var alpha = 0
        var swayPhase = 0f
        var swaySpeed = 0f
        var swayAmplitude = 0f

        fun init(width: Int, height: Int) {
            x = Random.nextFloat() * width
            y = Random.nextFloat() * height * 0.6f
            radiusX = 40f + Random.nextFloat() * 90f
            radiusY = radiusX * (0.4f + Random.nextFloat() * 0.3f)
            rotation = Random.nextFloat() * 360f
            alpha = 26 + Random.nextInt(30)
            swayPhase = Random.nextFloat() * Math.PI.toFloat() * 2f
            swaySpeed = 0.0025f + Random.nextFloat() * 0.004f
            swayAmplitude = 3f + Random.nextFloat() * 5f
        }

        fun update() {
            swayPhase += swaySpeed
        }

        fun currentX(): Float = x + sin(swayPhase.toDouble()).toFloat() * swayAmplitude
    }

    private val beams = ArrayList<Beam>()
    private val motes = ArrayList<DustMote>()
    private val canopyPatches = ArrayList<CanopyPatch>()
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val canopyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(35, 45, 22)
    }
    private val beamPath = Path()
    private var isAnimating = false

    /** Where the light source sits, as a fraction of view width/height (can be above the top edge). */
    private var sourceXFraction = 0.05f
    private var sourceY = -100f

    var intensity: Float = 0.7f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (isAnimating) rebuildIfReady()
        }

    private fun beamCount(): Int = (3 + (3 * intensity)).toInt()
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

        beams.clear()
        val count = beamCount()
        repeat(count) { i ->
            val b = Beam()
            b.init(i, count)
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

        canopyPatches.clear()
        repeat(5 + (5 * intensity).toInt()) {
            val patch = CanopyPatch()
            patch.init(w, h)
            canopyPatches.add(patch)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sourceY = -h * 0.05f
        rebuildIfReady()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAnimating) return
        val w = width
        val h = height
        if (w > 0 && h > 0) {
            for (i in 0 until beams.size) beams[i].update()
            for (i in 0 until motes.size) motes[i].update(w, h)
            for (i in 0 until canopyPatches.size) canopyPatches[i].update()
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
        val beamLength = h * 1.35f

        // 1. Source Bloom: A soft glow around the light source
        motePaint.shader = RadialGradient(
            sourceX, sourceY, h * 0.6f,
            intArrayOf(Color.argb((60 * intensity).toInt(), 255, 245, 220), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(sourceX, sourceY, h * 0.6f, motePaint)
        motePaint.shader = null

        for (i in 0 until beams.size) {
            val b = beams[i]
            canvas.save()
            canvas.translate(sourceX, sourceY)
            canvas.rotate(b.currentAngle())

            beamPath.reset()
            beamPath.moveTo(-b.topHalfWidth, 0f)
            beamPath.lineTo(b.topHalfWidth, 0f)
            beamPath.lineTo(b.bottomHalfWidth, beamLength)
            beamPath.lineTo(-b.bottomHalfWidth, beamLength)
            beamPath.close()

            val lengthFade = LinearGradient(
                0f, 0f, 0f, beamLength,
                intArrayOf(
                    Color.argb(255, Color.red(b.tintColor), Color.green(b.tintColor), Color.blue(b.tintColor)),
                    Color.argb(0, Color.red(b.tintColor), Color.green(b.tintColor), Color.blue(b.tintColor))
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            
            // Volumetric "streaks": add more stops to the width fade to simulate air particulate
            val widthFade = LinearGradient(
                -b.bottomHalfWidth, 0f, b.bottomHalfWidth, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(180, 255, 255, 255), Color.argb(255, 255, 255, 255), Color.argb(180, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            beamPaint.shader = ComposeShader(lengthFade, widthFade, PorterDuff.Mode.MULTIPLY)
            beamPaint.alpha = (b.baseAlpha * b.shimmerFactor()).toInt().coerceIn(0, 255)
            canvas.drawPath(beamPath, beamPaint)
            canvas.restore()
        }
        beamPaint.shader = null

        // Leaf/branch silhouettes dappling the beams — the "filtering through trees" look.
        for (i in 0 until canopyPatches.size) {
            val p = canopyPatches[i]
            canopyPaint.alpha = p.alpha
            canvas.save()
            canvas.translate(p.currentX(), p.y)
            canvas.rotate(p.rotation)
            canvas.drawOval(-p.radiusX, -p.radiusY, p.radiusX, p.radiusY, canopyPaint)
            canvas.restore()
        }

        for (i in 0 until motes.size) {
            val m = motes[i]
            val twinkle = (sin(m.twinklePhase.toDouble()).toFloat() + 1f) / 2f
            
            // Selective Visibility: Motes "light up" when passing through beams.
            // We approximate this by checking distance to the center of the beam array.
            val angleToSource = Math.toDegrees(atan2((m.x - sourceX).toDouble(), (m.y - sourceY).toDouble())).toFloat()
            
            // Beams originate from top-left, so we check angles centered around ~40 degrees
            val inBeamFactor = if (abs(angleToSource - 40f) < 28f) 1.6f else 0.3f
            
            motePaint.alpha = (m.alpha * (0.4f + 0.6f * twinkle) * inBeamFactor).toInt().coerceIn(0, 255)
            motePaint.shader = RadialGradient(
                m.x, m.y, m.radius * 2.2f,
                intArrayOf(Color.WHITE, Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(m.x, m.y, m.radius * 2.2f, motePaint)
        }
        motePaint.shader = null
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}
