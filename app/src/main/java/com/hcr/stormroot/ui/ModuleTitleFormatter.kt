package com.hcr.stormroot.ui

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.core.content.ContextCompat
import com.hcr.stormroot.R
import com.hcr.stormroot.ui.dialogs.MinutesFormatter

object ModuleTitleFormatter {
    fun withMinutes(context: Context, title: String, minutes: Int): CharSequence {
        val suffix = context.getString(R.string.module_title_minutes_suffix, MinutesFormatter.format(minutes))
        val full = "$title $suffix"
        val suffixStart = title.length + 1
        return SpannableString(full).apply {
            setSpan(RelativeSizeSpan(0.78f), suffixStart, full.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, R.color.text_secondary)),
                suffixStart,
                full.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
