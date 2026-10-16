package app.duongondro.core.export

import app.duongondro.core.Catalogue
import app.duongondro.core.Practice
import app.duongondro.core.PracticeGroup
import app.duongondro.core.Session
import app.duongondro.core.TrackedPractice
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Invented data for the export tests: two practices, a custom one, sessions over a few days. */
object ExportFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    val now: Instant = Instant.parse("2026-10-15T19:14:00Z")
    val dorje = TrackedPractice(Catalogue.builtIn.first { it.id == "dorje-sempa" }, openingCount = 110_000, sortOrder = 0)
    val chenrezig = TrackedPractice(Catalogue.builtIn.first { it.id == "chenrezig" }, wantsStreakOnly = true, sortOrder = 1)
    val custom = TrackedPractice(Practice("my-own", "=cmd|' /C calc'!A0", null, PracticeGroup.AnyTime, null, true, isCustom = true),
        archived = true, sortOrder = 2)

    fun session(practice: String, amount: Int, start: String, chosen: LocalDate? = null) = Session(
        id = UUID.nameUUIDFromBytes("$practice$start".toByteArray()), practiceId = practice, amount = amount,
        startedAt = Instant.parse(start), startExact = true, zoneId = zone.id, chosenDay = chosen, loggedAt = Instant.parse(start),
    )

    val sessions = listOf(
        session("dorje-sempa", 1_000, "2026-10-13T04:40:00Z"),
        session("dorje-sempa", 111, "2026-10-13T05:05:00Z"),
        // 00:20 local on the 14th, counted for the 13th.
        session("dorje-sempa", 108, "2026-10-13T22:20:00Z", LocalDate.parse("2026-10-13")),
        session("chenrezig", 0, "2026-10-14T17:30:00Z"),
    )

    val server = """
        {"exportedAt":"2026-10-15T19:14:00Z",
         "users":{"id":"00000000-0000-0000-0000-000000000001","displayName":"Tidy Raccoon","username":null,"gender":"female","createdAt":"2026-10-01T10:00:00Z"},
         "auth_identities":[{"provider":"email","subject":"x","email":"raccoon@example.org","createdAt":"2026-10-01T10:00:00Z"}],
         "credentials":[{"id":"AQID","createdAt":"2026-10-02T10:00:00Z","lastUsedAt":null}],
         "devices":[{"id":"00000000-0000-0000-0000-00000000000d","userId":"00000000-0000-0000-0000-000000000001","publicKey":"AA==","tier":"hardware","createdAt":"2026-10-01T10:00:00Z"}],
         "friendships":[{"id":"00000000-0000-0000-0000-000000000002","displayName":"Quiet Heron","gender":null,"identityPublicKey":"AA==","createdAt":"2026-10-05T08:00:00Z","notifyDone":false}],
         "invites":[{"id":"inv1","inviterId":"00000000-0000-0000-0000-000000000001","createdAt":"2026-10-04T08:00:00Z","expiresAt":"2026-10-11T08:00:00Z","revokedAt":null}],
         "invite_redemptions":[{"inviteId":"inv1","inviteeId":"00000000-0000-0000-0000-000000000002","createdAt":"2026-10-05T08:00:00Z"}],
         "blocks":[{"blockerId":"00000000-0000-0000-0000-000000000001","blockedId":"00000000-0000-0000-0000-000000000003","createdAt":"2026-10-06T08:00:00Z"}],
         "reports":[{"id":"00000000-0000-0000-0000-0000000000aa","reportedId":"00000000-0000-0000-0000-000000000003","reason":"-spam, and \"worse\"","createdAt":"2026-10-06T08:01:00Z"}],
         "practice_logs":[{"sealed":"secret"}]}
    """.trimIndent().toByteArray()

    fun content(withServer: Boolean = true) = ExportContent.build(
        practices = listOf(custom, chenrezig, dorje), sessions = sessions, seeds = emptyList(), publicPractices = setOf("dorje-sempa"),
        account = ExportContent.AccountFacts("Local name", null, null, emptyList(), null),
        server = if (withServer) ServerAccount.parse(server) else null, now = now, zone = zone,
    )
}
