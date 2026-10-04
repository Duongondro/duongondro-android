package app.duongondro.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.duongondro.core.Catalogue
import app.duongondro.core.TrackedPractice
import app.duongondro.model.AppModel
import app.duongondro.model.Preferences

/** Stand-in until onboarding lands: local mode with Dorje Sempa. */
@Composable
fun OnboardingScreen(model: AppModel) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = {
            model.perform {
                model.store.completeOnboarding(
                    listOf(TrackedPractice(Catalogue.builtIn.first { it.id == "dorje-sempa" })),
                    emptyList(),
                    Preferences(onboarded = true, finishedShortRefuge = true),
                )
            }
        }) { Text("Just me, on this phone") }
    }
}
