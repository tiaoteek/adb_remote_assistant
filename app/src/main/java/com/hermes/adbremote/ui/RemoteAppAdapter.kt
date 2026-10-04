package com.hermes.adbremote.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hermes.adbremote.databinding.ItemRemoteAppBinding
import com.hermes.adbremote.model.RemoteAppInfo

class RemoteAppAdapter(
    private var appList: List<RemoteAppInfo>,
    private val onStopClick: (RemoteAppInfo) -> Unit
) : RecyclerView.Adapter<RemoteAppAdapter.ViewHolder>() {

    private var filteredList = appList.toList()

    inner class ViewHolder(val binding: ItemRemoteAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRemoteAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = filteredList.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = filteredList[position]
        holder.binding.tvPackageName.text = app.packageName
        holder.binding.tvAppType.text = if (app.isSystemApp) "系统应用" else "第三方应用"
        
        holder.binding.btnForceStop.setOnClickListener {
            onStopClick(app)
        }
    }

    fun updateData(newList: List<RemoteAppInfo>) {
        appList = newList
        filteredList = newList
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        filteredList = if (query.isEmpty()) {
            appList
        } else {
            appList.filter { it.packageName.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }
}
