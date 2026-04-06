package com.DM.VideoEditor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps video export alive in the background with a foreground notification and partial wake lock.
 */
class ExportForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var exportJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val job = getJobExtra(intent) ?: run {
                    Log.e(TAG, "Missing export job")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (exportJob?.isActive == true) {
                    Log.w(TAG, "Export already running")
                    return START_NOT_STICKY
                }
                acquireWakeLock()
                val notification = buildNotification(0, getString(R.string.export_status_preparing))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                exportJob = serviceScope.launch {
                    try {
                        val orch = VideoExportOrchestrator(this@ExportForegroundService)
                        val result = orch.runExport(job) { pct, status ->
                            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            nm.notify(NOTIFICATION_ID, buildNotification(pct.coerceIn(0, 100), status))
                        }
                        result.onSuccess { out ->
                            sendBroadcast(
                                Intent(ACTION_EXPORT_FINISHED).apply {
                                    setPackage(packageName)
                                    putExtra(EXTRA_SUCCESS, true)
                                    putExtra(EXTRA_OUTPUT_PATH, out.file.absolutePath)
                                    out.mediaStoreUri?.let { putExtra(EXTRA_OUTPUT_URI, it.toString()) }
                                    putExtra(EXTRA_DURATION_MS, out.durationMs)
                                    putExtra(EXTRA_TEXT_OVERLAY_COUNT, out.textOverlayCount)
                                }
                            )
                        }.onFailure { e ->
                            sendBroadcast(
                                Intent(ACTION_EXPORT_FINISHED).apply {
                                    setPackage(packageName)
                                    putExtra(EXTRA_SUCCESS, false)
                                    putExtra(EXTRA_ERROR_MESSAGE, e.message ?: "")
                                }
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "export", e)
                        sendBroadcast(
                            Intent(ACTION_EXPORT_FINISHED).apply {
                                setPackage(packageName)
                                putExtra(EXTRA_SUCCESS, false)
                                putExtra(EXTRA_ERROR_MESSAGE, e.message ?: "")
                            }
                        )
                    } finally {
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            ACTION_CANCEL -> {
                FfmpegExecutor.cancel()
                exportJob?.cancel()
                releaseWakeLock()
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {}
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:Export").apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_MAX_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.export_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW          // silent progress updates
        ).apply {
            description = getString(R.string.export_notification_channel_desc)
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(progress: Int, contentText: String): Notification {
        val cancelIntent = Intent(this, ExportForegroundService::class.java).apply { action = ACTION_CANCEL }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val cancelPi = PendingIntent.getService(this, 1, cancelIntent, piFlags)

        // Tap notification → open / resume the editor
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPi = if (openIntent != null)
            PendingIntent.getActivity(this, 2, openIntent, piFlags)
        else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.export_notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(progress < 100)
            .apply { if (contentPi != null) setContentIntent(contentPi) }
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.export_cancel),
                cancelPi
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getJobExtra(intent: Intent): ExportJob? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_JOB, ExportJob::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_JOB)
        }
    }

    companion object {
        private const val TAG = "ExportFgService"
        private const val CHANNEL_ID = "video_export"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_MAX_MS = 10L * 60L * 60L * 1000L

        const val ACTION_START = "com.DM.VideoEditor.action.START_EXPORT"
        const val ACTION_CANCEL = "com.DM.VideoEditor.action.CANCEL_EXPORT"
        const val EXTRA_JOB = "extra_export_job"

        const val ACTION_EXPORT_FINISHED = "com.DM.VideoEditor.action.EXPORT_FINISHED"
        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_OUTPUT_PATH = "extra_output_path"
        const val EXTRA_OUTPUT_URI = "extra_output_uri"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_TEXT_OVERLAY_COUNT = "extra_text_overlay_count"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"

        fun start(context: Context, job: ExportJob) {
            val i = Intent(context, ExportForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_JOB, job)
            }
            ContextCompat.startForegroundService(context, i)
        }
    }
}
