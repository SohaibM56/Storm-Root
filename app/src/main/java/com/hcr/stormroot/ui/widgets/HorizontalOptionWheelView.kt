package com.hcr.stormroot.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textview.MaterialTextView
import com.hcr.stormroot.R
import kotlin.math.abs
import androidx.core.view.isEmpty

class HorizontalOptionWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    data class Option(val key: String, val label: String, val locked: Boolean = false)

    var onSelectionChanged: ((String) -> Unit)? = null

    var options: List<Option> = emptyList()
        set(newOptions) {
            field = newOptions
            adapter.notifyDataSetChanged()
            scrollToKey(_selectedKey)
        }

    var selectedKey: String
        get() = _selectedKey
        set(newKey) {
            _selectedKey = newKey
            scrollToKey(newKey)
        }

    private var _selectedKey: String = ""
    private var lastKnownWidth = 0

    private val recyclerView: RecyclerView
    private val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    private val snapHelper = LinearSnapHelper()
    private val adapter = OptionAdapter()

    init {
        recyclerView = RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            layoutManager = this@HorizontalOptionWheelView.layoutManager
            adapter = this@HorizontalOptionWheelView.adapter
            clipToPadding = false
            clipChildren = false
            isHorizontalScrollBarEnabled = false
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

        recyclerView.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != lastKnownWidth) {
                lastKnownWidth = width
                val pad = (width / 2 - dp(ITEM_WIDTH_DP / 2f)).coerceAtLeast(0)
                recyclerView.setPadding(pad, 0, pad, 0)
                scrollToKey(_selectedKey)
            }
        }
    }

    private fun indexForKey(key: String): Int {
        val idx = options.indexOfFirst { it.key == key }
        return if (idx >= 0) idx else 0
    }

    private fun centeredPosition(): Int {
        if (recyclerView.isEmpty()) return indexForKey(_selectedKey)
        val center = recyclerView.width / 2
        var bestPos = 0
        var bestDist = Int.MAX_VALUE
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val pos = layoutManager.getPosition(child)
            val dist = abs((child.left + child.right) / 2 - center)
            if (dist < bestDist) {
                bestDist = dist
                bestPos = pos
            }
        }
        return bestPos.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    }

    private fun settle() {
        if (options.isEmpty()) return
        val pos = centeredPosition()
        _selectedKey = options[pos].key
        onSelectionChanged?.invoke(_selectedKey)
        applyWheelStyle()
    }

    private fun scrollToKey(key: String) {
        if (options.isEmpty() || recyclerView.width == 0) return
        val index = indexForKey(key)
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
        if (recyclerView.width == 0) return
        val center = recyclerView.width / 2f
        val maxDistance = dp(ITEM_WIDTH_DP) * 1.6f
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val childCenter = (child.left + child.right) / 2f
            val t = (1f - (abs(childCenter - center) / maxDistance)).coerceIn(0f, 1f)
            child.scaleX = 0.78f + 0.22f * t
            child.scaleY = child.scaleX
            child.alpha = 0.45f + 0.55f * t

            val label = (child as? ViewGroup)?.getChildAt(0) as? MaterialTextView ?: continue
            val isCentered = t > 0.92f
            label.setTextColor(
                ContextCompat.getColor(context, if (isCentered) R.color.primary else R.color.text_primary)
            )
            label.setTypeface(label.typeface, if (isCentered) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun dp(valueDp: Float): Int = (valueDp * resources.displayMetrics.density).toInt()

    private inner class OptionAdapter : RecyclerView.Adapter<OptionAdapter.OptionViewHolder>() {

        override fun getItemCount() = options.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionViewHolder {
            val container = LinearLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(dp(ITEM_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT)
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
            }
            val label = MaterialTextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
            // Same visual language as the app's other pill badges (mint background, primary-green
            // text) instead of a bare lock icon, so it reads as "unlock via ad" at a glance.
            val badge = MaterialTextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(3f)
                }
                text = context.getString(R.string.effect_locked_badge)
                textSize = 9f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.primary))
                background = ContextCompat.getDrawable(context, R.drawable.bg_badge_ads)
                setPadding(dp(6f), dp(1f), dp(6f), dp(1f))
                visibility = View.GONE
            }
            container.addView(label)
            container.addView(badge)
            return OptionViewHolder(container, label, badge)
        }

        override fun onBindViewHolder(holder: OptionViewHolder, position: Int) {
            val option = options[position]
            holder.label.text = option.label
            holder.badge.visibility = if (option.locked) View.VISIBLE else View.GONE
            holder.container.setOnClickListener { scrollToKey(option.key) }
        }

        inner class OptionViewHolder(
            val container: LinearLayout,
            val label: MaterialTextView,
            val badge: MaterialTextView
        ) : RecyclerView.ViewHolder(container)
    }

    private companion object {
        const val ITEM_WIDTH_DP = 96f
    }
}
