package app.duongondro.ui

import app.duongondro.ui.PushMotion.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which screen changes slide, rise or cut, as on iOS. */
class PushMotionTest {
    private val roots = setOf("today", "friends", "settings")
    private val modals = setOf("settings/account/new", "settings/licences")
    private fun kind(from: String, to: String, pop: Boolean, animate: Boolean = true) =
        PushMotion.kind(from, to, pop, roots, modals, animate)

    @Test fun pushAndPopSlide() {
        assertEquals(Kind.Push, kind("today", "practice/{id}", pop = false))
        assertEquals(Kind.Pop, kind("practice/{id}", "today", pop = true))
        assertEquals(Kind.Push, kind("settings/data", "settings/data/delete", pop = false))
        assertEquals(Kind.Pop, kind("settings/data/delete", "settings/data", pop = true))
    }

    @Test fun tabsCut() {
        assertEquals(Kind.Cut, kind("today", "friends", pop = false))
        assertEquals(Kind.Cut, kind("friends", "today", pop = true))
        // A tab tapped from deep inside another tab.
        assertEquals(Kind.Cut, kind("settings/practices", "friends", pop = false))
    }

    @Test fun modalsRiseAndDrop() {
        assertEquals(Kind.Present, kind("settings", "settings/account/new", pop = false))
        assertEquals(Kind.Dismiss, kind("settings/account/new", "settings", pop = true))
        assertEquals(Kind.Present, kind("settings/about", "settings/licences", pop = false))
    }

    @Test fun noMotionWhenAnimationsAreOff() {
        assertEquals(Kind.Cut, kind("today", "practice/{id}", pop = false, animate = false))
        assertEquals(Kind.Cut, kind("settings/account/new", "settings", pop = true, animate = false))
    }

    private fun e(id: String, route: String) = PushMotion.Entry(id, route)
    private val today = e("1", "today")
    private val practice = e("2", "practice/{id}")
    private val friends = e("3", "friends")
    private val newAccount = e("4", "settings/account/new")
    private val settings = e("5", "settings")
    private fun covered(before: List<PushMotion.Entry>, after: List<PushMotion.Entry>, animate: Boolean = true) =
        PushMotion.covered(before, after, roots, modals, animate)

    @Test fun pushShadesTheScreenBeneath() {
        assertEquals(setOf("1"), covered(listOf(today), listOf(today, practice)))
    }

    @Test fun popShadesTheScreenUncovered() {
        assertEquals(setOf("1"), covered(listOf(today, practice), listOf(today)))
    }

    @Test fun backGestureFindsTheScreenBeneathBeforeThePop() {
        // The stack does not change until the gesture commits.
        assertEquals(setOf("1"), covered(listOf(today, practice), listOf(today, practice)))
    }

    @Test fun tabsAndModalsShadeNothing() {
        assertEquals(emptySet<String>(), covered(listOf(today), listOf(today, friends)))
        assertEquals(emptySet<String>(), covered(listOf(today, friends), listOf(today)))
        assertEquals(emptySet<String>(), covered(listOf(today, settings), listOf(today, settings, newAccount)))
        assertEquals(emptySet<String>(), covered(listOf(today, settings, newAccount), listOf(today, settings)))
    }

    @Test fun nothingShadedWhenAnimationsAreOff() {
        assertEquals(emptySet<String>(), covered(listOf(today), listOf(today, practice), animate = false))
    }
}
