package com.hcr.stormroot.fragments.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import java.util.Calendar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.hcr.stormroot.R
import com.hcr.stormroot.core.anchors.AnchorStatus
import com.hcr.stormroot.core.bedtime.BedtimeDriftPrefs
import com.hcr.stormroot.core.doomscroll.DoomscrollPrefs
import com.hcr.stormroot.core.overlay.OverlayService
import com.hcr.stormroot.core.permissions.ActivityRecognitionPermission
import com.hcr.stormroot.core.permissions.UsageAccessPermission
import com.hcr.stormroot.core.sitting.SittingRootsPrefs
import com.hcr.stormroot.core.stats.StatsStore
import com.hcr.stormroot.databinding.FragmentOverviewBinding
import com.hcr.stormroot.ui.ModuleTitleFormatter
import com.hcr.stormroot.ui.dialogs.BedtimeDialog
import com.hcr.stormroot.ui.dialogs.DoomscrollAppsDialog
import com.hcr.stormroot.ui.dialogs.OverlayEffectOptions
import com.hcr.stormroot.ui.dialogs.SittingThresholdDialog

class OverviewFragment : Fragment() {

    private lateinit var binding: FragmentOverviewBinding
    private var isRequestingUsageAccess = false

    private val usageAccessSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isRequestingUsageAccess) {
            isRequestingUsageAccess = false
            if (UsageAccessPermission.isGranted(requireContext())) {
                DoomscrollPrefs.setEnabled(requireContext(), true)
                OverlayService.startDoomscrollMonitor(requireContext())
            }
            updateDoomscrollSwitch()
            updateAnchorsCount()
        }
    }

    private val activityRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            SittingRootsPrefs.setEnabled(requireContext(), true)
            OverlayService.startRootsMonitor(requireContext())
        }
        updateSittingRootsSwitch()
        updateAnchorsCount()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentOverviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.bedtimeDriftSwitch.setOnCheckedChangeListener { _, isChecked -> onBedtimeToggled(isChecked) }
        binding.doomscrollMistSwitch.setOnCheckedChangeListener { _, isChecked -> onDoomscrollToggled(isChecked) }
        binding.sittingRootsSwitch.setOnCheckedChangeListener { _, isChecked -> onSittingRootsToggled(isChecked) }
        updateGreeting()
        updateBedtimeDisplay()
        updateBedtimeSwitch()
        updateDoomscrollSwitch()
        updateSittingRootsSwitch()
        updateModuleTitles()
        updateAnchorsCount()
        updateStats()
    }

    override fun onResume() {
        super.onResume()
        updateGreeting()
        updateBedtimeDisplay()
        updateBedtimeSwitch()
        if (!isRequestingUsageAccess) {
            updateDoomscrollSwitch()
        }
        updateSittingRootsSwitch()
        updateModuleTitles()
        updateAnchorsCount()
        updateStats()
    }

    private fun updateModuleTitles() {
        val context = requireContext()
        binding.materialTextView6.text = ModuleTitleFormatter.withMinutes(
            context,
            getString(R.string.anchor_sitting_roots_title),
            SittingRootsPrefs.getSittingThresholdMinutes(context)
        )
        binding.materialTextView5.text = getString(
            R.string.anchor_subtitle_with_overlay_format,
            getString(R.string.anchor_sitting_roots_subtitle),
            getString(OverlayEffectOptions.labelRes(SittingRootsPrefs.getOverlayEffect(context)))
        )

        binding.materialTextView8.text = ModuleTitleFormatter.withMinutes(
            context,
            getString(R.string.anchor_doomscroll_mist_title),
            DoomscrollPrefs.getDailyLimitMinutes(context)
        )
        binding.materialTextView7.text = getString(
            R.string.anchor_subtitle_with_overlay_format,
            getString(R.string.anchor_doomscroll_mist_subtitle),
            getString(OverlayEffectOptions.labelRes(DoomscrollPrefs.getOverlayEffect(context)))
        )
    }

    private fun updateGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greetingRes = when {
            hour < 12 -> R.string.overview_greeting_morning
            hour < 17 -> R.string.overview_greeting_afternoon
            else -> R.string.overview_greeting_evening
        }
        binding.mindfulGreeting.text = getString(greetingRes)
    }

    private fun updateStats() {
        val context = requireContext()
        binding.calmPercentValue.text = getString(R.string.overview_calm_percent_format, StatsStore.calmPercentForToday(context))
        binding.frictionValue.text = getString(R.string.overview_friction_value_format, StatsStore.totalNudgeMinutes(context, 1))
    }

    private fun updateBedtimeDisplay() {
        val context = requireContext()
        val formatted = BedtimeDriftPrefs.formattedBedtime(context)
        binding.windDownValue.text = getString(R.string.overview_wind_down_value_format, formatted)
        binding.bedtimeDriftSubtitle.text = getString(
            R.string.anchor_subtitle_with_overlay_format,
            getString(R.string.anchor_bedtime_drift_subtitle_format, formatted),
            getString(OverlayEffectOptions.labelRes(BedtimeDriftPrefs.getOverlayEffect(context)))
        )
    }

    private fun updateAnchorsCount() {
        binding.anchorsCount.text = getString(R.string.overview_anchors_count_format, AnchorStatus.activeCount(requireContext()))
    }

    private fun updateBedtimeSwitch() {
        val enabled = BedtimeDriftPrefs.isEnabled(requireContext())
        binding.bedtimeDriftSwitch.setOnCheckedChangeListener(null)
        binding.bedtimeDriftSwitch.isChecked = enabled
        binding.bedtimeDriftSwitch.setOnCheckedChangeListener { _, isChecked -> onBedtimeToggled(isChecked) }
    }

    private fun onBedtimeToggled(isChecked: Boolean) {
        if (!isChecked) {
            BedtimeDriftPrefs.setEnabled(requireContext(), false)
            OverlayService.stopMonitor(requireContext())
            updateAnchorsCount()
            return
        }
        updateBedtimeSwitch()
        BedtimeDialog.show(requireContext(), onConfirmed = {
            updateBedtimeDisplay()
            BedtimeDriftPrefs.setEnabled(requireContext(), true)
            OverlayService.startMonitor(requireContext())
            updateBedtimeSwitch()
            updateAnchorsCount()
        })
    }

    private fun updateDoomscrollSwitch() {
        val enabled = DoomscrollPrefs.isEnabled(requireContext()) && UsageAccessPermission.isGranted(requireContext())
        binding.doomscrollMistSwitch.setOnCheckedChangeListener(null)
        binding.doomscrollMistSwitch.isChecked = enabled
        binding.doomscrollMistSwitch.setOnCheckedChangeListener { _, isChecked -> onDoomscrollToggled(isChecked) }
    }

    private fun onDoomscrollToggled(isChecked: Boolean) {
        if (!isChecked) {
            DoomscrollPrefs.setEnabled(requireContext(), false)
            OverlayService.stopDoomscrollMonitor(requireContext())
            updateAnchorsCount()
            return
        }
        updateDoomscrollSwitch()
        DoomscrollAppsDialog.show(requireContext(), onConfirmed = {
            updateModuleTitles()
            enableDoomscroll()
        })
    }

    private fun enableDoomscroll() {
        if (!UsageAccessPermission.isGranted(requireContext())) {
            isRequestingUsageAccess = true
            usageAccessSettingsLauncher.launch(UsageAccessPermission.requestIntent())
            return
        }
        DoomscrollPrefs.setEnabled(requireContext(), true)
        OverlayService.startDoomscrollMonitor(requireContext())
        updateDoomscrollSwitch()
        updateAnchorsCount()
    }

    private fun updateSittingRootsSwitch() {
        val enabled = SittingRootsPrefs.isEnabled(requireContext()) && ActivityRecognitionPermission.isGranted(requireContext())
        binding.sittingRootsSwitch.setOnCheckedChangeListener(null)
        binding.sittingRootsSwitch.isChecked = enabled
        binding.sittingRootsSwitch.setOnCheckedChangeListener { _, isChecked -> onSittingRootsToggled(isChecked) }
    }

    private fun onSittingRootsToggled(isChecked: Boolean) {
        if (!isChecked) {
            SittingRootsPrefs.setEnabled(requireContext(), false)
            OverlayService.stopRootsMonitor(requireContext())
            updateAnchorsCount()
            return
        }
        updateSittingRootsSwitch()
        SittingThresholdDialog.show(requireContext(), onConfirmed = {
            updateModuleTitles()
            enableSittingRoots()
        })
    }

    private fun enableSittingRoots() {
        if (!ActivityRecognitionPermission.isGranted(requireContext())) {
            activityRecognitionLauncher.launch(ActivityRecognitionPermission.PERMISSION)
            return
        }
        SittingRootsPrefs.setEnabled(requireContext(), true)
        OverlayService.startRootsMonitor(requireContext())
        updateSittingRootsSwitch()
        updateAnchorsCount()
    }
}
