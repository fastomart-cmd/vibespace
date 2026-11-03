package com.vibespace.app.ui.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vibespace.app.R
import com.vibespace.app.data.api.Post
import com.vibespace.app.ui.common.CollectEvents
import com.vibespace.app.ui.common.Validators
import com.vibespace.app.ui.components.LocalLoginGate
import com.vibespace.app.ui.components.MessageState
import com.vibespace.app.ui.theme.VibeColors

@Composable
fun CreatePostScreen(vm: CreatePostViewModel, onPublished: (Post) -> Unit, onLogin: () -> Unit, onSignup: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val loggedIn by vm.isLoggedIn.collectAsStateWithLifecycle()
    val gate = LocalLoginGate.current
    CollectEvents(vm.events)
    LaunchedEffect(vm) { vm.published.collect { onPublished(it) } }

    if (!loggedIn) {
        Box(Modifier.fillMaxSize().background(VibeColors.backgroundGradient)) {
            MessageState(
                title = stringResource(R.string.create_guest_title),
                body = stringResource(R.string.create_guest_body),
                actionLabel = stringResource(R.string.signup),
                onAction = onSignup,
                secondaryLabel = stringResource(R.string.login),
                onSecondary = onLogin,
            )
        }
        return
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.onImagePicked(uri.toString()) }

    Column(Modifier.fillMaxSize().background(VibeColors.backgroundGradient).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.create_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.size(16.dp))
        OutlinedTextField(
            value = s.caption,
            onValueChange = vm::onCaption,
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            placeholder = { Text(stringResource(R.string.create_hint)) },
            enabled = !s.busy,
            isError = s.validation != null,
            supportingText = {
                Row(Modifier.fillMaxWidth()) {
                    s.validation?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
                    Text("${s.caption.length}/${Validators.MAX_CAPTION}")
                }
            },
            shape = RoundedCornerShape(16.dp),
        )
        s.imageUri?.let { uri ->
            Box(Modifier.padding(top = 8.dp)) {
                AsyncImage(
                    model = uri,
                    contentDescription = stringResource(R.string.post_image),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(16.dp)),
                )
                IconButton(
                    onClick = vm::removeImage,
                    enabled = !s.busy,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(VibeColors.Black.copy(alpha = 0.6f), CircleShape),
                ) { Icon(Icons.Filled.Close, stringResource(R.string.remove_image)) }
            }
        }
        Spacer(Modifier.size(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !s.busy,
            ) {
                Icon(painterResource(R.drawable.ic_image), null, modifier = Modifier.size(18.dp), tint = VibeColors.NeonCyan)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_image))
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (gate.require()) vm.publish() }, enabled = s.canPublish) {
                if (s.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        when (s.stage) {
                            CreateStage.UPLOADING -> R.string.uploading_image
                            CreateStage.PUBLISHING -> R.string.publishing
                            CreateStage.EDITING -> R.string.publish
                        },
                    ),
                )
            }
        }
    }
}
