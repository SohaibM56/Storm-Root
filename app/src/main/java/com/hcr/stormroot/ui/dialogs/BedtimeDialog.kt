package com.hcr.stormroot.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hcr.stormroot.core.bedtime.BedtimeDriftPrefs
import com.hcr.stormroot.databinding.DialogBedtimeBinding

object BedtimeDialog {

    private val STEPS_DRIFT_MINUTES = 15..180 step 15
    private val DRIFT_PRESETS = listOf(30, 60, 90, 120)

    fun show(context: Context, onConfirmed: () -> Unit, onCancelled: () -> Unit = {}) {
        val binding = DialogBedtimeBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)

        val hour24 = BedtimeDriftPrefs.getBedtimeHour(context)
        val minute = BedtimeDriftPrefs.getBedtimeMinute(context)
        val isPm = hour24 >= 12
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }

        binding.hourStepper.min = 1
        binding.hourStepper.max = 12
        binding.hourStepper.step = 1
        binding.hourStepper.formatter = { it.toString() }
        binding.hourStepper.value = hour12

        binding.minuteStepper.min = 0
        binding.minuteStepper.max = 55
        binding.minuteStepper.step = 5
        binding.minuteStepper.formatter = { String.format("%02d", it) }
        binding.minuteStepper.value = ((minute + 2) / 5 * 5).coerceIn(0, 55)

        binding.amChip.isChecked = !isPm
        binding.pmChip.isChecked = isPm

        OverlayEffectOptions.bind(binding.overlayChipGroup, BedtimeDriftPrefs.getOverlayEffect(context))

        binding.driftWindowStepper.min = STEPS_DRIFT_MINUTES.first
        binding.driftWindowStepper.max = STEPS_DRIFT_MINUTES.last
        binding.driftWindowStepper.step = STEPS_DRIFT_MINUTES.step
        binding.driftWindowStepper.formatter = MinutesFormatter::format
        binding.driftWindowStepper.setPresets(DRIFT_PRESETS)
        binding.driftWindowStepper.value = BedtimeDriftPrefs.getDriftWindowMinutes(context)

        dialog.setOnCancelListener { onCancelled() }
        binding.cancelButton.setOnClickListener { dialog.cancel() }
        binding.saveButton.setOnClickListener {
            val hour12Value = binding.hourStepper.value
            val pm = binding.pmChip.isChecked
            val hour24Value = when {
                hour12Value == 12 && !pm -> 0
                hour12Value == 12 && pm -> 12
                pm -> hour12Value + 12
                else -> hour12Value
            }
            BedtimeDriftPrefs.setBedtime(context, hour24Value, binding.minuteStepper.value)
            BedtimeDriftPrefs.setOverlayEffect(
                context,
                OverlayEffectOptions.selectedEffect(binding.overlayChipGroup, BedtimeDriftPrefs.DEFAULT_OVERLAY_EFFECT)
            )
            BedtimeDriftPrefs.setDriftWindowMinutes(context, binding.driftWindowStepper.value)
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
