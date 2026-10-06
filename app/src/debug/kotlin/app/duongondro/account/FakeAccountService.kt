package app.duongondro.account

import kotlinx.coroutines.delay

/**
 * FAKE, DEBUG BUILDS ONLY. Talks to no server and holds no keys: it makes the
 * onboarding flow walkable on an emulator. It lives in src/debug so a release
 * build cannot link it. The network and crypto implementation replaces it.
 *
 * Rules to exercise the error paths: an invitation code starting with 0 is
 * unknown; the sign-in code 00000000 is wrong and 11111111 has expired;
 * every other code is accepted; the passkey sheet is never cancelled.
 */
class FakeAccountService : AccountService {
    private suspend fun latency() = delay(350)

    override suspend fun checkInvite(code: String): InviteCheck {
        latency()
        return if (code.startsWith("0")) InviteCheck.Unknown else InviteCheck.Valid
    }

    override suspend fun requestMagicLink(email: String) = latency()

    override suspend fun redeemCode(email: String, code: String): RedeemResult {
        latency()
        return when (code) {
            "00000000" -> RedeemResult.Wrong
            "11111111" -> RedeemResult.Expired
            else -> RedeemResult.Ok
        }
    }

    override suspend fun setProfile(name: String, username: String?, gender: Gender?) = latency()

    override suspend fun createPasskey(): Passkey {
        latency()
        return Passkey.Saved
    }

    override suspend fun signInWithPasskey(): Boolean {
        latency()
        return true
    }

    /** Not random: a stand-in that looks like the real thing (26 Crockford characters). */
    override suspend fun createRecoveryCode(): String {
        latency()
        return "7K2MQ9XAH4N8R2CJ6TPW3ZQF8D"
    }

    override suspend fun saveRecoveryCode(code: String): Boolean {
        latency()
        return true
    }
}
