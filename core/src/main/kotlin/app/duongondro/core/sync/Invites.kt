package app.duongondro.core.sync

import app.duongondro.core.api.Api
import app.duongondro.core.api.ApiError
import app.duongondro.core.api.SignUpProof
import app.duongondro.core.api.SignedStatementDto
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.Identity
import app.duongondro.core.crypto.StatementTypes
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * An invitation as its inviter holds it: the 8-character id the server knows,
 * the 10-byte secret it never sees, and when it stops working. The same 24
 * characters are the link's path and fragment and the code typed by hand.
 */
class MadeInvite(val invite: Invitation.Invite, val expiresAt: Instant) {
    val id: String get() = invite.id
    val secretText: String get() = Crockford.encode(invite.secret)

    /** The code typed by hand: the id, then the secret. */
    val code: String get() = id + secretText

    /** The link people share: the lower-case host is the one Android verifies as an app link. */
    val link: String get() = "https://$HOST/i/$id#$secretText"

    /** The link as a QR code carries it: all upper case, so it fits alphanumeric mode (version 3 rather than 4), as on iOS. */
    val qrText: String get() = "HTTPS://${HOST.uppercase()}/I/$id#$secretText"

    /** For the cache of the invite on show, bound to the account that made it: `<owner> <id> <secret> <expiresAt ms>`. */
    fun serialised(owner: UUID): ByteArray = "$owner $id $secretText ${expiresAt.toEpochMilli()}".toByteArray()

    companion object {
        const val HOST = "duongondro.app"

        /** The cached invite, or null when it does not parse or another account made it. */
        fun deserialised(data: ByteArray, owner: UUID): MadeInvite? = runCatching {
            val (madeBy, id, secret, expires) = data.decodeToString().split(' ')
            if (UUID.fromString(madeBy) != owner) return null
            val parsed = Invitation.parse(id + secret) as Invitation.Invite
            MadeInvite(parsed, Instant.ofEpochMilli(expires.toLong()))
        }.getOrNull()
    }
}

/**
 * Invitations on the phone (design: Social), as iOS's Social: making one,
 * and accepting one after the invitee's keys exist. Checking one before
 * sign-up is [Invitation.check].
 */
object Invites {
    /** An invitation works for seven days (the server allows up to thirty). */
    val LIFETIME: Duration = Duration.ofDays(7)

    /**
     * Makes a reusable invitation signed by this account's identity key, with
     * the MAC under the link's pin that lets the invitee check that key. A
     * taken id (409) draws another, up to three times.
     */
    fun create(
        api: Api,
        user: UUID,
        identity: Identity,
        now: Instant = Instant.now(),
        lifetime: Duration = LIFETIME,
        random: (Int) -> ByteArray = E2EE::randomBytes,
    ): MadeInvite {
        // Whole milliseconds, as the statement carries them.
        val expiresAt = now.plus(lifetime).toSyncTime()
        val pk = identity.publicKey
        repeat(3) {
            val invite = Invitation.Invite(Crockford.encode(random(5)), random(10))
            val keys = E2EE.inviteKeys(invite.secret)
            val payload = Statements.invite(invite.id, user, pk, expiresAt)
            val statement = SignedStatement.sign(StatementTypes.INVITE, payload, identity)
            try {
                api.createInvite(invite.id, keys.auth, SyncTime.rfc3339(expiresAt),
                    SignedStatementDto(statement.payload, statement.signature), E2EE.inviteMAC(keys.pin, pk))
                return MadeInvite(invite, expiresAt)
            } catch (_: ApiError.Conflict) {
                // The id is taken: draw another.
            }
        }
        throw ApiError.Conflict("no free invite id")
    }

    /** Thrown by [redeem] for an invitation this account made. */
    class OwnInvite : Exception("own invite")

    /**
     * Accepts a checked invitation with a signed acceptance: the server makes
     * the two accounts friends. The inviter's key to pin is the one the check
     * proved ([Invitation.Checked.inviterIdentityPk]), never the server's word.
     */
    fun redeem(api: Api, checked: Invitation.Checked, user: UUID, identity: Identity) {
        if (checked.inviter == user) throw OwnInvite()
        val payload = Statements.acceptance(checked.invite.id, user, identity.publicKey)
        val acceptance = SignedStatement.sign(StatementTypes.ACCEPTANCE, payload, identity)
        api.redeemInvite(checked.invite.id, (checked.invite.proof as SignUpProof.Invite).auth,
            SignedStatementDto(acceptance.payload, acceptance.signature))
    }
}
