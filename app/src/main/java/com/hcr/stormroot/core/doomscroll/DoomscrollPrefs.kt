package com.hcr.stormroot.core.doomscroll

import android.content.Context
import androidx.core.content.edit

object DoomscrollPrefs {
    private const val PREFS_NAME = "doomscroll_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DAILY_LIMIT_MINUTES = "daily_limit_minutes"
    private const val KEY_TARGET_PACKAGES = "target_packages"

    const val DEFAULT_DAILY_LIMIT_MINUTES = 60
    const val RESET_HOUR = 5

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
}
