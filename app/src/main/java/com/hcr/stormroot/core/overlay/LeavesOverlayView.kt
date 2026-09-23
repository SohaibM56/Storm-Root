package com.hcr.stormroot.core.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.hcr.stormroot.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import androidx.core.graphics.toColorInt
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import androidx.core.graphics.withClip

private fun shade(base: Int, factor: Float): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(base, hsv)
    hsv[1] = (hsv[1] * (1f + (1f - factor) * 0.3f)).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
    return Color.HSVToColor(hsv)
}

private fun jitterColor(base: Int): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(base, hsv)
    hsv[0] = (hsv[0] + (Random.nextFloat() - 0.5f) * 14f + 360f) % 360f
    hsv[1] = (hsv[1] * (0.85f + Random.nextFloat() * 0.3f)).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] * (0.82f + Random.nextFloat() * 0.36f)).coerceIn(0f, 1f)
    return Color.HSVToColor(hsv)
}

private fun smoothPolarPath(points: List<Pair<Float, Float>>, xStretch: Float, yStretch: Float, jitter: Float): Path {
    val n = points.size
    val verts = Array(n) { i ->
        val (angleDeg, r0) = points[i]
        val r = r0 * (1f - jitter / 2f + Random.nextFloat() * jitter)
        val rad = Math.toRadians(angleDeg.toDouble())
        floatArrayOf((r * sin(rad)).toFloat() * xStretch, (-r * cos(rad)).toFloat() * yStretch)
    }
    fun midX(a: FloatArray, b: FloatArray) = (a[0] + b[0]) / 2f
    fun midY(a: FloatArray, b: FloatArray) = (a[1] + b[1]) / 2f
    val path = Path()
    val last = verts[n - 1]
    val first = verts[0]
    path.moveTo(midX(last, first), midY(last, first))
    for (i in 0 until n) {
        val v = verts[i]
        val nextV = verts[(i + 1) % n]
        path.quadTo(v[0], v[1], midX(v, nextV), midY(v, nextV))
    }
    path.close()
    return path
}

private fun buildLobedLeafPath(
    lobes: Int,
    tipRadius: Float,
    notchRadius: Float,
    xStretch: Float = 1f,
    yStretch: Float = 1f,
    jitter: Float = 0.12f
): Path {
    val n = lobes * 2
    val points = (0 until n).map { i ->
        val angleJitter = (Random.nextFloat() - 0.5f) * (360f / n) * 0.25f
        val r = if (i % 2 == 0) tipRadius else notchRadius
        (((360f / n) * i + angleJitter)) to r
    }
    return smoothPolarPath(points, xStretch, yStretch, jitter)
}

private fun buildOvalPath(): Path {
    val topY = -26f - Random.nextFloat() * 6f
    val botY = 30f + Random.nextFloat() * 8f
    val leftW = 18f + Random.nextFloat() * 6f
    val rightW = 18f + Random.nextFloat() * 6f
    val leftBulgeY = 10f + Random.nextFloat() * 10f
    val rightBulgeY = 10f + Random.nextFloat() * 10f
    return Path().apply {
        moveTo(0f, topY)
        cubicTo(-leftW * 0.9f, topY * 0.5f, -leftW, leftBulgeY, 0f, botY)
        cubicTo(rightW, rightBulgeY, rightW * 0.9f, topY * 0.5f, 0f, topY)
        close()
    }
}

private fun leafVeinExtent(type: LeavesOverlayView.LeafParticle.LeafType): Pair<Float, Float> = when (type) {
    LeavesOverlayView.LeafParticle.LeafType.OVAL -> -26f to 32f
    LeavesOverlayView.LeafParticle.LeafType.MAPLE -> -32f to 32f
    LeavesOverlayView.LeafParticle.LeafType.OAK -> -34f to 34f
    LeavesOverlayView.LeafParticle.LeafType.NARROW -> -30f to 30f
}

class LeavesOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    class LeafParticle {
        enum class LeafType { OVAL, MAPLE, OAK, NARROW }
        var type = LeafType.MAPLE
        var shapePath: Path = Path()

