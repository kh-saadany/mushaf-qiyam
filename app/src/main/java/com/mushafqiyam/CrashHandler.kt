package com.mushafqiyam

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashHandler: Dual-Layer Crash & Native Process Exit Diagnostic Reporter.
 * Layer 1: Captures uncaught Java/Kotlin exceptions with thread, memory, and in-app logs.
 * Layer 2: Captures Native C++/JNI crashes (SIGSEGV, SIGABRT, ANR, Low Memory) via Android 11+
 *          ApplicationExitInfo API to diagnose native crashes in libsherpa-onnx-jni.so.
 */
class CrashHandler private constructor(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        private const val CRASH_FILE_NAME = "latest_crash.txt"
        private const val PREFS_NAME = "mushaf_prefs"
        private const val PREFS_KEY_CRASH = "last_fatal_crash"
        private const val PREFS_KEY_TIMESTAMP = "last_crash_timestamp"
        private const val PREFS_KEY_LAST_SHOWN_EXIT_TIME = "last_shown_exit_timestamp"

        @Volatile
        private var isInstalled = false

        fun init(context: Context) {
            if (isInstalled) return
            synchronized(this) {
                if (isInstalled) return
                val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext, currentHandler))
                isInstalled = true
                Log.i(TAG, "Global Dual-Layer CrashHandler installed successfully")
            }
        }

        fun recordException(context: Context, tag: String, message: String, t: Throwable) {
            try {
                val report = buildCrashReport(tag, message, t, Thread.currentThread())
                val file = File(context.filesDir, CRASH_FILE_NAME)
                file.writeText(report)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(PREFS_KEY_CRASH, report)
                    .putLong(PREFS_KEY_TIMESTAMP, System.currentTimeMillis())
                    .apply()
                Log.e(TAG, "Handled exception recorded to disk: $message", t)
            } catch (_: Throwable) {}
        }

        /**
         * Checks for pending crash report:
         * 1. Check local file or prefs from Kotlin/Java uncaught exceptions.
         * 2. If none, check historical native/OS process exit reasons on Android 11+ (API 30+).
         */
        fun getPendingCrashReport(context: Context): String? {
            // 1. Check Java/Kotlin crash dump first
            try {
                val file = File(context.filesDir, CRASH_FILE_NAME)
                if (file.exists() && file.length() > 0) {
                    val content = file.readText()
                    if (content.isNotBlank()) return content
                }
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val prefReport = prefs.getString(PREFS_KEY_CRASH, null)
                if (!prefReport.isNullOrBlank()) return prefReport
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to read Kotlin crash report: ${t.message}")
            }

            // 2. Check historical native process exits (Android 11+ / API 30+)
            return checkHistoricalNativeCrashes(context)
        }

        fun clearPendingCrashReport(context: Context) {
            try {
                val file = File(context.filesDir, CRASH_FILE_NAME)
                if (file.exists()) {
                    file.delete()
                }
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().remove(PREFS_KEY_CRASH).remove(PREFS_KEY_TIMESTAMP).apply()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to clear crash report: ${t.message}")
            }
        }

        /**
         * Queries ApplicationExitInfo on Android 11+ (API 30+) to detect Native C++ SIGSEGV/SIGABRT,
         * ANRs, and Low Memory kills that occurred in prior process invocations.
         */
        private fun checkHistoricalNativeCrashes(context: Context): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

            return try {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return null
                val exitReasons = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                if (exitReasons.isNullOrEmpty()) return null

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val lastShownTimestamp = prefs.getLong(PREFS_KEY_LAST_SHOWN_EXIT_TIME, 0L)

                // Find the newest exit reason that represents an abnormal crash and has not been shown yet
                val targetExit = exitReasons.firstOrNull { exitInfo ->
                    exitInfo.timestamp > lastShownTimestamp && isAbnormalExit(exitInfo.reason)
                } ?: return null

                // Persist timestamp so we don't present the same native crash again
                prefs.edit().putLong(PREFS_KEY_LAST_SHOWN_EXIT_TIME, targetExit.timestamp).apply()

                buildNativeExitReport(targetExit)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to inspect historical process exit reasons: ${t.message}")
                null
            }
        }

        private fun isAbnormalExit(reason: Int): Boolean {
            return when (reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_SIGNALED -> true
                else -> false
            }
        }

        private fun getReasonString(reason: Int): String {
            return when (reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE (C++/JNI Crash)"
                ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH (Java/Kotlin Fatal Exception)"
                ApplicationExitInfo.REASON_ANR -> "REASON_ANR (Application Not Responding)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY (Killed by Low Memory Killer / LMK)"
                ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED (Terminated by POSIX Signal)"
                else -> "REASON_OTHER ($reason)"
            }
        }

        private fun getSignalString(status: Int): String {
            return when (status) {
                11 -> "SIGSEGV (11 - Segmentation Fault / Invalid Memory Access)"
                6 -> "SIGABRT (6 - Abort Signal / Assertion Failure)"
                7 -> "SIGBUS (7 - Bus Error / Memory Alignment Fault)"
                4 -> "SIGILL (4 - Illegal Instruction)"
                9 -> "SIGKILL (9 - Hard Process Kill)"
                15 -> "SIGTERM (15 - Termination Request)"
                else -> "Signal $status"
            }
        }

        private fun buildNativeExitReport(exitInfo: ApplicationExitInfo): String {
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(exitInfo.timestamp))
            val pssMb = exitInfo.pss / 1024
            val rssMb = exitInfo.rss / 1024

            var traceSnippet = ""
            try {
                exitInfo.traceInputStream?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    if (text.isNotBlank()) {
                        traceSnippet = text.take(4096)
                    }
                }
            } catch (_: Throwable) {}

            return buildString {
                appendLine("==================================================")
                appendLine("🛑 MUSHAF QIYAM - NATIVE PROCESS EXIT REPORT")
                appendLine("==================================================")
                appendLine("Timestamp: $dateStr")
                appendLine("App Version: ${MainActivity.APP_VERSION}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android SDK ${Build.VERSION.SDK_INT}, ABI: ${Build.SUPPORTED_ABIS.firstOrNull()})")
                appendLine("PID: ${exitInfo.pid} (Process: ${exitInfo.processName})")
                appendLine("Exit Reason: ${getReasonString(exitInfo.reason)}")
                appendLine("Status / Signal: ${getSignalString(exitInfo.status)}")
                appendLine("Process Description: ${exitInfo.description ?: "(No description provided by OS)"}")
                appendLine("Memory at Exit: PSS ${pssMb}MB, RSS ${rssMb}MB")
                appendLine("Importance: ${exitInfo.importance}")
                appendLine()
                if (traceSnippet.isNotBlank()) {
                    appendLine("----------------- NATIVE TRACE -----------------")
                    appendLine(traceSnippet.trim())
                    appendLine()
                }
                appendLine("------------- RECENT IN-APP LOGS -------------")
                try {
                    val logs = AppLogger.getAllLogsText()
                    if (logs.isNotBlank()) {
                        appendLine(logs.takeLast(4000))
                    } else {
                        appendLine("(No in-app logs recorded)")
                    }
                } catch (_: Throwable) {
                    appendLine("(Unable to retrieve in-app logs)")
                }
                appendLine("==================================================")
            }
        }

        private fun buildCrashReport(tag: String, message: String, t: Throwable, thread: Thread): String {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            t.printStackTrace(pw)
            val stackTrace = sw.toString()

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            return buildString {
                appendLine("==================================================")
                appendLine("🛑 MUSHAF QIYAM - FATAL EXCEPTION REPORT")
                appendLine("==================================================")
                appendLine("Timestamp: $timestamp")
                appendLine("App Version: ${MainActivity.APP_VERSION}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android SDK ${Build.VERSION.SDK_INT}, ABI: ${Build.SUPPORTED_ABIS.firstOrNull()})")
                appendLine("Thread: ${thread.name} (id: ${thread.id})")
                appendLine("Source Tag: $tag")
                appendLine("Message: $message")
                appendLine("Exception Type: ${t::class.java.name}")
                appendLine("Cause: ${t.cause?.message ?: "None"}")
                appendLine()
                appendLine("---------------- STACK TRACE ----------------")
                appendLine(stackTrace.trim())
                appendLine()
                appendLine("------------- RECENT IN-APP LOGS -------------")
                try {
                    val logs = AppLogger.getAllLogsText()
                    if (logs.isNotBlank()) {
                        appendLine(logs.takeLast(4000))
                    } else {
                        appendLine("(No in-app logs recorded)")
                    }
                } catch (_: Throwable) {
                    appendLine("(Unable to retrieve in-app logs)")
                }
                appendLine("==================================================")
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val report = buildCrashReport("UncaughtException", throwable.localizedMessage ?: "Unknown", throwable, thread)
            val file = File(context.filesDir, CRASH_FILE_NAME)
            file.writeText(report)

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(PREFS_KEY_CRASH, report)
                .putLong(PREFS_KEY_TIMESTAMP, System.currentTimeMillis())
                .commit() // Synchronous commit to survive immediate process death

            Log.e(TAG, "FATAL CRASH CAUGHT BY HANDLER:\n$report")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed in uncaughtException saving: ${e.message}")
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
