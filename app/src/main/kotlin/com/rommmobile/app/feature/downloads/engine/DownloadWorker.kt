package com.rommmobile.app.feature.downloads.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.rommmobile.app.MainActivity
import com.rommmobile.app.R
import com.rommmobile.app.core.util.FileLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground executor for the queue. WorkManager gives us: survival when the app is swiped
 * away, automatic restart on reboot and when the (unmetered) network returns, and a clean
 * foreground-service lifecycle without background-start restrictions.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: DownloadEngine,
    private val notifier: DownloadNotifier,
    private val log: FileLogger,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = notifier.foregroundInfo(null)

    override suspend fun doWork(): Result {
        try {
            setForeground(notifier.foregroundInfo(null))
        } catch (t: Throwable) {
            // Missing notification permission on Android 13+: keep going without the banner.
            log.w("DownloadWorker", "foreground not available: ${t.message}")
        }
        log.i("DownloadWorker", "start")
        val drained = engine.runUntilIdle { summary ->
            runCatching { setForeground(notifier.foregroundInfo(summary)) }
        }
        log.i("DownloadWorker", if (drained) "queue drained" else "waiting for network")
        // "Pause all" also drains the runnable queue: only announce real completions.
        if (drained && engine.completedInRun) notifier.notifyQueueFinished()
        return if (drained) Result.success() else Result.retry()
    }
}

class DownloadNotifier @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init { ensureChannel() }

    private fun ensureChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_downloads), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.notif_channel_downloads_desc)
                    setShowBadge(false)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, context.getString(R.string.notif_channel_done), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun foregroundInfo(summary: QueueSummary?): ForegroundInfo {
        val n = build(summary)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(ONGOING_ID, n)
    }

    private fun build(summary: QueueSummary?): Notification {
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_DOWNLOADS, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val content = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when {
            summary == null || summary.isEmpty -> context.getString(R.string.notif_preparing)
            summary.activeCount > 1 -> context.getString(R.string.notif_downloading_n, summary.activeCount)
            else -> summary.currentTitle ?: context.getString(R.string.notif_downloading)
        }
        val text = when {
            summary == null -> ""
            summary.pendingCount > 0 -> context.getString(R.string.notif_progress_queue, summary.currentPercent, summary.pendingCount)
            else -> context.getString(R.string.notif_progress, summary.currentPercent)
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(content)
            .setProgress(100, summary?.currentPercent ?: 0, summary == null || summary.currentPercent == 0)
            .addAction(0, context.getString(R.string.action_pause_all), broadcast(DownloadActionReceiver.ACTION_PAUSE_ALL, 2))
            .addAction(0, context.getString(R.string.action_cancel_all), broadcast(DownloadActionReceiver.ACTION_CANCEL_ALL, 3))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun notifyQueueFinished() {
        val open = Intent(context, MainActivity::class.java).apply { putExtra(EXTRA_OPEN_DOWNLOADS, true) }
        val content = PendingIntent.getActivity(context, 4, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_done_title))
            .setContentText(context.getString(R.string.notif_done_text))
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(DONE_ID, n) }
    }

    private fun broadcast(action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(context, code, Intent(context, DownloadActionReceiver::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val CHANNEL = "downloads"
        const val CHANNEL_DONE = "downloads_done"
        const val ONGOING_ID = 1001
        const val DONE_ID = 1002
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }
}

@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: DownloadEngine

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE_ALL -> engine.pauseAll()
            ACTION_RESUME_ALL -> engine.resumeAll()
            ACTION_CANCEL_ALL -> engine.cancelAll()
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> engine.resumeAfterProcessStart()
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.rommmobile.app.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.rommmobile.app.action.RESUME_ALL"
        const val ACTION_CANCEL_ALL = "com.rommmobile.app.action.CANCEL_ALL"
    }
}
