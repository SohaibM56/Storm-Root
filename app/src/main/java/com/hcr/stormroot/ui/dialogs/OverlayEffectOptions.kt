package com.hcr.stormroot.ui.dialogs

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.shape.CornerFamily
import com.hcr.stormroot.R
import com.hcr.stormroot.core.ads.EffectUnlockPrefs
import com.hcr.stormroot.core.overlay.OverlayService
import com.hcr.stormroot.ui.widgets.HorizontalOptionWheelView

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
        Option(OverlayService.EFFECT_DEW_WEB, R.string.preview_tab_dew_web),
        Option(OverlayService.EFFECT_STARRY_NIGHT, R.string.preview_tab_starry_night)
    )

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

    fun selectedEffect(chipGroup: ChipGroup, fallback: String): String {
        val checkedId = chipGroup.checkedChipId
        if (checkedId == View.NO_ID) return fallback
        return chipGroup.findViewById<Chip>(checkedId)?.tag as? String ?: fallback
    }

    fun labelRes(effect: String): Int =
        ALL.firstOrNull { it.effect == effect }?.labelRes ?: R.string.preview_tab_soft_mist

    fun bindWheel(wheel: HorizontalOptionWheelView, selected: String) {
        val context = wheel.context
        wheel.options = ALL.map {
            HorizontalOptionWheelView.Option(
                key = it.effect,
                label = context.getString(it.labelRes),
                locked = !EffectUnlockPrefs.isUnlocked(context, it.effect)
            )
        }
        wheel.selectedKey = selected
    }

    fun isUnlocked(context: Context, effect: String): Boolean = EffectUnlockPrefs.isUnlocked(context, effect)

    // A module's saved effect is only ever gated at the moment it's picked (the Save button) —
    // nothing re-checks it afterward. Without this, a temporary 3-day unlock would keep rendering
    // forever once saved, since OverlayService just reads the persisted value directly. Called
    // from OverlayService wherever a module fetches its effect for actual rendering, so an expired
    // unlock silently falls back to that module's own default (free) effect instead.
    fun effectiveEffect(context: Context, savedEffect: String, fallback: String): String =
        if (isUnlocked(context, savedEffect)) savedEffect else fallback

    fun selectedEffect(wheel: HorizontalOptionWheelView, fallback: String): String =
        wheel.selectedKey.ifEmpty { fallback }
}
