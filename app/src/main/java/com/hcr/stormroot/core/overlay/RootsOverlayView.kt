package com.hcr.stormroot.core.overlay

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow
import kotlin.random.Random
import androidx.core.graphics.withTranslation
import androidx.core.graphics.toColorInt
import kotlin.math.atan2

class RootsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        // Root generation (branch count, control-point jitter, leaf/moss sizes) was tuned
        // for a full-screen overlay around this tall. Views shorter than that (e.g. the
        // in-app preview panel) get everything scaled down proportionally, otherwise the
        // same absolute-pixel jitter overwhelms much shorter path lengths and the vines
        // loop back on themselves into a dense, unreadable clump.
        const val REFERENCE_HEIGHT_PX = 1600f
        const val MIN_SIZE_SCALE = 0.32f
    }

    var growth: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val leafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val mossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = "#4E7A27".toColorInt()
    }

    private val dewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 255, 255, 255)
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val revealPath = Path()
    private val roots = mutableListOf<Root>()

    private var viewWidth = 0
    private var viewHeight = 0
    private var sizeScale = 1f

    private val mainHandler = Handler(Looper.getMainLooper())
    private var generationId = 0

    private val pos = FloatArray(2)
    private val tan = FloatArray(2)

    private data class Root(
        val path: Path,
        val measure: PathMeasure,
        val length: Float,
        val startGrowth: Float,
        val leaves: List<Leaf>,
        val tendrils: List<Tendril>,
        val mossSpots: List<MossSpot>,
        val bounds: RectF,
        val depth: Int,
        val hueShift: Float,
        val thicknessScale: Float
    ) {
        var cachedBitmap: Bitmap? = null
        var cachedBucket: Int = -1
    }

    private data class Leaf(
        val distance: Float,
        val isLeft: Boolean,
        val rotation: Float,
        val sizeScale: Float,
        val variant: Int,
        val dewDrops: List<PointF>
    )
    
    private data class Tendril(val path: Path, val measure: PathMeasure, val length: Float, val startDist: Float)
    private data class MossSpot(val distance: Float, val size: Float, val offset: Float)

    /** Shows the vines immediately at the current [growth] level — no growth ramp, no wind
     *  sway. Rendering is fully static once baked. */
    fun start() {
        scheduleGenerateRoots()
    }

    /** Path/branch generation is too heavy to run on the UI thread without a jank spike on
     *  first show or rotation, so it's built on a background thread and swapped in on the
     *  main thread once ready. */
    private fun scheduleGenerateRoots() {
        val w = viewWidth
        val h = viewHeight
        val scale = sizeScale
        if (w == 0 || h == 0) return

        val myGenerationId = ++generationId
        Thread {
            val generated = buildRoots(w, h, scale)
            mainHandler.post {
                if (myGenerationId != generationId) {
                    generated.forEach { it.cachedBitmap?.recycle() }
                    return@post
                }
                roots.forEach { it.cachedBitmap?.recycle() }
                roots.clear()
                roots.addAll(generated)
                invalidate()
            }
        }.start()
    }

    private fun buildRoots(w: Int, h: Int, scale: Float): List<Root> {
        val generated = mutableListOf<Root>()
        val random = Random(System.currentTimeMillis())

        val centerX = w / 2f
        val centerY = h.toFloat()

        val mainRootCount = (18 * scale).toInt().coerceAtLeast(5)
        repeat(mainRootCount) { index ->
            // Real growth doesn't fan out from one exact point on the ground — scatter each
            // root's origin along the bottom edge, and break the perfectly even 12° spacing
            // with a little jitter so the whole cluster doesn't read as machine-generated.
            val originX = centerX + (random.nextFloat() - 0.5f) * w * 0.5f
            val targetAngle = -90f + (index - (mainRootCount - 1) / 2f) * 12f + (random.nextFloat() - 0.5f) * 5f
            val totalLength = h * (0.85f + random.nextFloat() * 0.4f)

            val mainPath = generateVinePath(originX, centerY, targetAngle, totalLength, 15, random, scale)
            val mainMeasure = PathMeasure(mainPath, false)
            val mainLen = mainMeasure.length

            generated.add(createRootData(mainPath, mainMeasure, 0f, random, depth = 0, scale = scale))

            val branchCount = 3 + random.nextInt(4)
            repeat(branchCount) { bIdx ->
                val bStartDist = mainLen * (0.2f + 0.6f * (bIdx.toFloat() / branchCount))
                val tPos = FloatArray(2)
                val tTan = FloatArray(2)
                mainMeasure.getPosTan(bStartDist, tPos, tTan)

                val baseAngle = Math.toDegrees(Math.atan2(tTan[1].toDouble(), tTan[0].toDouble())).toFloat()
                val bAngle = baseAngle + (if (random.nextBoolean()) 60f else -60f) + (random.nextFloat() - 0.5f) * 40f
                val bLen = mainLen * (0.25f + random.nextFloat() * 0.35f)

                val bPath = generateVinePath(tPos[0], tPos[1], bAngle, bLen, 6, random, scale)
                val bMeasure = PathMeasure(bPath, false)

                generated.add(createRootData(bPath, bMeasure, bStartDist / mainLen, random, depth = 1, scale = scale))
            }
        }
        return generated
    }

    fun stop() {
        generationId++
        roots.forEach {
            it.cachedBitmap?.recycle()
            it.cachedBitmap = null
            it.cachedBucket = -1
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        sizeScale = (h / REFERENCE_HEIGHT_PX).coerceIn(MIN_SIZE_SCALE, 1f)
        scheduleGenerateRoots()
    }

    private fun createRootData(path: Path, measure: PathMeasure, startGrowth: Float, random: Random, depth: Int, scale: Float): Root {
        val len = measure.length
        
        // Generate tendrils
        val tendrils = mutableListOf<Tendril>()
        repeat(3) {
            val tStartDist = len * (0.2f + random.nextFloat() * 0.6f)
            val tLen = len * 0.15f
            val tPath = Path()
            val pos = FloatArray(2)
            val tan = FloatArray(2)
            measure.getPosTan(tStartDist, pos, tan)
            tPath.moveTo(pos[0], pos[1])
            
            var curX = pos[0]
            var curY = pos[1]
            val baseAngle = Math.toDegrees(Math.atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat()
            
            repeat(10) { step ->
                val spiral = sin(step.toDouble() * 1.5).toFloat() * 20f
                val angle = Math.toRadians((baseAngle + spiral + (random.nextFloat() - 0.5f) * 30f).toDouble())
                val nextX = curX + (cos(angle) * (tLen / 10f)).toFloat()
                val nextY = curY + (sin(angle) * (tLen / 10f)).toFloat()
                tPath.lineTo(nextX, nextY)
                curX = nextX
                curY = nextY
            }
            val tMeasure = PathMeasure(tPath, false)
            tendrils.add(Tendril(tPath, tMeasure, tMeasure.length, tStartDist))
        }

        // Generate moss spots — moss favors the damp, shaded base of a plant far more than
        // the newer growth near the tip, so bias distance toward the root's start.
        val mossSpots = mutableListOf<MossSpot>()
        repeat(15) {
            mossSpots.add(MossSpot(
                distance = len * random.nextFloat().pow(2.2f),
                size = (4f + random.nextFloat() * 8f) * scale,
                offset = (random.nextFloat() - 0.5f) * 10f * scale
            ))
        }

        val leaves = generateLeaves(measure, random, scale)

        val bounds = RectF()
        path.computeBounds(bounds, true)
        tendrils.forEach { t ->
            val tBounds = RectF()
            t.path.computeBounds(tBounds, true)
            bounds.union(tBounds)
        }
        bounds.inset(-120f, -120f)

        val hueShift = (random.nextFloat() - 0.5f) * 18f
        // Individual vines are thicker or thinner than their neighbors, like real plants —
        // not every stem in a cluster grows at the same rate.
        val thicknessScale = 0.75f + random.nextFloat() * 0.5f
        return Root(path, measure, len, startGrowth, leaves, tendrils, mossSpots, bounds, depth, hueShift, thicknessScale)
    }

    private fun generateLeaves(measure: PathMeasure, random: Random, scale: Float): List<Leaf> {
        val leaves = mutableListOf<Leaf>()
        val len = measure.length
        var dist = len * 0.12f
        while (dist < len * 0.97f) {
            // Newer growth near the tip carries denser, larger leaves; the older base is
            // sparser and smaller — real vines don't foliate uniformly along their length.
            val progress = dist / len
            val presence = 0.5f + 0.5f * progress
            if (random.nextFloat() < presence) {
                val sizeBias = 0.7f + 0.5f * progress
                val dewDrops = mutableListOf<PointF>()
                if (random.nextFloat() < 0.4f) {
                    repeat(1 + random.nextInt(2)) {
                        dewDrops.add(PointF(random.nextFloat(), random.nextFloat()))
                    }
                }

                leaves.add(Leaf(
                    distance = dist,
                    isLeft = random.nextBoolean(),
                    rotation = (random.nextFloat() - 0.5f) * 45f,
                    sizeScale = (0.7f + random.nextFloat() * 0.6f) * sizeBias * scale,
                    variant = random.nextInt(3),
                    dewDrops = dewDrops
                ))
            }
            dist += len * (0.05f + random.nextFloat() * 0.05f)
        }
        return leaves
    }

    private fun jitterColor(baseColor: Int, hueShift: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        hsv[0] = (hsv[0] + hueShift + 360f) % 360f
        return Color.HSVToColor(hsv)
    }

    private fun generateVinePath(startX: Float, startY: Float, targetAngle: Float, length: Float, steps: Int, random: Random, scale: Float): Path {
        return Path().apply {
            moveTo(startX, startY)
            var curX = startX
            var curY = startY
            val stepLen = length / steps

            repeat(steps) { step ->
                val wind = sin(step * 0.6f) * 30f
                val angleJitter = (random.nextFloat() - 0.5f) * 20f + wind
                val angle = Math.toRadians((targetAngle + angleJitter).toDouble())

                val nextX = curX + (Math.cos(angle) * stepLen).toFloat()
                val nextY = curY + (Math.sin(angle) * stepLen).toFloat()

                // Control-point wobble must shrink with the view, otherwise it overshoots a
                // much shorter step distance and the curve loops back on itself instead of
                // gently waving (the bug that made vines clump into an unreadable mass
                // inside the small preview panel).
                val ctrlX = (curX + nextX) / 2f + (random.nextFloat() - 0.5f) * 80f * scale
                val ctrlY = (curY + nextY) / 2f + (random.nextFloat() - 0.5f) * 80f * scale

                quadTo(ctrlX, ctrlY, nextX, nextY)
                curX = nextX
                curY = nextY
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (growth <= 0f || roots.isEmpty()) return

        val density = resources.displayMetrics.density

        roots.forEach { root ->
            val effectiveGrowth = if (growth <= root.startGrowth) 0f
                                  else ((growth - root.startGrowth) / (1f - root.startGrowth)).coerceIn(0f, 1f)

            if (effectiveGrowth <= 0f) return@forEach

            // Bake the vine into a bitmap once per growth bucket and reuse it — rendering is
            // fully static, so a given bucket only needs to be drawn once, ever.
            val bucket = (effectiveGrowth * 40).toInt()
            if (root.cachedBitmap == null || root.cachedBucket != bucket) {
                bakeRoot(root, effectiveGrowth, density)
                root.cachedBucket = bucket
            }
            val bitmap = root.cachedBitmap ?: return@forEach
            canvas.drawBitmap(bitmap, root.bounds.left, root.bounds.top, bitmapPaint)
        }
    }

    private fun bakeRoot(root: Root, effectiveGrowth: Float, density: Float) {
        val width = root.bounds.width().toInt().coerceAtLeast(1)
        val height = root.bounds.height().toInt().coerceAtLeast(1)

        val bitmap = root.cachedBitmap?.takeIf { it.width == width && it.height == height && !it.isRecycled }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { fresh ->
                root.cachedBitmap?.recycle()
                root.cachedBitmap = fresh
            }
        bitmap.eraseColor(Color.TRANSPARENT)

        val bakeCanvas = Canvas(bitmap)
        bakeCanvas.translate(-root.bounds.left, -root.bounds.top)

        val distance = root.length * effectiveGrowth
        val depthFade = if (root.depth == 0) 1f else 0.88f
        val bodyColor = jitterColor("#2E4C23".toColorInt(), root.hueShift)
        val highlightColor = jitterColor("#4A7A36".toColorInt(), root.hueShift)

        // Pass 0: Soft contact shadow, offset down-right and blurred — grounds the vine
        // against whatever's behind it instead of reading as a flat sticker pasted on top.
        revealPath.reset()
        root.measure.getSegment(0f, distance, revealPath, true)
        paint.color = Color.argb((90 * effectiveGrowth * depthFade).toInt().coerceIn(0, 255), 12, 18, 8)
        paint.strokeWidth = 7f * density * root.thicknessScale
        paint.maskFilter = BlurMaskFilter(3.5f * density, BlurMaskFilter.Blur.NORMAL)
        bakeCanvas.withTranslation(2.5f * density, 3f * density) {
            drawPath(revealPath, paint)
        }
        paint.maskFilter = null

        // Pass 1: Bark Texture/Body with Highlights and Shadows
        val steps = 50
        val stepDist = distance / steps
        for (i in 0 until steps) {
            val start = i * stepDist
            val end = (i + 1) * stepDist
            // Eased taper: stays thick near the base and thins out faster near the tip,
            // like a real root, instead of a straight linear shrink.
            val eased = (i.toFloat() / steps).pow(1.6f)

            revealPath.reset()
            root.measure.getSegment(start, end, revealPath, true)

            // Main vine body
            paint.color = bodyColor
            paint.strokeWidth = (6f - 4f * eased) * density * root.thicknessScale
            paint.alpha = (220 * effectiveGrowth * depthFade).toInt()
            bakeCanvas.drawPath(revealPath, paint)

            // Highlight for cylindrical 3D look
            paint.color = highlightColor
            paint.strokeWidth = (2f - 1f * eased) * density * root.thicknessScale
            bakeCanvas.drawPath(revealPath, paint)
        }

        // Growth-tip glow: a soft highlight at the actively growing tip while it's still
        // extending, so growth reads as living rather than a static reveal.
        if (effectiveGrowth in 0.001f..0.999f) {
            root.measure.getPosTan(distance, pos, tan)
            val glowRadius = 10f * density
            glowPaint.shader = RadialGradient(
                pos[0], pos[1], glowRadius,
                Color.argb((150 * effectiveGrowth).toInt().coerceIn(0, 255), 200, 255, 150),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            bakeCanvas.drawCircle(pos[0], pos[1], glowRadius, glowPaint)
        }

        // Pass 2: Moss Spots
        mossPaint.alpha = (180 * effectiveGrowth).toInt()
        root.mossSpots.forEach { spot ->
            if (spot.distance <= distance) {
                root.measure.getPosTan(spot.distance, pos, tan)
                bakeCanvas.drawCircle(pos[0] + spot.offset, pos[1], spot.size * density * effectiveGrowth, mossPaint)
            }
        }

        // Pass 3: Tendrils
        paint.color = "#3D6B2C".toColorInt()
        paint.strokeWidth = 0.8f * density
        root.tendrils.forEach { tendril ->
            if (distance >= tendril.startDist) {
                val tGrowth = ((distance - tendril.startDist) / tendril.length).coerceIn(0f, 1f)
                if (tGrowth > 0f) {
                    revealPath.reset()
                    tendril.measure.getSegment(0f, tendril.length * tGrowth, revealPath, true)
                    bakeCanvas.drawPath(revealPath, paint)
                }
            }
        }

        // Pass 4: Leaves and Dew
        root.leaves.forEach { leaf ->
            if (leaf.distance <= distance) {
                root.measure.getPosTan(leaf.distance, pos, tan)
                drawLeaf(bakeCanvas, pos[0], pos[1], tan[0], tan[1], leaf, density)
            }
        }
    }

    private fun drawLeaf(canvas: Canvas, x: Float, y: Float, tx: Float, ty: Float, leaf: Leaf, density: Float) {
        canvas.withTranslation(x, y) {
            val angle = Math.toDegrees(atan2(ty.toDouble(), tx.toDouble())).toFloat()
            rotate(angle + (if (leaf.isLeft) -60f else 60f) + leaf.rotation)

            val leafSize = 18f * leaf.sizeScale * density
            // Three silhouettes (round, standard, pointed) so leaves don't all read as
            // identical stamped shapes.
            val leafWidth = when (leaf.variant) {
                0 -> leafSize * 0.85f
                1 -> leafSize * 0.7f
                else -> leafSize * 0.55f
            }
            val leafPath = Path().apply {
                moveTo(0f, 0f)
                cubicTo(
                    leafSize * 0.4f,
                    -leafWidth,
                    leafSize * 0.8f,
                    -leafWidth,
                    leafSize,
                    0f
                )
                cubicTo(leafSize * 0.8f, leafWidth, leafSize * 0.4f, leafWidth, 0f, 0f)
                close()
            }

            // Leaf body with translucency
            leafPaint.color = if (leaf.isLeft) "#3D6B2C".toColorInt() else "#4A7C36".toColorInt()
            leafPaint.alpha = (230 * growth).toInt()
            drawPath(leafPath, leafPaint)

            // Microscopic Vein Patterns
            paint.color = "#2E4C23".toColorInt()
            paint.strokeWidth = 0.6f * density
            paint.alpha = (120 * growth).toInt()
            drawLine(0f, 0f, leafSize * 0.9f, 0f, paint) // Main vein

            repeat(3) { i ->
                val vDist = leafSize * (0.2f + i * 0.25f)
                val vLen = leafSize * 0.4f * (1f - i * 0.2f)
                val vAngle = Math.toRadians(45.0)
                drawLine(
                    vDist,
                    0f,
                    (vDist + cos(vAngle) * vLen).toFloat(),
                    (sin(vAngle) * vLen).toFloat(),
                    paint
                )
                drawLine(
                    vDist,
                    0f,
                    (vDist + cos(vAngle) * vLen).toFloat(),
                    (-sin(vAngle) * vLen).toFloat(),
                    paint
                )
            }

            // Dew Drops
            dewPaint.alpha = (200 * growth).toInt()
            leaf.dewDrops.forEach { drop ->
                val dx = drop.x * leafSize * 0.7f + leafSize * 0.15f
                val dy = (drop.y - 0.5f) * leafSize * 0.4f
                drawCircle(dx, dy, 1.5f * density, dewPaint)
                // Tiny highlight on dew drop
                paint.color = Color.WHITE
                paint.strokeWidth = 0.5f * density
                paint.alpha = (255 * growth).toInt()
                drawPoint(dx - 0.5f * density, dy - 0.5f * density, paint)
            }

        }
    }
}
