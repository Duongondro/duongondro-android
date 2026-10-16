package app.duongondro

import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import app.duongondro.data.Exports
import app.duongondro.ui.theme.Theme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.duongondro.account.Crockford
import app.duongondro.reminders.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.duongondro.ui.PushNavHost
import androidx.navigation.compose.rememberNavController
import app.duongondro.model.AppModel
import app.duongondro.model.SqliteStore
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.plus
import androidx.compose.material3.ScaffoldDefaults
import app.duongondro.ui.onboarding.OnboardingScreen
import app.duongondro.ui.onboarding.RecoveryCodeScreen
import app.duongondro.ui.onboarding.Step
import app.duongondro.ui.practice.AfterMidnightDialog
import app.duongondro.ui.practice.PracticeScreen
import app.duongondro.ui.settings.ArchivedScreen
import app.duongondro.ui.settings.PracticeSettingsScreen
import app.duongondro.ui.settings.AboutScreen
import app.duongondro.ui.settings.PasskeysScreen
import app.duongondro.ui.settings.PracticeListScreen
import app.duongondro.ui.settings.DeleteEverythingScreen
import app.duongondro.ui.settings.AcceptInviteDialog
import app.duongondro.ui.settings.InviteScreen
import app.duongondro.ui.settings.LicencesScreen
import app.duongondro.ui.settings.SettingsScreen
import app.duongondro.ui.settings.YourDataScreen
import app.duongondro.ui.theme.DuongondroTheme
import app.duongondro.ui.today.TodayScreen
import app.duongondro.ui.friends.FriendDetailScreen
import app.duongondro.ui.friends.FriendsScreen

class MainActivity : AppCompatActivity() {
    private val model: AppModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppModel(SqliteStore(applicationContext), context = applicationContext) as T
        }
    }

    // A new day or a changed clock or zone re-renders "today" and the streaks.
    private val timeChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) = model.tick()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DuongondroTheme { App(model) }
        }
        // Recreated (rotation, a language switch, process death): the link was handled already.
        if (savedInstanceState == null) {
            handleLink(intent)
            // The last export, kept for the app it was shared to, which may read it late.
            lifecycleScope.launch(Dispatchers.IO) { Exports.folder(applicationContext).deleteRecursively() }
        }
        // Reminders follow the data: a session logged today cancels today's.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.snapshot.collect { Reminders.reschedule(this@MainActivity, it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /**
     * Invitation links, https://duongondro.app/i/<8-character id>#<16-character
     * secret> (upper case from a QR code), and magic links,
     * https://duongondro.app/m#<token>. The secret and the token travel in the
     * fragment, which never reaches a server.
     */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.data ?: return
        // Used once: the intent outlives this call, and the token must not be read again.
        intent.data = null
        setIntent(intent)
        if (uri.host?.lowercase() != "duongondro.app") return
        val segments = uri.pathSegments
        val fragment = uri.fragment ?: return
        when (segments.firstOrNull()?.lowercase()) {
            "i" -> {
                val id = segments.getOrNull(1) ?: return
                Crockford.normalise(id + fragment, Crockford.INVITE_LENGTH).takeIf { it.length == Crockford.INVITE_LENGTH }
                    ?.let(model::openedInvite)
            }
            "m" -> fragment.takeIf { it.isNotBlank() && it.length <= 128 }?.let(model::openedMagicLink)
        }
    }

    override fun onStart() {
        super.onStart()
        model.resumed()
        Reminders.reschedule(this, model.snapshot.value)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, timeChanged, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Leaving the app closes the undo window: its pending session becomes final and syncs. */
    override fun onStop() {
        unregisterReceiver(timeChanged)
        // Rotation or a language switch recreates the activity; the ViewModel
        // keeps the undo window alive through it, so only finalise when leaving.
        if (!isChangingConfigurations) model.finalisePending()
        super.onStop()
    }
}

