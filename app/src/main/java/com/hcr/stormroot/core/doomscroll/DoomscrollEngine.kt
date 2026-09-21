package com.hcr.stormroot.core.doomscroll

enum class DoomscrollStage { NONE, LIGHT_RAIN, FOG, STORM }

data class DoomscrollResult(val stage: DoomscrollStage, val rampProgress: Float)

object DoomscrollEngine {

    fun calculate(usedMinutesToday: Int, dailyLimitMinutes: Int, rampMinutes: Int): DoomscrollResult {
        if (dailyLimitMinutes <= 0) return DoomscrollResult(DoomscrollStage.NONE, 0f)
        val minutesOverLimit = usedMinutesToday - dailyLimitMinutes
        if (minutesOverLimit <= 0) return DoomscrollResult(DoomscrollStage.NONE, 0f)

        val ramp = rampMinutes.coerceAtLeast(1)
        val progress = (minutesOverLimit.toFloat() / ramp).coerceIn(0f, 1f)
        val stage = when {
            progress < 1f / 3f -> DoomscrollStage.LIGHT_RAIN
            progress < 2f / 3f -> DoomscrollStage.FOG
            else -> DoomscrollStage.STORM
        }
        return DoomscrollResult(stage, progress)
    }
}
