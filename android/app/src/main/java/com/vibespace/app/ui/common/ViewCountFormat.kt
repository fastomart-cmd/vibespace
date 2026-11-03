package com.vibespace.app.ui.common

/**
 * View counts, YouTube / Instagram style, rounded DOWN to one decimal so a number never looks bigger than it is.
 *  - International (English UI): 999, 1.2K, 12.5K, 1.2M, 3.4B
 *  - Indian (Hindi UI):           999, 1.2K, 99.9K, 3.4 lakh, 1.2 Cr  (lakh = 1,00,000; crore = 1,00,00,000)
 * The lakh / crore words come from string resources (Hindi: "लाख" / "करोड़"), so they read naturally in each language.
 * Always uses "." as the decimal point.
 */
object ViewCountFormat {
    enum class System { INTERNATIONAL, INDIAN }

    fun format(n: Long, system: System, lakh: String = "lakh", crore: String = "Cr"): String {
        val v = n.coerceAtLeast(0)
        return when (system) {
            System.INTERNATIONAL -> when {
                v < 1_000 -> v.toString()
                v < 1_000_000 -> oneDecimal(v, 1_000) + "K"
                v < 1_000_000_000 -> oneDecimal(v, 1_000_000) + "M"
                else -> oneDecimal(v, 1_000_000_000) + "B"
            }
            System.INDIAN -> when {
                v < 1_000 -> v.toString()
                v < 1_00_000 -> oneDecimal(v, 1_000) + "K"
                v < 1_00_00_000 -> oneDecimal(v, 1_00_000) + " " + lakh
                else -> oneDecimal(v, 1_00_00_000) + " " + crore
            }
        }
    }

    /** floor(v / unit, 1 decimal) without floating-point surprises; "1.0" is shown as "1". */
    private fun oneDecimal(v: Long, unit: Long): String {
        val tenths = v * 10 / unit
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0L) whole.toString() else "$whole.$frac"
    }

    fun systemFor(code: String): System = if (code == "indian") System.INDIAN else System.INTERNATIONAL
}
