package com.DM.VideoEditor

/**
 * Advanced cinematic color grading presets for the editor.
 */
data class ColorPreset(
    val label: String,
    val icon: String, // Icon emoji for the visual selector
    val filter: String? // Null for "None"
) {
    companion object {
        fun getAll(): List<ColorPreset> = listOf(
            ColorPreset("None", "❌", null),

            // Cinematic Teal & Orange (High End Look)
            ColorPreset("Teal & Orange", "🎞️", "colorbalance=rs=0.2:gs=0.1:bs=-0.1:rm=0.1:gm=0.05:bm=-0.2:rh=-0.1:gh=-0.05:bh=0.2,eq=saturation=1.2:contrast=1.1"),

            // Warm Golden Hour (Sunlit Look)
            ColorPreset("Golden Hour", "🌅", "colorbalance=rs=0.3:gs=0.1:bs=-0.2,eq=brightness=0.05:saturation=1.3"),

            // Cold Winter (Blueish tones)
            ColorPreset("Ice Blue", "❄️", "colorbalance=rs=-0.2:gs=-0.05:bs=0.3,eq=brightness=0.05:contrast=1.05"),

            // Dramatic B&W (Professional Noir)
            ColorPreset("Noir", "⬛", "colorbalance=rs=0.1:gs=0.1:bs=0.1,hue=s=0,eq=contrast=1.4:brightness=-0.05"),

            // Vintage / Retro Film
            ColorPreset("Vintage", "📺", "curves=vintage,vignette=PI/4,eq=saturation=0.9:contrast=1.1"),

            // Vibrant Nature
            ColorPreset("Vivid", "🌈", "eq=saturation=1.6:contrast=1.15:brightness=0.05"),

            // Faded Pastel
            ColorPreset("Pastel", "🌫️", "eq=brightness=0.1:contrast=0.8:saturation=0.7"),

            // Cyberpunk (Neon Purple/Pink)
            ColorPreset("Cyberpunk", "🟣", "colorbalance=rs=0.2:gs=-0.1:bs=0.3:rh=0.1:gh=-0.2:bh=0.4,eq=contrast=1.2:saturation=1.3"),

            // Sepia (Antique)
            ColorPreset("Sepia", "🟤", "colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131,eq=contrast=1.1")
        )
    }
}
