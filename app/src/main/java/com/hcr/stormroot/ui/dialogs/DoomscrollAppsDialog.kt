package com.hcr.stormroot.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.hcr.stormroot.R
import com.hcr.stormroot.core.doomscroll.DoomscrollPrefs
import com.hcr.stormroot.core.doomscroll.InstalledApp
import com.hcr.stormroot.core.doomscroll.InstalledAppsHelper
import com.hcr.stormroot.databinding.DialogDoomscrollAppsBinding
import kotlin.math.abs
import androidx.core.graphics.drawable.toDrawable

object DoomscrollAppsDialog {

    private val STEPS_MINUTES = (5..240 step 15).toList()

    fun show(context: Context, onConfirmed: () -> Unit, onCancelled: () -> Unit = {}) {
        val binding = DialogDoomscrollAppsBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(binding.root)
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }

        val selectedPackages = DoomscrollPrefs.getTargetPackages(context).toMutableSet()
        val adapter = InstalledAppsAdapter(selectedPackages)
        binding.appsRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.appsRecyclerView.adapter = adapter
        binding.appsRecyclerView.setHasFixedSize(true)

        val currentLimit = DoomscrollPrefs.getDailyLimitMinutes(context)
        binding.limitPicker.minValue = 0
        binding.limitPicker.maxValue = STEPS_MINUTES.size - 1
        binding.limitPicker.displayedValues = STEPS_MINUTES.map { "$it min" }.toTypedArray()
        binding.limitPicker.wrapSelectorWheel = false
        binding.limitPicker.value = STEPS_MINUTES.indices
            .minByOrNull { abs(STEPS_MINUTES[it] - currentLimit) } ?: 0

        dialog.setOnCancelListener { onCancelled() }
        binding.cancelButton.setOnClickListener { dialog.cancel() }
        binding.saveButton.setOnClickListener {
            DoomscrollPrefs.setTargetPackages(context, selectedPackages)
            DoomscrollPrefs.setDailyLimitMinutes(context, STEPS_MINUTES[binding.limitPicker.value])
            dialog.dismiss()
            onConfirmed()
        }

        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

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
