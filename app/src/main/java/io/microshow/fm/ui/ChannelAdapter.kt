package io.microshow.fm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.microshow.fm.R
import io.microshow.fm.model.RadioChannel

class ChannelAdapter(
    private var channels: List<RadioChannel>,
    private val onItemClick: (RadioChannel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private var selectedIndex = -1

    fun updateChannels(newChannels: List<RadioChannel>) {
        this.channels = newChannels
        notifyDataSetChanged()
    }

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_channel_name)
        val tvFrequency: TextView = itemView.findViewById(R.id.tv_channel_frequency)
        val tvCategory: TextView = itemView.findViewById(R.id.tv_channel_category)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = channels[position]
        holder.tvName.text = channel.name
        holder.tvFrequency.text = channel.frequency
        holder.tvCategory.text = channel.category

        val isSelected = position == selectedIndex
        holder.itemView.setBackgroundColor(
            if (isSelected) 0x1A1E88E5 else 0x00000000
        )

        holder.itemView.setOnClickListener {
            val prev = selectedIndex
            selectedIndex = holder.adapterPosition
            notifyItemChanged(prev)
            notifyItemChanged(selectedIndex)
            onItemClick(channel)
        }
    }

    override fun getItemCount(): Int = channels.size
}
