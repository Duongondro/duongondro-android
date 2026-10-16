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
}
