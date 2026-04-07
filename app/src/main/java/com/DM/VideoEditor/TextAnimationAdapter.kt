package com.DM.VideoEditor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * Adapter for selecting text animations.
 */
class TextAnimationAdapter(
    private val animations: List<TextAnimationType>,
    private var selectedCode: String,
    private val onSelected: (TextAnimationType) -> Unit
) : RecyclerView.Adapter<TextAnimationAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView    = v.findViewById(R.id.animationCard)
        val tvIcon: TextView        = v.findViewById(R.id.tvAnimationIcon)
        val tvLabel: TextView       = v.findViewById(R.id.tvAnimationLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_text_animation_selector, parent, false))

    override fun getItemCount() = animations.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val anim = animations[pos]
        holder.tvIcon.text = anim.icon
        holder.tvLabel.text = anim.label

        val isSelected = anim.code == selectedCode
        val accent = ContextCompat.getColor(holder.itemView.context, R.color.colorAccentOrange)
        val density = holder.itemView.resources.displayMetrics.density
        val strokePx = (3 * density).toInt()

        holder.card.strokeWidth = if (isSelected) strokePx else 0
        holder.tvLabel.setTextColor(if (isSelected) accent else ContextCompat.getColor(holder.itemView.context, R.color.colorTextMuted))

        holder.itemView.setOnClickListener {
            selectedCode = anim.code
            notifyDataSetChanged()
            onSelected(anim)
        }
    }
}
