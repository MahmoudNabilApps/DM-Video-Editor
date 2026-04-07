package com.DM.VideoEditor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * Adapter for selecting cinematic video overlays.
 */
class OverlaySelectorAdapter(
    private val effects: List<OverlayEffect>,
    private var selectedFilter: String?,
    private val onSelected: (OverlayEffect) -> Unit
) : RecyclerView.Adapter<OverlaySelectorAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView    = v.findViewById(R.id.overlayCard)
        val tvIcon: TextView        = v.findViewById(R.id.tvOverlayIcon)
        val tvLabel: TextView       = v.findViewById(R.id.tvOverlayLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_overlay_selector, parent, false))

    override fun getItemCount() = effects.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val e = effects[pos]
        holder.tvIcon.text = e.icon
        holder.tvLabel.text = e.label

        val isSelected = e.filter == selectedFilter
        val accent = ContextCompat.getColor(holder.itemView.context, R.color.colorAccentOrange)

        val density = holder.itemView.resources.displayMetrics.density
        val strokePx = (3 * density).toInt()

        holder.card.strokeWidth = if (isSelected) strokePx else 0
        holder.tvLabel.setTextColor(if (isSelected) accent else ContextCompat.getColor(holder.itemView.context, R.color.colorTextMuted))

        holder.itemView.setOnClickListener {
            selectedFilter = e.filter
            notifyDataSetChanged()
            onSelected(e)
        }
    }
}
