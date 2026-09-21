package com.hcr.stormroot.core.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

class ButterflyOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    private var globalWindTime = 0f

    private enum class AnchorKind { FLOWER, LEAF }
    private class Anchor(val x: Float, val y: Float, val kind: AnchorKind, val scale: Float, val rotation: Float, val hue: Int)

    private class ButterflyParticle {
        enum class State { FLYING, GLIDING, RESTING }
        var state = State.FLYING
        var stateTime = 0f

        var x = 0f
        var y = 0f
        var speedX = 0f
        var speedY = 0f
        var wingPhase = 0f
        var wingSpeed = 0f
        var baseScale = 1f
        var scale = 1f
        var zPhase = 0f
        var zSpeed = 0f
        var baseAlpha = 255
        var color = 0
        var angle = 0f
        var targetAngle = 0f
        var aspectDifference = 1f
        var flapRandomOffset = 0f
        var bankTilt = 1f
        var forwardSpeed = 2f

        // Where a RESTING butterfly is heading to perch, and whether it has arrived.
        var restTargetX = 0f
        var restTargetY = 0f
        var hasRestTarget = false
        var isLanded = false

        fun init(width: Int, height: Int, spawnAtEdge: Boolean = false) {
            if (spawnAtEdge) {
                val edge = Random.nextInt(4)
                val pad = 150f
                when (edge) {
                    0 -> { // Left
                        x = -pad
                        y = Random.nextFloat() * height
                        targetAngle = 45f + Random.nextFloat() * 90f
                    }
                    1 -> { // Right
                        x = width + pad
                        y = Random.nextFloat() * height
                        targetAngle = 225f + Random.nextFloat() * 90f
                    }
                    2 -> { // Top
                        x = Random.nextFloat() * width
                        y = -pad
                        targetAngle = 135f + Random.nextFloat() * 90f
                    }
                    3 -> { // Bottom
                        x = Random.nextFloat() * width
                        y = height + pad
                        targetAngle = 315f + Random.nextFloat() * 90f
                    }
                }
            } else {
                x = Random.nextFloat() * width
                y = Random.nextFloat() * height
                targetAngle = Random.nextFloat() * 360f
            }
            
            forwardSpeed = 1.2f + Random.nextFloat() * 1.5f
            wingPhase = Random.nextFloat() * 100f
            wingSpeed = 0.10f + Random.nextFloat() * 0.10f
            baseScale = 0.8f + Random.nextFloat() * 1.2f
            scale = baseScale
            zPhase = Random.nextFloat() * 50f
            zSpeed = 0.02f + Random.nextFloat() * 0.04f
            baseAlpha = 140 + Random.nextInt(100)
            aspectDifference = 0.85f + Random.nextFloat() * 0.3f 
            flapRandomOffset = Random.nextFloat() * 0.2f
            bankTilt = 1f
            state = State.FLYING
            stateTime = 50f + Random.nextFloat() * 100f
            
            val colors = intArrayOf(
                Color.argb(baseAlpha, 255, 125, 0),    // Vivid Monarch Orange
                Color.argb(baseAlpha, 255, 185, 0),    // Wild Tiger Gold
                Color.argb(baseAlpha, 0, 150, 240),    // Blue Morpho Cerulean
                Color.argb(baseAlpha, 190, 75, 210),   // Forest Emerald Violet
                Color.argb(baseAlpha, 245, 242, 235),  // Velvet White Cabbage
                Color.argb(baseAlpha, 139, 195, 74),   // Pale Leaf Green
                Color.argb(baseAlpha, 233, 30, 99),    // Rose Pink Flutter
                Color.argb(baseAlpha, 93, 64, 55),     // Organic Bark Wood Brown
                Color.argb(baseAlpha, 0, 150, 136)     // Deep Teal Peacock
            )
            color = colors[Random.nextInt(colors.size)]
            angle = targetAngle
            
            val rad = Math.toRadians(angle.toDouble())
            speedX = sin(rad).toFloat() * forwardSpeed
            speedY = -cos(rad).toFloat() * forwardSpeed
        }

        fun update(width: Int, height: Int, windEffect: Float, anchors: List<Anchor>) {
            stateTime -= 1f
            if (stateTime <= 0) {
                val previousState = state
                state = when (state) {
                    State.FLYING -> if (Random.nextFloat() > 0.7f) State.GLIDING else State.RESTING
                    State.GLIDING -> if (Random.nextFloat() > 0.4f) State.FLYING else State.RESTING
                    State.RESTING -> State.FLYING
                }
                stateTime = when (state) {
                    State.FLYING -> 40f + Random.nextFloat() * 80f
                    State.GLIDING -> 30f + Random.nextFloat() * 60f
                    // Real butterflies linger on a flower far longer than they glide.
                    State.RESTING -> 150f + Random.nextFloat() * 220f
                }
                if (state == State.RESTING) {
                    isLanded = false
                    hasRestTarget = false
                } else if (previousState == State.RESTING) {
                    // Leaving a perch — pick a fresh wandering heading instead of reusing the
                    // stale pre-landing target, which would cause an abrupt snap-turn.
                    targetAngle = Random.nextFloat() * 360f
                }
            }

            if (state == State.RESTING) {
                if (!isLanded) {
                    if (anchors.isEmpty()) {
                        // No flowers/leaves on screen yet — settle in place rather than hover.
                        isLanded = true
                        restTargetX = x
                        restTargetY = y
                    } else {
                        if (!hasRestTarget) {
                            val nearest = anchors.minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }!!
                            restTargetX = nearest.x
                            restTargetY = nearest.y - 6f // perch just above the anchor's center
                            hasRestTarget = true
                        }
                        val dx = restTargetX - x
                        val dy = restTargetY - y
                        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        if (dist < 10f) {
                            isLanded = true
                            x = restTargetX
                            y = restTargetY
                            speedX = 0f
                            speedY = 0f
                        } else {
                            val desiredAngle = Math.toDegrees(Math.atan2(dx.toDouble(), -dy.toDouble())).toFloat()
                            var turnDelta = desiredAngle - angle
                            while (turnDelta > 180) turnDelta -= 360
                            while (turnDelta < -180) turnDelta += 360
                            angle += turnDelta * 0.1f
                            if (angle < 0) angle += 360f
                            if (angle >= 360) angle -= 360f

                            val approachSpeed = (dist * 0.05f).coerceIn(0.3f, 1.2f)
                            val rad = Math.toRadians(angle.toDouble())
                            speedX = sin(rad).toFloat() * approachSpeed
                            speedY = -cos(rad).toFloat() * approachSpeed
                            x += speedX
                            y += speedY

                            zPhase += zSpeed
                            scale = baseScale + sin(zPhase.toDouble()).toFloat() * 0.1f
                            wingPhase += wingSpeed * 0.6f
                            bankTilt = 1f
                        }
                    }
                }
                if (isLanded) {
                    // Gentle idle wing motion while perched, with only a whisper of wind drift.
                    wingPhase += 0.015f
                    x += windEffect * 0.05f
                }
            } else {
                var turnDelta = targetAngle - angle
                while (turnDelta > 180) turnDelta -= 360
                while (turnDelta < -180) turnDelta += 360
                
                angle += turnDelta * 0.08f 
                if (angle < 0) angle += 360f
                if (angle >= 360) angle -= 360f

                val rad = Math.toRadians(angle.toDouble())
                val currentForwardSpeed = if (state == State.GLIDING) forwardSpeed * 0.6f else forwardSpeed
                speedX = sin(rad).toFloat() * currentForwardSpeed
                speedY = -cos(rad).toFloat() * currentForwardSpeed

                x += speedX + windEffect
                y += speedY
                
                zPhase += zSpeed
                scale = baseScale + sin(zPhase.toDouble()).toFloat() * 0.22f

                val dynamicVelocity = sqrt((speedX * speedX + speedY * speedY).toDouble()).toFloat()
                val flapMultiplier = if (state == State.GLIDING) 0.15f else 0.5f + dynamicVelocity * 0.5f
                wingPhase += (wingSpeed * flapMultiplier) + (0.04f * Random.nextFloat()) + flapRandomOffset

                if (Random.nextFloat() > 0.96f) {
                    val headingChange = (Random.nextFloat() - 0.5f) * 70f 
                    targetAngle = (angle + headingChange)
                    if (state == State.FLYING) forwardSpeed = 1.2f + Random.nextFloat() * 1.8f
                }

                bankTilt = (1.0f - (abs(turnDelta) / 90f)).coerceIn(0.4f, 1.0f)
            }

            // Wrapping/Respawn logic: if it flies significantly off screen, respawn it at a random edge
            val out = 180f
            if (x < -out || x > width + out || y < -out || y > height + out) {
                init(width, height, spawnAtEdge = true)
            }
        }
    }

    private val particles = ArrayList<ButterflyParticle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        alpha = 40
    }
    private val path = Path()
    private var isAnimating = false

    private val anchors = ArrayList<Anchor>()
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val anchorPath = Path()

    var intensity: Float = 0.7f
        set(value) {
            field = value.coerceIn(0f, 1f)
            // Update existing particles' alpha based on new intensity
            particles.forEach { 
                it.baseAlpha = (160 + (95 * field)).toInt().coerceIn(0, 255)
            }
            // Dynamically adjust count if already animating
            if (isAnimating) {
                val targetCount = (15 + 45 * field).toInt() // Increased from 8-28 to 15-60 butterflies for a rich density range
                adjustParticleCount(targetCount)
            }
        }

    fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        setupParticlesIfReady()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopAnimation() {
        isAnimating = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun adjustParticleCount(targetCount: Int) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        while (particles.size < targetCount) {
            val p = ButterflyParticle()
            p.init(w, h)
            particles.add(p)
        }
        while (particles.size > targetCount) {
            particles.removeAt(particles.size - 1)
        }
    }

    private fun setupParticlesIfReady() {
        val w = width
        val h = height
        if (w == 0 || h == 0 || !isAnimating) return

        val targetCount = (15 + 45 * intensity).toInt()
        if (particles.isEmpty()) {
            repeat(targetCount) {
                val p = ButterflyParticle()
                p.init(w, h)
                particles.add(p)
            }
        } else {
            adjustParticleCount(targetCount)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildAnchors(w, h)
        setupParticlesIfReady()
    }

    /** Scatters a handful of flowers/leaves for butterflies to fly between and land on. */
    private fun rebuildAnchors(w: Int, h: Int) {
        anchors.clear()
        if (w == 0 || h == 0) return
        val count = 8 + Random.nextInt(6)
        repeat(count) {
            val x = Random.nextFloat() * w
            // Biased toward the lower two-thirds — foliage sits below flight height.
            val y = h * (0.35f + Random.nextFloat() * 0.6f)
            val kind = if (Random.nextFloat() < 0.55f) AnchorKind.FLOWER else AnchorKind.LEAF
            anchors.add(Anchor(x, y, kind, 0.7f + Random.nextFloat() * 0.8f, Random.nextFloat() * 360f, Random.nextInt(4)))
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAnimating) return
        
        val w = width
        val h = height
        if (w > 0 && h > 0) {
            // Update global atmospheric fluid wind phase time counter
            globalWindTime += 0.03f
            val calculatedWind = sin(globalWindTime.toDouble()).toFloat() * 0.8f

            for (i in 0 until particles.size) {
                particles[i].update(w, h, calculatedWind, anchors)
            }
            invalidate()
        }
        
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawAnchors(canvas)
        if (particles.isEmpty()) return

        for (i in 0 until particles.size) {
            val p = particles[i]
            
            // 1. Draw Altitude-Aware Shadow Pass
            if (p.state != ButterflyParticle.State.RESTING) {
                canvas.save()
                
                // Shadow drifts further, scales up, and fades as the butterfly "climbs" (higher scale)
                val heightFactor = (p.scale - p.baseScale + 0.25f) / 0.5f 
                val shadowOffset = (12f + 25f * heightFactor) * p.scale
                val shadowAlpha = (45 * (1f - heightFactor * 0.5f)).toInt().coerceIn(0, 255)
                
                shadowPaint.alpha = shadowAlpha
                
                canvas.translate(p.x + shadowOffset, p.y + shadowOffset)
                canvas.rotate(p.angle)
                canvas.scale(p.scale * p.bankTilt * (1f + heightFactor * 0.1f), p.scale * (1f + heightFactor * 0.1f))
                
                drawButterflyShapes(canvas, p, shadowPaint)
                canvas.restore()
            }

            // 2. Draw Butterfly Pass with Dynamic Lighting
            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.angle)
            
            val effectiveBank = if (p.state == ButterflyParticle.State.RESTING) 1f else p.bankTilt
            canvas.scale(p.scale * effectiveBank, p.scale)
            
            drawButterflyShapes(canvas, p, paint)
            canvas.restore()
        }
    }

    private fun drawAnchors(canvas: Canvas) {
        for (a in anchors) {
            canvas.save()
            canvas.translate(a.x, a.y)
            canvas.rotate(a.rotation)
            canvas.scale(a.scale, a.scale)
            when (a.kind) {
                AnchorKind.FLOWER -> drawFlower(canvas, a.hue)
                AnchorKind.LEAF -> drawSmallLeaf(canvas)
            }
            canvas.restore()
        }
    }

    private fun drawFlower(canvas: Canvas, hue: Int) {
        val petalColors = intArrayOf(
            Color.rgb(255, 170, 200), // pink
            Color.rgb(255, 210, 90),  // yellow
            Color.rgb(200, 160, 255), // lavender
            Color.rgb(255, 255, 255)  // white
        )
        anchorPaint.color = petalColors[hue % petalColors.size]
        for (i in 0 until 5) {
            val petalAngle = Math.toRadians(i * 72.0)
            val px = (10f * cos(petalAngle)).toFloat()
            val py = (10f * sin(petalAngle)).toFloat()
            canvas.drawCircle(px, py, 7f, anchorPaint)
        }
        anchorPaint.color = Color.rgb(255, 205, 60)
        canvas.drawCircle(0f, 0f, 5f, anchorPaint)
    }

    private fun drawSmallLeaf(canvas: Canvas) {
        anchorPaint.color = Color.rgb(90, 140, 70)
        anchorPath.reset()
        anchorPath.moveTo(0f, -14f)
        anchorPath.cubicTo(-10f, -6f, -10f, 8f, 0f, 16f)
        anchorPath.cubicTo(10f, 8f, 10f, -6f, 0f, -14f)
        canvas.drawPath(anchorPath, anchorPaint)
    }

    private fun drawButterflyShapes(canvas: Canvas, p: ButterflyParticle, targetPaint: Paint) {
        val originalColor = targetPaint.color
        val originalAlpha = targetPaint.alpha
        val originalStyle = targetPaint.style
        val originalStrokeWidth = targetPaint.strokeWidth
        
        // Dynamic wing flap factor based on sinusoidal wave phase
        val flapFactor = abs(sin(p.wingPhase.toDouble())).toFloat()

        if (targetPaint == paint) {
            // DYNAMIC LIGHTING: Brighten color when wings are open, darken when closed
            val lightFactor = 0.8f + (flapFactor * 0.4f) 
            val r = (Color.red(p.color) * lightFactor).toInt().coerceIn(0, 255)
            val g = (Color.green(p.color) * lightFactor).toInt().coerceIn(0, 255)
            val b = (Color.blue(p.color) * lightFactor).toInt().coerceIn(0, 255)
            targetPaint.color = Color.argb(p.baseAlpha, r, g, b)
        }

        // 1. Draw Wing Base Shapes
        path.reset()
        path.moveTo(0f, 0f)
        path.cubicTo(-15f * flapFactor, -30f * p.aspectDifference, -40f * flapFactor, -25f, -30f * flapFactor, -5f)
        path.cubicTo(-35f * flapFactor, 10f, -15f * flapFactor, 22f * p.aspectDifference, 0f, 0f)
        canvas.drawPath(path, targetPaint)

        path.reset()
        path.moveTo(0f, 0f)
        path.cubicTo(15f * flapFactor, -30f * p.aspectDifference, 40f * flapFactor, -25f, 30f * flapFactor, -5f)
        path.cubicTo(35f * flapFactor, 10f, 15f * flapFactor, 22f * p.aspectDifference, 0f, 0f)
        canvas.drawPath(path, targetPaint)

        // 2. Draw Realistic Patterns (only for main pass)
        if (targetPaint == paint) {
            // Draw Wing Edge Borders (Many butterflies have dark borders)
            targetPaint.style = Paint.Style.STROKE
            targetPaint.strokeWidth = 1.5f
            targetPaint.color = Color.argb((p.baseAlpha * 0.7f).toInt(), 10, 10, 10)
            
            // Re-draw paths as stroke for borders
            path.reset()
            path.moveTo(0f, 0f)
            path.cubicTo(-15f * flapFactor, -30f * p.aspectDifference, -40f * flapFactor, -25f, -30f * flapFactor, -5f)
            path.cubicTo(-35f * flapFactor, 10f, -15f * flapFactor, 22f * p.aspectDifference, 0f, 0f)
            canvas.drawPath(path, targetPaint)

            path.reset()
            path.moveTo(0f, 0f)
            path.cubicTo(15f * flapFactor, -30f * p.aspectDifference, 40f * flapFactor, -25f, 30f * flapFactor, -5f)
            path.cubicTo(35f * flapFactor, 10f, 15f * flapFactor, 22f * p.aspectDifference, 0f, 0f)
            canvas.drawPath(path, targetPaint)

            // Draw White "Pearls" (Tiny dots on the dark borders like Monarchs)
            targetPaint.style = Paint.Style.FILL
            targetPaint.color = Color.argb((p.baseAlpha * 0.8f).toInt(), 255, 255, 240)
            val dotSize = 1.2f
            // Top Edge Dots
            canvas.drawCircle(-25f * flapFactor, -22f * p.aspectDifference, dotSize, targetPaint)
            canvas.drawCircle(-32f * flapFactor, -12f, dotSize, targetPaint)
            canvas.drawCircle(25f * flapFactor, -22f * p.aspectDifference, dotSize, targetPaint)
            canvas.drawCircle(32f * flapFactor, -12f, dotSize, targetPaint)
            // Bottom Edge Dots
            canvas.drawCircle(-22f * flapFactor, 12f * p.aspectDifference, dotSize, targetPaint)
            canvas.drawCircle(22f * flapFactor, 12f * p.aspectDifference, dotSize, targetPaint)

            // Draw Internal Veins (Atmospheric detail)
            targetPaint.color = Color.argb((p.baseAlpha * 0.4f).toInt(), 10, 10, 10)
            targetPaint.style = Paint.Style.STROKE
            targetPaint.strokeWidth = 0.8f
            path.reset()
            path.moveTo(0f, 0f); path.lineTo(-22f * flapFactor, -18f)
            path.moveTo(-8f * flapFactor, -4f); path.quadTo(-20f * flapFactor, -8f, -26f * flapFactor, -4f)
            path.moveTo(0f, 0f); path.lineTo(22f * flapFactor, -18f)
            path.moveTo(8f * flapFactor, -4f); path.quadTo(20f * flapFactor, -8f, 26f * flapFactor, -4f)
            canvas.drawPath(path, targetPaint)

            // Draw Eyespots (Organic defense patterns)
            targetPaint.style = Paint.Style.FILL
            targetPaint.color = Color.argb((p.baseAlpha * 0.6f).toInt(), 30, 30, 30)
            canvas.drawCircle(-18f * flapFactor, -8f, 2.5f, targetPaint)
            canvas.drawCircle(18f * flapFactor, -8f, 2.5f, targetPaint)
            targetPaint.color = Color.argb((p.baseAlpha * 0.5f).toInt(), 255, 255, 255)
            canvas.drawCircle(-18f * flapFactor, -8f, 1f, targetPaint)
            canvas.drawCircle(18f * flapFactor, -8f, 1f, targetPaint)

            targetPaint.color = Color.argb(p.baseAlpha, 20, 20, 20)
        }

        // 3. Draw Body Anatomy
        targetPaint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(0f, -15f)
        path.quadTo(-2f, 0f, -1f, 12f)
        path.lineTo(1f, 12f)
        path.quadTo(2f, 0f, 0f, -15f)
        canvas.drawPath(path, targetPaint)

        // Draw circular head distinct from the body thorax
        canvas.drawCircle(0f, -18f, 3f, targetPaint)

        // 4. Antennas (only for main pass for visibility)
        if (targetPaint == paint) {
            targetPaint.style = Paint.Style.STROKE
            targetPaint.strokeWidth = 0.8f
            
            path.reset()
            path.moveTo(0f, -18f)
            path.quadTo(-4f, -24f, -8f, -26f)
            canvas.drawPath(path, targetPaint)
            
            path.reset()
            path.moveTo(0f, -18f)
            path.quadTo(4f, -24f, 8f, -26f)
            canvas.drawPath(path, targetPaint)
            
            targetPaint.style = Paint.Style.FILL
        }

        targetPaint.color = originalColor
        targetPaint.alpha = originalAlpha
        targetPaint.style = originalStyle
        targetPaint.strokeWidth = originalStrokeWidth
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}
