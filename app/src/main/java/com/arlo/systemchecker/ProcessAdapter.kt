package com.arlo.systemchecker

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.arlo.systemchecker.databinding.ItemProcessBinding

class ProcessAdapter : ListAdapter<ProcessInfo, ProcessAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemProcessBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProcessBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            processName.text = item.label
            processPackage.text = item.packageName
            processScope.text = item.scopeLabel
            processStatus.text = item.status.name
            // CPU / memory are only real for our own process.
            // Everything else is "—" — never fabricated. See ProcessRepository.
            processCpu.text = item.cpuPercent?.let { "CPU %.1f%%".format(it) } ?: "CPU —"
            processMemory.text = item.memoryMb?.let { "Memory ${it} MB" } ?: "Memory —"
            processStart.text = item.startTimeMillis?.let {
                "Started ${DateFormat.format("dd MMM HH:mm", it)}"
            } ?: item.lastUsedMillis?.let {
                "Last used ${DateFormat.format("dd MMM HH:mm", it)}"
            } ?: "Start time —"
            processSource.text = "Source: ${item.source}" +
                if (item.isTrusted) " • Trusted" else " • Not trusted"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ProcessInfo>() {
            override fun areItemsTheSame(old: ProcessInfo, new: ProcessInfo) =
                old.packageName == new.packageName

            override fun areContentsTheSame(old: ProcessInfo, new: ProcessInfo) =
                old == new
        }
    }
}
