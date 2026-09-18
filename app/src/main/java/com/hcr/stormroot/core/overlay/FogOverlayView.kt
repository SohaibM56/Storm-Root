package com.hcr.stormroot.core.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random
import androidx.core.graphics.createBitmap

class FogOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var intensity: Float = 0.5f
        set(value) {
            val coerced = value.coerceIn(0f, 1f)
            if (field == coerced) return
            field = coerced
            bake()
        }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val patchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var patches: List<Patch> = emptyList()
    private var cachedBitmap: Bitmap? = null
    private var isActive = false

    private data class Patch(val xFraction: Float, val yFraction: Float, val radiusFraction: Float, val alphaBase: Int)

    fun start() {
        isActive = true
        if (patches.isEmpty()) randomizePatches()
        bake()
    }

    fun stop() {
        isActive = false
        cachedBitmap?.recycle()
        cachedBitmap = null
        invalidate()
    }

    private fun randomizePatches() {
        val random = Random(System.currentTimeMillis())

        patches = List(16) {
            Patch(
                xFraction = random.nextFloat(),
                yFraction = random.nextFloat(),
                radiusFraction = 0.28f + random.nextFloat() * 0.5f,
                alphaBase = 40 + random.nextInt(70)
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isActive) bake()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun bake() {
        val w = width
        val h = height
        if (!isActive || w == 0 || h == 0 || intensity <= 0f) {
            cachedBitmap?.recycle()
            cachedBitmap = null
            invalidate()
            return
        }
        if (patches.isEmpty()) randomizePatches()

        val bitmap = cachedBitmap?.takeIf { it.width == w && it.height == h && !it.isRecycled }
            ?: createBitmap(w, h).also { fresh ->
                cachedBitmap?.recycle()
                cachedBitmap = fresh
            }
        bitmap.eraseColor(Color.TRANSPARENT)
        val bakeCanvas = Canvas(bitmap)

        val washAlpha = (110 * intensity).toInt().coerceIn(0, 255)
        bakeCanvas.drawColor(Color.argb(washAlpha, 232, 236, 234))

        patches.forEach { patch ->
            val cx = patch.xFraction * w
            val cy = patch.yFraction * h
            val radius = w * patch.radiusFraction
            val alpha = (patch.alphaBase * intensity).toInt().coerceIn(0, 255)
            patchPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(Color.argb(alpha, 235, 240, 238), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP
            )
            bakeCanvas.drawCircle(cx, cy, radius, patchPaint)
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = cachedBitmap ?: return
        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
    }
}
