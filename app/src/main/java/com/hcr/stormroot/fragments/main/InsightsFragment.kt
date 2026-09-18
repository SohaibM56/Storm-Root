package com.hcr.stormroot.fragments.main

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.textview.MaterialTextView
import com.hcr.stormroot.R
import com.hcr.stormroot.core.stats.StatsStore
import com.hcr.stormroot.databinding.FragmentInsightsBinding
import com.hcr.stormroot.databinding.ItemWeekDayBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class InsightsFragment : Fragment() {

    private lateinit var binding: FragmentInsightsBinding
    private var selectedPeriod = Period.WEEK

    private enum class Period(val days: Int) { DAY(1), WEEK(7), MONTH(30) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentInsightsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val periodTabs = listOf(
            binding.periodDay to Period.DAY,
            binding.periodWeek to Period.WEEK,
            binding.periodMonth to Period.MONTH
        )
        periodTabs.forEach { (tabView, period) ->
            tabView.setOnClickListener { selectPeriod(period, periodTabs, animate = true) }
        }
        selectPeriod(Period.WEEK, periodTabs, animate = false)

        renderMindfulRhythm()
    }

    override fun onResume() {
        super.onResume()
        updatePeriodStats()
        renderMindfulRhythm()
    }

    private fun selectPeriod(selected: Period, tabs: List<Pair<MaterialTextView, Period>>, animate: Boolean) {
        if (animate) {
            TransitionManager.beginDelayedTransition(binding.periodTabsRow, AutoTransition().setDuration(180))
        }
        tabs.forEach { (view, period) ->
            val isSelected = period == selected
            view.background = if (isSelected) requireContext().getDrawable(R.drawable.bg_pill_white) else null
            view.setTextColor(requireContext().getColorStateList(if (isSelected) R.color.nav_active else R.color.text_secondary))
            view.setTypeface(view.typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            if (isSelected && animate) {
                view.animate().cancel()
                view.scaleX = 0.92f
                view.scaleY = 0.92f
                view.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
        }
        selectedPeriod = selected
        updatePeriodStats()
    }

    private fun updatePeriodStats() {
        val context = requireContext()
        val days = selectedPeriod.days

        val reclaimedMinutes = StatsStore.totalNudgeMinutes(context, days)
        binding.reclaimedLabel.setText(
            when (selectedPeriod) {
                Period.DAY -> R.string.insights_reclaimed_label_day
                Period.WEEK -> R.string.insights_reclaimed_label_week
                Period.MONTH -> R.string.insights_reclaimed_label_month
            }
        )
        binding.reclaimedSavedLabel.setText(
            when (selectedPeriod) {
                Period.DAY -> R.string.insights_reclaimed_saved_day
                Period.WEEK -> R.string.insights_reclaimed_saved_week
                Period.MONTH -> R.string.insights_reclaimed_saved_month
            }
        )
        binding.reclaimedValue.text = reclaimedMinutes.toString()

        val nudgeCount = StatsStore.totalNudgeCount(context, days)
        val previousNudgeCount = StatsStore.totalNudgeCount(context, days, endOffsetDays = days)
        binding.nudgesValue.text = getString(R.string.insights_nudges_value_format, nudgeCount)
        binding.nudgesPill.text = getString(R.string.insights_nudges_pill_format, nudgeCount - previousNudgeCount)

        val sleepMinutesOfDay = StatsStore.averageBedtimeNudgeMinutesOfDay(context, days)
        binding.sleepValue.text = if (sleepMinutesOfDay != null) {
            getString(R.string.insights_sleep_value_format, formatMinutesOfDay(sleepMinutesOfDay))
        } else {
            getString(R.string.insights_sleep_no_data)
        }
        val nightsOnTarget = days - StatsStore.bedtimeActiveDayCount(context, days)
        binding.sleepPill.text = getString(R.string.insights_sleep_pill_format, nightsOnTarget)
    }

    private fun renderMindfulRhythm() {
        val context = requireContext()
        val flags = StatsStore.weeklyCompletionFlags(context)
        val restedDays = flags.count { it }

        binding.rhythmTitle.text = getString(R.string.insights_rest_title_format, restedDays, flags.size)

        val currentMinutes = StatsStore.totalNudgeMinutes(context, 7)
        val previousMinutes = StatsStore.totalNudgeMinutes(context, 7, endOffsetDays = 7)
        val improvement = StatsStore.percentImprovement(currentMinutes, previousMinutes)
        binding.rhythmBadge.text = when {
            improvement == null -> getString(R.string.insights_rhythm_badge_neutral)
            improvement >= 0 -> getString(R.string.insights_rhythm_badge_calmer_format, improvement)
            else -> getString(R.string.insights_rhythm_badge_busier_format, -improvement)
        }

        val weeklyNudgeCount = StatsStore.totalNudgeCount(context, 7)
        binding.rhythmFooter.text = HtmlCompat.fromHtml(
            getString(R.string.insights_footer_format, weeklyNudgeCount),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )

        binding.weekDaysRow.removeAllViews()
        val dayLabels = lastSevenDayLabels()
        flags.forEachIndexed { index, completed ->
            val dayBinding = ItemWeekDayBinding.inflate(layoutInflater, binding.weekDaysRow, true)
            dayBinding.dayLabel.text = dayLabels[index]
            if (completed) {
                dayBinding.dayCircle.background = requireContext().getDrawable(R.drawable.bg_avatar_circle)
                dayBinding.dayCheck.visibility = View.VISIBLE
                dayBinding.dayDash.visibility = View.GONE
            } else {
                dayBinding.dayCircle.background = requireContext().getDrawable(R.drawable.bg_circle_gray)
                dayBinding.dayCheck.visibility = View.GONE
                dayBinding.dayDash.visibility = View.VISIBLE
            }
        }
    }

    private fun lastSevenDayLabels(): List<String> {
        val format = SimpleDateFormat("EEEEE", Locale.getDefault())
        val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -6) }
        return (0 until 7).map {
            val label = format.format(calendar.time)
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            label
        }
    }

    private fun formatMinutesOfDay(minutesOfDay: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutesOfDay / 60)
            set(Calendar.MINUTE, minutesOfDay % 60)
        }
        val is24Hour = android.text.format.DateFormat.is24HourFormat(requireContext())
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(calendar.time).uppercase(Locale.getDefault())
    }
}