private object Routes {
    const val TODAY = "today"
    const val SETTINGS = "settings"
    const val PRACTICE = "practice/{id}"
    const val PRACTICES = "settings/practices"
    const val PRACTICE_SETTINGS = "settings/practice/{id}"
    const val ARCHIVED = "settings/archived"
    const val YOUR_DATA = "settings/data"
    const val DELETE = "settings/data/delete"
    const val ABOUT = "settings/about"
    const val LICENCES = "settings/licences"
    const val SIGN_IN = "settings/account/sign-in"
    const val NEW_ACCOUNT = "settings/account/new"
    const val RECOVERY_CODE = "settings/account/recovery-code"
    const val INVITE = "settings/invite"
    const val PASSKEYS = "settings/passkeys"
    const val FRIENDS = "friends"
    const val FRIEND = "friends/friend/{id}"
    const val FRIENDS_INVITE = "friends/invite"
    /** The bottom bar's tabs: switching between them never slides. */
    val TABS = setOf(TODAY, FRIENDS, SETTINGS)
    /** What iOS presents as a sheet or full-screen cover: rises from the bottom instead. */
    val MODALS = setOf(SIGN_IN, NEW_ACCOUNT, RECOVERY_CODE, LICENCES)
    /**
     * Full-screen routes without the bottom bar: the practice screen (the +mala button owns
     * the bottom edge), the account steps, and whatever iOS presents as a sheet or cover.
     */
    fun hidesBar(route: String?) = route == PRACTICE || route?.startsWith("settings/account/") == true || route in MODALS
    fun friend(id: java.util.UUID) = "friends/friend/$id"
    fun practice(id: String) = "practice/$id"
    fun practiceSettings(id: String) = "settings/practice/$id"
}

@Composable
fun App(model: AppModel) {
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    StorageErrorDialog(model)
    // An account made from Settings whose optional steps were cut short (the app
    // closed) resumes them at the name, as the first run does.
    var resumeSignUp by remember(loaded) { mutableStateOf(loaded && snapshot.preferences.onboarded && model.accounts?.signUpUnfinished == true) }
    when {
        !loaded -> Unit
        !snapshot.preferences.onboarded -> OnboardingScreen(model)
        resumeSignUp -> OnboardingScreen(model, start = Step.Name) { resumeSignUp = false }
        else -> Home(model)
    }
}

