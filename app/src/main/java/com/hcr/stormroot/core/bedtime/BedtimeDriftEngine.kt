package com.hcr.stormroot.core.bedtime

import java.util.Calendar

enum class DriftStage { NONE, STAGE_1, STAGE_2, STAGE_3 }

data class DriftResult(val stage: DriftStage, val overallProgress: Float)

object BedtimeDriftEngine {
    fun calculate(
        nowMillis: Long,
        bedtimeHour: Int,
        bedtimeMinute: Int,
        driftWindowMinutes: Int,
        snoozedUntilMillis: Long
    ): DriftResult {
        if (nowMillis < snoozedUntilMillis) return DriftResult(DriftStage.NONE, 0f)
        if (driftWindowMinutes <= 0) return DriftResult(DriftStage.NONE, 0f)

        val minutesPast = listOf(0, -1)
            .map { dayOffset -> bedtimeMillisOnDay(nowMillis, bedtimeHour, bedtimeMinute, dayOffset) }
            .map { bedtimeMillis -> (nowMillis - bedtimeMillis) / 60_000.0 }
            .firstOrNull { diff -> diff >= 0 && diff <= driftWindowMinutes }
            ?: return DriftResult(DriftStage.NONE, 0f)

        val overallProgress = (minutesPast / driftWindowMinutes).toFloat().coerceIn(0f, 1f)
        val stageWindow = driftWindowMinutes / 3.0
        val stage = when {
            minutesPast <= stageWindow -> DriftStage.STAGE_1
            minutesPast <= stageWindow * 2 -> DriftStage.STAGE_2
            else -> DriftStage.STAGE_3
        }
        return DriftResult(stage, overallProgress)
    }

    private fun bedtimeMillisOnDay(referenceMillis: Long, hour: Int, minute: Int, dayOffset: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = referenceMillis
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
