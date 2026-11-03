package com.vibespace.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibespace.app.R
import com.vibespace.app.data.repo.PostRepository

/**
 * Guest-first login gate. Screens call [LoginGate.require] before member-only actions
 * (post, VibeTalk, VibeClap, save, VibeMate, VibeStar, report). Returns true when logged in;
 * otherwise shows the login / sign-up prompt and returns false.
 */
class LoginGate(private val isLoggedIn: () -> Boolean, private val show: () -> Unit) {
    fun require(): Boolean = if (isLoggedIn()) true else { show(); false }
    fun prompt() = show()
}

val LocalLoginGate = compositionLocalOf { LoginGate({ false }, {}) }

@Composable
fun LoginPromptDialog(onLogin: () -> Unit, onSignup: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Image(painterResource(R.drawable.ic_vibespace_logo), null, modifier = Modifier.size(48.dp)) },
        title = { Text(stringResource(R.string.login_prompt_title)) },
        text = { Text(stringResource(R.string.login_prompt_body)) },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(onClick = onSignup, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.signup)) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.login)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.continue_as_guest)) }
            }
        },
    )
}

@Composable
fun reasonLabel(reason: String): String = stringResource(
    when (reason) {
        "spam" -> R.string.reason_spam
        "harassment" -> R.string.reason_harassment
        "hate" -> R.string.reason_hate
        "nudity" -> R.string.reason_nudity
        "violence" -> R.string.reason_violence
        "misinformation" -> R.string.reason_misinformation
        "impersonation" -> R.string.reason_impersonation
        else -> R.string.reason_other
    },
)

@Composable
fun ReportDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            Column {
                Text(stringResource(R.string.report_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                PostRepository.REPORT_REASONS.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { selected = r }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == r, onClick = { selected = r })
                        Text(reasonLabel(r))
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = selected != null, onClick = { selected?.let(onSubmit) }) { Text(stringResource(R.string.action_report)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
