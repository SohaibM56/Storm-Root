package com.hcr.stormroot.core.doomscroll

import android.content.Context
import androidx.core.content.edit

object DoomscrollPrefs {
    private const val PREFS_NAME = "doomscroll_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DAILY_LIMIT_MINUTES = "daily_limit_minutes"
    private const val KEY_TARGET_PACKAGES = "target_packages"
    private const val KEY_OVERLAY_EFFECT = "overlay_effect"
    private const val KEY_RAMP_MINUTES = "ramp_minutes"
    private const val KEY_SNOOZED_UNTIL = "snoozed_until"

    const val DEFAULT_DAILY_LIMIT_MINUTES = 60
    const val RESET_HOUR = 5
    const val DEFAULT_OVERLAY_EFFECT = "SOFT_MIST"
    const val DEFAULT_RAMP_MINUTES = 30

    val DEFAULT_TARGET_PACKAGES = setOf(
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.twitter.android",
        "com.facebook.katana",
        "com.google.android.youtube",
        "com.snapchat.android",
        "com.reddit.frontpage",
        "com.pinterest"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun getDailyLimitMinutes(context: Context): Int =
        prefs(context).getInt(KEY_DAILY_LIMIT_MINUTES, DEFAULT_DAILY_LIMIT_MINUTES)

    fun setDailyLimitMinutes(context: Context, minutes: Int) {
        prefs(context).edit { putInt(KEY_DAILY_LIMIT_MINUTES, minutes) }
    }

    fun getTargetPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_TARGET_PACKAGES, null) ?: DEFAULT_TARGET_PACKAGES

    fun setTargetPackages(context: Context, packages: Set<String>) {
        prefs(context).edit { putStringSet(KEY_TARGET_PACKAGES, packages) }
    }

    fun getOverlayEffect(context: Context): String =
        prefs(context).getString(KEY_OVERLAY_EFFECT, DEFAULT_OVERLAY_EFFECT) ?: DEFAULT_OVERLAY_EFFECT

    fun setOverlayEffect(context: Context, effect: String) {
        prefs(context).edit { putString(KEY_OVERLAY_EFFECT, effect) }
    }

    fun getRampMinutes(context: Context): Int =
        prefs(context).getInt(KEY_RAMP_MINUTES, DEFAULT_RAMP_MINUTES)

    fun setRampMinutes(context: Context, minutes: Int) {
        prefs(context).edit { putInt(KEY_RAMP_MINUTES, minutes) }
    }

    fun getSnoozedUntil(context: Context): Long = prefs(context).getLong(KEY_SNOOZED_UNTIL, 0L)

    fun snoozeFor(context: Context, minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        prefs(context).edit { putLong(KEY_SNOOZED_UNTIL, until) }
    }
}
