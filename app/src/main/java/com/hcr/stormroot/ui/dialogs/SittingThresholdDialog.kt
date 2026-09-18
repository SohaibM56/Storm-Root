package com.hcr.stormroot.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import android.view.WindowManager
import com.hcr.stormroot.core.sitting.SittingRootsPrefs
import com.hcr.stormroot.databinding.DialogSittingThresholdBinding
import kotlin.math.abs
import androidx.core.graphics.drawable.toDrawable

object SittingThresholdDialog {

    private val STEPS_MINUTES = (1..180 step 5).toList()

    fun show(context: Context, onConfirmed: () -> Unit, onCancelled: () -> Unit = {}) {
        val binding = DialogSittingThresholdBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(binding.root)
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }

        val currentMinutes = SittingRootsPrefs.getSittingThresholdMinutes(context)
        binding.thresholdPicker.minValue = 0
        binding.thresholdPicker.maxValue = STEPS_MINUTES.size - 1
        binding.thresholdPicker.displayedValues = STEPS_MINUTES.map { "$it min" }.toTypedArray()
        binding.thresholdPicker.wrapSelectorWheel = false
        binding.thresholdPicker.value = STEPS_MINUTES.indices
            .minByOrNull { abs(STEPS_MINUTES[it] - currentMinutes) } ?: 0

        dialog.setOnCancelListener { onCancelled() }
        binding.cancelButton.setOnClickListener { dialog.cancel() }
        binding.saveButton.setOnClickListener {
            SittingRootsPrefs.setSittingThresholdMinutes(context, STEPS_MINUTES[binding.thresholdPicker.value])
            dialog.dismiss()
            onConfirmed()
        }

        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }
}
