package com.hcr.stormroot.core.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.withTranslation
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class WaterDropletsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    private class Droplet {
        var x = 0f
        var y = 0f
        var radius = 0f
        var life = 0f // fade-in progress, 0..1
        var highlightOffsetX = 0f
        var highlightOffsetY = 0f

        var isSliding = false
        var isSatellite = false // tiny static condensation droplet clustered near a larger one
        var vy = 0f
        var trailStartY = 0f
        var trailAlpha = 0f
        var justFinishedSliding = false // one-frame flag the view uses to spawn trail residue
        fun stretchFactor(): Float = if (isSliding) 1f + (vy / 2.2f) * 0.6f else 1f
        fun init(width: Int, height: Int) {
            x = Random.nextFloat() * width
            y = Random.nextFloat() * height
            radius = 4f + Random.nextFloat() * Random.nextFloat() * 22f // weighted toward small
            life = 0f
            highlightOffsetX = -radius * 0.32f
            highlightOffsetY = -radius * 0.38f
            isSliding = false
            isSatellite = false
            vy = 0f
            trailAlpha = 0f
        }

        fun update(width: Int, height: Int, intensity: Float) {
            if (life < 1f) life = (life + 0.03f).coerceAtMost(1f)

            if (isSliding) {
                val accel = 0.04f + 0.08f * intensity
                vy = (vy + accel + Random.nextFloat() * 0.03f).coerceAtMost(3.5f)
                y += vy
                
                x += sin(y * 0.05f) * (0.5f + 0.5f * intensity)

                if (y > height + radius) {
                    isSliding = false
                    trailAlpha = 1f
                } else if (Random.nextFloat() < 0.008f + 0.01f * intensity) {
                    isSliding = false
                    trailAlpha = 1f
                    justFinishedSliding = true
                }
            } else if (!isSatellite) {

                val slideChance = 0.0006f + 0.006f * intensity
                if (radius > 8f && Random.nextFloat() < slideChance) {
                    isSliding = true
                    trailStartY = y
                    vy = 0.2f + 0.4f * intensity
                }
            }

            if (!isSliding && trailAlpha > 0f) {
                trailAlpha = (trailAlpha - 0.006f).coerceAtLeast(0f)
            }

            if (y > height + radius + 40f) {
                init(width, height)
                y = -radius
            }
        }
    }

    private val droplets = ArrayList<Droplet>()
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var isAnimating = false

    // Gradients are built once per (bucketed) radius and reused via canvas translation instead
    // of being reallocated every frame for every droplet — onDraw runs at 60fps for ~70+ droplets.
    private val bodyGradients = HashMap<Int, RadialGradient>()
    private val highlightGradientsA = HashMap<Int, RadialGradient>()
    private val highlightGradientsB = HashMap<Int, RadialGradient>()
    private val highlightGradientsC = HashMap<Int, RadialGradient>()
    private val trailGradients = HashMap<Int, LinearGradient>()

    private fun radiusBucket(radius: Float) = (radius * 4f).toInt()

    private fun bodyGradientFor(radius: Float): RadialGradient {
        val key = radiusBucket(radius)
        return bodyGradients.getOrPut(key) {
            val r = key / 4f
            RadialGradient(
                -r * 0.15f, -r * 0.15f, r * 1.15f,
                intArrayOf(
                    Color.argb(90, 235, 245, 250),
                    Color.argb(55, 200, 222, 232),
                    Color.argb(120, 130, 165, 185)
                ),
                floatArrayOf(0f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    private fun highlightGradientFor(cache: HashMap<Int, RadialGradient>, radiusFraction: Float, radius: Float, baseAlpha: Int): RadialGradient {
        val key = radiusBucket(radius)
        return cache.getOrPut(key) {
            val r = key / 4f
            RadialGradient(
                0f, 0f, r * radiusFraction,
                Color.argb(baseAlpha, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
        }
    }

    private fun trailGradientFor(length: Float): LinearGradient {
        val key = (length / 8f).toInt().coerceAtLeast(1)
        return trailGradients.getOrPut(key) {
            val len = key * 8f
            LinearGradient(
                0f, 0f, 0f, len,
                Color.argb(0, 200, 225, 235),
                Color.argb(70, 200, 225, 235),
                Shader.TileMode.CLAMP
            )
        }
    }

    var intensity: Float = 0.7f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (isAnimating) adjustCount(targetCount())
        }

    private fun targetCount(): Int = (18 + 55 * intensity).toInt()

    fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        setupIfReady()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopAnimation() {
        isAnimating = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun adjustCount(target: Int) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        while (droplets.size < target) {
            val d = Droplet()
            d.init(w, h)
            droplets.add(d)
        }
        while (droplets.size > target) {
            droplets.removeAt(droplets.size - 1)
        }
    }

    private fun setupIfReady() {
        val w = width
        val h = height
        if (w == 0 || h == 0 || !isAnimating) return
        if (droplets.isEmpty()) {
            val hosts = ArrayList<Droplet>()
            repeat(targetCount()) {
                val d = Droplet()
                d.init(w, h)
                d.life = 1f
                droplets.add(d)
                if (d.radius > 11f) hosts.add(d)
            }
            for (host in hosts) {
                if (Random.nextFloat() > 0.6f) continue
                repeat(1 + Random.nextInt(2)) {
                    val satellite = Droplet()
                    val angle = Random.nextFloat() * (Math.PI * 2).toFloat()
                    val dist = host.radius * (1.1f + Random.nextFloat() * 0.6f)
                    satellite.x = host.x + cos(angle) * dist
                    satellite.y = host.y + sin(angle) * dist
                    satellite.radius = 1.2f + Random.nextFloat() * 2.3f
                    satellite.life = 1f
                    satellite.isSatellite = true
                    satellite.highlightOffsetX = -satellite.radius * 0.32f
                    satellite.highlightOffsetY = -satellite.radius * 0.38f
                    droplets.add(satellite)
                }
            }
        } else {
            adjustCount(targetCount())
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setupIfReady()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAnimating) return
        val w = width
        val h = height
        if (w > 0 && h > 0) {
            val residueCap = targetCount() + (40 * intensity).toInt()
            
            var i = 0
            while (i < droplets.size) {
                val d1 = droplets[i]
                if (d1.isSliding) {
                    var j = i + 1
                    while (j < droplets.size) {
                        val d2 = droplets[j]
                        val dx = d1.x - d2.x
                        val dy = d1.y - d2.y
                        val distSq = dx * dx + dy * dy
                        val mergeThreshold = (d1.radius + d2.radius) * 0.85f
                        if (distSq < mergeThreshold * mergeThreshold) {
                            d1.radius = sqrt(d1.radius * d1.radius + d2.radius * d2.radius).coerceAtMost(35f)
                            d1.vy = (d1.vy + 0.2f * intensity + 0.1f).coerceAtMost(3.5f)
                            droplets.removeAt(j)
                            continue
                        }
                        j++
                    }
                }
                
                d1.update(w, h, intensity)
                if (d1.justFinishedSliding) {
                    d1.justFinishedSliding = false
                    if (droplets.size < residueCap) {
                        repeat(1 + Random.nextInt(2)) {
                            val residue = Droplet()
                            residue.x = d1.x + (Random.nextFloat() - 0.5f) * 6f
                            residue.y = d1.trailStartY + Random.nextFloat() * (d1.y - d1.trailStartY)
                            residue.radius = 1.2f + Random.nextFloat() * 2f
                            residue.life = 1f
                            residue.isSatellite = true
                            residue.highlightOffsetX = -residue.radius * 0.32f
                            residue.highlightOffsetY = -residue.radius * 0.38f
                            droplets.add(residue)
                        }
                    }
                }
                i++
            }
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (i in droplets.indices) {
            val d = droplets[i]
            val alpha = d.life
            val globalAlpha = (255 * alpha).toInt().coerceIn(0, 255)

            val trailTop = d.trailStartY
            if (d.isSliding || d.trailAlpha > 0f) {
                val trailBottom = d.y
                if (trailBottom > trailTop) {
                    val length = trailBottom - trailTop
                    trailPaint.shader = trailGradientFor(length)
                    trailPaint.alpha = (255 * alpha * (if (d.isSliding) 1f else d.trailAlpha)).toInt().coerceIn(0, 255)
                    canvas.withTranslation(d.x, trailTop) {
                        drawRect(-d.radius * 0.16f, 0f, d.radius * 0.16f, length, trailPaint)
                    }
                }
            }

            bodyPaint.shader = bodyGradientFor(d.radius)
            bodyPaint.alpha = globalAlpha
            val stretch = d.stretchFactor()
            canvas.withTranslation(d.x, d.y) {
                if (stretch > 1.01f) {
                    val rx = d.radius * (1f / sqrt(stretch))
                    val ry = d.radius * stretch
                    drawOval(-rx, -ry, rx, ry, bodyPaint)
                } else {
                    drawCircle(0f, 0f, d.radius, bodyPaint)
                }
            }

            highlightPaint.shader = highlightGradientFor(highlightGradientsA, 0.55f, d.radius, 220)
            highlightPaint.alpha = globalAlpha
            canvas.withTranslation(d.x + d.highlightOffsetX, d.y + d.highlightOffsetY) {
                drawCircle(0f, 0f, d.radius * 0.55f, highlightPaint)
            }

            if (d.radius > 5f) {
                highlightPaint.shader = highlightGradientFor(highlightGradientsB, 0.45f, d.radius, 110)
                highlightPaint.alpha = globalAlpha
                canvas.withTranslation(d.x - d.highlightOffsetX * 1.1f, d.y - d.highlightOffsetY * 1.1f) {
                    drawCircle(0f, 0f, d.radius * 0.45f, highlightPaint)
                }
            }

            if (d.radius > 6f) {
                highlightPaint.shader = highlightGradientFor(highlightGradientsC, 0.28f, d.radius, 70)
                highlightPaint.alpha = globalAlpha
                canvas.withTranslation(d.x - d.highlightOffsetX * 0.7f, d.y - d.highlightOffsetY * 0.4f) {
                    drawCircle(0f, 0f, d.radius * 0.28f, highlightPaint)
                }
            }
        }
        bodyPaint.shader = null
        highlightPaint.shader = null
        trailPaint.shader = null
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}
