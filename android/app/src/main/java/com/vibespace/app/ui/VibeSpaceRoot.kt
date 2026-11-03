package com.vibespace.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.vibespace.app.R
import com.vibespace.app.data.repo.FeedType
import com.vibespace.app.data.repo.UserListKind
import com.vibespace.app.data.settings.LanguageManager
import com.vibespace.app.ui.auth.ForgotPasswordScreen
import com.vibespace.app.ui.auth.ForgotPasswordViewModel
import com.vibespace.app.ui.auth.LoginScreen
import com.vibespace.app.ui.auth.LoginViewModel
import com.vibespace.app.ui.auth.SignupScreen
import com.vibespace.app.ui.auth.SignupViewModel
import com.vibespace.app.ui.common.appContainer
import com.vibespace.app.ui.common.vibeViewModel
import com.vibespace.app.ui.components.LocalLoginGate
import com.vibespace.app.ui.components.LoginGate
import com.vibespace.app.ui.components.LoginPromptDialog
import com.vibespace.app.ui.create.AndroidImageReader
import com.vibespace.app.ui.create.CreatePostScreen
import com.vibespace.app.ui.create.CreatePostViewModel
import com.vibespace.app.ui.feed.FeedScreen
import com.vibespace.app.ui.feed.FeedViewModel
import com.vibespace.app.ui.post.PostDetailScreen
import com.vibespace.app.ui.post.PostDetailViewModel
import com.vibespace.app.ui.profile.BlockedUsersViewModel
import com.vibespace.app.ui.profile.EditProfileScreen
import com.vibespace.app.ui.profile.EditProfileViewModel
import com.vibespace.app.ui.profile.ProfileScreen
import com.vibespace.app.ui.profile.ProfileViewModel
import com.vibespace.app.ui.profile.UserListScreen
import com.vibespace.app.ui.profile.UserListViewModel
import com.vibespace.app.ui.profile.userListTitle
import com.vibespace.app.ui.pulse.PulseScreen
import com.vibespace.app.ui.settings.SettingsScreen
import com.vibespace.app.ui.settings.SettingsViewModel
import com.vibespace.app.ui.theme.VibeColors
import com.vibespace.app.ui.common.CollectEvents

object Routes {
    const val HOME = "home"
    const val EXPLORE = "explore"
    const val CREATE = "create"
    const val PULSE = "pulse"
    const val ME = "me"
    const val POST = "post/{id}"
    const val USER = "user/{username}"
    const val USER_LIST = "users/{username}/{kind}"
    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val FORGOT = "forgot?token={token}"
    const val SETTINGS = "settings"
    const val BLOCKED = "blocked"
    const val EDIT_PROFILE = "edit-profile"

    fun post(id: String) = "post/$id"
    fun user(username: String) = "user/$username"
    fun userList(username: String, kind: UserListKind) = "users/$username/${kind.name}"
    fun explore(tag: String?) = if (tag == null) EXPLORE else "$EXPLORE?tag=$tag"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector? = null, val drawable: Int? = null)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, icon = Icons.Filled.Home),
    Tab(Routes.EXPLORE, R.string.nav_explore, drawable = R.drawable.ic_explore),
    Tab(Routes.CREATE, R.string.nav_create, icon = Icons.Filled.Add),
    Tab(Routes.PULSE, R.string.nav_pulse, drawable = R.drawable.ic_pulse),
    Tab(Routes.ME, R.string.nav_profile, icon = Icons.Filled.Person),
)