        var bitmap: Bitmap? = null
        var bitmapScale = 1f
        var tintFilter: ColorFilter? = null

        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f

        var rotation = 0f
        var rotationSpeed = 0f
        var tumblePhase = 0f
        var tumbleSpeed = 0f

        var baseScale = 1f
        var scaleX = 1f
        var scaleY = 1f
        var color = 0
        var secondaryColor = 0
        var baseAlpha = 255

        var blotches: Array<FloatArray> = emptyArray()

        var veins: Array<FloatArray> = emptyArray()

        // Built once per init() instead of allocated every onDraw frame — color/secondaryColor/
        // baseScale are fixed for the particle's lifetime, only the front/back face toggles.
        var frontShader: RadialGradient? = null
        var backShader: RadialGradient? = null

        private fun buildShader(lightFactor: Float): RadialGradient {
            val highlight = shade(color, 1.3f * lightFactor)
            val mid = shade(color, 0.95f * lightFactor)
            val edge = shade(secondaryColor, 0.55f * lightFactor)
            return RadialGradient(
                -6f, -10f, 58f * baseScale,
                intArrayOf(highlight, mid, edge),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        var landed = false

        var settling = false
        var settleT = 0f
        var settleFromX = 0f
        var settleFromY = 0f
        var settleFromRotation = 0f
        var settleFromScaleX = 1f
        var settleFromScaleY = 1f
        var settleTargetX = 0f
        var settleTargetY = 0f

        fun init(width: Int, height: Int, mapleBitmaps: List<Bitmap>, spawnAtTop: Boolean = false) {
            val typeRoll = Random.nextFloat()
            type = when {
                typeRoll < 0.60f -> LeafType.MAPLE
                typeRoll < 0.76f -> LeafType.OVAL
                typeRoll < 0.90f -> LeafType.OAK
                else -> LeafType.NARROW
            }
            landed = false
            settling = false
            settleT = 0f

            baseScale = 1.0f + Random.nextFloat() * 1.1f

            bitmap = null
            tintFilter = null
            shapePath = when (type) {
                LeafType.OVAL -> buildOvalPath()
                LeafType.MAPLE -> {
                    val bmp = mapleBitmaps[Random.nextInt(mapleBitmaps.size)]
                    bitmap = bmp
                    val longSide = max(bmp.width, bmp.height).toFloat()
                    bitmapScale = 68f / longSide
                    tintFilter = PorterDuffColorFilter(jitterColor(Color.rgb(255, 214, 170)), PorterDuff.Mode.MULTIPLY)
                    Path()
                }
                LeafType.OAK -> buildLobedLeafPath(
                    lobes = 6 + Random.nextInt(2),
                    tipRadius = 20f + Random.nextFloat() * 5f,
                    notchRadius = 17f + Random.nextFloat() * 4f,
                    xStretch = 0.85f + Random.nextFloat() * 0.15f,
                    yStretch = 1.45f + Random.nextFloat() * 0.25f,
                    jitter = 0.14f
                )
                LeafType.NARROW -> buildLobedLeafPath(
                    lobes = 9 + Random.nextInt(3),
                    tipRadius = 13f + Random.nextFloat() * 3f,
                    notchRadius = 12f + Random.nextFloat() * 2.5f,
                    xStretch = 0.45f + Random.nextFloat() * 0.15f,
                    yStretch = 2.0f + Random.nextFloat() * 0.4f,
                    jitter = 0.1f
                )
            }

            x = Random.nextFloat() * width
            y = if (spawnAtTop) -80f - Random.nextFloat() * 320f else Random.nextFloat() * height

            vy = 2.6f + Random.nextFloat() * 3.0f
            vx = (Random.nextFloat() - 0.5f) * 1.2f

            rotation = Random.nextFloat() * 360f
            rotationSpeed = (Random.nextFloat() - 0.5f) * 1.1f

            tumblePhase = Random.nextFloat() * Math.PI.toFloat() * 2f
            tumbleSpeed = 0.012f + Random.nextFloat() * 0.022f

            scaleX = baseScale
            scaleY = baseScale
            baseAlpha = 165 + Random.nextInt(85)

            val autumnColors = arrayOf(
                "#E08A2E" to "#B44A16",
                "#D9721F" to "#A13712",
                "#C94A24" to "#7E2412",
                "#E0A72E" to "#B5691C",
                "#B33A1E" to "#6E1C10"
            )
            val pair = autumnColors[Random.nextInt(autumnColors.size)]
            color = jitterColor(pair.first.toColorInt())
            secondaryColor = jitterColor(pair.second.toColorInt())
            frontShader = buildShader(1f)
            backShader = buildShader(0.65f)

            if (type != LeafType.MAPLE) {
                val (topY, botY) = leafVeinExtent(type)

                val blotchCount = 4 + Random.nextInt(5)
                blotches = Array(blotchCount) {
                    val isLight = Random.nextFloat() < 0.35f
                    floatArrayOf(
                        (Random.nextFloat() - 0.5f) * 40f,
                        topY + Random.nextFloat() * (botY - topY),
                        2.5f + Random.nextFloat() * 5f,
                        1.5f + Random.nextFloat() * 3.5f,
                        Random.nextFloat() * 180f,
                        if (isLight) 20f + Random.nextFloat() * 25f else 30f + Random.nextFloat() * 45f,
                        if (isLight) 1f else 0f
                    )
                }

                val veinCount = 3 + Random.nextInt(3)
                veins = Array(veinCount) { i ->
                    val t = (i + 1f) / (veinCount + 1f)
                    val yStart = topY * 0.3f + (botY - topY) * t * 0.55f
                    val reach = (botY - yStart) * (0.45f + Random.nextFloat() * 0.35f)
                    floatArrayOf(yStart, reach * (0.6f + Random.nextFloat() * 0.3f), reach)
                }
            } else {
                blotches = emptyArray()
                veins = emptyArray()
            }
        }

        fun update(
            width: Int,
            height: Int,
            windEffect: Float,
            mapleBitmaps: List<Bitmap>,
            groundHeightAt: (Float) -> Float,
            trySettle: (LeafParticle) -> Boolean,
            onLanded: (LeafParticle) -> Unit
        ) {
            if (landed) return

            if (settling) {
                settleT = (settleT + 0.035f).coerceAtMost(1f)
                val ease = settleT * settleT * (3f - 2f * settleT) // smoothstep
                x = settleFromX + (settleTargetX - settleFromX) * ease
                y = settleFromY + (settleTargetY - settleFromY) * ease
                scaleX = settleFromScaleX + (baseScale - settleFromScaleX) * ease
                scaleY = settleFromScaleY + (baseScale - settleFromScaleY) * ease
                if (settleT >= 1f) {
                    landed = true
                    settling = false
                    onLanded(this)
                }
                return
            }

            tumblePhase += tumbleSpeed

            val tumbleFactor = cos(tumblePhase.toDouble()).toFloat()
            scaleX = baseScale * abs(tumbleFactor).coerceIn(0.15f, 1.0f)

            val airCatch = abs(tumbleFactor)
            val gravityEffect = 1.1f - (airCatch * 0.4f)

            vx += (tumbleFactor * 0.1f) + (windEffect * 0.04f)
            vx *= 0.985f

            x += vx + windEffect

            x = x.coerceIn(-40f, width + 40f)
            y += vy * gravityEffect

            rotation += rotationSpeed + (vx * 1.4f)

            val groundY = height - groundHeightAt(x)
            if (y >= groundY) {
                y = groundY
                if (!trySettle(this)) {
                    init(width, height, mapleBitmaps, spawnAtTop = true)
                } else {
                    settling = true
                    settleT = 0f
                    settleFromX = x
                    settleFromY = y
                    settleFromRotation = rotation
                    settleFromScaleX = scaleX
                    settleFromScaleY = scaleY
                    vx = 0f
                    vy = 0f
                    rotationSpeed = 0f
                    tumblePhase = 0f
                }
            } else if (x < -200f || x > width + 200f) {
                init(width, height, mapleBitmaps, spawnAtTop = true)
            }
        }
    }

    companion object {
        private var cachedMapleBitmaps: List<Bitmap>? = null
        private val shadowSilhouetteFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
        private val backOfLeafFilter = PorterDuffColorFilter(Color.rgb(150, 138, 120), PorterDuff.Mode.MULTIPLY)

        private fun ensureMapleBitmaps(context: Context): List<Bitmap> {
            cachedMapleBitmaps?.let { return it }
            // Leaves are drawn at ~68px (bitmapScale below), so decode at ~2x that instead of
            // the drawable's full resolution to avoid holding oversized bitmaps for the process lifetime.
            val targetLongSide = (68f * 2f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val loaded = listOf(R.drawable.leaf_maple_red, R.drawable.leaf_maple_orange, R.drawable.leaf_maple_yellow)
                .map { decodeDownsampled(context, it, targetLongSide) }
            cachedMapleBitmaps = loaded
            return loaded
        }

        private fun decodeDownsampled(context: Context, resId: Int, targetLongSide: Int): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, resId, bounds)
            val longSide = max(bounds.outWidth, bounds.outHeight)
            var sampleSize = 1
            while (longSide / (sampleSize * 2) >= targetLongSide) sampleSize *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            return BitmapFactory.decodeResource(context.resources, resId, opts)
        }
    }

