package com.hcr.stormroot.ui.dialogs

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.R
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

        val storedHour24 = BedtimeDriftPrefs.getBedtimeHour(context)
        val storedMinute = BedtimeDriftPrefs.getBedtimeMinute(context)
        // The minute stepper only supports 5-minute increments. Round the saved time-of-day as a
        // whole (not just the minute field in isolation) so a value like 23:58 correctly rolls
        // into 00:00 instead of being silently clamped down to 23:55.
        val roundedTotalMinutes = (((storedHour24 * 60 + storedMinute) + 2) / 5 * 5).mod(24 * 60)
        val hour24 = roundedTotalMinutes / 60
        val minute = roundedTotalMinutes % 60
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
        binding.minuteStepper.value = minute

        binding.amChip.isChecked = !isPm
        binding.pmChip.isChecked = isPm

        OverlayEffectOptions.bindWheel(binding.overlayWheel, BedtimeDriftPrefs.getOverlayEffect(context))

        binding.driftWindowStepper.min = STEPS_DRIFT_MINUTES.first
        binding.driftWindowStepper.max = STEPS_DRIFT_MINUTES.last
        binding.driftWindowStepper.step = STEPS_DRIFT_MINUTES.step
        binding.driftWindowStepper.formatter = MinutesFormatter::format
        binding.driftWindowStepper.setPresets(DRIFT_PRESETS)
        binding.driftWindowStepper.value = BedtimeDriftPrefs.getDriftWindowMinutes(context)

        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onCancelled() }
        binding.cancelButton.setOnClickListener { dialog.cancel() }
        binding.closeButton.setOnClickListener { dialog.cancel() }
        binding.saveButton.setOnClickListener {
            val chosenEffect = OverlayEffectOptions.selectedEffect(binding.overlayWheel, BedtimeDriftPrefs.DEFAULT_OVERLAY_EFFECT)

            fun performSave() {
                val hour12Value = binding.hourStepper.value
                val pm = binding.pmChip.isChecked
                val hour24Value = when {
                    hour12Value == 12 && !pm -> 0
                    hour12Value == 12 && pm -> 12
                    pm -> hour12Value + 12
                    else -> hour12Value
                }
                BedtimeDriftPrefs.setBedtime(context, hour24Value, binding.minuteStepper.value)
                BedtimeDriftPrefs.setOverlayEffect(context, chosenEffect)
                BedtimeDriftPrefs.setDriftWindowMinutes(context, binding.driftWindowStepper.value)
                dialog.dismiss()
                onConfirmed()
            }

            if (OverlayEffectOptions.isUnlocked(context, chosenEffect)) {
                performSave()
            } else {
                val activity = context as? Activity ?: return@setOnClickListener
                EffectUnlockFlow.show(activity, chosenEffect, onUnlocked = { performSave() })
            }
        }

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                // Material's default bottomSheetStyle background (a gray/surface rounded shape)
                // was peeking out from behind our own bg_bottom_sheet drawable at the top edge.
                sheet.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(sheet)
                // Measure the sheet's real attached parent instead of trusting
                // displayMetrics.heightPixels, which doesn't reliably match the actual
                // CoordinatorLayout height available here.
                val parentHeight = (sheet.parent as? View)?.height ?: context.resources.displayMetrics.heightPixels
                val targetHeight = ((parentHeight * 0.72f) + 80 * context.resources.displayMetrics.density).toInt()
                    .coerceAtMost(parentHeight)
                sheet.layoutParams = sheet.layoutParams.apply { height = targetHeight }
                behavior.isFitToContents = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = false
                sheet.requestLayout()
            }
        }
        dialog.show()
    }
}
