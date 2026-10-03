package com.paypeico.chat.data

import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimeFmt {
    private val db = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone(Config.LARAVEL_TIMEZONE)
    }

    /** Current time in the same format/timezone Laravel writes to created_at. */
    fun now(): String = synchronized(db) { db.format(Date()) }

    fun parse(ts: String?): Date? = try {
        if (ts.isNullOrBlank()) null else synchronized(db) { db.parse(ts) }
    } catch (_: Exception) {
        null
    }

    private fun fmt(pattern: String, d: Date) = SimpleDateFormat(pattern, Locale.getDefault()).format(d)

    private fun isYesterday(d: Date) = DateUtils.isToday(d.time + DateUtils.DAY_IN_MILLIS)

    private fun sameYear(d: Date): Boolean {
        val a = Calendar.getInstance().apply { time = d }
        return a.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
    }

    /** Time shown inside a message bubble. */
    fun clock(ts: String): String = parse(ts)?.let { fmt("h:mm a", it) } ?: ts

    /** Time shown in the chat list. */
    fun listLabel(ts: String?): String {
        val d = parse(ts) ?: return ""
        return when {
            DateUtils.isToday(d.time) -> fmt("h:mm a", d)
            isYesterday(d) -> "Yesterday"
            sameYear(d) -> fmt("dd MMM", d)
            else -> fmt("dd/MM/yy", d)
        }
    }

    /** Key that is equal for messages sent on the same local day. */
    fun dayKey(ts: String): String = parse(ts)?.let { fmt("yyyyMMdd", it) } ?: ts.take(10)

    /** Label for the date separators inside a conversation. */
    fun dayLabel(ts: String): String {
        val d = parse(ts) ?: return ts.take(10)
        return when {
            DateUtils.isToday(d.time) -> "Today"
            isYesterday(d) -> "Yesterday"
            sameYear(d) -> fmt("EEE, dd MMM", d)
            else -> fmt("dd MMM yyyy", d)
        }
    }
}
