package com.openscribe.android.util

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app crash catcher + ring-buffer debug logger for OpenScribe Android.
 * Writes uncaught exceptions to filesDir/last_crash.txt before the process exits,
 * and records timestamped logs from ColabClient, StreamExtractor, OpenScribeCoordinator,
 * OpenScribeForegroundService, and MpvPlayerController for one-tap clipboard export.
 */
object AppLogger {

    private const val MAX_ENTRIES = 350
    private const val CRASH_FILE_NAME = "last_crash.txt"

    private val lock = Any()
    private val ringBuffer = ArrayDeque<String>(MAX_ENTRIES + 1)
    private val crashHandlerInstalled = AtomicBoolean(false)
    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
    private val fullDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile
    private var crashFile: File? = null

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _lastCrashText = MutableStateFlow<String?>(null)
    val lastCrashText: StateFlow<String?> = _lastCrashText.asStateFlow()

    fun init(appContext: Context) {
        val file = File(appContext.filesDir, CRASH_FILE_NAME)
        crashFile = file
        val existing = runCatching {
            if (file.exists() && file.length() > 0L) file.readText(Charsets.UTF_8) else null
        }.getOrNull()
        _lastCrashText.value = existing?.takeIf { it.isNotBlank() }
    }

    fun installCrashHandler(appContext: Context) {
        init(appContext)
        if (!crashHandlerInstalled.compareAndSet(false, true)) return

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val report = buildCrashReport(thread, throwable)
                val target = crashFile ?: File(appContext.filesDir, CRASH_FILE_NAME)
                target.writeText(report, Charsets.UTF_8)
                Log.e("OpenScribeCrash", report)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        appendEntry("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        appendEntry("I", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
            appendEntry("W", tag, "$message (${throwable::class.simpleName}: ${throwable.message})")
        } else {
            Log.w(tag, message)
            appendEntry("W", tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            val trace = Log.getStackTraceString(throwable).lineSequence().take(6).joinToString("\n")
            appendEntry("E", tag, "$message\n$trace")
        } else {
            Log.e(tag, message)
            appendEntry("E", tag, message)
        }
    }

    fun clearLastCrash() {
        runCatching { crashFile?.delete() }
        _lastCrashText.value = null
    }

    fun clearLogs() {
        synchronized(lock) {
            ringBuffer.clear()
            _logs.value = emptyList()
        }
    }

    fun buildFullReport(): String {
        val snapshotLogs = synchronized(lock) { ringBuffer.toList() }
        val crash = _lastCrashText.value
        return buildString {
            append("=== OpenScribe Debug Report ===\n")
            append("Generated: ").append(fullDateFormat.get()?.format(Date())).append('\n')
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("ABIs: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append("\n\n")

            if (!crash.isNullOrBlank()) {
                append("=== Last Uncaught Crash (last_crash.txt) ===\n")
                append(crash.trim()).append("\n\n")
            }

            append("=== Live Debug Logs (${snapshotLogs.size} entries) ===\n")
            if (snapshotLogs.isEmpty()) {
                append("(no log entries recorded yet)\n")
            } else {
                snapshotLogs.forEach { append(it).append('\n') }
            }
        }
    }

    private fun appendEntry(level: String, tag: String, message: String) {
        val ts = timeFormat.get()?.format(Date()) ?: "--:--:--"
        val entry = "[$ts] $level/$tag: $message"
        synchronized(lock) {
            while (ringBuffer.size >= MAX_ENTRIES) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(entry)
            _logs.value = ringBuffer.toList()
        }
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val snapshotTail = synchronized(lock) { ringBuffer.takeLast(80) }
        return buildString {
            append("Crash Time: ").append(fullDateFormat.get()?.format(Date())).append('\n')
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Thread: ").append(thread.name).append("\n\n")
            append("Stack Trace:\n")
            append(Log.getStackTraceString(throwable)).append('\n')
            if (snapshotTail.isNotEmpty()) {
                append("\nRecent Logs Before Crash:\n")
                snapshotTail.forEach { append(it).append('\n') }
            }
        }
    }
}
