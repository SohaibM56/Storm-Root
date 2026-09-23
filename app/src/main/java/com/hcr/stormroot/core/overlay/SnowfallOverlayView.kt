package com.hcr.stormroot.core.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation

class SnowfallOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    private class Snowflake {
        var x = 0f
        var y = 0f
        var radius = 0f
        var depth = 0f // 0 = far (small, slow, dim), 1 = near (big, fast, bright)
        var vy = 0f
        var swayPhase = 0f
        var swaySpeed = 0f
        var swayAmplitude = 0f
        var alpha = 255
        var rotation = 0f
        var rotationSpeed = 0f
        var isCrystal = false // occasional larger flake drawn as a detailed 6-armed ice crystal

        fun init(width: Int, height: Int, spawnAtTop: Boolean = false) {
            depth = Random.nextFloat()
            x = Random.nextFloat() * width
            y = if (spawnAtTop) -20f - Random.nextFloat() * height * 0.3f else Random.nextFloat() * height

            radius = 1.5f + depth * 4.5f + Random.nextFloat() * 1.2f
            vy = 0.5f + depth * 2.2f + Random.nextFloat() * 0.4f
            swayPhase = Random.nextFloat() * Math.PI.toFloat() * 2f
            swaySpeed = 0.008f + Random.nextFloat() * 0.018f
            swayAmplitude = 0.6f + depth * 1.4f
            alpha = (110 + depth * 130f).toInt().coerceIn(0, 255)
            rotation = Random.nextFloat() * 360f
            rotationSpeed = (Random.nextFloat() - 0.5f) * 1.2f
            isCrystal = depth > 0.72f && Random.nextFloat() < 0.35f
        }

