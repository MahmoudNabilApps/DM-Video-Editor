package com.DM.VideoEditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.io.File
import java.io.FileOutputStream

object LottieFrameExtractor {

    /**
     * Extracts frames from a Lottie animation URL or Asset and saves them as PNGs.
     * Returns the directory containing the frames.
     */
    suspend fun extractFrames(
        context: Context,
        lottieUrl: String,
        outputDir: File,
        width: Int = 512,
        height: Int = 512,
        fps: Int = 30
    ): File? = withContext(Dispatchers.IO) {
        try {
            outputDir.mkdirs()

            val composition = if (lottieUrl.startsWith("http")) {
                LottieCompositionFactory.fromUrlSync(context, lottieUrl).value
            } else {
                LottieCompositionFactory.fromAssetSync(context, lottieUrl).value
            } ?: return@withContext null

            val drawable = LottieDrawable()
            drawable.composition = composition

            val totalFrames = composition.durationFrames
            val durationMs = composition.duration
            val frameCount = (durationMs / 1000f * fps).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            for (i in 0 until frameCount) {
                val progress = i.toFloat() / frameCount
                drawable.progress = progress

                bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)

                val frameFile = File(outputDir, String.format("frame_%04d.png", i))
                FileOutputStream(frameFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }

            bitmap.recycle()
            outputDir
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
