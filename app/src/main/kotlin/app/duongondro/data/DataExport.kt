package app.duongondro.data

import app.duongondro.core.civilDate
import app.duongondro.model.Snapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Settings › Your data › Export (GDPR Articles 15 and 20): one ZIP with
 * everything, machine-readable, in the same format as the iOS export.
 * Local-mode users get it from the local database alone; with an account,
 * `GET /api/me/export` supplies account.json and server-raw.json, and the phone
 * decrypts the sealed sessions itself.
 */
object DataExport {
    /** What the server returned from `GET /api/me/export`, passed through as is. */
    class Server(val account: ByteArray, val raw: ByteArray)

    fun zip(snapshot: Snapshot, server: Server?, covers: Map<String, ByteArray> = emptyMap(),
            appVersion: String, now: Instant = Instant.now()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun add(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name).apply { time = now.toEpochMilli() })
                z.write(bytes)
                z.closeEntry()
            }
            add("README.txt", readme(server != null, covers.isNotEmpty()).toByteArray())
            add("practice.json", practiceJson(snapshot, appVersion, now).toByteArray())
            server?.let {
                add("account.json", it.account)
                add("server-raw.json", it.raw)
            }
            covers.toSortedMap().forEach { (id, image) -> add("covers/$id.jpg", image) }
        }
        return out.toByteArray()
    }

    fun fileName(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) =
        "duongondro-export-${civilDate(now, zone)}.zip"

    @Serializable
    private data class PracticeFile(
        val format: String = "duongondro/practice-export/v1",
        val exportedAt: String,
        val appVersion: String,
        val preferences: PreferencesOut,
        val practices: List<PracticeOut>,
        val sessions: List<SessionOut>,
        val streakSeeds: List<SeedOut>,
    )

    @Serializable
    private data class PreferencesOut(
        val malaSize: Int, val finishedShortRefuge: Boolean, val finishedNgondro: Boolean,
        val reminderMinutesAfterMidnight: Int?, val discreetNotifications: Boolean, val gender: String?,
    )

    @Serializable
    private data class PracticeOut(
        val id: String, val name: String, val secondName: String?, val group: String, val custom: Boolean,
        val target: Int?, val streakOnly: Boolean, val malaSize: Int?, val archived: Boolean,
        val openingCount: Int, val lifetime: Int, val round: Int?, val countInRound: Int?,
    )

    @Serializable
    private data class SessionOut(
        val id: String, val practiceId: String, val amount: Int, val startedAt: String, val startExact: Boolean,
        val timeZone: String, val day: String, val dayChosenByUser: Boolean, val loggedAt: String,
    )

    @Serializable
    private data class SeedOut(val practiceId: String, val days: Int, val longest: Int?, val lastDay: String, val timeZone: String)

    private val json = Json { prettyPrint = true; explicitNulls = false; encodeDefaults = true }

    internal fun practiceJson(s: Snapshot, appVersion: String, now: Instant): String {
        val p = s.preferences
        val file = PracticeFile(
            exportedAt = now.iso(), appVersion = appVersion,
            preferences = PreferencesOut(p.malaSize, p.finishedShortRefuge, p.finishedNgondro, p.reminderMinutes, p.discreetNotifications, p.gender?.wire),
            practices = s.practices.map { t ->
                val r = t.rounds(s.sessions)
                PracticeOut(t.id, t.practice.name, t.practice.secondName, t.practice.group.name.replaceFirstChar { it.lowercase() },
                    t.practice.isCustom, t.practice.target, t.streakOnly, t.practice.malaSize, t.archived,
                    t.openingCount, t.lifetime(s.sessions), r?.round, r?.inRound)
            },
            sessions = s.sessions.map { x ->
                SessionOut(x.id.toString(), x.practiceId, x.amount, x.startedAt.iso(), x.startExact, x.zoneId,
                    x.day.toString(), x.chosenDay != null, x.loggedAt.iso())
            },
            streakSeeds = s.seeds.map { SeedOut(it.practiceId, it.days, it.longest, it.lastDay.toString(), it.zoneId) },
        )
        return json.encodeToString(PracticeFile.serializer(), file)
    }

    internal fun readme(hasServer: Boolean, hasCovers: Boolean): String = buildString {
        appendLine("Duongöndro: all of your data")
        appendLine()
        appendLine("practice.json")
        appendLine("  Your practices, every session and your private streak seeds, decrypted on")
        appendLine("  your phone. Times are ISO 8601 in UTC; each session also has the time zone")
        appendLine("  it started in and the day it counts for (YYYY-MM-DD). openingCount is what")
        appendLine("  you entered at onboarding; lifetime adds every session to it.")
        if (hasServer) {
            appendLine()
            appendLine("account.json")
            appendLine("  Your account, linked sign-ins, devices, friendships, invites, blocks,")
            appendLine("  reports and public streak statements, as the server holds them.")
            appendLine()
            appendLine("server-raw.json")
            appendLine("  Exactly what the server stores, sealed blobs as base64. The server cannot")
            appendLine("  read them; practice.json is the same data decrypted.")
        } else {
            appendLine()
            appendLine("This phone is in local mode: no account exists, so nothing about you is on")
            appendLine("any server, and there is no account.json or server-raw.json.")
        }
        if (hasCovers) {
            appendLine()
            appendLine("covers/")
            appendLine("  Your own cover photos, which never left the phone.")
        }
    }

    /** Whole-second ISO 8601 in UTC, as the iOS export writes it. */
    private fun Instant.iso(): String = truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
}
