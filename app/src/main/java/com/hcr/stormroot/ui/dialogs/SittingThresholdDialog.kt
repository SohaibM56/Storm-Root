package com.hcr.stormroot.ui.dialogs

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hcr.stormroot.core.sitting.SittingRootsPrefs
import com.hcr.stormroot.databinding.DialogSittingThresholdBinding

object SittingThresholdDialog {

    private val STEPS_MINUTES = 5..180 step 5
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

        OverlayEffectOptions.bindWheel(binding.overlayWheel, SittingRootsPrefs.getOverlayEffect(context))

        binding.rampStepper.min = 5
        binding.rampStepper.max = 120
        binding.rampStepper.step = 5
        binding.rampStepper.formatter = MinutesFormatter::format
        binding.rampStepper.setPresets(RAMP_PRESETS)
        binding.rampStepper.value = SittingRootsPrefs.getGrowthRampMinutes(context)

        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onCancelled() }
        binding.cancelButton.setOnClickListener { dialog.cancel() }
        binding.closeButton.setOnClickListener { dialog.cancel() }
        binding.saveButton.setOnClickListener {
            val chosenEffect = OverlayEffectOptions.selectedEffect(binding.overlayWheel, SittingRootsPrefs.DEFAULT_OVERLAY_EFFECT)

            fun performSave() {
                SittingRootsPrefs.setSittingThresholdMinutes(context, binding.thresholdStepper.value)
                SittingRootsPrefs.setOverlayEffect(context, chosenEffect)
                SittingRootsPrefs.setGrowthRampMinutes(context, binding.rampStepper.value)
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
                val targetHeight = (parentHeight * 0.72f).toInt().coerceAtMost(parentHeight)
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
