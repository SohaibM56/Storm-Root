package com.hcr.stormroot.ui.dialogs

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
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
        binding.appsRecyclerView.layoutManager =
            GridLayoutManager(context, 3, GridLayoutManager.HORIZONTAL, false)
        binding.appsRecyclerView.adapter = adapter
        binding.appsRecyclerView.setHasFixedSize(true)

        binding.limitStepper.min = STEPS_MINUTES.first
        binding.limitStepper.max = STEPS_MINUTES.last
        binding.limitStepper.step = STEPS_MINUTES.step
        binding.limitStepper.formatter = MinutesFormatter::format
        binding.limitStepper.setPresets(LIMIT_PRESETS)
        binding.limitStepper.value = DoomscrollPrefs.getDailyLimitMinutes(context)

        OverlayEffectOptions.bindWheel(binding.overlayWheel, DoomscrollPrefs.getOverlayEffect(context))

        binding.rampStepper.min = 5
        binding.rampStepper.max = 120
        binding.rampStepper.step = 5
        binding.rampStepper.formatter = MinutesFormatter::format
        binding.rampStepper.setPresets(RAMP_PRESETS)
        binding.rampStepper.value = DoomscrollPrefs.getRampMinutes(context)

        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onCancelled() }
        binding.cancelButton.setOnClickListener { dialog.cancel() }
        binding.closeButton.setOnClickListener { dialog.cancel() }
        binding.saveButton.setOnClickListener {
            val chosenEffect = OverlayEffectOptions.selectedEffect(binding.overlayWheel, DoomscrollPrefs.DEFAULT_OVERLAY_EFFECT)

            fun performSave() {
                DoomscrollPrefs.setTargetPackages(context, selectedPackages)
                DoomscrollPrefs.setDailyLimitMinutes(context, binding.limitStepper.value)
                DoomscrollPrefs.setOverlayEffect(context, chosenEffect)
                DoomscrollPrefs.setRampMinutes(context, binding.rampStepper.value)
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
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                // Material's default bottomSheetStyle background (a gray/surface rounded shape)
                // was peeking out from behind our own bg_bottom_sheet drawable at the top edge.
                sheet.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(sheet)
                // Measure the sheet's real attached parent instead of trusting
                // displayMetrics.heightPixels, which doesn't reliably match the actual
                // CoordinatorLayout height available here (edge-to-edge insets caused it to
                // under/over-shoot, producing a gap at the bottom or the sheet growing too tall).
                val parentHeight = (sheet.parent as? View)?.height ?: context.resources.displayMetrics.heightPixels
                val targetHeight = ((parentHeight * 0.72f) + 300 * context.resources.displayMetrics.density).toInt()
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

        fun showApps(apps: List<InstalledApp>) {
            binding.appsLoading.visibility = View.GONE
            if (apps.isEmpty()) {
                binding.appsEmpty.visibility = View.VISIBLE
                binding.appsRecyclerView.visibility = View.GONE
            } else {
                binding.appsEmpty.visibility = View.GONE
                binding.appsRecyclerView.visibility = View.VISIBLE
            }
            adapter.submitApps(apps)
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
