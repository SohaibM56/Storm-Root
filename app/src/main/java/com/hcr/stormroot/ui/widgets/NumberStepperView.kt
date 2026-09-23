package com.hcr.stormroot.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.textview.MaterialTextView
import com.hcr.stormroot.R

class NumberStepperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var min: Int = 0
    var max: Int = Int.MAX_VALUE
    var step: Int = 1
    var formatter: (Int) -> String = { it.toString() }
    var onValueChanged: ((Int) -> Unit)? = null

    var value: Int = 0
        set(newValue) {
            val coerced = newValue.coerceIn(min, max)
            field = coerced
            valueText.text = formatter(coerced)
            refreshPresetSelection()
            onValueChanged?.invoke(coerced)
        }

    private val valueText: MaterialTextView
    private val presetsRow = LinearLayout(context)
    private val presetsScroll: HorizontalScrollView
    private val presetChips = mutableListOf<Pair<Int, MaterialTextView>>()

    init {
        orientation = VERTICAL

        val stepperRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        stepperRow.addView(stepperButton("–") { value -= step })

        valueText = MaterialTextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER
            }
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        stepperRow.addView(valueText)

        stepperRow.addView(stepperButton("+") { value += step })

        addView(stepperRow)

        presetsRow.orientation = HORIZONTAL
        presetsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10f)
            }
        }
        presetsScroll.addView(presetsRow)
        addView(presetsScroll)
    }

    private fun stepperButton(label: String, onClick: () -> Unit): View {
        return MaterialTextView(context).apply {
            layoutParams = LayoutParams(dp(38f), dp(38f))
            text = label
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            background = ContextCompat.getDrawable(context, R.drawable.bg_stepper_button)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun setPresets(presets: List<Int>) {
        presetsScroll.visibility = if (presets.isEmpty()) View.GONE else View.VISIBLE
        presetsRow.removeAllViews()
        presetChips.clear()
        presets.forEach { preset ->
            val chip = MaterialTextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8f)
                }
                text = formatter(preset)
                textSize = 11.5f
                setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
                isClickable = true
                isFocusable = true
                setOnClickListener { value = preset }
            }
            presetsRow.addView(chip)
            presetChips.add(preset to chip)
        }
        refreshPresetSelection()
    }

    private fun refreshPresetSelection() {
        presetChips.forEach { (preset, chip) ->
            val selected = preset == value
            chip.background = ContextCompat.getDrawable(
                context,
                if (selected) R.drawable.bg_preset_chip_selected else R.drawable.bg_preset_chip
            )
            chip.setTextColor(
                ContextCompat.getColor(context, if (selected) R.color.primary else R.color.text_secondary)
            )
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
