package app.duongondro.ui

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import app.duongondro.ui.theme.Theme

/**
 * Screen-to-screen motion as in a UIKit/SwiftUI NavigationStack, for the app's one NavHost.
 *
 * - **Push**: the new screen slides in from the trailing edge over the full width; the one
 *   beneath moves [PARALLAX] of the way toward the leading edge and darkens slightly.
 * - **Pop** (an Up button, the Back key, the back gesture): the exact reverse. System Back
 *   runs Navigation's predictive pop transitions, which are given the same pop bodies, so on
 *   Android 14+ the gesture scrubs this animation and a cancelled swipe settles back.
 * - **Tabs**: switching between [roots] (the bottom bar) cuts, without sliding, as on iOS.
 * - **Modal** routes (iOS shows them as sheets or full-screen covers) rise from the bottom
 *   over a screen that stays put, and drop back down.
 *
 * Slides go by Start/End, so right-to-left languages are mirrored. With animations removed
 * in the system settings (animator duration scale 0) every change cuts.
 */
object PushMotion {
    const val DURATION_MS = 350

    /** How far the covered screen travels, as a fraction of the width. */
    const val PARALLAX = 0.3f

    /** A fast start that settles gently, close to UIKit's navigation spring. */
    val Easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

    enum class Kind { Push, Pop, Present, Dismiss, Cut }

    /** What a change from [from] to [to] looks like; [pop] says it goes back. */
    fun kind(from: String?, to: String?, pop: Boolean, roots: Set<String>, modals: Set<String>, animate: Boolean = true): Kind = when {
        !animate -> Kind.Cut
        // A tab tapped (from any depth) or Back between tabs.
        to in roots && (!pop || from in roots) -> Kind.Cut
        pop && from in modals -> Kind.Dismiss
        !pop && to in modals -> Kind.Present
        pop -> Kind.Pop
        else -> Kind.Push
    }

    internal fun <T> spec(): FiniteAnimationSpec<T> = tween(DURATION_MS, easing = Easing)
}

/**
 * A NavHost whose destinations, declared with [PushGraph.screen], move as on iOS ([PushMotion]).
 * [roots] are the tabs' start routes; [modals] the routes iOS presents as sheets or covers.
 */
@Composable
fun PushNavHost(
    nav: NavHostController,
    startDestination: String,
    roots: Set<String>,
    modals: Set<String>,
    modifier: Modifier = Modifier,
    builder: PushGraph.() -> Unit,
) {
    val state = remember { PushState() }
    fun AnimatedContentTransitionScope<NavBackStackEntry>.kind(pop: Boolean): PushMotion.Kind {
        val k = PushMotion.kind(initialState.destination.route, targetState.destination.route, pop, roots, modals,
            animate = ValueAnimator.areAnimatorsEnabled())
        // The screen left beneath: the one covered by a push, or uncovered by a pop.
        state.covered = when (k) {
            PushMotion.Kind.Push -> initialState.id
            PushMotion.Kind.Pop -> targetState.id
            else -> null
        }
        return k
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        when (kind(pop = true)) {
            PushMotion.Kind.Pop -> slideIntoContainer(SlideDirection.End, PushMotion.spec()) { (it * PushMotion.PARALLAX).toInt() }
            else -> EnterTransition.None
        }
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        when (kind(pop = true)) {
            PushMotion.Kind.Pop -> slideOutOfContainer(SlideDirection.End, PushMotion.spec())
            PushMotion.Kind.Dismiss -> slideOutVertically(PushMotion.spec<IntOffset>()) { it }
            else -> ExitTransition.None
        }
    }
    NavHost(
        nav, startDestination = startDestination, modifier = modifier,
        enterTransition = {
            when (kind(pop = false)) {
                PushMotion.Kind.Push -> slideIntoContainer(SlideDirection.Start, PushMotion.spec())
                PushMotion.Kind.Present -> slideInVertically(PushMotion.spec<IntOffset>()) { it }
                else -> EnterTransition.None
            }
        },
        exitTransition = {
            when (kind(pop = false)) {
                PushMotion.Kind.Push -> slideOutOfContainer(SlideDirection.Start, PushMotion.spec()) { (it * PushMotion.PARALLAX).toInt() }
                PushMotion.Kind.Present -> ExitTransition.KeepUntilTransitionsFinished
                else -> ExitTransition.None
            }
        },
        popEnterTransition = popEnter,
        popExitTransition = popExit,
        // System Back (key and gesture) goes through these, not popEnter/popExit: without
        // them Navigation fades and shrinks the screen instead.
        predictivePopEnterTransition = { _ -> popEnter() },
        predictivePopExitTransition = { _ -> popExit() },
    ) { PushGraph(this, state).builder() }
}

/** Which back stack entry is the covered one in the transition under way. */
class PushState {
    var covered: String? by mutableStateOf(null)
}

class PushGraph internal constructor(private val graph: NavGraphBuilder, private val state: PushState) {
    /** A destination; its screen gets an opaque ground and the shade it takes while covered. */
    fun screen(route: String, content: @Composable (NavBackStackEntry) -> Unit) {
        graph.composable(route) { entry -> Frame(entry) { content(entry) } }
    }

    @Composable
    private fun AnimatedContentScope.Frame(entry: NavBackStackEntry, content: @Composable () -> Unit) {
        val dim by transition.animateFloat({ PushMotion.spec() }, label = "pushDim") { s ->
            if (s != EnterExitState.Visible && state.covered == entry.id) 1f else 0f
        }
        val shade = Theme.colors.pushDim
        Box(
            Modifier.fillMaxSize().background(Theme.colors.ground)
                .drawWithContent { drawContent(); if (dim > 0f) drawRect(shade, alpha = dim) },
        ) { content() }
    }
}
