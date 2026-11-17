package com.openscribe.android

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.openscribe.android.colab.ColabAuthManager
import com.openscribe.android.colab.ColabClient
import com.openscribe.android.data.SettingsRepository
import com.openscribe.android.media.StreamExtractor
import com.openscribe.android.service.OpenScribeCoordinator
import com.openscribe.android.util.AppLogger
import com.openscribe.android.util.SubtitleManager
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class OpenScribeApp : Application() {

    companion object {
        const val CHANNEL_PROGRESS_ID = "openscribe_progress"
        const val CHANNEL_READY_ID = "openscribe_ready"
        private const val TAG = "OpenScribeApp"

        private val startedActivitiesCount = AtomicInteger(0)
        val isAppInForeground: Boolean
            get() = startedActivitiesCount.get() > 0
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var authManager: ColabAuthManager
        private set
    lateinit var colabClient: ColabClient
        private set
    lateinit var streamExtractor: StreamExtractor
        private set
    lateinit var subtitleManager: SubtitleManager
        private set
    lateinit var coordinator: OpenScribeCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        AppLogger.installCrashHandler(this)
        AppLogger.i(TAG, "OpenScribeApp.onCreate (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                startedActivitiesCount.incrementAndGet()
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                startedActivitiesCount.updateAndGet { current -> maxOf(0, current - 1) }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        createNotificationChannels()

        var ytInitialized = false
        runCatching {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            Aria2c.getInstance().init(this)
            ytInitialized = true
            AppLogger.i(TAG, "youtubedl-android, ffmpeg, aria2c initialized")
        }.onFailure { err ->
            AppLogger.e(TAG, "Failed to initialize youtubedl-android", err)
        }

        settingsRepository = SettingsRepository(this)
        authManager = ColabAuthManager(this)
        colabClient = ColabClient(authManager)
        streamExtractor = StreamExtractor()
        subtitleManager = SubtitleManager()
        coordinator = OpenScribeCoordinator(
            appContext = this,
            settingsRepo = settingsRepository,
            colabClient = colabClient,
            streamExtractor = streamExtractor,
            subtitleManager = subtitleManager,
        )

        if (ytInitialized) {
            appScope.launch {
                streamExtractor.updateYtDlpIfDue(this@OpenScribeApp)
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val progressChannel = NotificationChannel(
            CHANNEL_PROGRESS_ID,
            getString(R.string.channel_progress_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows live Colab T4 GPU and Whisper translation progress"
            setShowBadge(false)
        }

        val readyChannel = NotificationChannel(
            CHANNEL_READY_ID,
            getString(R.string.channel_ready_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts when 4 English subtitle cues are ready to watch"
        }

        nm.createNotificationChannel(progressChannel)
        nm.createNotificationChannel(readyChannel)
    }
}
