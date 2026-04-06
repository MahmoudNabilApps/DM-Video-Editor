package com.DM.VideoEditor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

// ── TextOverlayAdapter ────────────────────────────────────────

class TextOverlayAdapter(
    private val overlays: MutableList<TextOverlay>,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<TextOverlayAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.tvOverlayText)
        val tvTime: TextView = v.findViewById(R.id.tvOverlayTime)
        val btnEdit: ImageButton = v.findViewById(R.id.btnEditOverlay)
        val btnDel: ImageButton = v.findViewById(R.id.btnDeleteOverlay)
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_text_overlay, p, false))

    override fun getItemCount() = overlays.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val o = overlays[pos]
        h.tv.text = "${pos + 1}. ${o.text.take(35)}"
        h.tvTime.text = when {
            o.endSec < 0 && o.startSec <= 0 -> h.itemView.context.getString(R.string.text_overlay_full_video)
            o.endSec < 0 -> "من ${fmtS(o.startSec)}"
            else -> "${fmtS(o.startSec)} ← ${fmtS(o.endSec)}"
        }
        h.btnEdit.setOnClickListener { onEdit(pos) }
        h.btnDel.setOnClickListener { onDelete(pos) }
    }

    private fun fmtS(s: Float) = String.format(Locale.getDefault(), "%d:%05.2f", s.toInt() / 60, s % 60)
}
