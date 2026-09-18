package com.hcr.stormroot.core.bedtime

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object BedtimeDriftPrefs {
    private const val PREFS_NAME = "bedtime_drift_prefs"
    private const val KEY_HOUR = "bedtime_hour"
    private const val KEY_MINUTE = "bedtime_minute"
    private const val KEY_DRIFT_WINDOW_MINUTES = "drift_window_minutes"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SNOOZED_UNTIL = "snoozed_until"

    private const val DEFAULT_HOUR = 22
    private const val DEFAULT_MINUTE = 30
    const val DEFAULT_DRIFT_WINDOW_MINUTES = 90

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBedtimeHour(context: Context): Int = prefs(context).getInt(KEY_HOUR, DEFAULT_HOUR)
    fun getBedtimeMinute(context: Context): Int = prefs(context).getInt(KEY_MINUTE, DEFAULT_MINUTE)

    fun setBedtime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit {
            putInt(KEY_HOUR, hour)
            putInt(KEY_MINUTE, minute)
        }
    }

    fun getDriftWindowMinutes(context: Context): Int =
        prefs(context).getInt(KEY_DRIFT_WINDOW_MINUTES, DEFAULT_DRIFT_WINDOW_MINUTES)

    fun setDriftWindowMinutes(context: Context, minutes: Int) {
        prefs(context).edit { putInt(KEY_DRIFT_WINDOW_MINUTES, minutes) }
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun getSnoozedUntil(context: Context): Long = prefs(context).getLong(KEY_SNOOZED_UNTIL, 0L)

    fun snoozeFor(context: Context, minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        prefs(context).edit { putLong(KEY_SNOOZED_UNTIL, until) }
    }

    fun formattedBedtime(context: Context): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, getBedtimeHour(context))
            set(Calendar.MINUTE, getBedtimeMinute(context))
        }
        val format = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return SimpleDateFormat(format, Locale.getDefault())
            .format(calendar.time)
            .replace(".", "")
            .uppercase(Locale.getDefault())
    }
}
