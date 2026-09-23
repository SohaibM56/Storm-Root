package com.hcr.stormroot.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textview.MaterialTextView
import com.hcr.stormroot.R
import kotlin.math.abs
import androidx.core.view.isEmpty

class HorizontalWheelPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var min: Int = 0
    var max: Int = Int.MAX_VALUE
    var step: Int = 1
    var formatter: (Int) -> String = { it.toString() }
    var onValueChanged: ((Int) -> Unit)? = null

    var value: Int
        get() = _value
        set(newValue) {
            _value = newValue.coerceIn(min, max)
            scrollToValue(_value)
        }

    private val itemExtentDp: Float get() = if (isVertical) 40f else 72f

    private var _value: Int = 0
    private var built = false
    private var lastKnownExtent = 0
    private val values = mutableListOf<Int>()

    private val isVertical: Boolean = attrs?.let {
        val a = context.obtainStyledAttributes(it, R.styleable.HorizontalWheelPickerView)
        val v = a.getBoolean(R.styleable.HorizontalWheelPickerView_vertical, false)
        a.recycle()
        v
    } ?: false
    private val recyclerView: RecyclerView
    private val layoutManager: LinearLayoutManager
    private val snapHelper = LinearSnapHelper()
    private val adapter = NumberAdapter()

    init {

        layoutManager = LinearLayoutManager(
            context,
            if (isVertical) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL,
            false
        )

        recyclerView = RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            layoutManager = this@HorizontalWheelPickerView.layoutManager
            adapter = this@HorizontalWheelPickerView.adapter
            clipToPadding = false
            clipChildren = false
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        addView(recyclerView)
        snapHelper.attachToRecyclerView(recyclerView)

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = applyWheelStyle()

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) settle()
            }
        })

        recyclerView.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val extent = if (isVertical) bottom - top else right - left
            if (extent > 0 && extent != lastKnownExtent) {
                lastKnownExtent = extent
                val pad = (extent / 2 - dp(itemExtentDp / 2f)).coerceAtLeast(0)
                if (isVertical) recyclerView.setPadding(0, pad, 0, pad) else recyclerView.setPadding(pad, 0, pad, 0)
                scrollToValue(_value)
            }
        }
    }

    fun setPresets(presetList: List<Int>) = Unit

    private fun ensureBuilt() {
        if (built) return
        built = true
        values.clear()
        var v = min
        while (v <= max) {
            values.add(v)
            v += step
        }
        adapter.notifyDataSetChanged()
    }

    private fun indexForValue(target: Int): Int {
        ensureBuilt()
        if (values.isEmpty()) return 0
        var closestIndex = 0
        var closestDiff = Int.MAX_VALUE
        values.forEachIndexed { i, v ->
            val diff = abs(v - target)
            if (diff < closestDiff) {
                closestDiff = diff
                closestIndex = i
            }
        }
        return closestIndex
    }

    private fun extent() = if (isVertical) recyclerView.height else recyclerView.width

    private fun childStart(child: View) = if (isVertical) child.top else child.left
    private fun childEnd(child: View) = if (isVertical) child.bottom else child.right

    private fun centeredPosition(): Int {
        if (recyclerView.isEmpty()) return indexForValue(_value)
        val center = extent() / 2
        var bestPos = 0
        var bestDist = Int.MAX_VALUE
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val pos = layoutManager.getPosition(child)
            val dist = abs((childStart(child) + childEnd(child)) / 2 - center)
            if (dist < bestDist) {
                bestDist = dist
                bestPos = pos
            }
        }
        return bestPos.coerceIn(0, (values.size - 1).coerceAtLeast(0))
    }

    private fun settle() {
        if (values.isEmpty()) return
        _value = values[centeredPosition()]
        onValueChanged?.invoke(_value)
        applyWheelStyle()
    }

    private fun scrollToValue(target: Int) {
        ensureBuilt()
        if (values.isEmpty() || extent() == 0) return
        val index = indexForValue(target)
        layoutManager.scrollToPosition(index)
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(recyclerView.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(recyclerView.height, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(recyclerView.left, recyclerView.top, recyclerView.right, recyclerView.bottom)
        applyWheelStyle()
        settle()
    }

    private fun applyWheelStyle() {
        if (extent() == 0) return
        val center = extent() / 2f
        val maxDistance = dp(itemExtentDp) * 2.2f
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val childCenter = (childStart(child) + childEnd(child)) / 2f
            val t = (1f - (abs(childCenter - center) / maxDistance)).coerceIn(0f, 1f)
            child.scaleX = 0.6f + 0.4f * t
            child.scaleY = child.scaleX
            child.alpha = 0.35f + 0.65f * t

            val label = child as? MaterialTextView ?: continue
            val isCentered = t > 0.92f
            label.setTextColor(
                ContextCompat.getColor(context, if (isCentered) R.color.primary else R.color.text_primary)
            )
            label.setTypeface(label.typeface, if (isCentered) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun dp(valueDp: Float): Int = (valueDp * resources.displayMetrics.density).toInt()
    private fun dp(valueDp: Int): Int = dp(valueDp.toFloat())

    private inner class NumberAdapter : RecyclerView.Adapter<NumberAdapter.NumberViewHolder>() {

        override fun getItemCount() = values.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NumberViewHolder {
            val label = MaterialTextView(parent.context).apply {
                layoutParams = if (isVertical) {
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(itemExtentDp))
                } else {
                    ViewGroup.LayoutParams(dp(itemExtentDp), ViewGroup.LayoutParams.MATCH_PARENT)
                }
                gravity = Gravity.CENTER
                textSize = 17f
                maxLines = 1
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                isClickable = true
                isFocusable = true
            }
            return NumberViewHolder(label)
        }

        override fun onBindViewHolder(holder: NumberViewHolder, position: Int) {
            val v = values[position]
            holder.label.text = formatter(v)
            holder.label.setOnClickListener { scrollToValue(v) }
        }

        inner class NumberViewHolder(val label: MaterialTextView) : RecyclerView.ViewHolder(label)
    }
}
