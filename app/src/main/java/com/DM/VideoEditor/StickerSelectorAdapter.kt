package com.DM.VideoEditor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil3.load

/**
 * Adapter for Lottie stickers selection.
 */
class StickerSelectorAdapter(
    private val stickers: List<StickerMetadata>,
    private val onSelected: (StickerMetadata) -> Unit
) : RecyclerView.Adapter<StickerSelectorAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ivThumb: ImageView = v.findViewById(R.id.ivStickerThumb)
    }

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_sticker_selector, parent, false))

    override fun getItemCount() = stickers.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val s = stickers[pos]
        holder.ivThumb.load(s.thumbnailUrl)
        holder.itemView.setOnClickListener { onSelected(s) }
    }
}