    private val mapleBitmaps = ensureMapleBitmaps(context)

    private val particles = ArrayList<LeafParticle>()
    private val leafCornerEffect = CornerPathEffect(3f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        pathEffect = leafCornerEffect
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        alpha = 30
        pathEffect = leafCornerEffect
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmapShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = shadowSilhouetteFilter
        alpha = 45
    }
    private val veinPath = Path()
    private var isAnimating = false
    private var globalWindTime = 0f
    private var spawnAccumulator = 0f
    private var groundBitmap: Bitmap? = null
    private var groundCanvas: Canvas? = null

    private val groundBinCount = 40
    private var groundHeights = FloatArray(groundBinCount)
    private var groundBinWidth = 0f
    private var maxPileHeight = 0f

    private fun binIndexForX(x: Float): Int {
        if (groundBinWidth <= 0f) return 0
        return (x / groundBinWidth).toInt().coerceIn(0, groundBinCount - 1)
    }

    private fun groundHeightAt(x: Float): Float = groundHeights[binIndexForX(x)]

    private fun currentMaxAirborne(): Int = (18 + 42 * intensity).toInt()

    private fun trySettleOnGround(p: LeafParticle): Boolean {
        val footprint = 10f + p.baseScale * 8f
        val bin = binIndexForX(p.x)
        val searchRadius = 2
        var targetBin = -1
        var bestHeight = Float.MAX_VALUE
        for (offset in -searchRadius..searchRadius) {
            val c = bin + offset
            if (c !in 0 until groundBinCount) continue
            val h = groundHeights[c]
            if (h < maxPileHeight && h < bestHeight) {
                bestHeight = h
                targetBin = c
            }
        }
        if (targetBin == -1) return false

        p.settleTargetX = (targetBin + 0.5f) * groundBinWidth
        p.settleTargetY = height - groundHeights[targetBin]
        groundHeights[targetBin] = (groundHeights[targetBin] + footprint).coerceAtMost(maxPileHeight)
        if (targetBin > 0 && Random.nextFloat() < 0.5f) {
            groundHeights[targetBin - 1] = (groundHeights[targetBin - 1] + footprint * 0.2f).coerceAtMost(maxPileHeight)
        }
        if (targetBin < groundBinCount - 1 && Random.nextFloat() < 0.5f) {
            groundHeights[targetBin + 1] = (groundHeights[targetBin + 1] + footprint * 0.2f).coerceAtMost(maxPileHeight)
        }
        return true
    }

