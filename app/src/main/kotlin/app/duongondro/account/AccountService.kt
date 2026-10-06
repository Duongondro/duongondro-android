package app.duongondro.account

/** Everything the onboarding flow asks of the server or of the keys, behind one seam. */
interface AccountService {
    /** Whether [code], an invitation code (24 Crockford characters, see [Crockford]), is one the server issued. */
    suspend fun checkInvite(code: String): InviteCheck

    /** Emails a sign-in link and an eight-character code to [email]; the same request signs up and signs in. */
    suspend fun requestMagicLink(email: String)

    /** Exchanges the eight-character [code] (or the link's) for a session. */
    suspend fun redeemCode(email: String, code: String): RedeemResult

    /** Saves the profile after sign-up: [name] is required, [username] and [gender] are optional. */
    suspend fun setProfile(name: String, username: String?, gender: Gender?)

    /** Creates a passkey for the new account; [Passkey.Cancelled] when the person backs out of the system sheet. */
    suspend fun createPasskey(): Passkey

    /** Offers the passkeys saved for this app and signs in with the chosen one; false when none or cancelled. */
    suspend fun signInWithPasskey(): Boolean

    /** A fresh 26-character Crockford recovery code, shown once. */
    suspend fun createRecoveryCode(): String

    /** Offers [code] to the system password manager (Google Password Manager); false when declined. */
    suspend fun saveRecoveryCode(code: String): Boolean
}

enum class Gender { Male, Female, NonBinary }

sealed interface InviteCheck {
    data object Valid : InviteCheck
    data object Unknown : InviteCheck
}

enum class RedeemResult { Ok, Wrong, Expired }

enum class Passkey { Saved, Cancelled }

/** The server or the network could not be reached. */
class AccountUnavailable(message: String) : Exception(message)

/** Used where no network implementation is linked yet: every call fails, so the flow says so instead of faking success. */
object UnavailableAccountService : AccountService {
    private fun fail(): Nothing = throw AccountUnavailable("accounts are not available in this build")
    override suspend fun checkInvite(code: String): InviteCheck = fail()
    override suspend fun requestMagicLink(email: String) = fail()
    override suspend fun redeemCode(email: String, code: String): RedeemResult = fail()
    override suspend fun setProfile(name: String, username: String?, gender: Gender?) = fail()
    override suspend fun createPasskey(): Passkey = fail()
    override suspend fun signInWithPasskey(): Boolean = fail()
    override suspend fun createRecoveryCode(): String = fail()
    override suspend fun saveRecoveryCode(code: String): Boolean = fail()
}