@Composable
private fun Home(model: AppModel) {
    val nav = rememberNavController()
    val prompt by model.afterMidnight.collectAsStateWithLifecycle()
    // The NavHost fills the window at one size; each screen brings its own bar and insets.
    PushNavHost(nav, startDestination = Routes.TODAY, roots = Routes.TABS, modals = Routes.MODALS,
        modifier = Modifier.fillMaxSize().background(Theme.colors.ground),
        chrome = { route, content -> ScreenChrome(nav, route, content) }) {
        screen(Routes.TODAY) { TodayScreen(model) { nav.navigate(Routes.practice(it)) } }
        screen(Routes.SETTINGS) {
            SettingsScreen(model, openPractices = { nav.navigate(Routes.PRACTICES) },
                openYourData = { nav.navigate(Routes.YOUR_DATA) }, openAbout = { nav.navigate(Routes.ABOUT) },
                openSignIn = { nav.navigate(Routes.SIGN_IN) }, openNewAccount = { nav.navigate(Routes.NEW_ACCOUNT) },
                openRecoveryCode = { nav.navigate(Routes.RECOVERY_CODE) }, openInvite = { nav.navigate(Routes.INVITE) },
                openPasskeys = { nav.navigate(Routes.PASSKEYS) })
        }
        screen(Routes.FRIENDS) {
            FriendsScreen(model, openFriend = { nav.navigate(Routes.friend(it)) }, openInvite = { nav.navigate(Routes.FRIENDS_INVITE) },
                openNewAccount = { nav.navigate(Routes.NEW_ACCOUNT) }, openSignIn = { nav.navigate(Routes.SIGN_IN) },
                openRecoveryCode = { nav.navigate(Routes.RECOVERY_CODE) })
        }
        screen(Routes.FRIEND) { entry ->
            FriendDetailScreen(model, entry.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
        }
        screen(Routes.FRIENDS_INVITE) { model.accounts?.let { InviteScreen(it) { nav.popBackStack() } } }
        screen(Routes.PRACTICES) {
            PracticeListScreen(model, openPractice = { nav.navigate(Routes.practiceSettings(it)) },
                openArchived = { nav.navigate(Routes.ARCHIVED) }) { nav.popBackStack() }
        }
        screen(Routes.PRACTICE_SETTINGS) { entry ->
            PracticeSettingsScreen(model, entry.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
        }
        screen(Routes.YOUR_DATA) { YourDataScreen(model, openDelete = { nav.navigate(Routes.DELETE) }) { nav.popBackStack() } }
        screen(Routes.DELETE) { DeleteEverythingScreen(model) { nav.popBackStack() } }
        screen(Routes.ABOUT) { AboutScreen(openLicences = { nav.navigate(Routes.LICENCES) }) { nav.popBackStack() } }
        screen(Routes.LICENCES) { LicencesScreen { nav.popBackStack() } }
        screen(Routes.SIGN_IN) { OnboardingScreen(model, start = Step.SignIn) { nav.popBackStack() } }
        screen(Routes.NEW_ACCOUNT) { OnboardingScreen(model, start = Step.Consent) { nav.popBackStack() } }
        screen(Routes.INVITE) { model.accounts?.let { InviteScreen(it) { nav.popBackStack() } } }
        screen(Routes.PASSKEYS) { model.accounts?.let { PasskeysScreen(it) { nav.popBackStack() } } }
        screen(Routes.RECOVERY_CODE) { model.accounts?.let { RecoveryCodeScreen(it) { nav.popBackStack() } } }
        screen(Routes.ARCHIVED) {
            ArchivedScreen(model, openPractice = { nav.navigate(Routes.practiceSettings(it)) }) { nav.popBackStack() }
        }
        screen(Routes.PRACTICE) { entry ->
            PracticeScreen(model, entry.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
        }
    }
    prompt?.let { AfterMidnightDialog(it, model) }
    model.accounts?.let { AcceptInviteDialog(model, it) }
}

/**
 * A screen's bar and insets. The bar belongs to each tab screen rather than to the window, so
 * a push slides a screen without it over one with it, as on iOS, and nothing changes size
 * when the back stack does.
 */
@Composable
private fun ScreenChrome(nav: NavHostController, route: String?, content: @Composable () -> Unit) {
    // The practice screen runs its cover under the status bar, so it takes the top inset itself.
    val insets = if (route == Routes.PRACTICE) {
        ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    } else ScaffoldDefaults.contentWindowInsets
    Scaffold(containerColor = Theme.colors.ground, contentWindowInsets = insets,
        bottomBar = { if (!Routes.hidesBar(route)) BottomBar(nav, route) }) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) { content() }
    }
}

/** Says plainly when something could not be saved, instead of losing it quietly. */
@Composable
private fun StorageErrorDialog(model: AppModel) {
    val error by model.storageError.collectAsStateWithLifecycle()
    error?.let {
        AlertDialog(
            onDismissRequest = { model.dismissStorageError() },
            title = { Text(stringResource(R.string.storage_error)) },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { model.dismissStorageError() }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String?) {
    NavigationBar(containerColor = Theme.colors.card, contentColor = Theme.colors.muted) {
        NavigationBarItem(
            selected = route == Routes.TODAY,
            onClick = { nav.navigate(Routes.TODAY) { popUpTo(Routes.TODAY) { inclusive = true }; launchSingleTop = true } },
            icon = { Icon(painterResource(R.drawable.ic_today), contentDescription = null) },
            label = { Text(stringResource(R.string.tab_today)) },
            colors = navColors(),
        )
        NavigationBarItem(
            selected = route?.startsWith("friends") == true,
            onClick = { nav.navigate(Routes.FRIENDS) { popUpTo(Routes.TODAY); launchSingleTop = true } },
            icon = { Icon(painterResource(R.drawable.ic_friends), contentDescription = null) },
            label = { Text(stringResource(R.string.tab_friends)) },
            colors = navColors(),
        )
        NavigationBarItem(
            selected = route?.startsWith("settings") == true,
            onClick = { nav.navigate(Routes.SETTINGS) { popUpTo(Routes.TODAY); launchSingleTop = true } },
            icon = { Icon(painterResource(R.drawable.ic_you), contentDescription = null) },
            label = { Text(stringResource(R.string.tab_settings)) },
            colors = navColors(),
        )
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Theme.colors.accent,
    selectedTextColor = Theme.colors.accent,
    indicatorColor = Theme.colors.streakCard,
    unselectedIconColor = Theme.colors.muted,
    unselectedTextColor = Theme.colors.muted,
)
