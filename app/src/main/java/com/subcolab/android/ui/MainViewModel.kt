package com.subcolab.android.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.subcolab.android.SubcolabApp
import com.subcolab.android.colab.AuthUiState
import com.subcolab.android.media.LocalAudioExtractor
import com.subcolab.android.model.AppSettings
import com.subcolab.android.model.JobProgressState
import com.subcolab.android.model.SubtitleRetranslateOptions
import com.subcolab.android.model.ThemeMode
import com.subcolab.android.model.WorkflowMode
import com.subcolab.android.service.SubcolabForegroundService
import com.subcolab.android.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SubcolabApp
    private val coordinator = app.coordinator
    private val authManager = app.authManager
    private val settingsRepo = app.settingsRepository

    val jobState: StateFlow<JobProgressState> = coordinator.state
    val liveSrtRevision: StateFlow<Long> = coordinator.liveSrtRevision
    val authState: StateFlow<AuthUiState> = authManager.authState
    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val lastCrashText: StateFlow<String?> = AppLogger.lastCrashText
    val debugLogs: StateFlow<List<String>> = AppLogger.logs

    private val initialUrl = if (coordinator.state.value.mode == WorkflowMode.LOCAL_VIDEO_SUBTITLES) {
        ""
    } else {
        coordinator.state.value.currentUrl
    }
    private val _urlInput = MutableStateFlow(initialUrl)
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _selectedLang = MutableStateFlow(coordinator.state.value.sourceLang.ifBlank { "auto" })
    val selectedLang: StateFlow<String> = _selectedLang.asStateFlow()

    private val _selectedLocalVideoUri = MutableStateFlow(
        if (coordinator.state.value.mode == WorkflowMode.LOCAL_VIDEO_SUBTITLES) {
            coordinator.state.value.currentUrl.takeIf { it.isNotBlank() }
        } else null,
    )
    val selectedLocalVideoUri: StateFlow<String?> = _selectedLocalVideoUri.asStateFlow()

    private val _selectedLocalVideoName = MutableStateFlow(coordinator.state.value.selectedLocalVideoName)
    val selectedLocalVideoName: StateFlow<String?> = _selectedLocalVideoName.asStateFlow()

    fun setUrlInput(raw: String) {
        _urlInput.value = raw
    }

    fun setSelectedLang(langCodeOrName: String) {
        _selectedLang.value = langCodeOrName.ifBlank { "auto" }
    }

    fun startTranslationJob(workflowMode: WorkflowMode = WorkflowMode.WATCH_NOW) {
        coordinator.startUrlJob(
            rawUrl = _urlInput.value,
            sourceLang = _selectedLang.value,
            workflowMode = workflowMode,
            fromExternalShare = false,
            reusePreviousCache = false,
        )
    }

    fun startWatchNowJob() = startTranslationJob(WorkflowMode.WATCH_NOW)

    fun startDownloadJob() = startTranslationJob(WorkflowMode.DOWNLOAD_AND_TRANSLATE)

    fun selectLocalVideo(uri: Uri) {
        _selectedLocalVideoUri.value = uri.toString()
        _selectedLocalVideoName.value = LocalAudioExtractor.resolveDisplayName(app, uri)
    }

    fun clearSelectedLocalVideo() {
        _selectedLocalVideoUri.value = null
        _selectedLocalVideoName.value = null
    }

    fun startLocalVideoJob() {
        val uri = _selectedLocalVideoUri.value ?: return
        coordinator.startLocalVideoJob(
            videoUriString = uri,
            sourceLang = _selectedLang.value,
        )
    }

    fun redownloadPackVideo(packId: String, maxHeight: Int = 720) {
        coordinator.redownloadPackVideo(packId, maxHeight)
    }

    fun retranslatePackSubtitles(packId: String, options: SubtitleRetranslateOptions) {
        coordinator.retranslatePackSubtitles(packId, options)
    }

    fun openSavedPackInPlayer(packId: String): Boolean {
        return coordinator.openSavedPackInPlayer(packId)
    }

    fun deletePackPermanently(packId: String) {
        coordinator.deletePackPermanently(packId)
    }

    fun runPrevious() {
        coordinator.runPreviousJob()
    }

    fun resumePreviousJob() {
        coordinator.resumePreviousJob()
    }

    suspend fun retryPlayback(): Pair<String, String?>? {
        return coordinator.retryPlayerStream()
    }

    fun prepareNewLink() {
        _urlInput.value = ""
        _selectedLocalVideoUri.value = null
        _selectedLocalVideoName.value = null
    }

    fun cancelCurrentJob() {
        coordinator.cancelCurrentJob()
    }

    fun stopT4Gpu() {
        coordinator.stopT4Gpu()
    }

    fun dismissReadyPrompt() {
        coordinator.dismissReadyPrompt()
    }

    fun onPlayerOpened(context: Context) {
        coordinator.dismissReadyPrompt()
        SubcolabForegroundService.cancelReadyNotification(context)
    }

    fun setPlayerScreenOpen(open: Boolean) {
        coordinator.setPlayerScreenOpen(open)
    }

    fun signInWithLoopback(activityContext: Context) {
        viewModelScope.launch {
            val s = settingsRepo.settings.value
            val result = authManager.signInWithLoopback(
                activityContext = activityContext,
                customClientId = s.customOauthClientId,
                customClientSecret = s.customOauthClientSecret,
            )
            if (result.isSuccess) {
                app.prewarmT4IfSignedIn("oauth_loopback")
            }
        }
    }

    fun launchCopyPasteAuth(activityContext: Context) {
        val s = settingsRepo.settings.value
        authManager.launchCopyPasteFlow(
            activityContext = activityContext,
            customClientId = s.customOauthClientId,
            customClientSecret = s.customOauthClientSecret,
        )
    }

    fun completeCopyPasteAuth(code: String) {
        viewModelScope.launch {
            val result = authManager.completeCopyPasteFlow(code)
            if (result.isSuccess) {
                app.prewarmT4IfSignedIn("oauth_copypaste")
            }
        }
    }

    fun dismissCopyPasteAuth() {
        authManager.dismissCopyPastePrompt()
    }

    fun signOut() {
        authManager.signOut()
        app.colabClient.clearLocalSession()
        coordinator.resetGpuWarmState()
    }

    fun importCookies(uri: Uri): Boolean = settingsRepo.importCookiesUri(uri)

    fun clearCookies() = settingsRepo.clearCookies()

    fun setCleanAudio(enabled: Boolean) {
        settingsRepo.update { it.copy(cleanAudio = enabled) }
    }

    fun setUseCustomTunnel(enabled: Boolean) {
        settingsRepo.update { it.copy(useCustomTunnel = enabled) }
    }

    fun setCustomTunnelConfig(url: String, token: String) {
        settingsRepo.update { it.copy(customTunnelUrl = url.trim(), customTunnelToken = token.trim()) }
    }

    fun setCustomOauthConfig(clientId: String, clientSecret: String) {
        settingsRepo.update {
            it.copy(
                customOauthClientId = clientId.trim(),
                customOauthClientSecret = clientSecret.trim(),
            )
        }
    }

    fun setSubtitleFontSize(size: Int) {
        settingsRepo.update { it.copy(subtitleFontSize = size.coerceIn(28, 72)) }
    }

    fun setSubtitleDelaySec(delaySec: Float) {
        settingsRepo.update { it.copy(subtitleDelaySec = delaySec.coerceIn(-15f, 15f)) }
    }

    fun setSubtitleBorderSize(borderSize: Int) {
        settingsRepo.update { it.copy(subtitleBorderSize = borderSize.coerceIn(0, 6)) }
    }

    fun setSubtitleBoxEnabled(enabled: Boolean) {
        settingsRepo.update { it.copy(subtitleBoxEnabled = enabled) }
    }

    fun setModelId(modelId: String) {
        settingsRepo.update { it.copy(modelId = modelId.ifBlank { "large-v3" }) }
    }

    fun setThemeMode(mode: ThemeMode) {
        settingsRepo.update { it.copy(themeMode = mode) }
    }

    fun clearLastCrash() {
        AppLogger.clearLastCrash()
    }

    fun clearDebugLogs() {
        AppLogger.clearLogs()
    }

    fun buildFullLogReport(): String = AppLogger.buildFullReport()
}