    private fun spawnLeavesIfNeeded(w: Int, h: Int) {
        if (maxPileHeight <= 0f || groundHeights.isEmpty()) return
        val avgFill = groundHeights.average().toFloat() / maxPileHeight
        if (avgFill >= 0.95f) return

        val maxAirborne = currentMaxAirborne()
        spawnAccumulator += 0.12f + intensity * 0.3f
        while (spawnAccumulator >= 1f && particles.size < maxAirborne) {
            val p = LeafParticle()
            p.init(w, h, mapleBitmaps, spawnAtTop = true)
            particles.add(p)
            spawnAccumulator -= 1f
        }
    }

    var intensity: Float = 0.7f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (isAnimating) {
                val target = currentMaxAirborne()
                while (particles.size > target) particles.removeAt(particles.size - 1)
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

    private fun setupParticlesIfReady() {
        val w = width
        val h = height
        if (w == 0 || h == 0 || !isAnimating) return
        if (particles.isEmpty()) {
            repeat(currentMaxAirborne()) {
                val p = LeafParticle()
                p.init(w, h, mapleBitmaps)
                particles.add(p)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) groundBinWidth = w.toFloat() / groundBinCount
        if (h > 0) maxPileHeight = h * 0.22f
        groundHeights.fill(0f)
        if (w > 0 && h > 0) {
            groundBitmap?.recycle()
            val bmp = createBitmap(w, h)
            groundBitmap = bmp
            groundCanvas = Canvas(bmp)
        }
        setupParticlesIfReady()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAnimating) return
        val w = width
        val h = height
        if (w > 0 && h > 0) {
            globalWindTime += 0.02f
            val wind = sin(globalWindTime.toDouble()).toFloat() * 1.5f
            var i = 0
            while (i < particles.size) {
                val p = particles[i]
                p.update(w, h, wind, mapleBitmaps, ::groundHeightAt, ::trySettleOnGround, ::stampParticleToGround)
                if (p.landed) {
                    particles.removeAt(i)
                } else {
                    i++
                }
            }
            spawnLeavesIfNeeded(w, h)
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        groundBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        for (i in particles.indices) {
            drawParticle(canvas, particles[i])
        }
    }

    private fun stampParticleToGround(p: LeafParticle) {
        val gc = groundCanvas ?: return
        if (p.bitmap != null) {
            drawBitmapLeafOnly(gc, p)
        } else {
            gc.withTranslation(p.x, p.y) {
                rotate(p.rotation)
                scale(p.scaleX, p.scaleY)
                drawLeafShape(this, p, paint)
            }
        }
    }

    private fun drawParticle(canvas: Canvas, p: LeafParticle) {
        if (p.bitmap != null) {
            drawBitmapShadow(canvas, p)
            drawBitmapLeafOnly(canvas, p)
            return
        }

        canvas.save()
        val shadowOffset = 25f * p.baseScale
        canvas.translate(p.x + shadowOffset, p.y + shadowOffset)
        canvas.rotate(p.rotation)
        canvas.scale(p.scaleX, p.scaleY)
        drawLeafShape(canvas, p, shadowPaint)
        canvas.restore()

        canvas.withTranslation(p.x, p.y) {
            rotate(p.rotation)
            scale(p.scaleX, p.scaleY)
            drawLeafShape(this, p, paint)
        }
    }

    private fun drawBitmapShadow(canvas: Canvas, p: LeafParticle) {
        val bmp = p.bitmap ?: return
        val shadowOffset = 25f * p.baseScale
        canvas.withTranslation(p.x + shadowOffset, p.y + shadowOffset) {
            rotate(p.rotation)
            scale(p.scaleX * p.bitmapScale, p.scaleY * p.bitmapScale)
            translate(-bmp.width / 2f, -bmp.height / 2f)
            drawBitmap(bmp, 0f, 0f, bitmapShadowPaint)
        }
    }

    private fun drawBitmapLeafOnly(canvas: Canvas, p: LeafParticle) {
        val bmp = p.bitmap ?: return
        val tumbleFactor = cos(p.tumblePhase.toDouble()).toFloat()
        val isFront = tumbleFactor >= 0

        bitmapPaint.alpha = (p.baseAlpha * (if (isFront) 1f else 0.8f)).toInt().coerceIn(0, 255)
        bitmapPaint.colorFilter = if (isFront) p.tintFilter else backOfLeafFilter

        canvas.withTranslation(p.x, p.y) {
            rotate(p.rotation)
            scale(p.scaleX * p.bitmapScale, p.scaleY * p.bitmapScale)
            translate(-bmp.width / 2f, -bmp.height / 2f)
            drawBitmap(bmp, 0f, 0f, bitmapPaint)
        }
    }

    private fun drawLeafShape(canvas: Canvas, p: LeafParticle, targetPaint: Paint) {
        val originalColor = targetPaint.color
        val originalAlpha = targetPaint.alpha
        val originalStyle = targetPaint.style
        val originalStrokeWidth = targetPaint.strokeWidth

        val tumbleFactor = cos(p.tumblePhase.toDouble()).toFloat()
        val absTumble = abs(tumbleFactor)
        val isFront = tumbleFactor >= 0
        val shapePath = p.shapePath

        if (targetPaint == paint) {
            targetPaint.shader = if (isFront) p.frontShader else p.backShader
            targetPaint.alpha = (p.baseAlpha * (0.82f + absTumble * 0.18f)).toInt().coerceIn(0, 255)
        }

        canvas.drawPath(shapePath, targetPaint)

        if (targetPaint == paint) {
            canvas.withClip(shapePath) {
                targetPaint.shader = null
                targetPaint.style = Paint.Style.FILL
                val alphaScale = if (isFront) 1f else 0.35f
                for (b in p.blotches) {
                    val isLight = b[6] > 0.5f
                    targetPaint.color = if (isLight) {
                        Color.argb((b[5] * alphaScale).toInt().coerceIn(0, 255), 255, 236, 190)
                    } else {
                        Color.argb((b[5] * alphaScale).toInt().coerceIn(0, 255), 58, 36, 20)
                    }
                    withTranslation(b[0], b[1]) {
                        rotate(b[4])
                        scale(max(b[2], 0.1f), max(b[3], 0.1f))
                        drawCircle(0f, 0f, 1f, targetPaint)
                    }
                }
            }

            targetPaint.shader = null
            targetPaint.style = Paint.Style.STROKE
            targetPaint.strokeWidth = 1.1f * p.baseScale
            targetPaint.color = Color.argb((p.baseAlpha * (if (isFront) 0.3f else 0.12f)).toInt().coerceIn(0, 255), 45, 22, 10)
            canvas.drawPath(shapePath, targetPaint)

            val veinAlpha = if (isFront) 0.42f else 0.15f
            targetPaint.color = Color.argb((p.baseAlpha * veinAlpha).toInt(), 42, 22, 12)
            targetPaint.strokeWidth = 1.0f

            val (topY, botY) = leafVeinExtent(p.type)
            veinPath.reset()
            veinPath.moveTo(0f, topY * 0.8f)
            veinPath.quadTo(1.5f, (topY + botY) * 0.15f, 0f, botY * 0.82f)
            canvas.drawPath(veinPath, targetPaint)

            if (absTumble > 0.25f) {
                for (v in p.veins) {
                    val yStart = v[0]
                    val spreadX = v[1]
                    val spreadY = v[2]
                    veinPath.reset()
                    veinPath.moveTo(0f, yStart)
                    veinPath.quadTo(-spreadX * 0.5f, yStart + spreadY * 0.4f, -spreadX, yStart + spreadY)
                    canvas.drawPath(veinPath, targetPaint)
                    veinPath.reset()
                    veinPath.moveTo(0f, yStart)
                    veinPath.quadTo(spreadX * 0.5f, yStart + spreadY * 0.4f, spreadX, yStart + spreadY)
                    canvas.drawPath(veinPath, targetPaint)
                }
            }

            targetPaint.style = Paint.Style.FILL
        }

        targetPaint.shader = null
        targetPaint.color = originalColor
        targetPaint.alpha = originalAlpha
        targetPaint.style = originalStyle
        targetPaint.strokeWidth = originalStrokeWidth
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        groundBitmap?.recycle()
        groundBitmap = null
        groundCanvas = null
        super.onDetachedFromWindow()
    }
}
