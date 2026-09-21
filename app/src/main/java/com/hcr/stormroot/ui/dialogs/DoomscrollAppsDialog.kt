package com.hcr.stormroot.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hcr.stormroot.R
import com.hcr.stormroot.core.doomscroll.DoomscrollPrefs
import com.hcr.stormroot.core.doomscroll.InstalledApp
import com.hcr.stormroot.core.doomscroll.InstalledAppsHelper
import com.hcr.stormroot.databinding.DialogDoomscrollAppsBinding

object DoomscrollAppsDialog {

    private val STEPS_MINUTES = 5..240 step 15
    private val LIMIT_PRESETS = listOf(30, 60, 90, 120, 180)
    private val RAMP_PRESETS = listOf(15, 30, 45, 60, 90)

    fun show(context: Context, onConfirmed: () -> Unit, onCancelled: () -> Unit = {}) {
        val binding = DialogDoomscrollAppsBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)

        val selectedPackages = DoomscrollPrefs.getTargetPackages(context).toMutableSet()
        val adapter = InstalledAppsAdapter(selectedPackages)
        binding.appsRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.appsRecyclerView.adapter = adapter
        binding.appsRecyclerView.setHasFixedSize(true)

        binding.limitStepper.min = STEPS_MINUTES.first
        binding.limitStepper.max = STEPS_MINUTES.last
        binding.limitStepper.step = STEPS_MINUTES.step
        binding.limitStepper.formatter = MinutesFormatter::format
        binding.limitStepper.setPresets(LIMIT_PRESETS)
        binding.limitStepper.value = DoomscrollPrefs.getDailyLimitMinutes(context)

        OverlayEffectOptions.bind(binding.overlayChipGroup, DoomscrollPrefs.getOverlayEffect(context))

        binding.rampStepper.min = 5
        binding.rampStepper.max = 120
        binding.rampStepper.step = 5
        binding.rampStepper.formatter = MinutesFormatter::format
        binding.rampStepper.setPresets(RAMP_PRESETS)
        binding.rampStepper.value = DoomscrollPrefs.getRampMinutes(context)

        dialog.setOnCancelListener { onCancelled() }
        binding.cancelButton.setOnClickListener { dialog.cancel() }
        binding.saveButton.setOnClickListener {
            DoomscrollPrefs.setTargetPackages(context, selectedPackages)
            DoomscrollPrefs.setDailyLimitMinutes(context, binding.limitStepper.value)
            DoomscrollPrefs.setOverlayEffect(
                context,
                OverlayEffectOptions.selectedEffect(binding.overlayChipGroup, DoomscrollPrefs.DEFAULT_OVERLAY_EFFECT)
            )
            DoomscrollPrefs.setRampMinutes(context, binding.rampStepper.value)
            dialog.dismiss()
            onConfirmed()
        }

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                sheet.layoutParams = sheet.layoutParams.apply {
                    height = (context.resources.displayMetrics.heightPixels * 0.72f).toInt()
                }
                sheet.requestLayout()
            }
        }
        dialog.show()

        fun showApps(apps: List<InstalledApp>) {
            binding.appsLoading.visibility = View.GONE
            binding.appsRecyclerView.visibility = View.VISIBLE
            adapter.submitList(apps)
        }

        binding.appsRefresh.setColorSchemeResources(R.color.primary)
        binding.appsRefresh.setOnRefreshListener {
            InstalledAppsHelper.refresh(context) { apps ->
                if (!dialog.isShowing) return@refresh
                showApps(apps)
                binding.appsRefresh.isRefreshing = false
            }
        }

        InstalledAppsHelper.getOrLoad(context) { apps ->
            if (!dialog.isShowing) return@getOrLoad
            showApps(apps)
        }
    }
}
