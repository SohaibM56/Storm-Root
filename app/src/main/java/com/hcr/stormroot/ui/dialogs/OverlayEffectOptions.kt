package com.hcr.stormroot.ui.dialogs

import android.content.res.ColorStateList
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.shape.CornerFamily
import com.hcr.stormroot.R
import com.hcr.stormroot.core.overlay.OverlayService

/** The 9 overlay visuals a nudge feature can be set to show, shared by the sitting-roots,
 *  doomscroll, and bedtime settings dialogs so each one doesn't redefine this list. */
object OverlayEffectOptions {

    private data class Option(val effect: String, val labelRes: Int)

    private val ALL = listOf(
        Option(OverlayService.EFFECT_SOFT_MIST, R.string.preview_tab_soft_mist),
        Option(OverlayService.EFFECT_CALM_VINES, R.string.preview_tab_calm_vines),
        Option(OverlayService.EFFECT_WARM_GLOW, R.string.preview_tab_warm_glow),
        Option(OverlayService.EFFECT_BUTTERFLIES, R.string.preview_tab_butterflies),
        Option(OverlayService.EFFECT_FALLING_LEAVES, R.string.preview_tab_falling_leaves),
        Option(OverlayService.EFFECT_SNOWFALL, R.string.preview_tab_snowfall),
        Option(OverlayService.EFFECT_SUN_RAYS, R.string.preview_tab_sun_rays),
        Option(OverlayService.EFFECT_WATER_DROPLETS, R.string.preview_tab_water_droplets),
        Option(OverlayService.EFFECT_DEW_WEB, R.string.preview_tab_dew_web)
    )

    /** Populates [chipGroup] with one checkable chip per effect and checks [selected] —
     *  styled with the app's own green palette instead of Material3's default purple,
     *  which is otherwise unthemed (see themes.xml) and clashes with the rest of the UI. */
    fun bind(chipGroup: ChipGroup, selected: String) {
        chipGroup.removeAllViews()
        chipGroup.isSingleSelection = true
        chipGroup.isSelectionRequired = true

        val context = chipGroup.context
        val checkedStateSet = intArrayOf(android.R.attr.state_checked)
        val defaultStateSet = intArrayOf(-android.R.attr.state_checked)

        val backgroundTint = ColorStateList(
            arrayOf(checkedStateSet, defaultStateSet),
            intArrayOf(
                ContextCompat.getColor(context, R.color.surface_green_light),
                ContextCompat.getColor(context, R.color.natural)
            )
        )
        val strokeTint = ColorStateList(
            arrayOf(checkedStateSet, defaultStateSet),
            intArrayOf(
                ContextCompat.getColor(context, R.color.primary),
                ContextCompat.getColor(context, R.color.border_subtle)
            )
        )
        val textTint = ColorStateList(
            arrayOf(checkedStateSet, defaultStateSet),
            intArrayOf(
                ContextCompat.getColor(context, R.color.primary),
                ContextCompat.getColor(context, R.color.text_secondary)
            )
        )

        val density = context.resources.displayMetrics.density
        ALL.forEach { option ->
            val chip = Chip(context).apply {
                id = View.generateViewId()
                setText(option.labelRes)
                isCheckable = true
                tag = option.effect
                chipBackgroundColor = backgroundTint
                chipStrokeColor = strokeTint
                chipStrokeWidth = density
                setTextColor(textTint)
                chipIcon = null
                isChipIconVisible = false
                rippleColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surface_green_light))
                textSize = 12.5f
                setEnsureMinTouchTargetSize(false)
                chipMinHeight = 34f * density
                setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCorners(CornerFamily.ROUNDED, 17f * density)
                    .build()
            }
            chipGroup.addView(chip)
            if (option.effect == selected) chipGroup.check(chip.id)
        }
    }

    /** Reads back whichever chip is currently checked in a group populated by [bind]. */
    fun selectedEffect(chipGroup: ChipGroup, fallback: String): String {
        val checkedId = chipGroup.checkedChipId
        if (checkedId == View.NO_ID) return fallback
        return chipGroup.findViewById<Chip>(checkedId)?.tag as? String ?: fallback
    }

    /** The short display name for an EFFECT_* value, e.g. for showing which overlay a
     *  nudge is currently set to on its Overview tile. */
    fun labelRes(effect: String): Int =
        ALL.firstOrNull { it.effect == effect }?.labelRes ?: R.string.preview_tab_soft_mist
}
