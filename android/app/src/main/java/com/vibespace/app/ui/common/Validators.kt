package com.vibespace.app.ui.common

/** Client-side checks that mirror the backend's zod schemas (routes/auth.ts). The server still validates. */
object Validators {
    private val username = Regex("^[a-z0-9_.]{3,30}$")
    private val email = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun isValidUsername(u: String): Boolean {
        val s = u.trim().lowercase()
        return username.matches(s) && !s.startsWith('.') && !s.endsWith('.') && !s.contains("..")
    }

    fun isValidEmail(e: String): Boolean = e.trim().length <= 254 && email.matches(e.trim())

    fun isValidPassword(p: String): Boolean = p.length in 8..128

    const val MAX_CAPTION = 2200
    const val MAX_COMMENT = 1000
}
