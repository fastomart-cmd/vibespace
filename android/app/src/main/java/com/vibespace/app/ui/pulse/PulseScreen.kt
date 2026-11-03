package com.vibespace.app.ui.pulse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vibespace.app.R
import com.vibespace.app.ui.theme.VibeColors

@Composable
fun PulseScreen() {
    Column(
        Modifier.fillMaxSize().background(VibeColors.backgroundGradient).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_pulse), null, tint = VibeColors.NeonCyan, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.pulse_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.pulse_coming_soon), style = MaterialTheme.typography.titleMedium, color = VibeColors.NeonPurple)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.pulse_body), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
