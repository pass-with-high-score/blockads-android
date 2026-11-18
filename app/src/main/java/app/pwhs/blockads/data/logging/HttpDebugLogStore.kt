package app.pwhs.blockads.data.logging

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicLong

@Serializable
enum class HttpLogAction {
    BLOCKED,
    ALLOWED,
    SURROGATE,
    SCRIPTLET
}

@Serializable
data class HttpDebugLogEntry(
    val id: Long,
    val timestamp: Long,
    val source: String,
    val action: HttpLogAction,
    val rule: String,
    val method: String,
    val resourceType: String,
    val initiator: String,
    val url: String,
    val detail: String? = null
)

/**
 * Thread-safe ring buffer for uBlock-style HTTP, surrogate, and scriptlet debug logs,
 * persisted to [Context.getFilesDir] so logs survive app restarts and reinstalls (`adb install -r`).
 */
object HttpDebugLogStore {

    private const val MAX_ENTRIES = 800
    private const val FILE_NAME = "http_debug_logs.json"
    private const val PREFS_NAME = "http_debug_log_prefs"
    private const val KEY_RECORDING = "recording_enabled"

    private val nextId = AtomicLong(1L)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var logFile: File? = null
    @Volatile
    private var prefs: android.content.SharedPreferences? = null
    private var saveJob: Job? = null

    private val _isRecording = MutableStateFlow(true)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _entries = MutableStateFlow<List<HttpDebugLogEntry>>(emptyList())
    val entries: StateFlow<List<HttpDebugLogEntry>> = _entries.asStateFlow()

    fun init(context: Context) {
        val appCtx = context.applicationContext
        val sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        _isRecording.value = sp.getBoolean(KEY_RECORDING, true)
        val file = File(appCtx.filesDir, FILE_NAME)
        logFile = file
        scope.launch {
            if (!file.exists() || file.length() == 0L) return@launch
            runCatching {
                val loaded = json.decodeFromString<List<HttpDebugLogEntry>>(file.readText())
                synchronized(lock) {
                    val merged = (_entries.value + loaded)
                        .distinctBy { it.id }
                        .sortedByDescending { it.timestamp }
                        .take(MAX_ENTRIES)
                    val maxId = merged.maxOfOrNull { it.id } ?: 0L
                    nextId.set(maxOf(nextId.get(), maxId + 1L))
                    _entries.value = merged
                }
            }
        }
    }

    fun setRecordingEnabled(enabled: Boolean) {
        _isRecording.value = enabled
        prefs?.edit()?.putBoolean(KEY_RECORDING, enabled)?.apply()
    }

    fun record(
        source: String,
        action: HttpLogAction,
        rule: String,
        method: String,
        resourceType: String,
        initiator: String,
        url: String,
        detail: String? = null
    ) {
        if (!_isRecording.value) return
        val entry = HttpDebugLogEntry(
            id = nextId.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            source = source,
            action = action,
            rule = rule,
            method = method,
            resourceType = resourceType,
            initiator = initiator,
            url = url,
            detail = detail?.takeIf { it.isNotBlank() }
        )
        synchronized(lock) {
            val current = _entries.value
            val updated = ArrayList<HttpDebugLogEntry>(minOf(current.size + 1, MAX_ENTRIES))
            updated.add(entry)
            val limit = MAX_ENTRIES - 1
            for (i in 0 until minOf(current.size, limit)) {
                updated.add(current[i])
            }
            _entries.value = updated
        }
        scheduleSave()
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
        }
        saveJob?.cancel()
        val file = logFile ?: return
        scope.launch {
            runCatching { if (file.exists()) file.delete() }
        }
    }

    private fun scheduleSave() {
        val file = logFile ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(800L)
            val snapshot = synchronized(lock) { _entries.value }
            runCatching {
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(json.encodeToString(snapshot))
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }
}
