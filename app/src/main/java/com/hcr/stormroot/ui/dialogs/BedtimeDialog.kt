package com.hcr.stormroot.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.Window
import android.view.WindowManager
import com.hcr.stormroot.core.bedtime.BedtimeDriftPrefs
import com.hcr.stormroot.databinding.DialogBedtimeBinding
import androidx.core.graphics.drawable.toDrawable

object BedtimeDialog {

    fun show(context: Context, onConfirmed: () -> Unit, onCancelled: () -> Unit = {}) {
        val binding = DialogBedtimeBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(binding.root)
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }

        binding.bedtimePicker.setIs24HourView(DateFormat.is24HourFormat(context))
        binding.bedtimePicker.hour = BedtimeDriftPrefs.getBedtimeHour(context)
        binding.bedtimePicker.minute = BedtimeDriftPrefs.getBedtimeMinute(context)

        dialog.setOnCancelListener { onCancelled() }
        binding.cancelButton.setOnClickListener { dialog.cancel() }
        binding.saveButton.setOnClickListener {
            BedtimeDriftPrefs.setBedtime(context, binding.bedtimePicker.hour, binding.bedtimePicker.minute)
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
