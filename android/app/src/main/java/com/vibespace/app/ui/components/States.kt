package com.vibespace.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vibespace.app.R
import com.vibespace.app.data.api.AppError
import com.vibespace.app.ui.common.userMessage
import com.vibespace.app.ui.theme.BrandWordmark
import com.vibespace.app.ui.theme.VibeColors

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun ErrorState(error: AppError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    MessageState(
        title = stringResource(R.string.error_generic),
        body = error.userMessage(),
        actionLabel = stringResource(R.string.retry),
        onAction = onRetry,
        modifier = modifier,
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    MessageState(title, body, actionLabel, onAction, modifier)
}

@Composable
fun MessageState(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.ic_vibespace_logo), contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}

@Composable
fun InlineError(error: AppError, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(error.userMessage(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

/** Logo mark + "VibeSpace" wordmark. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, showTagline: Boolean = false) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_vibespace_logo), contentDescription = stringResource(R.string.logo), modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.app_name), style = BrandWordmark, color = VibeColors.TextPrimary)
        }
        if (showTagline) {
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.labelMedium, color = VibeColors.NeonCyan, modifier = Modifier.padding(start = 38.dp))
        }
    }
}
