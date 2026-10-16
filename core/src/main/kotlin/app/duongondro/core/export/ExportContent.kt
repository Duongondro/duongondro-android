package app.duongondro.core.export

import app.duongondro.core.Practice
import app.duongondro.core.Session
import app.duongondro.core.Streak
import app.duongondro.core.StreakSeed
import app.duongondro.core.TrackedPractice
import app.duongondro.core.civilDate
import app.duongondro.core.of
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

/**
 * What the PDF and CSV exports show, built on the phone from the local
 * database (final sessions only, the server's sealed sessions merged in) and,
 * with an account, from the account's part of `GET /api/me/export`. Offline and
 * local-mode exports have the phone's part alone. No cover photos: they never
 * reach the server (design decision 26).
 */
data class ExportContent(
    /** Null in local mode: no account exists. */
    val account: AccountFacts?,
    val practices: List<PracticeRow>,
    /** By practice (in [practices]' order), then by start. */
    val sessions: List<SessionRow>,
    val friends: List<FriendRow>,
    val invites: List<InviteRow>,
    val blocks: List<BlockRow>,
    val reports: List<ReportRow>,
    val devices: List<DeviceRow>,
    val passkeys: List<PasskeyRow>,
    /** Whether the server's part is in: an account offline or signed out has its own facts only. */
    val serverIncluded: Boolean,
    /** The phone's zone, in which the server's times are written. */
    val zone: ZoneId,
    /** Sealed sessions that did not open here, or opened unusable: only in the JSON export. */
    val unreadable: Int = 0,
) {
    data class AccountFacts(
        val displayName: String?,
        val username: String?,
        /** "male", "female" or "nonbinary"; null when not given. */
        val gender: String?,
        val emails: List<String>,
        val createdAt: Instant?,
    )

    data class PracticeRow(
        val id: String,
        val name: String,
        val secondName: String?,
        val custom: Boolean,
        val target: Int?,
        /** The practice's own, or the default. */
        val malaSize: Int,
        val streakOnly: Boolean,
        val archived: Boolean,
        /** Opening count plus every session. */
        val total: Int,
        /** The round under way and how far into it; null for open-ended and streak-only practices. */
        val round: Int?,
        val inRound: Int?,
        val currentStreak: Int,
        val longestStreak: Int,
        val public: Boolean,
    )

    data class SessionRow(
        val id: UUID,
        val practiceId: String,
        val startedAt: Instant,
        val zone: ZoneId,
        /** The local date it started on. */
        val startDay: LocalDate,
        /** The day it counts for. */
        val day: LocalDate,
        /** The after-midnight sheet's choice, when the person made one. */
        val chosenDay: LocalDate?,
        val count: Int,
        /** The round its last count fell in; null for open-ended and streak-only practices. */
        val round: Int?,
    )

    data class FriendRow(val id: UUID, val displayName: String, val gender: String?, val since: Instant?)
    /** A friendship code this account [made] (with how often it was redeemed), or one it redeemed. */
    data class InviteRow(val id: String, val made: Boolean, val createdAt: Instant, val expiresAt: Instant?, val revokedAt: Instant?, val redemptions: Int)
    /** [who] is the person's name where this phone knows it, else their account id. */
    data class BlockRow(val accountId: UUID, val who: String, val since: Instant)
    data class ReportRow(val accountId: UUID?, val who: String, val reason: String, val createdAt: Instant)
    /** [tier] as the signed device list has it: "hardware", "tee" or "software". */
    data class DeviceRow(val id: UUID, val tier: String, val addedAt: Instant)
    /** [id] is the credential id, base64url. */
    data class PasskeyRow(val id: String, val createdAt: Instant, val lastUsedAt: Instant?)

    companion object {
        /**
         * [sessions] are final ones only (an open undo window is closed first);
         * [names] gives a practice's names in the app's language. [account] is the
         * phone's own idea of the account, [server] the account's part of the
         * server export, which wins where both say something. [cachedFriends] are
         * the friends this phone last fetched, for an export without the server.
         */
        fun build(
            practices: List<TrackedPractice>,
            sessions: List<Session>,
            seeds: List<StreakSeed>,
            publicPractices: Set<String>,
            defaultMalaSize: Int = 108,
            account: AccountFacts?,
            server: ServerAccount?,
            cachedFriends: List<FriendRow> = emptyList(),
            now: Instant,
            zone: ZoneId,
            names: (Practice) -> Pair<String, String?> = { it.name to it.secondName },
            unreadable: Int = 0,
        ): ExportContent {
            val ordered = practices.sortedWith(compareBy({ it.archived }, { it.sortOrder }))
            val practiceRows = ordered.map { t ->
                val r = t.rounds(sessions)
                val streak = Streak.of(t.id, sessions, seeds.firstOrNull { it.practiceId == t.id }, now, zone)
                val (name, second) = names(t.practice)
                PracticeRow(t.id, name, second, t.practice.isCustom, t.practice.target, t.practice.effectiveMalaSize(defaultMalaSize), t.streakOnly, t.archived,
                    t.lifetime(sessions), r?.round, r?.inRound, streak.current, streak.longest, t.id in publicPractices)
            }
            val sessionRows = ordered.flatMap { t ->
                val target = t.practice.target?.takeIf { it > 0 && !t.streakOnly }
                var running = maxOf(0, t.openingCount)
                sessions.filter { it.practiceId == t.id }.sortedWith(compareBy({ it.startedAt }, { it.id.toString() })).map { s ->
                    running += s.amount
                    SessionRow(s.id, s.practiceId, s.startedAt, s.zone, civilDate(s.startedAt, s.zone), s.day, s.chosenDay, s.amount,
                        target?.let { maxOf(0, running - 1) / it + 1 })
                }
            }
            val names = (cachedFriends + server?.friends.orEmpty()).associate { it.id to it.displayName }
            fun who(id: UUID?): String = id?.let { names[it]?.takeIf(String::isNotBlank) ?: it.toString() }.orEmpty()
            val facts = when {
                server != null -> AccountFacts(server.displayName ?: account?.displayName, server.username, server.gender,
                    server.emails.ifEmpty { account?.emails.orEmpty() }, server.createdAt)
                else -> account
            }
            return ExportContent(
                account = facts,
                practices = practiceRows,
                sessions = sessionRows,
                friends = (server?.friends ?: cachedFriends).sortedBy { it.displayName.lowercase() },
                invites = server?.invites.orEmpty(),
                blocks = server?.blocks.orEmpty().map { it.copy(who = who(it.accountId)) },
                reports = server?.reports.orEmpty().map { it.copy(who = who(it.accountId)) },
                devices = server?.devices.orEmpty(),
                passkeys = server?.passkeys.orEmpty(),
                serverIncluded = server != null,
                zone = zone,
                unreadable = unreadable,
            )
        }
    }
}

