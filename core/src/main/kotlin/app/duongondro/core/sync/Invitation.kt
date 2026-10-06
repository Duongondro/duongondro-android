package app.duongondro.core.sync

import app.duongondro.core.api.Api
import app.duongondro.core.api.SignUpProof
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.StatementTypes
import java.time.Instant
import java.util.UUID

/**
 * The code a sign-up starts from (design: Onboarding v2, Invitation): 24
 * Crockford characters for a friend's invitation (the link's 8-character id
 * then its 16-character secret), 16 for an admission code. The length tells
 * them apart.
 */
sealed interface Invitation {
    /** A friend's invitation; the secret (10 bytes) never reaches the server, only auth derived from it. */
    class Invite(val id: String, val secret: ByteArray) : Invitation {
        val proof: SignUpProof get() = SignUpProof.Invite(id, E2EE.inviteKeys(secret).auth)
    }

    /** A single-use admission code; the server checks it when the sign-up is made. */
    class Admission(val code: String) : Invitation {
        val proof: SignUpProof get() = SignUpProof.Admission(code)
    }

    /** An invite as checked: its statement verifies under the key it names, and the link's MAC covers that key. */
    class Checked(val invite: Invite, val inviter: UUID, val inviterIdentityPk: ByteArray, val expiresAt: Instant)

    enum class Failure { NOT_AUTHENTIC, EXPIRED }

    class Error(val failure: Failure) : Exception(failure.name.lowercase())

    companion object {
        const val INVITE_LENGTH = 24
        const val ADMISSION_LENGTH = 16
        private const val ID_LENGTH = 8

        /** Reads a typed code (already normalised to Crockford characters); null for any other length or a character outside the alphabet. */
        fun parse(code: String): Invitation? {
            val cleaned = RecoveryCode.normalise(code)
            if (cleaned.any { it !in RecoveryCode.ALPHABET }) return null
            return when (cleaned.length) {
                INVITE_LENGTH -> {
                    val id = cleaned.take(ID_LENGTH)
                    val secret = Crockford.decode(cleaned.drop(ID_LENGTH))?.takeIf { it.size == 10 } ?: return null
                    Invite(id, secret)
                }
                ADMISSION_LENGTH -> Admission(cleaned)
                else -> null
            }
        }

        /**
         * Fetches the invite and checks it as iOS's Social.check does: the
         * statement is signed by the key it names and is for this id, and the
         * MAC under the link's pin covers that key, so the server (which knows
         * only auth) cannot have swapped it. An unknown, revoked or expired
         * invite is the API's NotFound.
         */
        fun check(api: Api, invite: Invite, now: Instant = Instant.now()): Checked {
            val record = api.invite(invite.id)
            val st = Statements.parseInvite(record.payload)
            if (st == null || st.id != invite.id || st.inviterIdentityPk.size != 32 ||
                !E2EE.verifyStatement(StatementTypes.INVITE, record.payload, record.signature, st.inviterIdentityPk)
            ) throw Error(Failure.NOT_AUTHENTIC)
            val expected = E2EE.inviteMAC(E2EE.inviteKeys(invite.secret).pin, st.inviterIdentityPk)
            if (!E2EE.equalTags(expected, record.mac)) throw Error(Failure.NOT_AUTHENTIC)
            if (!st.expiresAt.isAfter(now)) throw Error(Failure.EXPIRED)
            return Checked(invite, st.inviter, st.inviterIdentityPk, st.expiresAt)
        }
    }
}
