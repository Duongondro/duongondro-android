package app.duongondro.data

import app.duongondro.core.Catalogue
import app.duongondro.core.Practice
import app.duongondro.core.PracticeGroup
import app.duongondro.core.Session
import app.duongondro.core.TrackedPractice
import app.duongondro.core.civilDate
import app.duongondro.core.sync.ServerExport
import app.duongondro.model.Snapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
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
    /** What the server returned from `GET /api/me/export`: `raw` as is, `account` the account's part of it. */
    class Server(
        val account: ByteArray,
        val raw: ByteArray,
        /** The sealed practice sessions, opened on this phone; merged into practice.json unless the phone holds them. */
        val sessions: List<Session> = emptyList(),
        /** Names of custom practices, as sealed with their sessions, for practices this phone does not track. */
        val practiceNames: Map<String, String> = emptyMap(),
        /** Sealed logs this phone holds no key for. */
        val unreadable: Int = 0,
        /** Sealed logs that opened but are unusable or whose sealed time disagrees with the server's. */
        val refused: Int = 0,
    ) {
        constructor(opened: ServerExport.Opened) :
            this(opened.account, opened.raw, opened.sessions, opened.practiceNames, opened.unreadable, opened.refused)
    }

    /**
     * The phone's snapshot plus the server's sessions it does not hold yet, so a session synced from another
     * phone appears once. A session deleted here but not yet pushed is in `known` and stays gone. A practice
     * only the server's sessions mention gets a minimal entry, as sync adds one.
     */
    fun merged(snapshot: Snapshot, server: Server?, known: Set<UUID> = emptySet()): Snapshot {
        if (server == null || server.sessions.isEmpty()) return snapshot
        val seen = HashSet<UUID>(known).apply { snapshot.sessions.forEach { add(it.id) } }
        val sessions = (snapshot.sessions + server.sessions.filter { seen.add(it.id) })
            .sortedWith(compareBy({ it.startedAt }, { it.id.toString() }))
        val tracked = snapshot.practices.mapTo(HashSet()) { it.id }
        var order = (snapshot.practices.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val added = sessions.map { it.practiceId }.distinct().filter { tracked.add(it) }.map { id ->
            val practice = Catalogue.builtIn.firstOrNull { it.id == id }
                ?: Practice(id, server.practiceNames[id] ?: id, null, PracticeGroup.AnyTime, null, allowStreakOnly = true, isCustom = true)
            TrackedPractice(practice, wantsStreakOnly = practice.streakOnlyByDefault, sortOrder = order++)
        }
        return snapshot.copy(practices = snapshot.practices + added, sessions = sessions)
    }

    /** Where the README says the server part stands: not asked (local mode), included, or an account whose part is missing. */
    enum class ServerPart { LOCAL, INCLUDED, MISSING }

    /**
     * `knownSessionIds` are all of this phone's session ids, deleted ones too (the snapshot lacks those).
     * `accountWithoutServer` is a signed-in account whose server part is missing, which the README says
     * rather than "local mode".
     */
    fun zip(snapshot: Snapshot, server: Server?, covers: Map<String, ByteArray> = emptyMap(),
            knownSessionIds: Set<UUID> = emptySet(), accountWithoutServer: Boolean = false,
            appVersion: String, now: Instant = Instant.now()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun add(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name).apply { time = now.toEpochMilli() })
                z.write(bytes)
                z.closeEntry()
            }
            add("README.txt", readme(if (server != null) ServerPart.INCLUDED else if (accountWithoutServer) ServerPart.MISSING else ServerPart.LOCAL,
                covers.isNotEmpty(), server?.unreadable ?: 0, server?.refused ?: 0).toByteArray())
            add("practice.json", practiceJson(merged(snapshot, server, knownSessionIds), appVersion, now).toByteArray())
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
        val reminderMinutesAfterMidnight: Int?, val discreetNotifications: Boolean, val usualTimeNudge: Boolean,
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

    /** Final sessions only: one still in its undo window has not happened yet, as far as anything off the phone knows. */
    internal fun practiceJson(snapshot: Snapshot, appVersion: String, now: Instant): String {
        val s = snapshot.withoutPending()
        val p = s.preferences
        val file = PracticeFile(
            exportedAt = now.iso(), appVersion = appVersion,
            preferences = PreferencesOut(p.malaSize, p.finishedShortRefuge, p.finishedNgondro, p.reminderMinutes, p.discreetNotifications, p.usualTimeNudge),
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

    internal fun readme(part: ServerPart, hasCovers: Boolean, unreadable: Int = 0, refused: Int = 0): String = buildString {
        val hasServer = part == ServerPart.INCLUDED
        appendLine("Duongöndro: all of your data")
        appendLine()
        appendLine("practice.json")
        appendLine("  Your practices, every session and your private streak seeds, decrypted on")
        appendLine("  your phone. Times are ISO 8601 in UTC; each session also has the time zone")
        appendLine("  it started in and the day it counts for (YYYY-MM-DD). openingCount is what")
        appendLine("  you entered at onboarding; lifetime adds every session to it.")
        if (hasServer) {
            appendLine("  Sessions that came from your other phones through sync are included once.")
            appendLine()
            appendLine("account.json")
            appendLine("  Your account, linked sign-ins, devices, friendships, friendship codes, blocks,")
            appendLine("  reports and public streak statements, as the server holds them (the")
            appendLine("  sealed logs and keys are only in server-raw.json). Friends")
            appendLine("  appear with the display name and gender the app shows you.")
            appendLine()
            appendLine("server-raw.json")
            appendLine("  Exactly what the server stores, sealed blobs as base64. The server cannot")
            appendLine("  read them; practice.json is the same data decrypted.")
            if (unreadable > 0) {
                appendLine()
                appendLine("$unreadable sealed sessions could not be opened on this phone (they were sealed")
                appendLine("with a key it does not hold yet), so they are only in server-raw.json.")
            }
            if (refused > 0) {
                appendLine()
                appendLine("$refused sealed sessions were opened but left out of practice.json: their content")
                appendLine("was unusable, or its sealed time did not match the server's. They are in")
                appendLine("server-raw.json.")
            }
        } else if (part == ServerPart.MISSING) {
            appendLine()
            appendLine("This phone has an account, but the server's part of the export could not be")
            appendLine("fetched (offline, signed out or the server was away), so there is no")
            appendLine("account.json or server-raw.json, and practice.json holds only what is on this")
            appendLine("phone. Export again when you are connected to include them.")
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
