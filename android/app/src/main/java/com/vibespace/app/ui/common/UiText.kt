package com.vibespace.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vibespace.app.R
import com.vibespace.app.data.api.AppError

/** One-off UI events from ViewModels. */
sealed interface UiEvent {
    /** A guest tried a member-only action: show the login / signup prompt. */
    data object LoginRequired : UiEvent
    data class Error(val error: AppError) : UiEvent
    data class Message(val resId: Int) : UiEvent
}

@Composable
fun AppError.userMessage(): String = when (this) {
    is AppError.Api -> message.ifBlank { stringResource(R.string.error_generic) }
    is AppError.Network -> stringResource(R.string.error_network)
    AppError.LoginRequired -> stringResource(R.string.login_prompt_body)
    is AppError.Unknown -> stringResource(R.string.error_generic)
}

@Composable
fun relativeTime(iso: String?): String {
    val (unit, n) = TimeFormat.relative(iso, System.currentTimeMillis())
    return when (unit) {
        TimeFormat.Unit.NOW -> stringResource(R.string.just_now)
        TimeFormat.Unit.MINUTES -> stringResource(R.string.minutes_ago, n)
        TimeFormat.Unit.HOURS -> stringResource(R.string.hours_ago, n)
        TimeFormat.Unit.DAYS -> stringResource(R.string.days_ago, n)
    }
}
