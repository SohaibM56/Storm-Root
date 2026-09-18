package com.hcr.stormroot.core.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import kotlin.random.Random

class StormOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var intensity: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    // Thin, soft, desaturated blue-gray rather than a bright glowing white-blue — real rain
    // doesn't emit light, it just catches a little of whatever's around it. Alpha and stroke
    // width are set per depth layer just before each batched draw below.
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 176, 190, 205)
        strokeCap = Paint.Cap.ROUND
    }

    private val lightningPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stormClouds = mutableListOf<StormCloud>()

    private val random = Random(seed = 1337)
    private var rainLayers: List<RainLayer> = emptyList()
    private var phase = 0f
    private var lightningAlpha = 0
    private var animator: ValueAnimator? = null

    private data class Drop(
        val xSeed: Float,
        val ySeed: Float,
        val speedMult: Float,
        val lengthMult: Float,
        val angleJitter: Float
    )

    /** One depth plane of rain. [pts] is a preallocated, reused buffer (4 floats per drop —
     *  x0,y0,x1,y1) so a whole layer draws in a single batched [Canvas.drawLines] call instead
     *  of one [Canvas.drawLine] per drop, and no array is ever allocated mid-frame. */
    private class RainLayer(
        val drops: List<Drop>,
        val pts: FloatArray,
        val strokeWidth: Float,
        val baseAlpha: Int,
        val lengthScale: Float
    )

    private data class StormCloud(val xSeed: Float, val ySeed: Float, val sizeFraction: Float, val alphaBase: Int)

    init {
        // Randomized on start()
    }

    fun start() {
        randomizeStorm()
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                updateLightning()
                invalidate()
            }
            start()
        }
    }

    private fun randomizeStorm() {
        val random = Random(System.currentTimeMillis())

        // Three depth planes: far rain is smaller, thinner and fainter; near rain is bigger,
        // bolder and more visible — so the field reads as rain filling a 3D space instead of
        // a flat layer of identical lines stuck to the screen.
        rainLayers = listOf(
            buildRainLayer(random, count = 110, strokeWidth = 1.0f, baseAlpha = 60, lengthScale = 0.6f),
            buildRainLayer(random, count = 65, strokeWidth = 1.5f, baseAlpha = 110, lengthScale = 0.9f),
            buildRainLayer(random, count = 35, strokeWidth = 2.1f, baseAlpha = 165, lengthScale = 1.2f)
        )

        stormClouds.clear()
        repeat(12) {
            stormClouds.add(StormCloud(
                xSeed = random.nextFloat(),
                ySeed = random.nextFloat() * 0.25f,
                sizeFraction = 0.3f + random.nextFloat() * 0.5f,
                alphaBase = 100 + random.nextInt(120)
            ))
        }
        invalidate()
    }

    private fun buildRainLayer(random: Random, count: Int, strokeWidth: Float, baseAlpha: Int, lengthScale: Float): RainLayer {
        val drops = List(count) {
            Drop(
                xSeed = random.nextFloat(),
                ySeed = random.nextFloat(),
                // Per-drop fall speed and length so no two drops read as identical, and the
                // field never resets in a visible, synchronized pulse.
                speedMult = 0.75f + random.nextFloat() * 0.6f,
                lengthMult = 0.7f + random.nextFloat() * 0.7f,
                // A little per-drop angle variance instead of every line falling at the exact
                // same slant.
                angleJitter = (random.nextFloat() - 0.5f) * 0.7f
            )
        }
        return RainLayer(drops, FloatArray(count * 4), strokeWidth, baseAlpha, lengthScale)
    }

    private fun updateLightning() {
        if (intensity < 0.3f) {
            lightningAlpha = 0
            return
        }

        // Chance of lightning increases with intensity
        val chance = 0.005f * intensity
        if (random.nextFloat() < chance && lightningAlpha <= 0) {
            lightningAlpha = (100 + random.nextInt(100) * intensity).toInt().coerceIn(0, 255)
        } else if (lightningAlpha > 0) {
            lightningAlpha = (lightningAlpha * 0.85f).toInt()
            if (lightningAlpha < 5) lightningAlpha = 0
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // Draw Storm Clouds at top
        if (intensity > 0f) {
            stormClouds.forEachIndexed { index, cloud ->
                val cx = cloud.xSeed * w + sin(phase * 2f * Math.PI.toFloat() + index) * 20f
                val cy = cloud.ySeed * h
                val size = w * cloud.sizeFraction
                val alpha = (cloud.alphaBase * intensity).toInt().coerceIn(0, 255)

                cloudPaint.shader = RadialGradient(
                    cx, cy, size,
                    intArrayOf(Color.argb(alpha, 40, 45, 60), Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, size, cloudPaint)
            }
        }

        // Draw Lightning flash
        if (lightningAlpha > 0) {
            lightningPaint.alpha = lightningAlpha
            canvas.drawRect(0f, 0f, w, h, lightningPaint)
        }

        // Draw Rain — each depth layer is one batched drawLines() call (not one drawLine()
        // per drop), which is the efficient way to push this many segments on Android; the
        // per-drop buffers are reused every frame, never reallocated.
        if (intensity <= 0f) return

        val baseLength = h * 0.08f
        val windShift = w * 0.05f * intensity

        rainLayers.forEach { layer ->
            // Thinning out visible drops at low intensity, rather than a hard on/off cut,
            // keeps light rain reading as light rain instead of a sparse identical pattern.
            val visibleCount = (layer.drops.size * (0.35f + 0.65f * intensity)).toInt().coerceIn(0, layer.drops.size)
            if (visibleCount <= 0) return@forEach

            var idx = 0
            for (i in 0 until visibleCount) {
                val drop = layer.drops[i]
                val x = drop.xSeed * w
                val travel = h + baseLength * 2
                val y = ((drop.ySeed + phase * drop.speedMult) % 1.0f) * travel - baseLength
                val len = baseLength * drop.lengthMult * layer.lengthScale
                val dx = -(windShift * (1f + drop.angleJitter))

                layer.pts[idx++] = x
                layer.pts[idx++] = y
                layer.pts[idx++] = x + dx
                layer.pts[idx++] = y + len
            }

            rainPaint.strokeWidth = layer.strokeWidth
            rainPaint.alpha = (layer.baseAlpha * intensity).toInt().coerceIn(0, 255)
            canvas.drawLines(layer.pts, 0, idx, rainPaint)
        }
    }
}
