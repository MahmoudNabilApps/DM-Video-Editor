package com.DM.VideoEditor

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback

/**
 * Single gateway for all FFmpegKit operations. Callers must not use [FFmpegKit] or [FFmpegKitConfig]
 * directly so sessions, cancellation, and logging stay consistent.
 */
object FfmpegExecutor {

    private const val TAG = "FfmpegExecutor"

    class ExecutionException(
        message: String,
        val returnCode: Int?,
        val logTail: String?
    ) : Exception(message)

    /**
     * Synchronous [FFmpegKit.execute]. Use from [kotlinx.coroutines.Dispatchers.IO].
     */
    fun executeSync(command: String): Result<FFmpegSession> {
        return try {
            val session = FFmpegKit.execute(command)
            if (ReturnCode.isSuccess(session.returnCode)) {
                Result.success(session)
            } else {
                val tail = session.allLogsAsString?.takeLast(1200)
                Log.e(TAG, "Command failed (code=${session.returnCode?.value}): …${tail?.takeLast(400)}")
                Result.failure(
                    ExecutionException(
                        "FFmpeg failed: ${session.returnCode?.value}",
                        session.returnCode?.value,
                        tail
                    )
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "executeSync crashed", t)
            Result.failure(t)
        }
    }

    /**
     * Async encode; callbacks run on FFmpegKit threads — UI updates must post to the main thread.
     */
    fun executeAsync(
        command: String,
        onComplete: (FFmpegSession) -> Unit,
        onLog: LogCallback? = null,
        onStatistics: StatisticsCallback? = null
    ) {
        try {
            FFmpegKit.executeAsync(
                command,
                { session ->
                    try {
                        onComplete(session)
                    } catch (t: Throwable) {
                        Log.e(TAG, "executeAsync onComplete", t)
                    }
                },
                onLog,
                onStatistics
            )
        } catch (t: Throwable) {
            Log.e(TAG, "executeAsync failed to start", t)
        }
    }

    fun cancel() {
        try {
            FFmpegKit.cancel()
        } catch (t: Throwable) {
            Log.e(TAG, "cancel", t)
        }
    }

    fun setFontDirectory(context: Context, fontDirectoryPath: String, fontNameMap: Map<String, String>?) {
        try {
            FFmpegKitConfig.setFontDirectory(context, fontDirectoryPath, fontNameMap)
        } catch (t: Throwable) {
            Log.e(TAG, "setFontDirectory", t)
        }
    }

    fun isSuccess(session: FFmpegSession?): Boolean =
        session != null && ReturnCode.isSuccess(session.returnCode)

    fun isSuccess(result: Result<FFmpegSession>): Boolean =
        result.isSuccess && isSuccess(result.getOrNull())

    /** Progress ratio 0f..1f from Statistics (if available). */
    fun statisticsProgress(stats: Statistics, totalDurationMs: Long): Float {
        if (totalDurationMs <= 0) return 0f
        return (stats.time.toFloat() / totalDurationMs).coerceIn(0f, 1f)
    }
}
