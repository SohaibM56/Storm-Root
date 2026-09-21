package com.hcr.stormroot.ui.dialogs

/** Formats a minute count for picker display — plain minutes below an hour, "1h" /
 *  "1h 30m" once it crosses 60, since "90 min" is harder to eyeball than "1h 30m". */
object MinutesFormatter {
    fun format(minutes: Int): String {
        if (minutes < 60) return "$minutes min"
        val hours = minutes / 60
        val remaining = minutes % 60
        return if (remaining == 0) "${hours}h" else "${hours}h ${remaining}m"
    }
}
