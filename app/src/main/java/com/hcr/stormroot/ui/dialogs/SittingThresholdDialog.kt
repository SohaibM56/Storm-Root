package com.hcr.stormroot.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hcr.stormroot.core.sitting.SittingRootsPrefs
import com.hcr.stormroot.databinding.DialogSittingThresholdBinding

object SittingThresholdDialog {

    private val STEPS_MINUTES = 1..180 step 5
    private val THRESHOLD_PRESETS = listOf(15, 30, 45, 60, 90)
    private val RAMP_PRESETS = listOf(15, 30, 45, 60, 90)

    fun show(context: Context, onConfirmed: () -> Unit, onCancelled: () -> Unit = {}) {
        val binding = DialogSittingThresholdBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)

        binding.thresholdStepper.min = STEPS_MINUTES.first
        binding.thresholdStepper.max = STEPS_MINUTES.last
        binding.thresholdStepper.step = STEPS_MINUTES.step
        binding.thresholdStepper.formatter = MinutesFormatter::format
        binding.thresholdStepper.setPresets(THRESHOLD_PRESETS)
        binding.thresholdStepper.value = SittingRootsPrefs.getSittingThresholdMinutes(context)

        OverlayEffectOptions.bind(binding.overlayChipGroup, SittingRootsPrefs.getOverlayEffect(context))

        binding.rampStepper.min = 5
        binding.rampStepper.max = 120
        binding.rampStepper.step = 5
        binding.rampStepper.formatter = MinutesFormatter::format
        binding.rampStepper.setPresets(RAMP_PRESETS)
        binding.rampStepper.value = SittingRootsPrefs.getGrowthRampMinutes(context)

        dialog.setOnCancelListener { onCancelled() }
        binding.cancelButton.setOnClickListener { dialog.cancel() }
        binding.saveButton.setOnClickListener {
            SittingRootsPrefs.setSittingThresholdMinutes(context, binding.thresholdStepper.value)
            SittingRootsPrefs.setOverlayEffect(
                context,
                OverlayEffectOptions.selectedEffect(binding.overlayChipGroup, SittingRootsPrefs.DEFAULT_OVERLAY_EFFECT)
            )
            SittingRootsPrefs.setGrowthRampMinutes(context, binding.rampStepper.value)
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
    }
}
