package com.DM.VideoEditor

/**
 * Metadata for Lottie Stickers from a remote source.
 */
data class StickerMetadata(
    val id: String,
    val name: String,
    val thumbnailUrl: String,
    val lottieUrl: String,
    val category: String
) {
    companion object {
        fun getMockAssets(): List<StickerMetadata> = listOf(
            StickerMetadata("s1", "Heart Pop", "https://assets9.lottiefiles.com/thumbnails/render/1234.png", "https://assets9.lottiefiles.com/packages/lf20_st7re1.json", "Love"),
            StickerMetadata("s2", "Shiny Star", "https://assets9.lottiefiles.com/thumbnails/render/5678.png", "https://assets9.lottiefiles.com/packages/lf20_u4y3hy.json", "Party"),
            StickerMetadata("s3", "Arrow Point", "https://assets9.lottiefiles.com/thumbnails/render/9012.png", "https://assets9.lottiefiles.com/packages/lf20_vvp2yk.json", "UI")
        )
    }
}
