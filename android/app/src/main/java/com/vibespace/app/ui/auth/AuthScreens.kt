package com.vibespace.app.ui.auth

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibespace.app.R
import com.vibespace.app.data.api.AppError
import com.vibespace.app.ui.common.userMessage
import com.vibespace.app.ui.components.BrandMark
import com.vibespace.app.ui.theme.VibeColors

@Composable
private fun AuthScaffold(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(VibeColors.backgroundGradient).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(showTagline = true, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.back)) }
        }
        Spacer(Modifier.height(32.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        content()
        Spacer(Modifier.height(32.dp))
        Text(
            "🇮🇳 " + stringResource(R.string.made_in_india),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    error: Int?,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    supporting: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = (error?.let { stringResource(it) } ?: supporting)?.let { t -> { Text(t) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onGo = { onDone() }),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    )
}

@Composable
private fun ServerError(e: AppError?) {
    if (e != null) Text(e.userMessage(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun PrimaryButton(text: String, loading: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !loading, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(text)
    }
}

@Composable
fun LoginScreen(vm: LoginViewModel, onDone: () -> Unit, onClose: () -> Unit, onSignup: () -> Unit, onForgot: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.done.collect { onDone() } }
    AuthScaffold(stringResource(R.string.welcome_back), onClose) {
        Field(s.identifier, vm::onIdentifier, stringResource(R.string.email_or_username), s.fieldErrors["identifier"], KeyboardType.Email)
        Field(s.password, vm::onPassword, stringResource(R.string.password), s.fieldErrors["password"], password = true, imeAction = ImeAction.Go, onDone = vm::submit)
        ServerError(s.serverError)
        TextButton(onClick = onForgot) { Text(stringResource(R.string.forgot_password)) }
        Spacer(Modifier.height(8.dp))
        PrimaryButton(stringResource(R.string.login), s.loading, vm::submit)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onSignup, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.no_account), color = VibeColors.NeonCyan) }
    }
}

@Composable
fun SignupScreen(vm: SignupViewModel, onDone: () -> Unit, onClose: () -> Unit, onLogin: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.done.collect { onDone() } }
    AuthScaffold(stringResource(R.string.join_vibespace), onClose) {
        Field(s.email, vm::onEmail, stringResource(R.string.email), s.fieldErrors["email"], KeyboardType.Email)
        Field(s.username, vm::onUsername, stringResource(R.string.username), s.fieldErrors["username"], supporting = stringResource(R.string.username_rules))
        Field(s.displayName, vm::onDisplayName, stringResource(R.string.display_name), null)
        Field(s.password, vm::onPassword, stringResource(R.string.password), s.fieldErrors["password"], password = true, supporting = stringResource(R.string.password_rules), imeAction = ImeAction.Go, onDone = vm::submit)
        ServerError(s.serverError)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(stringResource(R.string.signup), s.loading, vm::submit)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.have_account), color = VibeColors.NeonCyan) }
    }
}

@Composable
fun ForgotPasswordScreen(vm: ForgotPasswordViewModel, onClose: () -> Unit, onResetDone: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var showToken by rememberSaveable { mutableStateOf(s.token.isNotEmpty()) }
    LaunchedEffect(vm) {
        vm.resetDone.collect {
            Toast.makeText(ctx, ctx.getString(R.string.reset_done), Toast.LENGTH_LONG).show()
            onResetDone()
        }
    }
    AuthScaffold(stringResource(R.string.forgot_title), onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!showToken) {
                Text(stringResource(R.string.forgot_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Field(s.email, vm::onEmail, stringResource(R.string.email), s.fieldErrors["email"], KeyboardType.Email, imeAction = ImeAction.Go, onDone = vm::requestReset)
                if (s.resetRequested) Text(stringResource(R.string.reset_sent), color = VibeColors.NeonCyan)
                ServerError(s.serverError)
                Spacer(Modifier.height(8.dp))
                PrimaryButton(stringResource(R.string.send_reset_link), s.loading, vm::requestReset)
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = VibeColors.Outline)
                TextButton(onClick = { showToken = true }) { Text(stringResource(R.string.have_reset_token)) }
            } else {
                Field(s.token, vm::onToken, stringResource(R.string.reset_token), s.fieldErrors["token"])
                Field(s.password, vm::onPassword, stringResource(R.string.new_password), s.fieldErrors["password"], password = true, supporting = stringResource(R.string.password_rules), imeAction = ImeAction.Go, onDone = vm::resetPassword)
                ServerError(s.serverError)
                Spacer(Modifier.height(8.dp))
                PrimaryButton(stringResource(R.string.set_new_password), s.loading, vm::resetPassword)
            }
        }
    }
}
