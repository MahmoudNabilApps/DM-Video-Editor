package com.DM.VideoEditor

/**
 * Animated text templates metadata.
 */
data class TextAnimationType(
    val label: String,
    val code: String,
    val icon: String
) {
    companion object {
        fun getAll(): List<TextAnimationType> = listOf(
            TextAnimationType("None",      "none",       "❌"),
            TextAnimationType("Slide In",  "slide_in",   "⬅️"),
            TextAnimationType("Zoom Fade", "zoom_fade",  "🔍"),
            TextAnimationType("Typewriter","typewriter", "⌨️"),
            TextAnimationType("Bounce",    "bounce",     "⚽"),
            TextAnimationType("Flash",     "flash",      "⚡")
        )
    }
}
