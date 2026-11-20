package com.subcolab.android

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.subcolab.android.colab.ColabAuthManager
import com.subcolab.android.colab.ColabClient
import com.subcolab.android.data.SettingsRepository
import com.subcolab.android.media.StreamExtractor
import com.subcolab.android.service.SubcolabCoordinator
import com.subcolab.android.util.AppLogger
import com.subcolab.android.util.SubtitleManager
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class SubcolabApp : Application() {

    companion object {
        const val CHANNEL_PROGRESS_ID = "subcolab_progress"
        const val CHANNEL_READY_ID = "subcolab_ready"
        private const val TAG = "SubcolabApp"

        private val startedActivitiesCount = AtomicInteger(0)
        val isAppInForeground: Boolean
            get() = startedActivitiesCount.get() > 0
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enginesReady = MutableStateFlow(false)
    val enginesReady: StateFlow<Boolean> = _enginesReady.asStateFlow()

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
    lateinit var coordinator: SubcolabCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        AppLogger.installCrashHandler(this)
        AppLogger.i(TAG, "SubcolabApp.onCreate (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")

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

        settingsRepository = SettingsRepository(this)
        authManager = ColabAuthManager(this)
        colabClient = ColabClient(authManager)
        streamExtractor = StreamExtractor()
        subtitleManager = SubtitleManager()
        coordinator = SubcolabCoordinator(
            appContext = this,
            settingsRepo = settingsRepository,
            colabClient = colabClient,
            streamExtractor = streamExtractor,
            subtitleManager = subtitleManager,
        )

        // Initialize embedded yt-dlp, FFmpeg, and Aria2c off the Main UI thread so launch is instant
        appScope.launch {
            var ytInitialized = false
            runCatching {
                YoutubeDL.getInstance().init(this@SubcolabApp)
                FFmpeg.getInstance().init(this@SubcolabApp)
                Aria2c.getInstance().init(this@SubcolabApp)
                ytInitialized = true
                AppLogger.i(TAG, "youtubedl-android, ffmpeg, aria2c initialized")
            }.onFailure { err ->
                AppLogger.e(TAG, "Failed to initialize youtubedl-android", err)
            }
            _enginesReady.value = true

            // Quietly pre-warm Colab T4 GPU right at boot if already signed in (never blocks share)
            prewarmT4IfSignedIn("app_startup")

            if (ytInitialized) {
                // Delay background yt-dlp update so the first shared video starts immediately without lock contention
                delay(15_000L)
                if (!coordinator.hasActiveJob()) {
                    streamExtractor.updateYtDlpIfDue(this@SubcolabApp)
                }
            }
        }
    }

    fun prewarmT4IfSignedIn(reason: String) {
        val settings = settingsRepository.settings.value
        if (settings.useCustomTunnel || !authManager.isSignedIn()) return
        appScope.launch {
            runCatching {
                AppLogger.i(TAG, "Background Colab T4 pre-warm requested ($reason)")
                val session = colabClient.prewarmIfSignedIn(settings.modelId)
                if (session != null) {
                    coordinator.onPrewarmedSession(session)
                }
            }.onFailure { err ->
                AppLogger.w(TAG, "Background T4 pre-warm skipped ($reason): ${err.message}")
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