/**
 * The account's tables of `GET /api/me/export` (account.json), read leniently:
 * a field the server leaves out or renames is skipped, never fatal, since the
 * JSON format carries the full data anyway.
 */
data class ServerAccount(
    val displayName: String?,
    val username: String?,
    val gender: String?,
    val createdAt: Instant?,
    val emails: List<String>,
    val passkeys: List<ExportContent.PasskeyRow>,
    val devices: List<ExportContent.DeviceRow>,
    val friends: List<ExportContent.FriendRow>,
    val invites: List<ExportContent.InviteRow>,
    val blocks: List<ExportContent.BlockRow>,
    val reports: List<ExportContent.ReportRow>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(bytes: ByteArray): ServerAccount {
            val body = json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: JsonObject(emptyMap())
            val user = body["users"] as? JsonObject
            val me = user?.string("id")?.let(::uuid)
            val redemptions = body.rows("invite_redemptions").mapNotNull { it.string("inviteId") }.groupingBy { it }.eachCount()
            return ServerAccount(
                displayName = user?.string("displayName")?.takeIf { it.isNotBlank() },
                username = user?.string("username")?.takeIf { it.isNotBlank() },
                gender = user?.string("gender")?.takeIf { it.isNotBlank() },
                createdAt = user?.instant("createdAt"),
                emails = body.rows("auth_identities").mapNotNull { it.string("email")?.takeIf(String::isNotBlank) }.distinct(),
                passkeys = body.rows("credentials").mapNotNull { r ->
                    val id = r.string("id") ?: return@mapNotNull null
                    val created = r.instant("createdAt") ?: return@mapNotNull null
                    ExportContent.PasskeyRow(base64url(id), created, r.instant("lastUsedAt"))
                }.sortedBy { it.createdAt },
                devices = body.rows("devices").mapNotNull { r ->
                    ExportContent.DeviceRow(r.string("id")?.let(::uuid) ?: return@mapNotNull null, r.string("tier").orEmpty(),
                        r.instant("createdAt") ?: return@mapNotNull null)
                }.sortedBy { it.addedAt },
                friends = body.rows("friendships").mapNotNull { r ->
                    ExportContent.FriendRow(r.string("id")?.let(::uuid) ?: return@mapNotNull null, r.string("displayName").orEmpty(),
                        r.string("gender"), r.instant("createdAt"))
                },
                invites = run {
                    val made = body.rows("invites").mapNotNull { r ->
                        val id = r.string("id") ?: return@mapNotNull null
                        ExportContent.InviteRow(id, true, r.instant("createdAt") ?: return@mapNotNull null, r.instant("expiresAt"),
                            r.instant("revokedAt"), redemptions[id] ?: 0)
                    }
                    val madeIds = made.mapTo(HashSet()) { it.id }
                    // A redemption of someone else's code: the code this account was invited or befriended with.
                    val redeemed = body.rows("invite_redemptions").mapNotNull { r ->
                        val id = r.string("inviteId")?.takeIf { it !in madeIds } ?: return@mapNotNull null
                        ExportContent.InviteRow(id, false, r.instant("createdAt") ?: return@mapNotNull null, null, null, 0)
                    }
                    (made + redeemed).sortedBy { it.createdAt }
                },
                blocks = body.rows("blocks").mapNotNull { r ->
                    val blocked = r.string("blockedId")?.let(::uuid) ?: return@mapNotNull null
                    // The table holds blocks both ways only for a purge; the export lists the account's own.
                    if (me != null && r.string("blockerId")?.let(::uuid).let { it != null && it != me }) return@mapNotNull null
                    ExportContent.BlockRow(blocked, blocked.toString(), r.instant("createdAt") ?: return@mapNotNull null)
                }.sortedBy { it.since },
                reports = body.rows("reports").mapNotNull { r ->
                    val about = r.string("reportedId")?.let(::uuid)
                    ExportContent.ReportRow(about, about?.toString().orEmpty(), r.string("reason").orEmpty(),
                        r.instant("createdAt") ?: return@mapNotNull null)
                }.sortedBy { it.createdAt },
            )
        }

        private fun JsonObject.rows(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

        private fun JsonObject.instant(key: String): Instant? = string(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

        private fun uuid(s: String): UUID? = runCatching { UUID.fromString(s) }.getOrNull()

        /** Go writes []byte as standard base64; a credential id reads as base64url, as WebAuthn shows it. */
        private fun base64url(standard: String): String = runCatching {
            Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getDecoder().decode(standard))
        }.getOrDefault(standard)
    }
}
