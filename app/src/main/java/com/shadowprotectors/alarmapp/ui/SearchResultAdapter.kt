package com.shadowprotectors.alarmapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shadowprotectors.alarmapp.databinding.ItemSearchSuggestionBinding
import com.shadowprotectors.alarmapp.util.PlaceSearchResult

class SearchResultAdapter(
    private val onItemClick: (PlaceSearchResult) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {

    private val items = mutableListOf<PlaceSearchResult>()

    fun submitList(newItems: List<PlaceSearchResult>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemSearchSuggestionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(result: PlaceSearchResult) {
            binding.tvPlaceTitle.text = result.title
            binding.tvPlaceSubtitle.text = result.subtitle
            binding.root.setOnClickListener {
                onItemClick(result)
            }
        }
    }
}