@Composable
fun VibeSpaceRoot(onNavController: (NavHostController) -> Unit) {
    val container = appContainer()
    val nav = rememberNavController()
    LaunchedEffect(nav) { onNavController(nav) }
    val ctx = LocalContext.current
    val loggedIn by container.authRepository.isLoggedIn.collectAsStateWithLifecycle()
    var showLoginPrompt by remember { mutableStateOf(false) }
    val gate = remember { LoginGate(isLoggedIn = container.authRepository::isLoggedInNow, show = { showLoginPrompt = true }) }

    LaunchedEffect(Unit) { container.authRepository.refreshMe() }
    LaunchedEffect(Unit) {
        container.sessionExpired.collect {
            container.authRepository.clearLocalSession()
            Toast.makeText(ctx, ctx.getString(R.string.error_session_expired), Toast.LENGTH_LONG).show()
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = tabs.any { currentRoute?.substringBefore('?') == it.route }

    fun goTab(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    CompositionLocalProvider(LocalLoginGate provides gate) {
        Scaffold(
            containerColor = VibeColors.Black,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar(containerColor = VibeColors.Surface) {
                        tabs.forEach { tab ->
                            val selected = currentRoute?.substringBefore('?') == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (tab.route == Routes.CREATE && !loggedIn) gate.prompt() else goTab(tab.route)
                                },
                                icon = {
                                    if (tab.route == Routes.CREATE) {
                                        Box(Modifier.size(40.dp).clip(CircleShape).background(VibeColors.brandGradient), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.Add, stringResource(tab.label), tint = Color.White)
                                        }
                                    } else if (tab.icon != null) {
                                        Icon(tab.icon, stringResource(tab.label))
                                    } else {
                                        Icon(painterResource(tab.drawable!!), stringResource(tab.label))
                                    }
                                },
                                label = { Text(stringResource(tab.label)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = VibeColors.NeonPurple,
                                    selectedTextColor = VibeColors.NeonPurple,
                                    indicatorColor = VibeColors.SurfaceHigh,
                                    unselectedIconColor = VibeColors.TextSecondary,
                                    unselectedTextColor = VibeColors.TextSecondary,
                                ),
                            )
                        }
                    }
                }
            },
        ) { pad ->
            NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
                val openPost: (String) -> Unit = { nav.navigate(Routes.post(it)) }
                val openUser: (String) -> Unit = { nav.navigate(Routes.user(it)) }
                val openTag: (String) -> Unit = { nav.navigate("${Routes.EXPLORE}?tag=${android.net.Uri.encode(it)}") }

                composable(Routes.HOME) {
                    val vm = vibeViewModel { c -> FeedViewModel(c.postRepository, c.authRepository) }
                    Box(Modifier.statusBarsPadding()) { FeedScreen(vm, openPost, openUser) }
                }
                composable(
                    "${Routes.EXPLORE}?tag={tag}",
                    arguments = listOf(navArgument("tag") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    val tag = entry.arguments?.getString("tag")
                    val vm = vibeViewModel(key = "explore-$tag") { c -> FeedViewModel(c.postRepository, c.authRepository, FeedType.EXPLORE, tag) }
                    Box(Modifier.statusBarsPadding()) { FeedScreen(vm, openPost, openUser, exploreOnly = true) }
                }
                composable(Routes.CREATE) {
                    val vm = vibeViewModel { c -> CreatePostViewModel(c.postRepository, c.mediaRepository, c.authRepository, AndroidImageReader(ctx.applicationContext)) }
                    Box(Modifier.statusBarsPadding()) {
                        CreatePostScreen(
                            vm,
                            onPublished = { p -> nav.navigate(Routes.post(p.id)) { popUpTo(Routes.CREATE) { inclusive = true } } },
                            onLogin = { nav.navigate(Routes.LOGIN) },
                            onSignup = { nav.navigate(Routes.SIGNUP) },
                        )
                    }
                }
                composable(Routes.PULSE) { Box(Modifier.statusBarsPadding()) { PulseScreen() } }
                composable(Routes.ME) {
                    val vm = vibeViewModel(key = "me") { c -> ProfileViewModel(null, c.userRepository, c.postRepository, c.authRepository) }
                    ProfileScreen(
                        vm, isOwnTab = true, onBack = null, onOpenPost = openPost, onOpenUser = openUser, onHashtag = openTag,
                        onOpenList = { u, k -> nav.navigate(Routes.userList(u, k)) },
                        onEditProfile = { nav.navigate(Routes.EDIT_PROFILE) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                        onLogin = { nav.navigate(Routes.LOGIN) },
                        onSignup = { nav.navigate(Routes.SIGNUP) },
                    )
                }
                composable(Routes.POST, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val vm = vibeViewModel(key = "post-$id") { c -> PostDetailViewModel(id, c.postRepository, c.authRepository, c.viewTracker) }
                    PostDetailScreen(vm, onBack = { nav.popBackStack() }, onOpenUser = openUser, onHashtag = openTag)
                }
                composable(
                    Routes.USER,
                    arguments = listOf(navArgument("username") { type = NavType.StringType }),
                    deepLinks = listOf(navDeepLink { uriPattern = "vibespace://u/{username}" }),
                ) { entry ->
                    val username = entry.arguments?.getString("username").orEmpty()
                    val vm = vibeViewModel(key = "user-$username") { c -> ProfileViewModel(username, c.userRepository, c.postRepository, c.authRepository) }
                    ProfileScreen(
                        vm, isOwnTab = false, onBack = { nav.popBackStack() }, onOpenPost = openPost, onOpenUser = openUser, onHashtag = openTag,
                        onOpenList = { u, k -> nav.navigate(Routes.userList(u, k)) },
                        onEditProfile = { nav.navigate(Routes.EDIT_PROFILE) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                        onLogin = { nav.navigate(Routes.LOGIN) },
                        onSignup = { nav.navigate(Routes.SIGNUP) },
                    )
                }
                composable(
                    Routes.USER_LIST,
                    arguments = listOf(navArgument("username") { type = NavType.StringType }, navArgument("kind") { type = NavType.StringType }),
                ) { entry ->
                    val username = entry.arguments?.getString("username").orEmpty()
                    val kind = runCatching { UserListKind.valueOf(entry.arguments?.getString("kind").orEmpty()) }.getOrDefault(UserListKind.VIBEMATES)
                    val vm = vibeViewModel(key = "list-$username-$kind") { c -> UserListViewModel(username, kind, c.userRepository) }
                    UserListScreen("${userListTitle(kind)} · @$username", vm.pager, onBack = { nav.popBackStack() }, onOpenUser = openUser)
                }
                composable(Routes.LOGIN) {
                    val vm = vibeViewModel { c -> LoginViewModel(c.authRepository) }
                    LoginScreen(
                        vm,
                        onDone = { nav.popBackStack() },
                        onClose = { nav.popBackStack() },
                        onSignup = { nav.navigate(Routes.SIGNUP) { popUpTo(Routes.LOGIN) { inclusive = true } } },
                        onForgot = { nav.navigate("forgot") },
                    )
                }
                composable(Routes.SIGNUP) {
                    val vm = vibeViewModel { c -> SignupViewModel(c.authRepository, LanguageManager::current) }
                    SignupScreen(
                        vm,
                        onDone = { nav.popBackStack() },
                        onClose = { nav.popBackStack() },
                        onLogin = { nav.navigate(Routes.LOGIN) { popUpTo(Routes.SIGNUP) { inclusive = true } } },
                    )
                }
                composable(
                    Routes.FORGOT,
                    arguments = listOf(navArgument("token") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    deepLinks = listOf(navDeepLink { uriPattern = "vibespace://reset-password?token={token}" }),
                ) { entry ->
                    val token = entry.arguments?.getString("token")
                    val vm = vibeViewModel(key = "forgot-$token") { c -> ForgotPasswordViewModel(c.authRepository, token) }
                    ForgotPasswordScreen(
                        vm,
                        onClose = { if (!nav.popBackStack()) goTab(Routes.HOME) },
                        onResetDone = { nav.navigate(Routes.LOGIN) { popUpTo(Routes.HOME) } },
                    )
                }
                composable(Routes.SETTINGS) {
                    val vm = vibeViewModel { c -> SettingsViewModel(c.authRepository, LanguageManager::current, LanguageManager::set) }
                    SettingsScreen(
                        vm,
                        onBack = { nav.popBackStack() },
                        onBlocked = { nav.navigate(Routes.BLOCKED) },
                        onLoggedOut = { goTab(Routes.HOME) },
                        apiBaseUrl = container.apiBaseUrl,
                    )
                }
                composable(Routes.BLOCKED) {
                    val vm = vibeViewModel { c -> BlockedUsersViewModel(c.userRepository) }
                    CollectEvents(vm.events)
                    UserListScreen(
                        stringResource(R.string.blocked_users), vm.pager, onBack = { nav.popBackStack() }, onOpenUser = openUser,
                        emptyText = stringResource(R.string.blocked_empty),
                        trailing = { u -> androidx.compose.material3.TextButton(onClick = { vm.unblock(u) }) { Text(stringResource(R.string.action_unblock)) } },
                    )
                }
                composable(Routes.EDIT_PROFILE) {
                    val vm = vibeViewModel { c -> EditProfileViewModel(c.authRepository, c.mediaRepository, AndroidImageReader(ctx.applicationContext)) }
                    EditProfileScreen(vm, onDone = { nav.popBackStack() })
                }
            }
        }

        if (showLoginPrompt) {
            LoginPromptDialog(
                onLogin = { showLoginPrompt = false; nav.navigate(Routes.LOGIN) },
                onSignup = { showLoginPrompt = false; nav.navigate(Routes.SIGNUP) },
                onDismiss = { showLoginPrompt = false },
            )
        }
    }
}
