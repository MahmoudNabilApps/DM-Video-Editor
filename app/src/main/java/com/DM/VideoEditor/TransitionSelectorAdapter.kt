package com.DM.VideoEditor

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * Adapter for the visual transition selector grid.
 */
class TransitionSelectorAdapter(
    private val transitions: List<TransitionType>,
    private var selectedCode: String,
    private val onSelected: (TransitionType) -> Unit
) : RecyclerView.Adapter<TransitionSelectorAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView    = v.findViewById(R.id.transitionCard)
        val tvIcon: TextView        = v.findViewById(R.id.tvTransitionIcon)
        val tvLabel: TextView       = v.findViewById(R.id.tvTransitionLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_transition_selector, parent, false))

    override fun getItemCount() = transitions.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val t = transitions[pos]
        holder.tvIcon.text = t.icon
        holder.tvLabel.text = t.label

        val isSelected = t.code == selectedCode
        val accent = ContextCompat.getColor(holder.itemView.context, R.color.colorAccentOrange)

        // Convert 3dp to px for stroke width
        val density = holder.itemView.resources.displayMetrics.density
        val strokePx = (3 * density).toInt()

        holder.card.strokeWidth = if (isSelected) strokePx else 0
        holder.tvLabel.setTextColor(if (isSelected) accent else ContextCompat.getColor(holder.itemView.context, R.color.colorTextMuted))

        holder.itemView.setOnClickListener {
            val oldSelected = selectedCode
            selectedCode = t.code
            notifyDataSetChanged()
            onSelected(t)
        }
    }
}
