package com.openstream.downloader.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openstream.downloader.MainActivity
import com.openstream.downloader.OpenStreamApp
import com.openstream.downloader.model.DownloadStatus
import com.openstream.downloader.model.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observerJob: Job? = null
    private var lastUpdateMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenStream:DownloadWakeLock").apply {
            setReferenceCounted(false)
        }

        startForegroundWithInitialNotification()
        observeDownloadTasks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            val taskId = intent.getStringExtra(EXTRA_TASK_ID)
            if (taskId != null) {
                val queueManager = OpenStreamApp.instance.queueManager
                when (action) {
                    ACTION_PAUSE -> queueManager.pause(taskId)
                    ACTION_RESUME -> queueManager.resume(taskId)
                    ACTION_CANCEL -> queueManager.cancel(taskId)
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Ensure background downloads continue uninterrupted even when user swipes app from Recents
        val activeTasks = OpenStreamApp.instance.queueManager.tasks.value.filter {
            it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.MERGING ||
            it.status == DownloadStatus.QUEUED
        }
        if (activeTasks.isNotEmpty()) {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(60 * 60 * 1000L)
            }
        } else {
            stopForegroundService()
        }
    }

    private fun startForegroundWithInitialNotification() {
        val initialNotification = buildNotification("OpenStream Downloader", "Preparing download engine...", 0, 0, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
        wakeLock?.acquire(60 * 60 * 1000L) // 1 hour max safeguard
    }

    private var hasObservedActiveTasks = false

    private fun observeDownloadTasks() {
        observerJob?.cancel()
        observerJob = serviceScope.launch {
            OpenStreamApp.instance.queueManager.tasks.collectLatest { tasks ->
                val activeTasks = tasks.filter {
                    it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.MERGING ||
                    it.status == DownloadStatus.QUEUED
                }

                if (activeTasks.isNotEmpty()) {
                    hasObservedActiveTasks = true
                    val primaryTask = activeTasks.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
                        ?: activeTasks.first()

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMillis >= 500) {
                        lastUpdateMillis = now
                        val pct = primaryTask.progress.percentage
                        val speedStr = formatSpeed(primaryTask.progress.speedBytesPerSec)
                        val stepStr = primaryTask.progress.currentStep
                        val title = primaryTask.title.ifBlank { "Downloading video" }
                        val content = if (activeTasks.size > 1) {
                            "$pct% • $speedStr • (${activeTasks.size} active)"
                        } else {
                            "$pct% • $speedStr • $stepStr"
                        }

                        val notification = buildNotification(title, content, pct, activeTasks.size, primaryTask.id)
                        val manager = NotificationManagerCompat.from(this@DownloadService)
                        try {
                            manager.notify(NOTIFICATION_ID, notification)
                        } catch (e: SecurityException) {
                            e.printStackTrace()
                        }
                    }
                } else if (hasObservedActiveTasks) {
                    // All active tasks have finished; clean up and stop service
                    stopForegroundService()
                }
            }
        }
    }

    private fun buildNotification(
        title: String,
        content: String,
        progress: Int,
        activeCount: Int,
        activeTaskId: String?
    ): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (activeTaskId != null) {
            val pauseIntent = Intent(this, DownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_TASK_ID, activeTaskId)
            }
            val pausePendingIntent = PendingIntent.getService(
                this,
                1,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)

            val cancelIntent = Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, activeTaskId)
            }
            val cancelPendingIntent = PendingIntent.getService(
                this,
                2,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
        }

        return builder.build()
    }

    private fun stopForegroundService() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OpenStream Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live download progress for background downloads"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val kb = bytesPerSec / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB/s", mb)
            kb >= 1.0 -> String.format(java.util.Locale.US, "%.0f KB/s", kb)
            else -> "$bytesPerSec B/s"
        }
    }

    companion object {
        const val CHANNEL_ID = "openstream_download_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PAUSE = "com.openstream.action.PAUSE"
        const val ACTION_RESUME = "com.openstream.action.RESUME"
        const val ACTION_CANCEL = "com.openstream.action.CANCEL"
        const val EXTRA_TASK_ID = "extra_task_id"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
