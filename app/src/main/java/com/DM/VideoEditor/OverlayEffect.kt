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
            OverlayEffect("Film Grain", "🎞️", "noise=alls=12:allf=t+u"),
            OverlayEffect("Old Film", "📽️", "noise=alls=20:allf=t+u,vignette=PI/4,colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131"),
            OverlayEffect("VHS Glitch", "📺", "noise=alls=18:allf=t,hue=h=8:s=0.85,eq=contrast=1.15:brightness=-0.05,unsharp=3:3:0.5:3:3:0"),
            OverlayEffect("Dreamy", "✨", "unsharp=5:5:0.8:5:5:0.8,eq=brightness=0.05:saturation=1.2"),
            OverlayEffect("Vignette", "🔦", "vignette=PI/3"),
            OverlayEffect("RGB Split", "🌈", "colorchannelmixer=rr=1.0:rg=0.05:rb=0.0:gr=0.0:gg=1.0:gb=0.0:br=0.0:bg=0.0:bb=1.0,hue=s=1.1")
        )
    }
}
