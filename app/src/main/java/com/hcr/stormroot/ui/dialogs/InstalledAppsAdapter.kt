package com.hcr.stormroot.ui.dialogs

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hcr.stormroot.core.doomscroll.InstalledApp
import com.hcr.stormroot.databinding.ItemAppCheckboxBinding

private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<InstalledApp>() {
    override fun areItemsTheSame(oldItem: InstalledApp, newItem: InstalledApp) =
        oldItem.packageName == newItem.packageName

    override fun areContentsTheSame(oldItem: InstalledApp, newItem: InstalledApp) =
        oldItem == newItem
}

class InstalledAppsAdapter(
    private val selectedPackages: MutableSet<String>
) : ListAdapter<InstalledApp, InstalledAppsAdapter.ViewHolder>(DIFF_CALLBACK) {

    fun submitApps(newApps: List<InstalledApp>) {
        submitList(newApps.sortedByDescending { it.packageName in selectedPackages })
    }

    class ViewHolder(val binding: ItemAppCheckboxBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppCheckboxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = getItem(position)
        val binding = holder.binding
        binding.appIcon.setImageDrawable(app.icon)
        binding.appLabel.text = app.label
        binding.appCheckbox.setOnCheckedChangeListener(null)
        binding.appCheckbox.isChecked = selectedPackages.contains(app.packageName)
        binding.appCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
        }
        binding.root.setOnClickListener { binding.appCheckbox.toggle() }
    }
}
