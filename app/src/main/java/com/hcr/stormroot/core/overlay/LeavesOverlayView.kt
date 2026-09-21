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

/** Smooths a closed polar-coordinate outline into a Path (quad curve through each vertex, passing through edge midpoints). */
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

/** Builds an irregular, slightly asymmetric uniform-lobe leaf outline (oak/narrow types). */
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

/** Simple rounded leaf (birch/heart-shaped) with organic per-leaf asymmetry. */
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

        // Real photographed maple leaf sprite (used instead of the vector path for MAPLE type).
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

        // Irregular blotches: x, y, radiusX, radiusY, rotationDeg, alpha, isLight(1f/0f) — vector types only
        var blotches: Array<FloatArray> = emptyArray()
        // Side-vein pairs for vector types: yStart, spreadX, spreadY
        var veins: Array<FloatArray> = emptyArray()

        var landed = false

        // Short ease-in used only in the last moment before landing, so a leaf slides and settles
        // flat instead of teleporting sideways to its resting bin and snapping flat in one frame.
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
            // Maple-dominant, matching the reference video; a little variety from the other shapes.
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
            // Staggered entry height so new leaves don't all appear in a single static-looking row.
            y = if (spawnAtTop) -80f - Random.nextFloat() * 320f else Random.nextFloat() * height

            // Brisk fall so the whole scene reaches its settled, full look within about a minute.
            // A hard x-clamp in update() keeps this safe from the old edge-drift bug regardless.
            vy = 2.6f + Random.nextFloat() * 3.0f
            vx = (Random.nextFloat() - 0.5f) * 1.2f

            rotation = Random.nextFloat() * 360f
            rotationSpeed = (Random.nextFloat() - 0.5f) * 1.1f

            tumblePhase = Random.nextFloat() * Math.PI.toFloat() * 2f
            tumbleSpeed = 0.012f + Random.nextFloat() * 0.022f

            scaleX = baseScale
            scaleY = baseScale
            baseAlpha = 165 + Random.nextInt(85)

            // Warm autumn palette for the vector leaf types, jittered so no two match
            val autumnColors = arrayOf(
                "#E08A2E" to "#B44A16", // Amber to burnt orange
                "#D9721F" to "#A13712", // Pumpkin to rust
                "#C94A24" to "#7E2412", // Persimmon to brick red
                "#E0A72E" to "#B5691C", // Golden to ochre
                "#B33A1E" to "#6E1C10"  // Deep red to maroon
            )
            val pair = autumnColors[Random.nextInt(autumnColors.size)]
            color = jitterColor(Color.parseColor(pair.first))
            secondaryColor = jitterColor(Color.parseColor(pair.second))

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
                // Slow enough to read as a leaf gently tipping over and settling (~0.5s), not a snap.
                settleT = (settleT + 0.035f).coerceAtMost(1f)
                val ease = settleT * settleT * (3f - 2f * settleT) // smoothstep
                x = settleFromX + (settleTargetX - settleFromX) * ease
                y = settleFromY + (settleTargetY - settleFromY) * ease
                // Rotation is left as-is on purpose — a real leaf keeps whatever angle it landed
                // at instead of snapping upright, which is what made this look "pasted" before.
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

            // 3D Tumble simulation: The leaf squashes and stretches as it flips
            val tumbleFactor = cos(tumblePhase.toDouble()).toFloat()
            scaleX = baseScale * abs(tumbleFactor).coerceIn(0.15f, 1.0f)

            // GLIDING PHYSICS: A leaf "catches" the air when it's flat and glides
            // When tumbleFactor is near 0, the leaf is "edge-on" and drops faster
            val airCatch = abs(tumbleFactor)
            val gravityEffect = 1.1f - (airCatch * 0.4f)

            // Drifting based on tilt and wind
            vx += (tumbleFactor * 0.1f) + (windEffect * 0.04f)
            vx *= 0.985f // Air friction

            x += vx + windEffect
            // Hard safety clamp: even with pathological drift over a long flight, a leaf can
            // never wander far past the edges — prevents leaves piling into edge-only towers.
            x = x.coerceIn(-40f, width + 40f)
            y += vy * gravityEffect

            rotation += rotationSpeed + (vx * 1.4f)

            val groundY = height - groundHeightAt(x)
            if (y >= groundY) {
                y = groundY
                if (!trySettle(this)) {
                    // pile is full at this spot; recycle the leaf back to the top
                    init(width, height, mapleBitmaps, spawnAtTop = true)
                } else {
                    // Ease into the resting spot over a few frames instead of snapping there —
                    // trySettle() already stashed the target bin's position in settleTargetX/Y.
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
            val loaded = listOf(R.drawable.leaf_maple_red, R.drawable.leaf_maple_orange, R.drawable.leaf_maple_yellow)
                .map { BitmapFactory.decodeResource(context.resources, it) }
            cachedMapleBitmaps = loaded
            return loaded
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

    // Landed leaves are baked into this bitmap instead of staying live particles,
    // so the pile can grow indefinitely without any per-frame rendering cost.
    private var groundBitmap: Bitmap? = null
    private var groundCanvas: Canvas? = null

    // Ground pile simulation: leaves collect at the bottom instead of vanishing.
    private val groundBinCount = 40
    private var groundHeights = FloatArray(groundBinCount)
    private var groundBinWidth = 0f
    private var maxPileHeight = 0f

    private fun binIndexForX(x: Float): Int {
        if (groundBinWidth <= 0f) return 0
        return (x / groundBinWidth).toInt().coerceIn(0, groundBinCount - 1)
    }

    private fun groundHeightAt(x: Float): Float = groundHeights[binIndexForX(x)]

    // More leaves in flight at once for a fuller scene.
    private fun currentMaxAirborne(): Int = (18 + 42 * intensity).toInt()

    /**
     * Attempts to settle a leaf onto the pile near its x position. Returns false if the pile is
     * full there. Picks the *least-filled* bin in a wide window (not just the first open one) so
     * leaves spread into a natural, patchy scatter instead of packing one column solid before
     * spilling to the next — and neighbor spreading is probabilistic so gaps stay between clusters.
     */
    private fun trySettleOnGround(p: LeafParticle): Boolean {
        val footprint = 10f + p.baseScale * 8f
        val bin = binIndexForX(p.x)
        // Kept small so the eased slide into place (see LeafParticle.settling) stays subtle —
        // a leaf should nudge into a nearby gap, not glide halfway across the pile to reach one.
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

        // Resting surface is the pile height *before* this leaf's own footprint is added.
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
        // Leaves accumulate until they cover almost the entire screen height (a sliver at the
        // very top stays clear so it never looks like a hard, artificial ceiling).
        // Capped well below full pile height so the ground layer stays a light scatter of leaves
        // near the very bottom edge, not a solid carpet that climbs the screen.
        if (h > 0) maxPileHeight = h * 0.22f
        groundHeights.fill(0f)
        if (w > 0 && h > 0) {
            groundBitmap?.recycle()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
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
        for (i in 0 until particles.size) {
            drawParticle(canvas, particles[i])
        }
    }

    private fun stampParticleToGround(p: LeafParticle) {
        // Deliberately skip the drop-shadow layer here: the ground bitmap is permanent, and
        // repeatedly alpha-blending a translucent shadow at the same pixels as leaves pile up
        // mathematically trends toward solid black over time. Only airborne leaves get a shadow.
        val gc = groundCanvas ?: return
        if (p.bitmap != null) {
            drawBitmapLeafOnly(gc, p)
        } else {
            gc.save()
            gc.translate(p.x, p.y)
            gc.rotate(p.rotation)
            gc.scale(p.scaleX, p.scaleY)
            drawLeafShape(gc, p, paint)
            gc.restore()
        }
    }

    private fun drawParticle(canvas: Canvas, p: LeafParticle) {
        if (p.bitmap != null) {
            drawBitmapShadow(canvas, p)
            drawBitmapLeafOnly(canvas, p)
            return
        }

        // 1. Shadow
        canvas.save()
        val shadowOffset = 25f * p.baseScale
        canvas.translate(p.x + shadowOffset, p.y + shadowOffset)
        canvas.rotate(p.rotation)
        canvas.scale(p.scaleX, p.scaleY)
        drawLeafShape(canvas, p, shadowPaint)
        canvas.restore()

        // 2. Leaf
        canvas.save()
        canvas.translate(p.x, p.y)
        canvas.rotate(p.rotation)
        canvas.scale(p.scaleX, p.scaleY)
        drawLeafShape(canvas, p, paint)
        canvas.restore()
    }

    private fun drawBitmapShadow(canvas: Canvas, p: LeafParticle) {
        val bmp = p.bitmap ?: return
        val shadowOffset = 25f * p.baseScale
        canvas.save()
        canvas.translate(p.x + shadowOffset, p.y + shadowOffset)
        canvas.rotate(p.rotation)
        canvas.scale(p.scaleX * p.bitmapScale, p.scaleY * p.bitmapScale)
        canvas.translate(-bmp.width / 2f, -bmp.height / 2f)
        canvas.drawBitmap(bmp, 0f, 0f, bitmapShadowPaint)
        canvas.restore()
    }

    private fun drawBitmapLeafOnly(canvas: Canvas, p: LeafParticle) {
        val bmp = p.bitmap ?: return
        val tumbleFactor = cos(p.tumblePhase.toDouble()).toFloat()
        val isFront = tumbleFactor >= 0

        bitmapPaint.alpha = (p.baseAlpha * (if (isFront) 1f else 0.8f)).toInt().coerceIn(0, 255)
        bitmapPaint.colorFilter = if (isFront) p.tintFilter else backOfLeafFilter

        canvas.save()
        canvas.translate(p.x, p.y)
        canvas.rotate(p.rotation)
        canvas.scale(p.scaleX * p.bitmapScale, p.scaleY * p.bitmapScale)
        canvas.translate(-bmp.width / 2f, -bmp.height / 2f)
        canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        canvas.restore()
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
            // Multi-stop gradient: a warm off-center highlight fading to a darker, drier edge —
            // reads as light passing through thin leaf tissue instead of a flat fill.
            val lightFactor = if (isFront) 1f else 0.65f
            val highlight = shade(p.color, 1.3f * lightFactor)
            val mid = shade(p.color, 0.95f * lightFactor)
            val edge = shade(p.secondaryColor, 0.55f * lightFactor)
            targetPaint.shader = RadialGradient(
                -6f, -10f, 58f * p.baseScale,
                intArrayOf(highlight, mid, edge),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            targetPaint.alpha = (p.baseAlpha * (0.82f + absTumble * 0.18f)).toInt().coerceIn(0, 255)
        }

        canvas.drawPath(shapePath, targetPaint)

        if (targetPaint == paint) {
            // Irregular mottled blotches (dry patches + light patches), clipped to the leaf silhouette
            canvas.save()
            canvas.clipPath(shapePath)
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
                canvas.save()
                canvas.translate(b[0], b[1])
                canvas.rotate(b[4])
                canvas.scale(max(b[2], 0.1f), max(b[3], 0.1f))
                canvas.drawCircle(0f, 0f, 1f, targetPaint)
                canvas.restore()
            }
            canvas.restore()

            // Edge definition: a faint darker rim so the silhouette reads clearly without looking painted-on
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
