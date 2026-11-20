package com.subcolab.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.subcolab.android.MainActivity
import com.subcolab.android.R
import com.subcolab.android.SubcolabApp
import com.subcolab.android.util.AppLogger

class ReadyDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? SubcolabApp
        if (intent?.action == SubcolabForegroundService.ACTION_HIDE_QUEUED) {
            val queueId = intent.getStringExtra(SubcolabForegroundService.EXTRA_QUEUE_ID).orEmpty()
            if (queueId.isNotBlank()) {
                SubcolabForegroundService.cancelQueuedNotification(context, queueId)
                app?.coordinator?.hideQueuedReadyOffer(queueId)
            }
            return
        }
        SubcolabForegroundService.cancelReadyNotification(context)
        app?.coordinator?.dismissReadyPrompt()
    }
}

class SubcolabForegroundService : Service() {

    companion object {
        private const val TAG = "SubcolabFgService"
        const val ACTION_START_JOB = "com.subcolab.android.action.START_JOB"
        const val ACTION_CANCEL_JOB = "com.subcolab.android.action.CANCEL_JOB"
        const val ACTION_STOP_GPU = "com.subcolab.android.action.STOP_GPU"
        const val ACTION_DISMISS_READY = "com.subcolab.android.action.DISMISS_READY"
        const val ACTION_HIDE_QUEUED = "com.subcolab.android.action.HIDE_QUEUED"
        const val EXTRA_QUEUE_ID = "com.subcolab.android.extra.QUEUE_ID"
        private const val NOTIFICATION_PROGRESS_ID = 1001
        const val NOTIFICATION_READY_ID = 1002
        private const val NOTIFICATION_QUEUED_BASE_ID = 1100

        private val activeQueuedNotificationIds = mutableSetOf<Int>()

        fun queuedNotificationId(queueId: String): Int =
            NOTIFICATION_QUEUED_BASE_ID + ((queueId.hashCode() and 0x7FFFFFFF) % 900)

        fun cancelReadyNotification(context: Context) {
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_READY_ID)
            }
        }

        fun cancelQueuedNotification(context: Context, queueId: String) {
            if (queueId.isBlank()) return
            val id = queuedNotificationId(queueId)
            synchronized(activeQueuedNotificationIds) {
                activeQueuedNotificationIds.remove(id)
            }
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(id)
            }
        }

        fun cancelAllQueuedNotifications(context: Context) {
            val ids = synchronized(activeQueuedNotificationIds) {
                val copy = activeQueuedNotificationIds.toList()
                activeQueuedNotificationIds.clear()
                copy
            }
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                ids.forEach { nm.cancel(it) }
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "onCreate")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Subcolab::T4WorkerWakeLock")
            .apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // up to 4h max safety timeout
            }

        promoteToForeground(
            title = "SUBCOLAB",
            body = "Starting background download & translation...",
        )

        val app = application as SubcolabApp
        app.coordinator.attachServiceCallbacks(
            onProgressNotify = { title, body, ongoing ->
                runCatching {
                    val stillOngoing = ongoing || app.coordinator.hasQueuedJobs()
                    if (!stillOngoing) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    }
                    notificationManager.notify(
                        NOTIFICATION_PROGRESS_ID,
                        buildProgressNotification(title, body, stillOngoing),
                    )
                }.onFailure { err ->
                    AppLogger.w(TAG, "Progress notify failed", err)
                }
            },
            onPlayerReadyNotify = { titleSlug, cueCount, isComplete, isLiveStream, useFullScreenIntent ->
                runCatching {
                    if (isComplete && !app.coordinator.hasQueuedJobs()) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                        notificationManager.cancel(NOTIFICATION_PROGRESS_ID)
                    }
                    postPlayerReadyNotification(titleSlug, cueCount, isComplete, isLiveStream, useFullScreenIntent)
                }.onFailure { err ->
                    AppLogger.w(TAG, "PlayerReady notify failed", err)
                }
            },
            onQueuedReadyNotify = { queueId, titleSlug, cueCount, isComplete, isLiveStream ->
                runCatching {
                    postQueuedReadyNotification(queueId, titleSlug, cueCount, isComplete, isLiveStream)
                }.onFailure { err ->
                    AppLogger.w(TAG, "QueuedReady notify failed", err)
                }
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as SubcolabApp
        when (intent?.action) {
            ACTION_START_JOB -> {
                promoteToForeground(
                    title = "SUBCOLAB",
                    body = app.coordinator.state.value.statusMessage.ifBlank {
                        "Downloading & translating in background..."
                    },
                )
            }
            ACTION_CANCEL_JOB -> app.coordinator.cancelCurrentJob()
            ACTION_STOP_GPU -> {
                app.coordinator.stopT4Gpu()
                stopSelf()
            }
            ACTION_DISMISS_READY -> {
                cancelReadyNotification(this)
                app.coordinator.dismissReadyPrompt()
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(title: String, body: String) {
        val notification = buildProgressNotification(
            title = title,
            body = body,
            ongoing = true,
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_PROGRESS_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_PROGRESS_ID, notification)
            }
        }.onFailure { err ->
            AppLogger.e(TAG, "startForeground failed", err)
        }
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "onDestroy")
        val app = application as SubcolabApp
        app.coordinator.detachServiceCallbacks()
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildProgressNotification(
        title: String,
        body: String,
        ongoing: Boolean,
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, SubcolabApp.CHANNEL_PROGRESS_ID)
            .setSmallIcon(R.drawable.ic_stat_subcolab)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)

        if (ongoing) {
            val cancelIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, SubcolabForegroundService::class.java).apply { action = ACTION_CANCEL_JOB },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stopGpuIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, SubcolabForegroundService::class.java).apply { action = ACTION_STOP_GPU },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Cancel Job", cancelIntent)
            builder.addAction(0, "Stop T4 GPU", stopGpuIntent)
        }
        return builder.build()
    }

    private fun postPlayerReadyNotification(
        titleSlug: String,
        cueCount: Int,
        isComplete: Boolean,
        isLiveStream: Boolean,
        useFullScreenIntent: Boolean,
    ) {
        val openPlayerPending = PendingIntent.getActivity(
            this,
            3,
            MainActivity.createOpenPlayerIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val laterPending = PendingIntent.getBroadcast(
            this,
            4,
            Intent(this, ReadyDismissReceiver::class.java).apply { action = ACTION_DISMISS_READY },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val cleanTitle = titleSlug.replace('_', ' ')
        val titleText = when {
            isComplete -> "Ready to watch ($cueCount cues)"
            isLiveStream -> "Live Stream Ready — Watch Now ($cueCount cues)"
            else -> "Ready to Watch ($cueCount cues)"
        }
        val bodyText = when {
            isComplete -> "$cleanTitle\nSaved to Downloads/SUBCOLAB/"
            isLiveStream -> "$cleanTitle\nSmooth live stream ready while remaining subtitles translate"
            else -> "$cleanTitle\nPlay from start while remaining chunks finish in background"
        }
        val builder = NotificationCompat.Builder(this, SubcolabApp.CHANNEL_READY_ID)
            .setSmallIcon(R.drawable.ic_stat_subcolab)
            .setContentTitle(titleText)
            .setContentText(cleanTitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (useFullScreenIntent) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(openPlayerPending)
            .addAction(0, "Watch Now", openPlayerPending)
            .addAction(0, "Later", laterPending)

        if (useFullScreenIntent) {
            builder.setFullScreenIntent(openPlayerPending, true)
        }
        notificationManager.notify(NOTIFICATION_READY_ID, builder.build())
    }

    private fun postQueuedReadyNotification(
        queueId: String,
        titleSlug: String,
        cueCount: Int,
        isComplete: Boolean,
        isLiveStream: Boolean,
    ) {
        val notifId = queuedNotificationId(queueId)
        synchronized(activeQueuedNotificationIds) {
            activeQueuedNotificationIds.add(notifId)
        }
        val baseReq = 2000 + ((queueId.hashCode() and 0x7FFFFFFF) % 4000) * 2
        val watchNowPending = PendingIntent.getActivity(
            this,
            baseReq,
            MainActivity.createSwitchToQueuedIntent(this, queueId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hidePending = PendingIntent.getBroadcast(
            this,
            baseReq + 1,
            Intent(this, ReadyDismissReceiver::class.java).apply {
                action = ACTION_HIDE_QUEUED
                putExtra(EXTRA_QUEUE_ID, queueId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val cleanTitle = titleSlug.replace('_', ' ')
        val titleText = "Queued video open to Watch Now ($cueCount cues)"
        val bodyText = when {
            isComplete -> "$cleanTitle — Ready. Tap Watch Now to switch, or Hide to keep watching current video."
            isLiveStream -> "$cleanTitle — Live stream ready ($cueCount cues). Tap Watch Now to switch, or Hide to keep translating in background."
            else -> "$cleanTitle — Ready ($cueCount cues). Tap Watch Now to switch or Hide."
        }
        val builder = NotificationCompat.Builder(this, SubcolabApp.CHANNEL_READY_ID)
            .setSmallIcon(R.drawable.ic_stat_subcolab)
            .setContentTitle(titleText)
            .setContentText(cleanTitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(watchNowPending)
            .addAction(0, "Watch Now", watchNowPending)
            .addAction(0, "Hide", hidePending)

        notificationManager.notify(notifId, builder.build())
    }
}
