package com.vibespace.app.ui.common

import java.util.Calendar
import java.util.TimeZone

/** Pure helpers (no java.time, so they work on API 24 without desugaring). */
object TimeFormat {
    private val iso = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:?\d{2})?$""")

    /** Parse the backend's ISO-8601 timestamps (e.g. 2026-11-02T08:27:00.123Z). Null if unparseable. */
    fun parseIsoMillis(s: String?): Long? {
        val m = iso.matchEntire(s?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toInt() ?: 0
        var t = cal.timeInMillis + millis
        val zone = g[8]
        if (zone.isNotEmpty() && zone != "Z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.substring(1).replace(":", "")
            val offMin = digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
            t -= sign * offMin * 60_000L
        }
        return t
    }

    enum class Unit { NOW, MINUTES, HOURS, DAYS }

    /** "how long ago", bucketed. */
    fun relative(iso: String?, nowMillis: Long): Pair<Unit, Int> {
        val t = parseIsoMillis(iso) ?: return Unit.NOW to 0
        val diff = (nowMillis - t).coerceAtLeast(0) / 1000
        return when {
            diff < 60 -> Unit.NOW to 0
            diff < 3600 -> Unit.MINUTES to (diff / 60).toInt()
            diff < 86400 -> Unit.HOURS to (diff / 3600).toInt()
            else -> Unit.DAYS to (diff / 86400).toInt()
        }
    }

    /** 1234 -> "1.2K", 2_500_000 -> "2.5M" (Indian users read K/M fine; counts stay exact below 1000). */
    fun compact(n: Int): String = when {
        n < 1000 -> n.toString()
        n < 1_000_000 -> trim(n / 1000.0) + "K"
        else -> trim(n / 1_000_000.0) + "M"
    }

    private fun trim(v: Double): String {
        val r = Math.floor(v * 10) / 10
        return if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()
    }
}
