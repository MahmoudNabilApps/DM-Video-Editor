package com.DM.VideoEditor

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class IntroTemplate(
    val name: String,
    val color: Int,
    val title: String,
    val subtitle: String
)

class IntroTemplateAdapter(
    private val templates: List<IntroTemplate>,
    private val onTemplateSelected: (IntroTemplate) -> Unit
) : RecyclerView.Adapter<IntroTemplateAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val preview: View = view.findViewById(R.id.templatePreview)
        val name: TextView = view.findViewById(R.id.templateName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_intro_template, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val template = templates[position]
        holder.preview.setBackgroundColor(template.color)
        holder.name.text = template.name
        holder.itemView.setOnClickListener { onTemplateSelected(template) }
    }

    override fun getItemCount() = templates.size
}
