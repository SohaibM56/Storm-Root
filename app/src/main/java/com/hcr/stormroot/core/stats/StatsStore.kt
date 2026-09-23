package com.hcr.stormroot.core.stats

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object StatsStore {

    const val MODULE_BEDTIME = "bedtime"
    const val MODULE_DOOMSCROLL = "doomscroll"
    const val MODULE_ROOTS = "roots"
    private val MODULES = listOf(MODULE_BEDTIME, MODULE_DOOMSCROLL, MODULE_ROOTS)

    private const val PREFS_NAME = "stats_store_prefs"
    private const val RETENTION_DAYS = 60

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Every write key here ends in a yyyyMMdd day suffix (nudge_count_*, nudge_millis_*,
    // bedtime_first_tod_*, effect_shown_*), and none of it is ever cleaned up otherwise, so this
    // prefs file grows forever over the app's lifetime. Prune once per process per day instead.
    private var lastPrunedDay: String? = null

    private fun pruneOldEntriesIfNeeded(context: Context) {
        val today = dayKey(System.currentTimeMillis())
        if (lastPrunedDay == today) return
        lastPrunedDay = today
        val cutoffKey = dayKey(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -RETENTION_DAYS) }.timeInMillis)
        val p = prefs(context)
        val toRemove = p.all.keys.filter { key ->
            val suffix = key.takeLast(8)
            suffix.length == 8 && suffix.all { it.isDigit() } && suffix < cutoffKey
        }
        if (toRemove.isNotEmpty()) {
            p.edit { toRemove.forEach { remove(it) } }
        }
    }

    private fun dayKey(millis: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(millis))

    private fun dayKeysBack(count: Int, endOffsetDays: Int = 0): List<String> {
        val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -endOffsetDays) }
        val keys = mutableListOf<String>()
        repeat(count) {
            keys.add(0, dayKey(calendar.timeInMillis))
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return keys
    }

    fun recordNudgeSession(context: Context, module: String, startMillis: Long, endMillis: Long) {
        pruneOldEntriesIfNeeded(context)
        if (endMillis <= startMillis) return
        val nextMidnight = startOfNextDay(startMillis)
        if (endMillis <= nextMidnight) {
            recordWithinSingleDay(context, module, startMillis, endMillis)
        } else {
            recordWithinSingleDay(context, module, startMillis, nextMidnight)
            recordNudgeSession(context, module, nextMidnight, endMillis)
        }
    }

    private fun recordWithinSingleDay(context: Context, module: String, startMillis: Long, endMillis: Long) {
        val day = dayKey(startMillis)
        val durationMs = endMillis - startMillis
        val p = prefs(context)
        val countKey = "nudge_count_${module}_$day"
        val millisKey = "nudge_millis_${module}_$day"
        p.edit {
            putInt(countKey, p.getInt(countKey, 0) + 1)
            putLong(millisKey, p.getLong(millisKey, 0L) + durationMs)
        }
        if (module == MODULE_BEDTIME) {
            val firstKey = "bedtime_first_tod_$day"
            if (!p.contains(firstKey)) {
                val calendar = Calendar.getInstance().apply { timeInMillis = startMillis }
                val minutesOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
                p.edit { putInt(firstKey, minutesOfDay) }
            }
        }
    }

    private fun startOfNextDay(millis: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = millis
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun countForDay(context: Context, day: String, module: String? = null): Int {
        val p = prefs(context)
        return if (module != null) {
            p.getInt("nudge_count_${module}_$day", 0)
        } else {
            MODULES.sumOf { p.getInt("nudge_count_${it}_$day", 0) }
        }
    }

    private fun minutesForDay(context: Context, day: String, module: String? = null): Int {
        val p = prefs(context)
        val millis = if (module != null) {
            p.getLong("nudge_millis_${module}_$day", 0L)
        } else {
            MODULES.sumOf { p.getLong("nudge_millis_${it}_$day", 0L) }
        }
        return (millis / 60_000L).toInt()
    }

    // A minute at 15%-opacity Stage 1 shouldn't count the same as a minute at full-intensity
    // Stage 3 towards "not calm" — callers report their current intensity each tick via
    // recordNudgeIntensityTick, and this is what calmPercentForToday actually subtracts.
    fun recordNudgeIntensityTick(context: Context, module: String, intensity: Float, tickMillis: Long) {
        if (intensity <= 0f) return
        val day = dayKey(System.currentTimeMillis())
        val weightedMillis = (tickMillis * intensity.coerceIn(0f, 1f)).toLong()
        val key = "nudge_weighted_millis_${module}_$day"
        val p = prefs(context)
        p.edit { putLong(key, p.getLong(key, 0L) + weightedMillis) }
    }

    private fun weightedMinutesForDay(context: Context, day: String): Int {
        val p = prefs(context)
        val millis = MODULES.sumOf { p.getLong("nudge_weighted_millis_${it}_$day", 0L) }
        return (millis / 60_000L).toInt()
    }

    fun totalNudgeCount(context: Context, days: Int, endOffsetDays: Int = 0): Int =
        dayKeysBack(days, endOffsetDays).sumOf { countForDay(context, it) }

    fun totalNudgeMinutes(context: Context, days: Int, endOffsetDays: Int = 0): Int =
        dayKeysBack(days, endOffsetDays).sumOf { minutesForDay(context, it) }

    fun activeDayCount(context: Context, days: Int, endOffsetDays: Int = 0): Int =
        dayKeysBack(days, endOffsetDays).count { countForDay(context, it) > 0 }

    fun bedtimeActiveDayCount(context: Context, days: Int, endOffsetDays: Int = 0): Int =
        dayKeysBack(days, endOffsetDays).count { countForDay(context, it, MODULE_BEDTIME) > 0 }

    fun calmPercentForToday(context: Context): Int {
        val weightedNudgedMinutes = weightedMinutesForDay(context, dayKey(System.currentTimeMillis()))
        val now = Calendar.getInstance()
        val elapsedMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (elapsedMinutes <= 0) return 100
        val calmMinutes = (elapsedMinutes - weightedNudgedMinutes).coerceAtLeast(0)
        return ((calmMinutes * 100f) / elapsedMinutes).roundToInt().coerceIn(0, 100)
    }

    fun weeklyCompletionFlags(context: Context): List<Boolean> =
        dayKeysBack(7).map { countForDay(context, it) > 0 }

    fun averageBedtimeNudgeMinutesOfDay(context: Context, days: Int, endOffsetDays: Int = 0): Int? {
        val p = prefs(context)
        val values = dayKeysBack(days, endOffsetDays).mapNotNull { day ->
            val key = "bedtime_first_tod_$day"
            if (p.contains(key)) p.getInt(key, 0) else null
        }
        if (values.isEmpty()) return null
        return values.average().roundToInt()
    }

    fun percentImprovement(current: Int, previous: Int): Int? {
        if (previous <= 0) return null
        return (((previous - current).toFloat() / previous) * 100).roundToInt()
    }

    // Lightweight per-module/effect usage counter so it's possible to tell which overlay
    // effects (fog, fire, snow, ...) are actually shown to users, not just which module fired.
    fun recordEffectShown(context: Context, module: String, effect: String) {
        pruneOldEntriesIfNeeded(context)
        val day = dayKey(System.currentTimeMillis())
        val key = "effect_shown_${module}_${effect}_$day"
        val p = prefs(context)
        p.edit { putInt(key, p.getInt(key, 0) + 1) }
    }

    fun effectShownCount(context: Context, module: String, effect: String, days: Int, endOffsetDays: Int = 0): Int {
        val p = prefs(context)
        return dayKeysBack(days, endOffsetDays).sumOf { day -> p.getInt("effect_shown_${module}_${effect}_$day", 0) }
    }
}
