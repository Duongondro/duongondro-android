package app.duongondro.data

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duongondro.core.Catalogue
import app.duongondro.core.Practice
import app.duongondro.core.PracticeGroup
import app.duongondro.core.Session
import app.duongondro.core.StreakSeed
import app.duongondro.core.TrackedPractice
import app.duongondro.core.export.ExportContent
import app.duongondro.model.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * The PDF and CSV exports made with the platform's PdfDocument and the app's resources,
 * from invented data. The files are left in the cache's export-samples folder to look at
 * (adb shell run-as app.duongondro ls cache/export-samples).
 */
@RunWith(AndroidJUnit4::class)
class ExportFormatsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone = ZoneId.of("Europe/Warsaw")
    private val now = Instant.parse("2026-10-15T19:14:00Z")

    private val snapshot: Snapshot = run {
        val dorje = TrackedPractice(Catalogue.builtIn.first { it.id == "dorje-sempa" }, openingCount = 104_000, sortOrder = 0)
        val chenrezig = TrackedPractice(Catalogue.builtIn.first { it.id == "chenrezig" }, sortOrder = 1)
        val karmapa = TrackedPractice(Catalogue.builtIn.first { it.id == "16th-karmapa" }, wantsStreakOnly = true, sortOrder = 2)
        val custom = TrackedPractice(Practice("custom-tara", "Green Tara", "Mantra", PracticeGroup.AnyTime, 100_000, true, isCustom = true),
            archived = true, sortOrder = 3)
        val sessions = mutableListOf<Session>()
        var day = Instant.parse("2026-08-01T04:30:00Z")
        for (i in 0 until 70) {
            sessions += session("dorje-sempa", 108 * (1 + i % 3), day)
            if (i % 2 == 0) sessions += session("dorje-sempa", 216, day.plus(Duration.ofMinutes(35)))
            if (i % 3 == 0) sessions += session("chenrezig", 1_080, day.plus(Duration.ofHours(12)))
            if (i % 4 == 0) sessions += session("16th-karmapa", 0, day.plus(Duration.ofHours(14)))
            day = day.plus(Duration.ofDays(1))
        }
        // After midnight, counted for the day before.
        sessions += session("dorje-sempa", 108, Instant.parse("2026-10-09T22:20:00Z"), LocalDate.parse("2026-10-09"))
        sessions += session("custom-tara", 540, Instant.parse("2026-09-02T06:00:00Z"))
        Snapshot(practices = listOf(dorje, chenrezig, karmapa, custom), sessions = sessions,
            seeds = listOf(StreakSeed("chenrezig", 30, 45, LocalDate.parse("2026-07-31"), zone.id)), publicPractices = setOf("dorje-sempa"))
    }

    private fun session(practice: String, amount: Int, start: Instant, chosen: LocalDate? = null) = Session(
        id = UUID.randomUUID(), practiceId = practice, amount = amount, startedAt = start, startExact = true, zoneId = zone.id,
        chosenDay = chosen, loggedAt = start,
    )

    private val server = DataExport.Server(
        account = """
            {"users":{"id":"00000000-0000-0000-0000-000000000001","displayName":"Tidy Raccoon","username":"raccoon","gender":"female","createdAt":"2026-07-30T10:00:00Z"},
             "auth_identities":[{"provider":"email","subject":"s","email":"raccoon@example.org","createdAt":"2026-07-30T10:00:00Z"}],
             "credentials":[{"id":"AQIDBA==","createdAt":"2026-07-30T10:01:00Z","lastUsedAt":"2026-10-14T06:00:00Z"}],
             "devices":[{"id":"00000000-0000-0000-0000-00000000000d","tier":"hardware","createdAt":"2026-07-30T10:00:00Z"},
                        {"id":"00000000-0000-0000-0000-00000000000e","tier":"software","createdAt":"2026-09-01T10:00:00Z"}],
             "friendships":[{"id":"00000000-0000-0000-0000-000000000002","displayName":"Quiet Heron","createdAt":"2026-08-05T08:00:00Z"},
                            {"id":"00000000-0000-0000-0000-000000000004","displayName":"=Odd Name","createdAt":"2026-09-05T08:00:00Z"}],
             "invites":[{"id":"inv1","createdAt":"2026-08-04T08:00:00Z","expiresAt":"2026-08-11T08:00:00Z","revokedAt":null}],
             "invite_redemptions":[{"inviteId":"inv1","inviteeId":"00000000-0000-0000-0000-000000000002","createdAt":"2026-08-05T08:00:00Z"}],
             "blocks":[],"reports":[]}
        """.trimIndent().toByteArray(),
        raw = "{}".toByteArray(),
    )

    private val folder = File(context.cacheDir, "export-samples").apply { mkdirs() }

    private fun make(locale: Locale, tag: String) {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localized = context.createConfigurationContext(config)
        val account = ExportContent.AccountFacts("Tidy Raccoon", null, null, emptyList(), null)
        val content = ExportFiles.content(localized, snapshot, server, emptySet(), account, emptyList(), now, zone)
        val pdf = ExportFiles.pdf(localized, content, "0.4 (abcdef0)", now)
        assertEquals("%PDF", pdf.copyOfRange(0, 4).decodeToString())
        File(folder, "export-$tag.pdf").writeBytes(pdf)
        val csv = ExportFiles.csv(localized, content, now)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(csv)).use { z -> while (true) names += (z.nextEntry ?: break).name }
        assertEquals(8, names.size) // README.txt and seven tables
        File(folder, "export-$tag-csv.zip").writeBytes(csv)
        val json = DataExport.zip(snapshot, server, appVersion = "0.4", now = now)
        File(folder, "export-$tag-json.zip").writeBytes(json)
        assertTrue(pdf.size > 10_000)
    }

    @Test fun english() = make(Locale.ENGLISH, "en")

    @Test fun german() = make(Locale.GERMAN, "de")

    @Test fun russian() = make(Locale.forLanguageTag("ru"), "ru")
}
