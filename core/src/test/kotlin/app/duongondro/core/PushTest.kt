package app.duongondro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID

class PushTest {
    private val device = UUID.fromString("0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6b")
    private val now = Instant.parse("2026-10-15T20:00:00Z")
    private val sent = PushUpload.sent(device, "fcm", "token-a")

    @Test fun neverSentIsDue() {
        assertTrue(PushUpload.due(device, "fcm", "token-a", null, null, now))
    }

    @Test fun theSameTokenWithinADayIsNotSentAgain() {
        assertFalse(PushUpload.due(device, "fcm", "token-a", sent, now.minus(Duration.ofHours(23)), now))
    }

    @Test fun aDayLaterItIsSentAgain() {
        assertTrue(PushUpload.due(device, "fcm", "token-a", sent, now.minus(Duration.ofHours(25)), now))
    }

    @Test fun aNewTokenDeviceOrPlatformIsSentAtOnce() {
        val recent = now.minusSeconds(60)
        assertTrue(PushUpload.due(device, "fcm", "token-b", sent, recent, now))
        assertTrue(PushUpload.due(UUID.randomUUID(), "fcm", "token-a", sent, recent, now))
        assertTrue(PushUpload.due(device, "apns", "token-a", sent, recent, now))
    }

    @Test fun keysAreTheServers() {
        // A copy of duongondro-api internal/service/nudges.go: its three base keys,
        // each with _MALE and _FEMALE variants but STREAK_AT_RISK.
        val expected = listOf("FRIEND_DONE", "FRIEND_DONE_MALE", "FRIEND_DONE_FEMALE", "POKE", "POKE_MALE", "POKE_FEMALE", "STREAK_AT_RISK")
        assertEquals(expected, PushKey.entries.map { it.wire })
        // Checked against the server's source when a checkout of it is beside this one.
        val nudges = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "duongondro-api/internal/service/nudges.go") }.firstOrNull { it.isFile } ?: return
        val base = Regex("""Loc\w+\s*=\s*"([A-Z_]+)"""").findAll(nudges.readText()).map { it.groupValues[1] }.toList()
        assertEquals(listOf("FRIEND_DONE", "POKE", "STREAK_AT_RISK"), base)
    }
}
