package com.DM.VideoEditor

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.Locale

// ── ClipAdapter ───────────────────────────────────────────────

class ClipAdapter(
    private val clips: MutableList<VideoClip>,
    private val onClipClick: (Int) -> Unit,
    private val onClipLongClick: (Int) -> Unit,
    private val onClipDelete: (Int) -> Unit,
    private val onAddClick: () -> Unit,
    private val onTransitionClick: (Int) -> Unit   // NEW: tap between clips
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_CLIP       = 0
        const val TYPE_TRANSITION = 1   // separator between clips
        const val TYPE_ADD        = 2
        private const val PAYLOAD_THUMB = "thumb"
    }
    private var selectedIndex = 0
    private val clipStripThumbnails = mutableMapOf<Int, Bitmap>()

    /**
     * Layout:
     *   clip[0] | trans[1] | clip[1] | trans[2] | clip[2] | ... | ADD
     * Position mapping:
     *   even positions (0, 2, 4…) → clips[pos/2]  (TYPE_CLIP)
     *   odd positions  (1, 3, 5…) → transition before clips[pos/2+1] (TYPE_TRANSITION)
     *   last position             → TYPE_ADD
     */
    private fun clipIndexForPos(adapterPos: Int) = adapterPos / 2
    override fun getItemCount() = if (clips.isEmpty()) 1 else (clips.size * 2)

    override fun getItemViewType(pos: Int): Int {
        val total = itemCount - 1
        return when {
            pos == total      -> TYPE_ADD
            pos % 2 == 0      -> TYPE_CLIP
            else              -> TYPE_TRANSITION
        }
    }

    inner class ClipVH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.ivClipThumb)
        val tvClipNumber: TextView = v.findViewById(R.id.tvClipNumber)
        val tvDur: TextView = v.findViewById(R.id.tvClipDuration)
        val tvTrans: TextView = v.findViewById(R.id.tvTransitionBadge)
        val btnDel: ImageButton = v.findViewById(R.id.btnDeleteClip)
        val selectedBorder: View? = v.findViewById(R.id.viewSelectedBorder)
    }
    inner class TransitionVH(v: View) : RecyclerView.ViewHolder(v) {
        val btnTrans: android.widget.TextView = v.findViewById(R.id.tvTransBtn)
    }
    inner class AddVH(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (vt) {
            TYPE_CLIP        -> ClipVH(inf.inflate(R.layout.item_clip, parent, false))
            TYPE_TRANSITION  -> TransitionVH(inf.inflate(R.layout.item_clip_transition, parent, false))
            else             -> AddVH(inf.inflate(R.layout.item_add_clip, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int, payloads: MutableList<Any>) {
        if (holder is ClipVH && payloads.isNotEmpty() && payloads.contains(PAYLOAD_THUMB)) {
            bindClipThumb(holder, pos / 2)
            return
        }
        onBindViewHolder(holder, pos)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        when (holder) {
            is ClipVH -> {
                val clipIdx = pos / 2
                if (clipIdx >= clips.size) return
                val c = clips[clipIdx]
                val dur = if (c.endTrimMs > 0) c.endTrimMs - c.startTrimMs else c.durationMs
                val sec = dur / 1000
                holder.tvClipNumber.text = (clipIdx + 1).toString()
                holder.tvDur.text = String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)
                holder.btnDel.setOnClickListener { onClipDelete(clipIdx) }
                holder.itemView.setOnClickListener { onClipClick(clipIdx) }
                holder.itemView.setOnLongClickListener {
                    onClipLongClick(clipIdx)
                    true
                }
                holder.itemView.alpha = if (clipIdx == selectedIndex) 1f else 0.6f
                holder.selectedBorder?.visibility = if (clipIdx == selectedIndex) View.VISIBLE else View.GONE
                bindClipThumb(holder, clipIdx)
                holder.tvTrans.visibility = if (c.speedFactor != 1f && clipIdx == selectedIndex) View.VISIBLE else View.GONE
                holder.tvTrans.text = "${c.speedFactor}×"
            }
            is TransitionVH -> {
                // This sits between clip[clipIdx-1] and clip[clipIdx]
                val clipIdx = pos / 2 + 1
                val tr = clips.getOrNull(clipIdx)?.transition ?: "none"
                val icon = when (tr) {
                    "none"       -> "✕"
                    "fade"       -> "⬛"
                    "slide_left" -> "◀"
                    "slide_right"-> "▶"
                    "dissolve"   -> "🔄"
                    "zoom"       -> "🔍"
                    "circleopen" -> "⭕"
                    else         -> "•"
                }
                holder.btnTrans.text = icon
                holder.itemView.setOnClickListener { onTransitionClick(clipIdx) }
            }
            is AddVH -> {
                holder.itemView.setOnClickListener { onAddClick() }
            }
        }
    }

    private fun bindClipThumb(holder: ClipVH, clipIdx: Int) {
        clipStripThumbnails[clipIdx]?.takeIf { !it.isRecycled }?.let { holder.thumb.setImageBitmap(it) }
            ?: holder.thumb.setImageResource(android.R.drawable.ic_media_play)
    }

    fun setThumbnail(clipIdx: Int, bmp: Bitmap) {
        if (clipIdx < 0 || clipIdx >= clips.size) {
            if (!bmp.isRecycled) bmp.recycle()
            return
        }
        clipStripThumbnails[clipIdx]?.takeIf { it !== bmp && !it.isRecycled }?.recycle()
        clipStripThumbnails[clipIdx] = bmp
        notifyItemChanged(clipIdx * 2, PAYLOAD_THUMB)
    }

    fun onClipRemoved(removedIdx: Int) {
        clipStripThumbnails.remove(removedIdx)?.takeIf { !it.isRecycled }?.recycle()
        val rest = mutableMapOf<Int, Bitmap>()
        clipStripThumbnails.forEach { (k, v) ->
            when {
                k < removedIdx -> rest[k] = v
                k > removedIdx -> rest[k - 1] = v
            }
        }
        clipStripThumbnails.clear()
        clipStripThumbnails.putAll(rest)
    }

    fun swapStripThumbnailIndices(i: Int, j: Int) {
        if (i == j) return
        val bi = clipStripThumbnails.remove(i)
        val bj = clipStripThumbnails.remove(j)
        bi?.let { clipStripThumbnails[j] = it }
        bj?.let { clipStripThumbnails[i] = it }
    }

    /** Shift cached thumbnails so indices >= [from] move up by one (room for insert at [from]). */
    fun shiftStripThumbnailsRight(fromIndexInclusive: Int) {
        val keys = clipStripThumbnails.keys.filter { it >= fromIndexInclusive }.sortedDescending()
        for (k in keys) {
            clipStripThumbnails.remove(k)?.let { clipStripThumbnails[k + 1] = it }
        }
    }

    fun recycleAllStripThumbnails() {
        clipStripThumbnails.values.forEach { if (!it.isRecycled) it.recycle() }
        clipStripThumbnails.clear()
    }

    fun setSelected(clipIdx: Int) {
        val oldAdapterPos = selectedIndex * 2
        selectedIndex = clipIdx
        notifyItemChanged(oldAdapterPos)
        notifyItemChanged(clipIdx * 2)
    }

    fun moveItem(fromClip: Int, toClip: Int) {
        if (fromClip < clips.size && toClip < clips.size) {
            Collections.swap(clips, fromClip, toClip)
            swapStripThumbnailIndices(fromClip, toClip)
            notifyDataSetChanged()
        }
    }
}
