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
import app.duongondro.ui.theme.Theme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.duongondro.model.AppModel
import app.duongondro.model.SqliteStore
import app.duongondro.ui.onboarding.OnboardingScreen
import app.duongondro.ui.practice.AfterMidnightDialog
import app.duongondro.ui.practice.PracticeScreen
import app.duongondro.ui.settings.ArchivedScreen
import app.duongondro.ui.settings.PracticeSettingsScreen
import app.duongondro.ui.settings.AboutScreen
import app.duongondro.ui.settings.PracticeListScreen
import app.duongondro.ui.settings.DeleteEverythingScreen
import app.duongondro.ui.settings.LicencesScreen
import app.duongondro.ui.settings.SettingsScreen
import app.duongondro.ui.settings.YourDataScreen
import app.duongondro.ui.theme.DuongondroTheme
import app.duongondro.ui.today.TodayScreen

class MainActivity : AppCompatActivity() {
    private val model: AppModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppModel(SqliteStore(applicationContext)) as T
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
        handleLink(intent)
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

    /** https://duongondro.app/invite/<24 characters> and https://duongondro.app/signin/<8 characters>. */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val kind = uri.pathSegments.firstOrNull()
        val last = uri.lastPathSegment ?: uri.getQueryParameter("code") ?: return
        when (kind) {
            "invite" -> Crockford.normalise(last, Crockford.INVITE_LENGTH).takeIf { it.length == Crockford.INVITE_LENGTH }?.let(model::openedInvite)
            "signin" -> Crockford.normalise(last, Crockford.SIGN_IN_LENGTH).takeIf { it.length == Crockford.SIGN_IN_LENGTH }?.let(model::openedSignIn)
        }
    }

    override fun onStart() {
        super.onStart()
        model.tick()
        Reminders.reschedule(this, model.snapshot.value)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, timeChanged, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** A session in its undo window is written before the process can be killed. */
    override fun onStop() {
        unregisterReceiver(timeChanged)
        // Rotation or a language switch recreates the activity; the ViewModel
        // keeps the undo window alive through it, so only commit when leaving.
        if (!isChangingConfigurations) model.commitPending()
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
    fun practice(id: String) = "practice/$id"
    fun practiceSettings(id: String) = "settings/practice/$id"
}

@Composable
fun App(model: AppModel) {
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    StorageErrorDialog(model)
    when {
        !loaded -> Unit
        !snapshot.preferences.onboarded -> OnboardingScreen(model)
        else -> Home(model)
    }
}

@Composable
private fun Home(model: AppModel) {
    val nav = rememberNavController()
    val prompt by model.afterMidnight.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    // The practice screen is full screen: the +mala button owns the bottom edge.
    val onPractice = entry?.destination?.route == Routes.PRACTICE
    Scaffold(containerColor = Theme.colors.ground, bottomBar = { if (!onPractice) BottomBar(nav) }) { padding ->
        NavHost(nav, startDestination = Routes.TODAY, modifier = Modifier.padding(padding)) {
            composable(Routes.TODAY) { TodayScreen(model) { nav.navigate(Routes.practice(it)) } }
            composable(Routes.SETTINGS) {
                SettingsScreen(model, openPractices = { nav.navigate(Routes.PRACTICES) },
                    openYourData = { nav.navigate(Routes.YOUR_DATA) }, openAbout = { nav.navigate(Routes.ABOUT) })
            }
            composable(Routes.PRACTICES) {
                PracticeListScreen(model, openPractice = { nav.navigate(Routes.practiceSettings(it)) },
                    openArchived = { nav.navigate(Routes.ARCHIVED) }) { nav.popBackStack() }
            }
            composable(Routes.PRACTICE_SETTINGS) { entry ->
                PracticeSettingsScreen(model, entry.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
            }
            composable(Routes.YOUR_DATA) { YourDataScreen(model, openDelete = { nav.navigate(Routes.DELETE) }) { nav.popBackStack() } }
            composable(Routes.DELETE) { DeleteEverythingScreen(model) { nav.popBackStack() } }
            composable(Routes.ABOUT) { AboutScreen(openLicences = { nav.navigate(Routes.LICENCES) }) { nav.popBackStack() } }
            composable(Routes.LICENCES) { LicencesScreen { nav.popBackStack() } }
            composable(Routes.ARCHIVED) {
                ArchivedScreen(model, openPractice = { nav.navigate(Routes.practiceSettings(it)) }) { nav.popBackStack() }
            }
            composable(Routes.PRACTICE) { entry ->
                PracticeScreen(model, entry.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
            }
        }
    }
    prompt?.let { AfterMidnightDialog(it, model) }
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
private fun BottomBar(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    NavigationBar(containerColor = Theme.colors.card, contentColor = Theme.colors.muted) {
        NavigationBarItem(
            selected = route == Routes.TODAY,
            onClick = { nav.navigate(Routes.TODAY) { popUpTo(Routes.TODAY) { inclusive = true }; launchSingleTop = true } },
            icon = { Icon(painterResource(R.drawable.ic_today), contentDescription = null) },
            label = { Text(stringResource(R.string.tab_today)) },
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
