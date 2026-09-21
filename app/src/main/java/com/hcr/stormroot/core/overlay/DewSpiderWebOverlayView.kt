package com.hcr.stormroot.core.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * A spider web anchored in a corner with tiny dew droplets sitting at the silk intersections,
 * each catching the light with its own slow, independent twinkle — like morning light on webs.
 */
class DewSpiderWebOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    /** [angleDeg] is the local silk direction at this point — droplets bead elongated along it. */
    private class Droplet(val x: Float, val y: Float, val radius: Float, val angleDeg: Float) {
        var twinklePhase = Random.nextFloat() * Math.PI.toFloat() * 2f
        val twinkleSpeed = 0.01f + Random.nextFloat() * 0.02f
    }

    /** A single silk strand with its own hand-spun thickness/opacity so the web isn't uniform. */
    private class Thread(val path: Path, val alpha: Int, val strokeWidth: Float)

    private val threads = ArrayList<Thread>()
    private val droplets = ArrayList<Droplet>()

    private val threadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val dropletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pollenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(230, 210, 150) // Golden-brown pollen
    }

    private var isAnimating = false
    private var centerX = 0f
    private var centerY = 0f
    private var swayPhase = 0f
    private var vibrationPhase = 0f
    private val pollenList = ArrayList<PointF>()

    var intensity: Float = 0.7f
        set(value) {
            field = value.coerceIn(0f, 1f)
            rebuildWebIfReady()
        }

    fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        rebuildWebIfReady()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopAnimation() {
        isAnimating = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildWebIfReady()
    }

    private fun rebuildWebIfReady() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        threads.clear()
        droplets.clear()
        val intersections = ArrayList<Triple<Float, Float, Float>>() // x, y, local silk angle (deg)

        // Anchored precisely in the top-right corner
        centerX = w.toFloat()
        centerY = 0f

        val spokeCount = 10 + (8 * intensity).toInt()
        val ringCount = 6 + (5 * intensity).toInt()
        val arcStartDeg = 95f
        val arcEndDeg = 265f
        val maxRadius = hypot(w.toDouble(), h.toDouble()).toFloat() * 1.1f

        val spokeAngles = FloatArray(spokeCount) { i ->
            val t = if (spokeCount == 1) 0f else i.toFloat() / (spokeCount - 1)
            val deg = arcStartDeg + (arcEndDeg - arcStartDeg) * t + (Random.nextFloat() - 0.5f) * 4f
            Math.toRadians(deg.toDouble()).toFloat()
        }

        // Ring radii aren't evenly spaced — real webs bunch some rings closer than others.
        val ringRadii = FloatArray(ringCount) { r ->
            val t = (r + 1f) / ringCount
            val eased = t * t * (3f - 2f * t) // smoothstep
            maxRadius * (0.18f + 0.82f * eased)
        }

        fun addThread(path: Path, faint: Boolean = false) {
            val alpha = if (faint) 30 + Random.nextInt(30) else 65 + Random.nextInt(55)
            val width = if (faint) 0.6f + Random.nextFloat() * 0.4f else 0.9f + Random.nextFloat() * 0.7f
            threads.add(Thread(path, alpha, width))
        }

        // Spokes (straight lines from the anchor point out to the last ring).
        for (angleIdx in spokeAngles.indices) {
            val angle = spokeAngles[angleIdx]
            val path = Path()
            path.moveTo(centerX, centerY)
            val endR = ringRadii.last() * (1.06f + Random.nextFloat() * 0.06f)
            path.lineTo(centerX + cos(angle) * endR, centerY + sin(angle) * endR)
            addThread(path)
        }

        // Rings connect adjacent spokes at each radius with a gentle outward sag, like real silk.
        val perSpokeJitter = Array(ringCount) { FloatArray(spokeCount) { 0.9f + Random.nextFloat() * 0.2f } }
        for (r in 0 until ringCount) {
            val path = Path()
            for (s in 0 until spokeCount) {
                val radius = ringRadii[r] * perSpokeJitter[r][s]
                val x = centerX + cos(spokeAngles[s]) * radius
                val y = centerY + sin(spokeAngles[s]) * radius
                val angleDeg = Math.toDegrees(spokeAngles[s].toDouble()).toFloat()
                if (s == 0) {
                    path.moveTo(x, y)
                } else {
                    val prevRadius = ringRadii[r] * perSpokeJitter[r][s - 1]
                    val prevX = centerX + cos(spokeAngles[s - 1]) * prevRadius
                    val prevY = centerY + sin(spokeAngles[s - 1]) * prevRadius
                    val midAngle = (spokeAngles[s - 1] + spokeAngles[s]) / 2f
                    val sagRadius = (radius + prevRadius) / 2f * 1.035f
                    val sagX = centerX + cos(midAngle) * sagRadius
                    val sagY = centerY + sin(midAngle) * sagRadius
                    path.quadTo(sagX, sagY, x, y)
                }

                intersections.add(Triple(x, y, angleDeg))

                // Dew mostly collects at the silk intersections, not on every single one.
                if (Random.nextFloat() < 0.72f) {
                    val depthFactor = radius / maxRadius
                    val dropRadius = 1.4f + depthFactor * 3.2f + Random.nextFloat() * 1.2f
                    droplets.add(Droplet(x, y, dropRadius, angleDeg))
                }
            }
            addThread(path)
        }

        // Significant increase in faint, stray "gossamer" threads to create a "cobweb" effect.
        val strayCount = 15 + (25 * intensity).toInt()
        repeat(strayCount) {
            val a = intersections.random()
            val nearby = intersections.filter { p ->
                val dx = p.first - a.first
                val dy = p.second - a.second
                val distSq = dx * dx + dy * dy
                distSq > 50f && distSq < (maxRadius * 0.35f) * (maxRadius * 0.35f)
            }
            val b = nearby.randomOrNull() ?: return@repeat
            
            val isBroken = Random.nextFloat() < 0.3f
            
            val path = Path()
            path.moveTo(a.first, a.second)
            val midX = (a.first + b.first) / 2f + (Random.nextFloat() - 0.5f) * 12f
            val midY = (a.second + b.second) / 2f + (Random.nextFloat() - 0.5f) * 12f
            
            if (isBroken) {
                path.quadTo(midX, midY, midX + (Random.nextFloat() - 0.5f) * 5f, midY + (Random.nextFloat() - 0.5f) * 5f)
            } else {
                path.quadTo(midX, midY, b.first, b.second)
            }
            addThread(path, faint = true)
        }

        // Add some pollen particles trapped in the web
        pollenList.clear()
        repeat(5 + (8 * intensity).toInt()) {
            val p = intersections.random()
            val offset = (Random.nextFloat() - 0.5f) * 8f
            pollenList.add(PointF(p.first + offset, p.second + offset))
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAnimating) return
        // Sway speed increases with intensity to simulate stronger wind
        swayPhase += 0.005f + (0.008f * intensity)
        vibrationPhase += 0.45f + (0.3f * intensity)
        for (d in droplets) d.twinklePhase += d.twinkleSpeed
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (threads.isEmpty()) return

        canvas.save()
        // Amplitude increases with intensity for a more "blown" look
        val maxSway = 1.0f + 3.5f * intensity
        val swayDeg = sin(swayPhase.toDouble()).toFloat() * maxSway
        val microVib = sin(vibrationPhase.toDouble()).toFloat() * (0.05f + 0.05f * intensity)
        canvas.rotate(swayDeg + microVib, centerX, centerY)

        for (t in threads) {
            threadPaint.alpha = t.alpha
            threadPaint.strokeWidth = t.strokeWidth
            canvas.drawPath(t.path, threadPaint)
        }

        // Draw pollen particles
        for (p in pollenList) {
            pollenPaint.alpha = 100 + Random.nextInt(60)
            canvas.drawCircle(p.x, p.y, 0.8f + Random.nextFloat() * 0.6f, pollenPaint)
        }

        for (d in droplets) {
            val twinkle = (sin(d.twinklePhase.toDouble()).toFloat() + 1f) / 2f
            val glintAlpha = (140 + 115 * twinkle).toInt().coerceIn(0, 255)

            // Beads elongate slightly along the silk direction rather than sitting as perfect
            // circles — drawn in local space (translate+rotate) so the gradient isn't
            // double-transformed by the canvas matrix.
            canvas.save()
            canvas.translate(d.x, d.y)
            canvas.rotate(d.angleDeg)
            
            // Fresnel-enhanced gradient: sharp rim light on the edge opposite to the glint
            dropletPaint.shader = RadialGradient(
                -d.radius * 0.25f, -d.radius * 0.3f, d.radius * 1.3f,
                intArrayOf(
                    Color.argb(glintAlpha, 255, 255, 255), // Glint
                    Color.argb((70 * (0.5f + 0.5f * twinkle)).toInt(), 220, 235, 240), // Body
                    Color.argb((180 * (0.4f + 0.6f * twinkle)).toInt(), 255, 255, 255), // Fresnel edge
                    Color.argb(0, 220, 235, 240)
                ),
                floatArrayOf(0f, 0.45f, 0.96f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(-d.radius * 1.2f, -d.radius * 0.85f, d.radius * 1.2f, d.radius * 0.85f, dropletPaint)
            canvas.restore()
        }
        dropletPaint.shader = null
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}
