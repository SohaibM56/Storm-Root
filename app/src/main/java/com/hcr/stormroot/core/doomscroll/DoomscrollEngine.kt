package com.hcr.stormroot.core.doomscroll

enum class DoomscrollStage { NONE, LIGHT_RAIN, FOG, STORM }

data class DoomscrollResult(val stage: DoomscrollStage, val percentOfLimit: Float)

object DoomscrollEngine {

    fun calculate(usedMinutesToday: Int, dailyLimitMinutes: Int): DoomscrollResult {
        if (dailyLimitMinutes <= 0) return DoomscrollResult(DoomscrollStage.NONE, 0f)
        val percent = usedMinutesToday.toFloat() / dailyLimitMinutes
        val stage = when {
            percent < 1.0f -> DoomscrollStage.NONE
            percent < 1.3f -> DoomscrollStage.LIGHT_RAIN
            percent < 1.6f -> DoomscrollStage.FOG
            else -> DoomscrollStage.STORM
        }
        return DoomscrollResult(stage, percent)
    }
}
