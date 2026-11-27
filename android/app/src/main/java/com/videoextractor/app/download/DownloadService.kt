package com.videoextractor.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.videoextractor.app.MainActivity
import com.videoextractor.app.model.DownloadState
import com.videoextractor.app.model.ExtractedVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared state bus between [DownloadService] and UI so background download progress
 * survives app switching, screen lock, and Activity recreation.
 */
object DownloadBus {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val _activeVideo = MutableStateFlow<ExtractedVideo?>(null)
    val activeVideo: StateFlow<ExtractedVideo?> = _activeVideo.asStateFlow()

    internal fun setActive(video: ExtractedVideo?, state: DownloadState) {
        _activeVideo.value = video
        _state.value = state
    }

    internal fun updateState(state: DownloadState) {
        _state.value = state
    }

    fun resetIfNotDownloading() {
        if (_state.value !is DownloadState.Downloading) {
            _activeVideo.value = null
            _state.value = DownloadState.Idle
        }
    }
}

/**
 * Foreground Service keeping video downloads alive in the background with a real-time
 * progress notification (percentage, speed MB/s, thread count, and Cancel action).
 */
class DownloadService : Service() {
    companion object {
        private const val CHANNEL_PROGRESS = "video_extractor_progress"
        private const val CHANNEL_DONE = "video_extractor_done"
        private const val NOTIF_PROGRESS_ID = 1001
        private const val NOTIF_DONE_ID = 1002

        const val ACTION_START = "com.videoextractor.app.action.DOWNLOAD_START"
        const val ACTION_CANCEL = "com.videoextractor.app.action.DOWNLOAD_CANCEL"

        @Volatile
        private var pendingVideo: ExtractedVideo? = null

        fun start(context: Context, video: ExtractedVideo) {
            pendingVideo = video
            DownloadBus.setActive(
                video,
                DownloadState.Downloading(-1f, 0L, -1L, 0L, 1, "正在启动后台下载…"),
            )
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            // Use startService (never startForegroundService) for cancel so we don't trigger FGS timeout if already stopped.
            runCatching { context.startService(intent) }
            if (DownloadBus.state.value is DownloadState.Downloading) {
                DownloadBus.setActive(null, DownloadState.Idle)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: Job? = null
    private var lastNotifyMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                activeJob?.cancel()
                activeJob = null
                pendingVideo = null
                MediaDownloader.cleanupStaleTempFiles(applicationContext)
                DownloadBus.setActive(null, DownloadState.Idle)
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val video = pendingVideo
                if (video == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                activeJob?.cancel()
                startForegroundCompat(
                    buildProgressNotification(
                        title = video.title,
                        text = "正在连接服务器…",
                        progressPercent = -1,
                    )
                )
                activeJob = serviceScope.launch {
                    try {
                        val result = MediaDownloader.downloadToGallery(
                            context = applicationContext,
                            video = video,
                            onProgress = { p ->
                                DownloadBus.updateState(p)
                                val now = System.currentTimeMillis()
                                if (now - lastNotifyMs >= 500L || p.progress >= 1f) {
                                    lastNotifyMs = now
                                    val pct = if (p.progress >= 0f) (p.progress * 100).toInt().coerceIn(0, 100) else -1
                                    val speedStr = if (p.speedBytesPerSec > 0L) " · ${formatSpeed(p.speedBytesPerSec)}" else ""
                                    val sizeStr = if (p.totalBytes > 0L) {
                                        "${formatBytes(p.bytesRead)} / ${formatBytes(p.totalBytes)}"
                                    } else {
                                        formatBytes(p.bytesRead)
                                    }
                                    val notif = buildProgressNotification(
                                        title = video.title,
                                        text = "${p.stage} · $sizeStr$speedStr",
                                        progressPercent = pct,
                                    )
                                    notifyManager().notify(NOTIF_PROGRESS_ID, notif)
                                }
                            },
                        )
                        DownloadBus.updateState(result)
                        when (result) {
                            is DownloadState.Saved -> postDoneNotification(video.title, result.displayName, result.uriString)
                            is DownloadState.Failed -> postFailedNotification(video.title, result.error)
                            else -> Unit
                        }
                    } catch (_: CancellationException) {
                        MediaDownloader.cleanupStaleTempFiles(applicationContext)
                        DownloadBus.setActive(null, DownloadState.Idle)
                    } finally {
                        pendingVideo = null
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_PROGRESS_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_PROGRESS_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notifyManager().cancel(NOTIF_PROGRESS_ID)
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = notifyManager()
            val progressCh = NotificationChannel(
                CHANNEL_PROGRESS,
                "视频下载进度",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "后台视频下载实时进度"
                setShowBadge(false)
            }
            val doneCh = NotificationChannel(
                CHANNEL_DONE,
                "视频下载结果",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "视频下载完成或失败通知"
            }
            nm.createNotificationChannel(progressCh)
            nm.createNotificationChannel(doneCh)
        }
    }

    private fun buildProgressNotification(title: String, text: String, progressPercent: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.take(40))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setProgress(100, progressPercent.coerceAtLeast(0), progressPercent < 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消下载", cancelIntent)
            .build()
    }

    private fun postDoneNotification(title: String, displayName: String, uriStr: String) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriStr), "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            this,
            2,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载完成 · 已存入相册")
            .setContentText("$title ($displayName)")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        notifyManager().notify(NOTIF_DONE_ID, notif)
    }

    private fun postFailedNotification(title: String, error: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("下载失败：$title")
            .setContentText(error)
            .setAutoCancel(true)
            .build()
        notifyManager().notify(NOTIF_DONE_ID, notif)
    }

    private fun notifyManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
        return "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    private fun formatSpeed(bps: Long): String {
        if (bps < 1024) return "$bps B/s"
        if (bps < 1024 * 1024) return "%.0f KB/s".format(bps / 1024.0)
        return "%.1f MB/s".format(bps / 1024.0 / 1024.0)
    }
}
