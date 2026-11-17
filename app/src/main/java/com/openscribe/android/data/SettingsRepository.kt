package com.openscribe.android.data

import android.content.Context
import android.net.Uri
import com.openscribe.android.model.AppSettings
import com.openscribe.android.model.PersistedJobSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class SettingsRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("openscribe_settings", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    val cookiesFile: File
        get() = File(context.filesDir, "cookies.txt")

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        val raw = prefs.getString("settings_json", null)
        val parsed = if (raw != null) {
            runCatching { json.decodeFromString<AppSettings>(raw) }.getOrNull()
        } else null
        return (parsed ?: AppSettings()).copy(
            hasCookiesFile = cookiesFile.exists() && cookiesFile.length() > 0L,
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value).copy(
            hasCookiesFile = cookiesFile.exists() && cookiesFile.length() > 0L,
        )
        _settings.value = updated
        prefs.edit().putString("settings_json", json.encodeToString(updated)).apply()
    }

    fun saveLastJobSession(session: PersistedJobSession) {
        runCatching {
            prefs.edit().putString("last_job_session_json", json.encodeToString(session)).apply()
        }
    }

    fun loadLastJobSession(): PersistedJobSession? {
        val raw = prefs.getString("last_job_session_json", null) ?: return null
        return runCatching { json.decodeFromString<PersistedJobSession>(raw) }.getOrNull()
    }

    fun clearLastJobSession() {
        prefs.edit().remove("last_job_session_json").apply()
    }

    fun importCookiesUri(uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                cookiesFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            val valid = cookiesFile.exists() && cookiesFile.length() > 0L
            update { it.copy(hasCookiesFile = valid) }
            valid
        }.getOrDefault(false)
    }

    fun clearCookies() {
        if (cookiesFile.exists()) cookiesFile.delete()
        update { it.copy(hasCookiesFile = false) }
    }
}
