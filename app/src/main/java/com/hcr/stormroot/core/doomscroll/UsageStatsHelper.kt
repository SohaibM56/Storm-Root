package com.hcr.stormroot.core.doomscroll

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

object UsageStatsHelper {

    fun todayUsageMinutes(context: Context, packages: Set<String>, resetHour: Int): Int {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val windowStart = resetTimeMillis(now, resetHour)
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, windowStart, now)
            ?: return 0
        val totalMs = stats.filter { it.packageName in packages }.sumOf { it.totalTimeInForeground }
        return (totalMs / 60_000L).toInt()
    }

    private const val FOREGROUND_LOOKBACK_MS = 6 * 60 * 60 * 1000L

    fun currentForegroundPackage(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - FOREGROUND_LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var currentForegroundPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> currentForegroundPackage = event.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (event.packageName == currentForegroundPackage) currentForegroundPackage = null
                }
            }
        }
        return currentForegroundPackage
    }

    private fun resetTimeMillis(nowMillis: Long, resetHour: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, resetHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis > nowMillis) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return calendar.timeInMillis
    }
}
