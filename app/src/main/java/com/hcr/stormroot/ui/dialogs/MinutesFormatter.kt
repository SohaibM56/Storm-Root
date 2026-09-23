package com.hcr.stormroot.ui.dialogs

object MinutesFormatter {
    fun format(minutes: Int): String {
        if (minutes < 60) return "$minutes min"
        val hours = minutes / 60
        val remaining = minutes % 60
        return if (remaining == 0) "${hours}h" else "${hours}h ${remaining}m"
    }
}
