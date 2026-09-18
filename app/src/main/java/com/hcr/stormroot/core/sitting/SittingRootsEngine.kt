package com.hcr.stormroot.core.sitting

object SittingRootsEngine {

    fun calculateGrowth(sedentaryMinutes: Int, thresholdMinutes: Int, rampMinutes: Int): Float {
        if (sedentaryMinutes < thresholdMinutes) return 0f
        if (rampMinutes <= 0) return 1f
        val progress = (sedentaryMinutes - thresholdMinutes).toFloat() / rampMinutes
        return progress.coerceIn(0f, 1f)
    }
}
