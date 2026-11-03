package com.vibespace.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vibespace.app.R
import com.vibespace.app.data.api.Profile
import com.vibespace.app.data.repo.UserListKind
import com.vibespace.app.ui.common.CollectEvents
import com.vibespace.app.ui.common.TimeFormat
import com.vibespace.app.ui.components.Avatar
import com.vibespace.app.ui.components.ConfirmDialog
import com.vibespace.app.ui.components.EmptyState
import com.vibespace.app.ui.components.ErrorState
import com.vibespace.app.ui.components.InlineError
import com.vibespace.app.ui.components.LoadingRow
import com.vibespace.app.ui.components.LoadingState
import com.vibespace.app.ui.components.LocalLoginGate
import com.vibespace.app.ui.components.MessageState
import com.vibespace.app.ui.components.ReportDialog
import com.vibespace.app.ui.feed.PostList
import com.vibespace.app.ui.theme.VibeColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    vm: ProfileViewModel,
    isOwnTab: Boolean,
    onBack: (() -> Unit)?,
    onOpenPost: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onHashtag: (String) -> Unit,
    onOpenList: (String, UserListKind) -> Unit,
    onEditProfile: () -> Unit,
    onSettings: () -> Unit,
    onLogin: () -> Unit,
    onSignup: () -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val loggedIn by vm.isLoggedIn.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    val postsState by vm.postsPager.state.collectAsStateWithLifecycle()
    val gate = LocalLoginGate.current
    CollectEvents(vm.events)
    var menu by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }

    // Own tab: reload when login state or my profile changes (e.g. after editing).
    if (isOwnTab) {
        LaunchedEffect(loggedIn, me?.displayName, me?.avatarUrl, me?.bio, me?.isPrivate) { vm.load() }
    }

    Scaffold(
        containerColor = VibeColors.Black,
        topBar = {
            TopAppBar(
                title = { Text(s.profile?.let { "@${it.username}" } ?: stringResource(R.string.nav_profile)) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (isOwnTab || s.isSelf) {
                        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
                    } else if (s.profile != null) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                val blocked = s.profile?.viewer?.isBlocked == true
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (blocked) R.string.action_unblock else R.string.action_block)) },
                                    onClick = { menu = false; if (gate.require()) { if (blocked) vm.toggleBlock() else confirmBlock = true } },
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_report)) }, onClick = { menu = false; if (gate.require()) reporting = true })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = VibeColors.Black),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(VibeColors.backgroundGradient)) {
            val p = s.profile
            when {
                isOwnTab && !loggedIn -> MessageState(
                    title = stringResource(R.string.profile_guest_title),
                    body = stringResource(R.string.profile_guest_body),
                    actionLabel = stringResource(R.string.signup),
                    onAction = onSignup,
                    secondaryLabel = stringResource(R.string.login),
                    onSecondary = onLogin,
                )
                s.loading && p == null -> LoadingState()
                s.error != null && p == null -> ErrorState(s.error!!, onRetry = vm::load)
                p != null -> PostList(
                    state = postsState,
                    actions = vm.actions,
                    onRefresh = vm::load,
                    onLoadMore = vm.postsPager::loadMore,
                    onOpenPost = onOpenPost,
                    onOpenUser = onOpenUser,
                    onHashtag = onHashtag,
                    header = {
                        item(key = "profile-header") {
                            ProfileHeader(
                                p = p,
                                isSelf = s.isSelf || isOwnTab,
                                busy = s.busy,
                                onVibeMate = { if (gate.require()) vm.toggleVibeMate() },
                                onVibeStar = { if (gate.require()) vm.toggleVibeStar() },
                                onEdit = onEditProfile,
                                onOpenList = { kind -> onOpenList(p.username, kind) },
                            )
                        }
                        if (!p.canViewContent) {
                            item(key = "private") {
                                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.Lock, null, tint = VibeColors.NeonPurple, modifier = Modifier.size(40.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text(stringResource(R.string.private_account), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.private_account_body), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    },
                    empty = {
                        Text(
                            stringResource(R.string.profile_posts_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            textAlign = TextAlign.Center,
                        )
                    },
                )
            }
        }
    }
    if (reporting) ReportDialog(onSubmit = { vm.reportUser(it); reporting = false }, onDismiss = { reporting = false })
    if (confirmBlock) {
        val name = s.profile?.username ?: ""
        ConfirmDialog(
            title = stringResource(R.string.blocked_confirm_title, name),
            body = stringResource(R.string.blocked_confirm_body),
            confirmLabel = stringResource(R.string.action_block),
            onConfirm = { confirmBlock = false; vm.toggleBlock() },
            onDismiss = { confirmBlock = false },
        )
    }
}

@Composable
private fun ProfileHeader(
    p: Profile,
    isSelf: Boolean,
    busy: Boolean,
    onVibeMate: () -> Unit,
    onVibeStar: () -> Unit,
    onEdit: () -> Unit,
    onOpenList: (UserListKind) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(p.avatarUrl, p.displayName, size = 96.dp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.displayName, style = MaterialTheme.typography.headlineMedium)
            if (p.isPrivate) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Lock, stringResource(R.string.private_account), tint = VibeColors.NeonPurple, modifier = Modifier.size(18.dp))
            }
        }
        Text("@${p.username}", color = VibeColors.NeonCyan)
        if (p.viewer?.isVibeMateOfYou == true) {
            Text(stringResource(R.string.vibemates_you), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (p.bio.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(p.bio, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(p.counts.posts, stringResource(R.string.posts)) {}
            Stat(p.counts.vibeMates, stringResource(R.string.vibemates)) { if (p.canViewContent) onOpenList(UserListKind.VIBEMATES) }
            Stat(p.counts.vibingWith, stringResource(R.string.vibing_with)) { if (p.canViewContent) onOpenList(UserListKind.VIBING_WITH) }
            Stat(p.counts.vibeStars, stringResource(R.string.vibestars)) { if (p.canViewContent) onOpenList(UserListKind.VIBESTARS) }
        }
        Spacer(Modifier.height(16.dp))
        if (isSelf) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.edit_profile)) }
        } else if (p.viewer?.isBlocked != true) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val mate = p.viewer?.vibeMateStatus ?: "none"
                Button(
                    onClick = onVibeMate,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    colors = if (mate == "none") ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = VibeColors.SurfaceHigh, contentColor = VibeColors.TextPrimary),
                ) {
                    Text(stringResource(when (mate) { "accepted" -> R.string.action_vibemated; "pending" -> R.string.action_requested; else -> R.string.action_vibemate }))
                }
                if (!p.isPrivate) {
                    val star = p.viewer?.isVibeStar == true
                    Button(
                        onClick = onVibeStar,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        colors = if (!star) ButtonDefaults.buttonColors(containerColor = VibeColors.NeonCyan, contentColor = VibeColors.Black)
                        else ButtonDefaults.buttonColors(containerColor = VibeColors.SurfaceHigh, contentColor = VibeColors.TextPrimary),
                    ) { Text(stringResource(if (star) R.string.action_vibestarred else R.string.action_vibestar)) }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(TimeFormat.compact(value), style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun userListTitle(kind: UserListKind): String = stringResource(
    when (kind) {
        UserListKind.VIBEMATES -> R.string.vibemates
        UserListKind.VIBING_WITH -> R.string.vibing_with
        UserListKind.VIBESTARS -> R.string.vibestars
    },
)

/** A list of accounts (VibeMates, Vibing with, VibeStars, blocked users). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserListScreen(
    title: String,
    pager: com.vibespace.app.ui.common.Paged<com.vibespace.app.data.api.UserSummary>,
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit,
    emptyText: String = stringResource(R.string.user_list_empty),
    trailing: (@Composable (com.vibespace.app.data.api.UserSummary) -> Unit)? = null,
) {
    val st by pager.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = VibeColors.Black,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = VibeColors.Black),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                st.isLoading && st.items.isEmpty() -> LoadingState()
                st.error != null && st.items.isEmpty() -> ErrorState(st.error!!, onRetry = pager::refresh)
                st.isEmpty -> EmptyState(title, emptyText)
                else -> LazyColumn {
                    items(st.items, key = { it.id }) { u ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenUser(u.username) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(u.avatarUrl, u.displayName, size = 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u.displayName, style = MaterialTheme.typography.titleSmall)
                                Text("@${u.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            trailing?.invoke(u)
                        }
                    }
                    if (st.nextCursor != null) item { LaunchedEffect(st.items.size) { pager.loadMore() }; LoadingRow() }
                    st.loadMoreError?.let { e -> item { InlineError(e, pager::loadMore) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(vm: EditProfileViewModel, onDone: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    CollectEvents(vm.events)
    LaunchedEffect(vm) { vm.saved.collect { onDone() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.onAvatarPicked(uri.toString()) }
    Scaffold(
        containerColor = VibeColors.Black,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_profile)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = { TextButton(onClick = vm::save, enabled = !s.saving && s.displayName.isNotBlank()) { Text(stringResource(R.string.save)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = VibeColors.Black),
            )
        },
    ) { pad ->
        if (!s.loaded) { LoadingState(Modifier.padding(pad)); return@Scaffold }
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.newAvatarUri != null) {
                AsyncImage(
                    model = s.newAvatarUri,
                    contentDescription = stringResource(R.string.avatar),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).clip(androidx.compose.foundation.shape.CircleShape),
                )
            } else {
                Avatar(s.avatarUrl, s.displayName, size = 96.dp)
            }
            Row {
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(stringResource(R.string.change_avatar)) }
                if (s.avatarUrl != null || s.newAvatarUri != null) TextButton(onClick = vm::onRemoveAvatar) { Text(stringResource(R.string.remove_avatar)) }
            }
            OutlinedTextField(s.displayName, vm::onName, label = { Text(stringResource(R.string.display_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(s.bio, vm::onBio, label = { Text(stringResource(R.string.bio)) }, minLines = 3, modifier = Modifier.fillMaxWidth(), supportingText = { Text("${s.bio.length}/300") })
            if (s.saving) LoadingRow()
        }
    }
}
