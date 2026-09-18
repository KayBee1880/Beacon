package com.beacon.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { DEBUG, WARN, ERROR }

// throwableMessage, not the Throwable itself: DiagnosticsScreen only ever needs a short
// line to show, not a full stack trace (adb logcat still gets the real Log.w/e call
// below, unabridged); holding onto live exception objects in a long-lived ring buffer
// would be needless memory to carry for a value nothing here ever uses.
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwableMessage: String?
)

/**
 * Docs/11 §2: every existing `Log.w` call in this codebase, migrated here almost
 * mechanically, same tag/message/throwable shape as before. Still reaches `adb logcat`
 * unchanged; the addition is capturing every entry into a bounded, observable in-memory
 * ring buffer too, so DiagnosticsScreen can show what's happening without a connected
 * computer, exactly the constraint docs/00 §1's disaster-response and remote-expedition
 * personas can't assume away.
 */
object BeaconLog {
    private const val MAX_ENTRIES = 200

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        record(LogLevel.DEBUG, tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        record(LogLevel.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        record(LogLevel.ERROR, tag, message, throwable)
    }

    // MutableStateFlow.update is the same atomic compare-and-swap pattern
    // BleCentralRole.rssiByPeerId already uses, needed here for the same reason: log
    // calls arrive from many different callback threads (BLE, GATT server, Wi-Fi P2P
    // broadcasts) with no other synchronization between them.
    private fun record(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message, throwable?.message)
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
    }
}
