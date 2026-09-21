package com.hcr.stormroot.core.sitting

import android.content.Context
import androidx.core.content.edit

object SittingRootsPrefs {
    private const val PREFS_NAME = "sitting_roots_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SITTING_THRESHOLD_MINUTES = "sitting_threshold_minutes"
    private const val KEY_OVERLAY_EFFECT = "overlay_effect"
    private const val KEY_GROWTH_RAMP_MINUTES = "growth_ramp_minutes"

    const val DEFAULT_SITTING_THRESHOLD_MINUTES = 45
    const val DEFAULT_OVERLAY_EFFECT = "CALM_VINES"
    const val DEFAULT_GROWTH_RAMP_MINUTES = 30

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun getSittingThresholdMinutes(context: Context): Int =
        prefs(context).getInt(KEY_SITTING_THRESHOLD_MINUTES, DEFAULT_SITTING_THRESHOLD_MINUTES)

    fun setSittingThresholdMinutes(context: Context, minutes: Int) {
        prefs(context).edit { putInt(KEY_SITTING_THRESHOLD_MINUTES, minutes) }
    }

    fun getOverlayEffect(context: Context): String =
        prefs(context).getString(KEY_OVERLAY_EFFECT, DEFAULT_OVERLAY_EFFECT) ?: DEFAULT_OVERLAY_EFFECT

    fun setOverlayEffect(context: Context, effect: String) {
        prefs(context).edit { putString(KEY_OVERLAY_EFFECT, effect) }
    }

    fun getGrowthRampMinutes(context: Context): Int =
        prefs(context).getInt(KEY_GROWTH_RAMP_MINUTES, DEFAULT_GROWTH_RAMP_MINUTES)

    fun setGrowthRampMinutes(context: Context, minutes: Int) {
        prefs(context).edit { putInt(KEY_GROWTH_RAMP_MINUTES, minutes) }
    }
}
