package com.hcr.stormroot.ui.dialogs

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hcr.stormroot.core.doomscroll.InstalledApp
import com.hcr.stormroot.databinding.ItemAppCheckboxBinding

/** Backs the app-picker list with real view recycling — only the handful of rows visible in
 *  the ~220sdp scroll area get inflated, instead of every installed app up front. */
class InstalledAppsAdapter(
    private val selectedPackages: MutableSet<String>
) : RecyclerView.Adapter<InstalledAppsAdapter.ViewHolder>() {

    private var apps: List<InstalledApp> = emptyList()

    fun submitList(newApps: List<InstalledApp>) {
        // Stable sort: apps already come back alphabetical from InstalledAppsHelper,
        // so this keeps each group (selected / not) alphabetical while surfacing
        // currently-targeted apps first.
        apps = newApps.sortedByDescending { it.packageName in selectedPackages }
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemAppCheckboxBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppCheckboxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
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

    override fun getItemCount(): Int = apps.size
}
