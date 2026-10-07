package app.duongondro.core.sync

import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.Identity
import app.duongondro.core.crypto.Tier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** A statement as the server stores and forwards it: the exact payload bytes and their signature. */
class SignedStatement(val payload: ByteArray, val signature: ByteArray) {
    /** Checks the signature over the exact bytes received, before anything parses them. */
    fun verify(type: String, identityPk: ByteArray): Boolean = E2EE.verifyStatement(type, payload, signature, identityPk)

    companion object {
        fun sign(type: String, payload: ByteArray, identity: Identity) =
            SignedStatement(payload, E2EE.signStatement(type, payload, identity))
    }
}

/**
 * Signed statements' payloads in the canonical form the server checks and every
 * verifier hashes: compact JSON, keys in lexicographic order, integers only,
 * byte strings as unpadded base64url, UUIDs lowercase with hyphens
 * (docs/crypto.md, Signed statements). Built by hand, as on iOS, so no
 * encoder's choices can change a byte. Parsing happens only after verifying.
 */
object Statements {
    class ListedDevice(val id: UUID, val publicKey: ByteArray, val tier: Tier) {
        override fun equals(other: Any?) =
            other is ListedDevice && id == other.id && publicKey.contentEquals(other.publicKey) && tier == other.tier
        override fun hashCode() = id.hashCode()
        override fun toString() = "ListedDevice($id, ${tier.raw})"
    }

    data class DeviceList(val devices: List<ListedDevice>, val issuedAt: Instant, val user: UUID, val version: Long)

    fun deviceList(devices: List<ListedDevice>, issuedAt: Instant, user: UUID, version: Long): ByteArray {
        val items = devices.joinToString(",") { d ->
            """{"id":"${d.id.lower}","pk":"${base64url(d.publicKey)}","tier":"${d.tier.raw}"}"""
        }
        return """{"devices":[$items],"issuedAt":${issuedAt.syncMillis()},"user":"${user.lower}","version":$version}""".toByteArray()
    }

    /** Reads a device list back (to extend it with a new device); null if any part does not parse. */
    fun parseDeviceList(payload: ByteArray): DeviceList? = runCatching {
        val raw = json.decodeFromString<RawDeviceList>(payload.decodeToString())
        val devices = raw.devices.map { d ->
            ListedDevice(UUID.fromString(d.id), fromBase64url(d.pk)!!, Tier.of(d.tier)!!)
        }
        DeviceList(devices, syncInstant(raw.issuedAt), UUID.fromString(raw.user), raw.version)
    }.getOrNull()

    // Phase 4: invites and public streaks

    data class Invite(val id: String, val inviter: UUID, val inviterIdentityPk: ByteArray, val expiresAt: Instant)

    fun invite(id: String, inviter: UUID, inviterIdentityPk: ByteArray, expiresAt: Instant): ByteArray {
        require(id.all { it in RecoveryCode.ALPHABET }) { "an invite id is Crockford base32" }
        return """{"expiresAt":${expiresAt.syncMillis()},"inviteId":"$id","inviter":"${inviter.lower}","inviterIdentityPk":"${base64url(inviterIdentityPk)}"}""".toByteArray()
    }

    fun parseInvite(payload: ByteArray): Invite? = runCatching {
        val raw = json.decodeFromString<RawInvite>(payload.decodeToString())
        Invite(raw.inviteId, UUID.fromString(raw.inviter), fromBase64url(raw.inviterIdentityPk)!!, syncInstant(raw.expiresAt))
    }.getOrNull()

    data class Acceptance(val inviteId: String, val invitee: UUID, val inviteeIdentityPk: ByteArray)

    fun acceptance(inviteId: String, invitee: UUID, inviteeIdentityPk: ByteArray): ByteArray {
        require(inviteId.all { it in RecoveryCode.ALPHABET }) { "an invite id is Crockford base32" }
        return """{"inviteId":"$inviteId","invitee":"${invitee.lower}","inviteeIdentityPk":"${base64url(inviteeIdentityPk)}"}""".toByteArray()
    }

    fun parseAcceptance(payload: ByteArray): Acceptance? = runCatching {
        val raw = json.decodeFromString<RawAcceptance>(payload.decodeToString())
        Acceptance(raw.inviteId, UUID.fromString(raw.invitee), fromBase64url(raw.inviteeIdentityPk)!!)
    }.getOrNull()

    /** A public streak: tracked days only, the day of the last one, and the deadline for streak-at-risk pushes. */
    data class Streak(
        val user: UUID,
        val practice: String,
        val day: String,
        val current: Int,
        val longest: Int,
        val deadline: Instant,
        val seq: Long,
    )

    /** The practice id travels as is: lowercase letters, digits and hyphens (the server's rule), so it needs no escaping. */
    fun streak(s: Streak): ByteArray {
        require(s.practice.matches(Regex("[a-z0-9-]+"))) { "a practice id is lowercase letters, digits and hyphens" }
        require(s.day.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "a day is YYYY-MM-DD" }
        return """{"current":${s.current},"day":"${s.day}","deadline":${s.deadline.syncMillis()},"longest":${s.longest},"practice":"${s.practice}","seq":${s.seq},"user":"${s.user.lower}"}""".toByteArray()
    }

    fun parseStreak(payload: ByteArray): Streak? = runCatching {
        val raw = json.decodeFromString<RawStreak>(payload.decodeToString())
        Streak(UUID.fromString(raw.user), raw.practice, raw.day, raw.current, raw.longest, syncInstant(raw.deadline), raw.seq)
    }.getOrNull()

    fun base64url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun fromBase64url(s: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull()

    private val UUID.lower: String get() = toString().lowercase()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private class RawDevice(val id: String, val pk: String, val tier: String)
    @Serializable private class RawDeviceList(val devices: List<RawDevice>, val issuedAt: Long, val user: String, val version: Long)
    @Serializable private class RawInvite(val expiresAt: Long, val inviteId: String, val inviter: String, val inviterIdentityPk: String)
    @Serializable private class RawAcceptance(val inviteId: String, val invitee: String, val inviteeIdentityPk: String)
    @Serializable private class RawStreak(
        val current: Int, val day: String, val deadline: Long, val longest: Int, val practice: String, val seq: Long, val user: String,
    )
}
