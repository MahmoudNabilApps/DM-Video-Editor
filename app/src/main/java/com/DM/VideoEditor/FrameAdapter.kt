package com.DM.VideoEditor

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.DM.VideoEditor.databinding.ItemFrameBinding

class FrameAdapter(private var frames: List<Bitmap>) :
    RecyclerView.Adapter<FrameAdapter.FrameViewHolder>() {

    class FrameViewHolder(val binding: ItemFrameBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FrameViewHolder {
        val binding = ItemFrameBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FrameViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FrameViewHolder, position: Int) {
        holder.binding.frameImageView.setImageBitmap(frames[position])
    }

    override fun getItemCount() = frames.size

    fun updateFrames(newFrames: List<Bitmap>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = frames.size
            override fun getNewListSize() = newFrames.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = oldPos == newPos
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                frames[oldPos] == newFrames[newPos]
        })
        frames = newFrames
        diff.dispatchUpdatesTo(this)
    }
}
