package com.DM.VideoEditor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * Adapter for the professional visual color preset selector grid.
 */
class FilterSelectorAdapter(
    private val presets: List<ColorPreset>,
    private var selectedFilter: String?,
    private val onSelected: (ColorPreset) -> Unit
) : RecyclerView.Adapter<FilterSelectorAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView    = v.findViewById(R.id.filterCard)
        val tvIcon: TextView        = v.findViewById(R.id.tvFilterIcon)
        val tvLabel: TextView       = v.findViewById(R.id.tvFilterLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_filter_selector, parent, false))

    override fun getItemCount() = presets.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val p = presets[pos]
        holder.tvIcon.text = p.icon
        holder.tvLabel.text = p.label

        val isSelected = p.filter == selectedFilter
        val accent = ContextCompat.getColor(holder.itemView.context, R.color.colorAccentOrange)

        val density = holder.itemView.resources.displayMetrics.density
        val strokePx = (3 * density).toInt()

        holder.card.strokeWidth = if (isSelected) strokePx else 0
        holder.tvLabel.setTextColor(if (isSelected) accent else ContextCompat.getColor(holder.itemView.context, R.color.colorTextMuted))

        holder.itemView.setOnClickListener {
            selectedFilter = p.filter
            notifyDataSetChanged()
            onSelected(p)
        }
    }
}
