package com.DM.VideoEditor

/**
 * Cinematic video overlay effects using FFmpeg procedural filters.
 */
data class OverlayEffect(
    val label: String,
    val icon: String,
    val filter: String? // Procedural filter string
) {
    companion object {
        fun getAll(): List<OverlayEffect> = listOf(
            OverlayEffect("None", "❌", null),

            // Film Grain using noise filter
            OverlayEffect("Film Grain", "🎞️", "noise=alls=12:allf=t+u"),

            // Old Film Look (Noise + Vignette + Sepia-ish)
            OverlayEffect("Old Film", "📽️", "noise=alls=20:allf=t+u,vignette=PI/4,colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131"),

            // VHS / Glitch effect simulation
            OverlayEffect("VHS Glitch", "📺", "chromashift=cbh=2:crh=-2,noise=alls=10:allf=t"),

            // Soft Bloom / Glow
            OverlayEffect("Dreamy", "✨", "unsharp=5:5:0.8:5:5:0.8,eq=brightness=0.05:saturation=1.2"),

            // Vignette focus
            OverlayEffect("Vignette", "🔦", "vignette=PI/3"),

            // RGB Split (Chromatic Aberration)
            OverlayEffect("RGB Split", "🌈", "chromashift=cbh=3:crh=-3")
        )
    }
}