        fun update(width: Int, height: Int, windEffect: Float, groundHeightAt: (Float) -> Float): Boolean {
            swayPhase += swaySpeed
            x += sin(swayPhase.toDouble()).toFloat() * swayAmplitude * 0.5f + windEffect * (0.3f + depth * 0.7f)
            y += vy
            rotation += rotationSpeed

            if (x < -20f) x = width + 20f
            if (x > width + 20f) x = -20f

            val surfaceY = height - groundHeightAt(x)
            if (y >= surfaceY) {
                y = surfaceY
                return true
            }
            if (y > height + 20f) {
                init(width, height, spawnAtTop = true)
            }
            return false
        }
    }

    private val flakes = ArrayList<Snowflake>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val crystalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val blurNear = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    private val blurFar = BlurMaskFilter(2.5f, BlurMaskFilter.Blur.NORMAL)

    private var isAnimating = false
    private var globalWindTime = 0f
    private var gustPhase = 0f

    private var groundBitmap: Bitmap? = null
    private var groundCanvas: Canvas? = null
    private val groundBinCount = 36
    private var groundHeights = FloatArray(groundBinCount)
    private var groundBinWidth = 0f
    private var maxPileHeight = 0f

    // Cached by radius bucket so onDraw doesn't allocate a RadialGradient per flake per frame
    // (up to 150 flakes at full intensity); alpha is applied separately via paint.alpha.
    private val flakeGradients = HashMap<Int, RadialGradient>()
    private val settleGradients = HashMap<Int, RadialGradient>()

    private fun flakeGradientFor(radius: Float): RadialGradient {
        val key = (radius * 4f).toInt()
        return flakeGradients.getOrPut(key) {
            val r = key / 4f
            RadialGradient(
                0f, 0f, r * 1.6f,
                intArrayOf(Color.WHITE, Color.WHITE, Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    private fun settleGradientFor(radius: Float): RadialGradient {
        val key = (radius * 4f).toInt()
        return settleGradients.getOrPut(key) {
            val r = key / 4f
            RadialGradient(
                0f, 0f, r * 2.2f,
                intArrayOf(Color.argb(230, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    var intensity: Float = 0.7f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (isAnimating) adjustFlakeCount(targetCount())
        }

    private fun targetCount(): Int = (40 + 110 * intensity).toInt()

    private fun binIndexForX(x: Float): Int {
        if (groundBinWidth <= 0f) return 0
        return (x / groundBinWidth).toInt().coerceIn(0, groundBinCount - 1)
    }

    private fun groundHeightAt(x: Float): Float = groundHeights[binIndexForX(x)]

    fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        setupFlakesIfReady()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopAnimation() {
        isAnimating = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun adjustFlakeCount(target: Int) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        while (flakes.size < target) {
            val f = Snowflake()
            f.init(w, h)
            flakes.add(f)
        }
        while (flakes.size > target) {
            flakes.removeAt(flakes.size - 1)
        }
    }

    private fun setupFlakesIfReady() {
        val w = width
        val h = height
        if (w == 0 || h == 0 || !isAnimating) return
        if (flakes.isEmpty()) {
            repeat(targetCount()) {
                val f = Snowflake()
                f.init(w, h)
                flakes.add(f)
            }
        } else {
            adjustFlakeCount(targetCount())
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) groundBinWidth = w.toFloat() / groundBinCount
        if (h > 0) maxPileHeight = h * 0.09f
        groundHeights.fill(0f)
        if (w > 0 && h > 0) {
            groundBitmap?.recycle()
            val bmp = createBitmap(w, h)
            groundBitmap = bmp
            groundCanvas = Canvas(bmp)
        }
        setupFlakesIfReady()
    }

    private fun settleOnGround(f: Snowflake) {
        val bin = binIndexForX(f.x)
        val footprint = 0.7f + f.radius * 0.5f
        groundHeights[bin] = (groundHeights[bin] + footprint).coerceAtMost(maxPileHeight)
        if (bin > 0) groundHeights[bin - 1] = (groundHeights[bin - 1] + footprint * 0.35f).coerceAtMost(maxPileHeight)
        if (bin < groundBinCount - 1) groundHeights[bin + 1] = (groundHeights[bin + 1] + footprint * 0.35f).coerceAtMost(maxPileHeight)

        val gc = groundCanvas ?: return
        val stampPaint = paint
        stampPaint.shader = settleGradientFor(f.radius)
        stampPaint.alpha = 255
        gc.withTranslation(f.x, f.y) {
            drawCircle(0f, 0f, f.radius * 2.2f, stampPaint)
        }
        stampPaint.shader = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAnimating) return
        val w = width
        val h = height
        if (w > 0 && h > 0) {
            globalWindTime += 0.01f
            gustPhase += 0.0016f

            val gustEnvelope = ((sin(gustPhase.toDouble()).toFloat() + 1f) / 2f).pow(3)
            val wind = sin(globalWindTime.toDouble()).toFloat() * 0.4f + gustEnvelope * 2.6f

            for (i in flakes.indices) {
                if (flakes[i].update(w, h, wind, ::groundHeightAt)) {
                    settleOnGround(flakes[i])
                    flakes[i].init(w, h, spawnAtTop = true)
                }
            }
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        groundBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (flakes.isEmpty()) return
        for (i in flakes.indices) {
            val f = flakes[i]
            if (f.isCrystal) {
                val glint = (sin(f.rotation.toDouble() * 0.05).toFloat() + 1f) / 2f
                crystalPaint.alpha = (f.alpha * (0.7f + 0.3f * glint)).toInt().coerceIn(0, 255)
                drawCrystal(canvas, f)
            } else {
                paint.alpha = f.alpha

                paint.maskFilter = when {
                    f.depth > 0.85f -> blurNear
                    f.depth < 0.25f -> blurFar
                    else -> null
                }

                paint.shader = flakeGradientFor(f.radius)

                val speedFactor = f.vy / 2.2f // Normalized roughly to 0..1
                if (speedFactor > 0.7f) {
                    val stretch = f.radius * speedFactor * 1.5f
                    canvas.withTranslation(f.x, f.y) {
                        drawRoundRect(-f.radius, -stretch, f.radius, stretch, f.radius, f.radius, paint)
                    }
                } else {
                    canvas.withTranslation(f.x, f.y) {
                        drawCircle(0f, 0f, f.radius * 1.6f, paint)
                    }
                }
            }
        }
        paint.shader = null
        paint.maskFilter = null
    }

    private fun drawCrystal(canvas: Canvas, f: Snowflake) {
        canvas.withTranslation(f.x, f.y) {
            rotate(f.rotation)
            crystalPaint.alpha = f.alpha
            val armLength = f.radius * 2.1f
            val branchLength = armLength * 0.32f
            repeat(6) { arm ->
                drawLine(0f, 0f, 0f, -armLength, crystalPaint)
                val branchY = -armLength * 0.55f
                drawLine(0f, branchY, -branchLength, branchY - branchLength, crystalPaint)
                drawLine(0f, branchY, branchLength, branchY - branchLength, crystalPaint)
                rotate(60f)
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        groundBitmap?.recycle()
        groundBitmap = null
        groundCanvas = null
        super.onDetachedFromWindow()
    }
}
