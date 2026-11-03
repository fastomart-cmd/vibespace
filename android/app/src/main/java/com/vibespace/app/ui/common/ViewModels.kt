package com.vibespace.app.ui.common

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vibespace.app.VibeSpaceApp
import com.vibespace.app.data.api.AppError
import com.vibespace.app.di.AppContainer
import com.vibespace.app.R
import com.vibespace.app.ui.components.LocalLoginGate
import kotlinx.coroutines.flow.Flow

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as VibeSpaceApp).container

/** viewModel() with manual DI. [key] separates instances of the same class (e.g. one per username). */
@Composable
inline fun <reified VM : ViewModel> vibeViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val c = appContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(c) } })
}

/** Collect a ViewModel's one-off events: login prompt, toasts for errors and messages. */
@Composable
fun CollectEvents(events: Flow<UiEvent>, onOther: (UiEvent) -> Unit = {}) {
    val ctx = LocalContext.current
    val gate = LocalLoginGate.current
    LaunchedEffect(events) {
        events.collect { e ->
            when (e) {
                UiEvent.LoginRequired -> gate.prompt()
                is UiEvent.Message -> Toast.makeText(ctx, ctx.getString(e.resId), Toast.LENGTH_SHORT).show()
                is UiEvent.Error -> Toast.makeText(ctx, e.error.plainMessage(ctx), Toast.LENGTH_LONG).show()
            }
            onOther(e)
        }
    }
}

fun AppError.plainMessage(ctx: android.content.Context): String = when (this) {
    is AppError.Api -> message.ifBlank { ctx.getString(R.string.error_generic) }
    is AppError.Network -> ctx.getString(R.string.error_network)
    AppError.LoginRequired -> ctx.getString(R.string.login_prompt_body)
    is AppError.Unknown -> ctx.getString(R.string.error_generic)
}
