package com.vibespace.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibespace.app.BuildConfig
import com.vibespace.app.R
import com.vibespace.app.data.api.AppError
import com.vibespace.app.data.repo.AuthRepository
import com.vibespace.app.ui.common.CollectEvents
import com.vibespace.app.ui.common.UiEvent
import com.vibespace.app.ui.components.ConfirmDialog
import com.vibespace.app.ui.theme.VibeColors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val auth: AuthRepository,
    private val getLanguage: () -> String,
    private val setLanguage: (String) -> Unit,
) : ViewModel() {
    val me = auth.me
    val isLoggedIn = auth.isLoggedIn
    private val _language = MutableStateFlow(getLanguage())
    val language: StateFlow<String> = _language.asStateFlow()
    private val _savingPrivacy = MutableStateFlow(false)
    val savingPrivacy: StateFlow<Boolean> = _savingPrivacy.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init { viewModelScope.launch { if (auth.isLoggedInNow() && auth.me.value == null) auth.refreshMe() } }

    fun chooseLanguage(lang: String) {
        if (lang == _language.value) return
        _language.value = lang
        viewModelScope.launch {
            // Keep the account's language (used for emails) in sync, then switch the UI (recreates the activity).
            if (auth.isLoggedInNow()) auth.updateProfile(language = lang)
            setLanguage(lang)
        }
    }

    fun setPrivate(value: Boolean) {
        if (_savingPrivacy.value) return
        _savingPrivacy.value = true
        viewModelScope.launch {
            auth.updateProfile(isPrivate = value).onFailure { _events.tryEmit(UiEvent.Error(it as? AppError ?: AppError.Unknown(it))) }
            _savingPrivacy.value = false
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch { auth.logout(); onDone() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, onBlocked: () -> Unit, onLoggedOut: () -> Unit, apiBaseUrl: String) {
    val me by vm.me.collectAsStateWithLifecycle()
    val loggedIn by vm.isLoggedIn.collectAsStateWithLifecycle()
    val lang by vm.language.collectAsStateWithLifecycle()
    val saving by vm.savingPrivacy.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    CollectEvents(vm.events)

    Scaffold(
        containerColor = VibeColors.Black,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = VibeColors.Black),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.language))
            listOf("en" to R.string.language_english, "hi" to R.string.language_hindi).forEach { (code, label) ->
                Row(Modifier.fillMaxWidth().clickable { vm.chooseLanguage(code) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = lang == code, onClick = { vm.chooseLanguage(code) })
                    Text(stringResource(label))
                }
            }
            if (loggedIn) {
                HorizontalDivider(color = VibeColors.Outline, modifier = Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.privacy_private), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.privacy_private_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = me?.isPrivate == true, onCheckedChange = vm::setPrivate, enabled = me != null && !saving)
                }
                Row(Modifier.fillMaxWidth().clickable(onClick = onBlocked).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.blocked_users), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
                HorizontalDivider(color = VibeColors.Outline, modifier = Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text(stringResource(R.string.logout)) }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "${stringResource(R.string.app_name)} · ${stringResource(R.string.version, BuildConfig.VERSION_NAME)}\n${stringResource(R.string.server)}: $apiBaseUrl\n🇮🇳 ${stringResource(R.string.made_in_india)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
    if (confirmLogout) {
        ConfirmDialog(
            title = stringResource(R.string.logout),
            body = stringResource(R.string.logout_confirm),
            confirmLabel = stringResource(R.string.logout),
            onConfirm = { confirmLogout = false; vm.logout(onLoggedOut) },
            onDismiss = { confirmLogout = false },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = VibeColors.NeonCyan, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}
