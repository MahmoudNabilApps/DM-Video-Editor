package com.DM.VideoEditor

/**
 * Professional transition definitions for the editor.
 */
data class TransitionType(
    val label: String,
    val code: String,
    val icon: String // Using Emojis or Icons as a simple representation like Filmora
) {
    companion object {
        fun getAll(): List<TransitionType> = listOf(
            TransitionType("None",       "none",         "❌"),
            TransitionType("Fade",       "fade",         "⬛"),
            TransitionType("Dissolve",   "dissolve",     "🔄"),
            TransitionType("Slide Left", "slide_left",   "◀▶"),
            TransitionType("Slide Right","slide_right",  "▶◀"),
            TransitionType("Zoom",       "zoom",         "🔳"),
            TransitionType("Wipe",       "wipe",         "⬜"),
            TransitionType("Circle",     "circleopen",   "🌀"),
            TransitionType("Wipe Up",    "wipeup",       "↗"),
            TransitionType("Wipe Down",  "wipedown",     "↙"),
            // Professional additions
            TransitionType("Pixelize",   "pixelize",     "👾"),
            TransitionType("Radial",     "radial",       "☸"),
            TransitionType("Squeeze",    "squeezev",     "↕"),
            TransitionType("Smooth",     "smoothstep",   "✨")
        )
    }
}
