package app.duongondro

import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.duongondro.core.Catalogue
import app.duongondro.core.TrackedPractice
import app.duongondro.model.AppModel
import app.duongondro.model.InMemoryStore
import app.duongondro.model.Preferences
import app.duongondro.model.Snapshot
import app.duongondro.ui.practice.AfterMidnightDialog
import app.duongondro.ui.practice.PracticeScreen
import app.duongondro.ui.settings.SettingsScreen
import app.duongondro.ui.theme.DuongondroTheme
import app.duongondro.ui.today.TodayScreen

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppModel(starterStore()) as T
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
    }

    override fun onStart() {
        super.onStart()
        model.tick()
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
        model.commitPending()
        super.onStop()
    }
}

/** Stand-in until Room storage and onboarding land: Dorje Sempa and Chenrezig in memory. */
private fun starterStore(): InMemoryStore {
    val pick = { id: String -> Catalogue.builtIn.first { it.id == id } }
    return InMemoryStore(
        Snapshot(
            practices = listOf(
                TrackedPractice(pick("dorje-sempa"), sortOrder = 0),
                TrackedPractice(pick("chenrezig"), wantsStreakOnly = true, sortOrder = 1),
            ),
            preferences = Preferences(onboarded = true, finishedShortRefuge = true),
        )
    )
}

private object Routes {
    const val TODAY = "today"
    const val SETTINGS = "settings"
    const val PRACTICE = "practice/{id}"
    fun practice(id: String) = "practice/$id"
}

@Composable
fun App(model: AppModel) {
    val nav = rememberNavController()
    val prompt by model.afterMidnight.collectAsStateWithLifecycle()
    Scaffold(bottomBar = { BottomBar(nav) }) { padding ->
        NavHost(nav, startDestination = Routes.TODAY, modifier = Modifier.padding(padding)) {
            composable(Routes.TODAY) { TodayScreen(model) { nav.navigate(Routes.practice(it)) } }
            composable(Routes.SETTINGS) { SettingsScreen(model) }
            composable(Routes.PRACTICE) { entry ->
                PracticeScreen(model, entry.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
            }
        }
    }
    prompt?.let { AfterMidnightDialog(it, model) }
}

@Composable
private fun BottomBar(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    NavigationBar {
        NavigationBarItem(
            selected = route == Routes.TODAY || route == Routes.PRACTICE,
            onClick = { nav.navigate(Routes.TODAY) { popUpTo(Routes.TODAY) { inclusive = true }; launchSingleTop = true } },
            icon = { Icon(painterResource(R.drawable.ic_flame), contentDescription = null) },
            label = { Text(stringResource(R.string.tab_today)) },
            colors = navColors(),
        )
        NavigationBarItem(
            selected = route == Routes.SETTINGS,
            onClick = { nav.navigate(Routes.SETTINGS) { popUpTo(Routes.TODAY); launchSingleTop = true } },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
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
