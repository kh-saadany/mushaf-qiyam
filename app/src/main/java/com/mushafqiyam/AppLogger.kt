package com.mushafqiyam

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val level: String, // "INFO", "WARN", "ERROR"
    val tag: String,
    val message: String,
    val throwable: Throwable? = null
)

/**
 * AppLogger: Centralized thread-safe logger that routes logs
 * to Android Logcat AND an in-app StateFlow for live diagnostic viewing.
 */
object AppLogger {

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun addEntry(level: String, tag: String, message: String, throwable: Throwable? = null) {
        synchronized(this) {
            val timestamp = dateFormat.format(Date())
            val entry = LogEntry(timestamp, level, tag, message, throwable)
            val currentList = _logs.value.toMutableList()
            if (currentList.size >= 100) {
                currentList.removeAt(0)
            }
            currentList.add(entry)
            _logs.value = currentList
        }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        addEntry("INFO", tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        Log.w(tag, msg, t)
        val stackMsg = if (t != null) "$msg -> ${t.localizedMessage}" else msg
        addEntry("WARN", tag, stackMsg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        val stackMsg = if (t != null) "$msg -> ${t.localizedMessage}\n${t.stackTraceToString().take(300)}" else msg
        addEntry("ERROR", tag, stackMsg, t)
    }

    fun clear() {
        synchronized(this) {
            _logs.value = emptyList()
        }
    }

    fun getAllLogsText(): String {
        return _logs.value.joinToString("\n") { entry ->
            "[${entry.timestamp}] [${entry.level}] ${entry.tag}: ${entry.message}"
        }
    }
}
