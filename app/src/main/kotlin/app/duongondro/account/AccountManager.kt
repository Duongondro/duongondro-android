package app.duongondro.account

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import app.duongondro.BuildConfig
import app.duongondro.core.api.Api
import app.duongondro.core.api.ApiError
import app.duongondro.core.api.Profile
import app.duongondro.core.api.SignInResult
import app.duongondro.core.api.SignedStatementDto
import app.duongondro.core.api.SignUpProof
import app.duongondro.core.crypto.StatementTypes
import app.duongondro.core.sync.Account
import app.duongondro.core.sync.AccountKeys
import app.duongondro.core.sync.Erased
import app.duongondro.core.sync.Invitation
import app.duongondro.core.sync.SignedStatement
import app.duongondro.core.sync.Statements
import app.duongondro.core.sync.SyncEngine
import app.duongondro.keys.KeystoreDeviceKeys
import app.duongondro.keys.SecretFiles
import app.duongondro.model.SqliteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

enum class Gender(val wire: String) { Male("male"), Female("female"), NonBinary("nonbinary") }

/** Where this phone stands with the server. */
enum class AccountStatus {
    /** No account: local mode, no network calls at all. */
    NONE,
    /** Signed in, but this phone holds no keys yet: set up, or restore. */
    NEEDS_KEYS,
    READY,
    /** The server ended the session (removed elsewhere): keys and data stay, sign in again to sync. */
    SIGNED_OUT,
}

data class AccountState(
    val status: AccountStatus = AccountStatus.NONE,
    val email: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val gender: Gender? = null,
    /** A recovery code was made but not yet checked or saved to the password manager. */
    val recoveryUnconfirmed: Boolean = false,
    /** A recovery code was made but its boxes are not all on the server yet. */
    val recoveryPending: Boolean = false,
    val syncing: Boolean = false,
    val lastSync: Instant? = null,
    /** The last sync did not finish: offline, or the server away. */
    val offline: Boolean = false,
    val refused: Int = 0,
    val unreadable: Int = 0,
) {
    /**
     * Signing out here forgets the keys, so it is allowed only when they can
     * come back: keys set up, the recovery code stored on the server and confirmed.
     */
    val canSignOut: Boolean get() = status == AccountStatus.READY && !recoveryUnconfirmed && !recoveryPending
}

sealed interface InviteCheck {
    data object Valid : InviteCheck
    /** Not a code of either length, or the server knows no such live invite. */
    data object Unknown : InviteCheck
    /** The invite does not verify: its signature or the link's MAC is wrong. */
    data object NotAuthentic : InviteCheck
}

enum class LinkRequest { Sent, UnknownInvite, TooMany }

sealed interface Redeem {
    /** Signed in; [created] when the link or passkey made the account. */
    data class SignedIn(val created: Boolean) : Redeem
    /** A wrong, expired, used or replaced code: the server does not say which. */
    data object Wrong : Redeem
    /** No account uses this address, and the link carried no invitation. */
    data object NoAccount : Redeem
    /** The invitation or admission code stopped being valid since the link was sent. */
    data object InviteGone : Redeem
    data object TooMany : Redeem
    /** Signed in, but this phone holds another account's practice: erase it, or cancel. */
    data object OtherAccountData : Redeem
}

enum class ProfileResult { Saved, UsernameTaken }

enum class PasskeyResult { Saved, Cancelled, UsernameTaken, InviteGone, OtherAccountData }

/**
 * The account side of the app, as iOS's AccountModel: signing up and in,
 * setting up keys or restoring them from the recovery code, and syncing.
 * Practice data never waits for it: the app works fully offline, and sync
 * catches up. Without an account it makes no network call at all.
 */
class AccountManager(
    context: Context,
    private val db: SqliteStore,
    private val scope: CoroutineScope,
    baseUrl: String = BuildConfig.API_BASE_URL,
) {
    private val app = context.applicationContext
    private val secrets = SecretFiles(app)
    private val deviceKeys = KeystoreDeviceKeys(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val anonymous = Api(baseUrl)
    private val passkeys = Passkeys(app)

    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private var account: Account? = null
    private val syncLock = Mutex()
    private var pendingSync: Job? = null

    /** The invitation a sign-up started from, checked; kept until the account exists and has keys. */
    private var invitation: Invitation? = null
    private var checkedInvite: Invitation.Checked? = null

    /** Reads the stored session; call once the database is open. */
    suspend fun load() = withContext(Dispatchers.IO) {
        val token = secrets.read(TOKEN)?.decodeToString()
        val synced = db.syncState() != null
        _state.value = profileState().copy(
            status = when {
                token != null -> { open(token); if (account!!.hasKeys()) AccountStatus.READY else AccountStatus.NEEDS_KEYS }
                synced -> AccountStatus.SIGNED_OUT
                else -> AccountStatus.NONE
            },
        )
        refreshPending()
    }

    private fun refreshPending() {
        val pending = runCatching { secrets.read(PENDING_RECOVERY) != null }.getOrDefault(true)
        _state.update { it.copy(recoveryPending = pending) }
    }

    private fun open(token: String) {
        account = Account(anonymous.withToken(token), secrets, deviceKeys, db)
    }

    private fun profileState() = AccountState(
        email = prefs.getString(EMAIL, null),
        username = prefs.getString(USERNAME, null),
        displayName = prefs.getString(DISPLAY_NAME, null),
        gender = prefs.getString(GENDER, null)?.let { g -> Gender.entries.firstOrNull { it.wire == g } },
        recoveryUnconfirmed = prefs.getBoolean(RECOVERY_UNCONFIRMED, false),
        lastSync = prefs.getLong(LAST_SYNC, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
    )

    private fun saveProfile(email: String? = null, username: String? = null, displayName: String? = null, gender: Gender? = null) {
        prefs.edit {
            email?.let { putString(EMAIL, it) }
            username?.let { putString(USERNAME, it) }
            displayName?.let { putString(DISPLAY_NAME, it) }
            gender?.let { putString(GENDER, it.wire) }
        }
        _state.update { profileState().copy(status = it.status, syncing = it.syncing, offline = it.offline, refused = it.refused, unreadable = it.unreadable) }
    }

    /** A sign-in held back because this phone holds another account's practice; see [eraseOtherAccountAndContinue]. */
    private var heldSignIn: SignInResult? = null

    /**
     * Keeps the session, unless this phone holds practice from another account
     * (the one it syncs with, or the last one signed out here): then nothing is
     * kept or uploaded until the person chooses, and this returns false.
     */
    private suspend fun signedIn(result: SignInResult): Boolean = withContext(Dispatchers.IO) {
        val other = db.syncState()?.user ?: prefs.getString(LAST_USER, null)?.let(java.util.UUID::fromString)
        val holdsPractice = db.snapshot.value.let { it.sessions.isNotEmpty() || it.practices.isNotEmpty() }
        if (other != null && other != result.userId && holdsPractice) {
            heldSignIn = result
            return@withContext false
        }
        keep(result)
        true
    }

    private suspend fun keep(result: SignInResult) {
        secrets.write(TOKEN, result.token.toByteArray())
        open(result.token)
        _state.update { it.copy(status = if (account!!.hasKeys()) AccountStatus.READY else AccountStatus.NEEDS_KEYS) }
    }

    /**
     * "Erase it here and continue": the other account's practice and keys leave
     * this phone (its server copy stays), then the held sign-in goes on.
     * Returns whether the held sign-in made its account.
     */
    suspend fun eraseOtherAccountAndContinue(): Boolean = withContext(Dispatchers.IO) {
        val result = heldSignIn ?: throw IllegalStateException("no sign-in is waiting")
        heldSignIn = null
        db.erasePractice()
        forgetSecrets()
        prefs.edit { remove(LAST_USER) }
        keep(result)
        result.created
    }

    /** "Cancel": the held session ends on the server, and nothing was uploaded. */
    suspend fun abandonHeldSignIn() = withContext(Dispatchers.IO) {
        val result = heldSignIn ?: return@withContext
        heldSignIn = null
        runCatching { anonymous.withToken(result.token).signOut() }
    }

    private fun requireAccount() = account ?: throw IllegalStateException("not signed in")

    // Invitation

    /** Checks a typed or opened code: an invite against the server (signature, then the link's MAC), an admission code by its shape only. */
    suspend fun checkInvite(code: String): InviteCheck = withContext(Dispatchers.IO) {
        invitation = null
        checkedInvite = null
        when (val parsed = Invitation.parse(code)) {
            null -> InviteCheck.Unknown
            is Invitation.Admission -> { invitation = parsed; InviteCheck.Valid }
            is Invitation.Invite -> try {
                checkedInvite = Invitation.check(anonymous, parsed)
                invitation = parsed
                InviteCheck.Valid
            } catch (_: ApiError.NotFound) {
                InviteCheck.Unknown
            } catch (e: Invitation.Error) {
                if (e.failure == Invitation.Failure.EXPIRED) InviteCheck.Unknown else InviteCheck.NotAuthentic
            }
        }
    }

    private val proof: SignUpProof?
        get() = when (val i = invitation) {
            is Invitation.Invite -> i.proof
            is Invitation.Admission -> i.proof
            null -> null
        }

    /** Forgets an invitation from an abandoned sign-up, so a later sign-in does not carry it. */
    fun clearInvitation() {
        invitation = null
        checkedInvite = null
    }

    // Email

    /**
     * When this phone last asked for a magic link: a link's token is redeemed
     * only within its 15 minutes, so a link someone else sent cannot sign this
     * phone in to their account (and upload its practice there).
     */
    @Volatile private var linkRequestedAt: Instant? = null

    /** Whether an opened magic link may be redeemed now: this phone asked for one in the last 15 minutes. */
    fun expectsLink(now: Instant = Instant.now()): Boolean =
        linkRequestedAt?.let { !now.isBefore(it) && java.time.Duration.between(it, now) <= LINK_LIFETIME } == true

    suspend fun requestMagicLink(email: String, signUp: Boolean): LinkRequest = withContext(Dispatchers.IO) {
        try {
            anonymous.requestMagicLink(email, if (signUp) proof else null)
            linkRequestedAt = Instant.now()
            saveProfile(email = email)
            LinkRequest.Sent
        } catch (_: ApiError.NotFound) {
            LinkRequest.UnknownInvite
        } catch (_: ApiError.TooManyRequests) {
            LinkRequest.TooMany
        }
    }

    suspend fun redeemCode(email: String, code: String): Redeem = redeem { anonymous.redeemMagicLinkCode(email, code) }

    /** Redeems an opened link's token, only while [expectsLink]; otherwise the token is dropped unused and null returned. */
    suspend fun redeemLink(token: String): Redeem? {
        if (!expectsLink()) return null
        return redeem { anonymous.redeemMagicLink(token) }.also { if (it is Redeem.SignedIn) linkRequestedAt = null }
    }

    private suspend fun redeem(call: () -> SignInResult): Redeem = withContext(Dispatchers.IO) {
        try {
            val result = call()
            if (signedIn(result)) Redeem.SignedIn(result.created) else Redeem.OtherAccountData
        } catch (e: ApiError.Status) {
            if (e.code == 400) Redeem.Wrong else throw e
        } catch (_: ApiError.Forbidden) {
            Redeem.NoAccount
        } catch (_: ApiError.NotFound) {
            Redeem.InviteGone
        } catch (_: ApiError.TooManyRequests) {
            Redeem.TooMany
        }
    }

    // Profile

    /** PATCH /api/me after a sign-up by email; the name is required, username and gender optional. */
    suspend fun setProfile(name: String, username: String?, gender: Gender?): ProfileResult = withContext(Dispatchers.IO) {
        try {
            requireAccount().api.updateMe(displayName = name, username = username?.lowercase(), gender = gender?.wire)
            saveProfile(username = username?.lowercase(), displayName = name, gender = gender)
            ProfileResult.Saved
        } catch (_: ApiError.Conflict) {
            ProfileResult.UsernameTaken
        }
    }

    suspend fun setDisplayName(name: String) = withContext(Dispatchers.IO) {
        // The server counts code points, at most 64.
        val trimmed = name.trim().let { it.substring(0, it.offsetByCodePoints(0, minOf(64, it.codePointCount(0, it.length)))) }
        requireAccount().api.updateMe(displayName = trimmed)
        saveProfile(displayName = trimmed)
    }

    // Passkeys

    /**
     * Without a session, makes the account with a passkey and the profile (the
     * no-email path); signed in, adds a passkey to the account. [activity] must
     * be the Activity, which the system sheet attaches to.
     */
    suspend fun createPasskey(activity: Context, profile: Profile?): PasskeyResult {
        val current = account
        if (current == null) {
            val p = proof ?: throw IllegalStateException("a passkey sign-up needs an invitation")
            val ceremony = try {
                withContext(Dispatchers.IO) { anonymous.beginPasskeySignUp(p, profile ?: Profile()) }
            } catch (_: ApiError.Conflict) {
                return PasskeyResult.UsernameTaken
            } catch (_: ApiError.NotFound) {
                return PasskeyResult.InviteGone
            }
            val credential = passkeys.create(activity, ceremony.publicKeyJson) ?: return PasskeyResult.Cancelled
            val result = try {
                withContext(Dispatchers.IO) { anonymous.finishPasskey(ceremony.sessionId, credential) }
            } catch (_: ApiError.Conflict) {
                return PasskeyResult.UsernameTaken
            } catch (_: ApiError.NotFound) {
                return PasskeyResult.InviteGone
            }
            if (!signedIn(result)) return PasskeyResult.OtherAccountData
            profile?.let { saveProfile(username = it.username, displayName = it.displayName, gender = Gender.entries.firstOrNull { g -> g.wire == it.gender }) }
        } else {
            val ceremony = withContext(Dispatchers.IO) { current.api.beginPasskeyAdd() }
            val credential = passkeys.create(activity, ceremony.publicKeyJson) ?: return PasskeyResult.Cancelled
            withContext(Dispatchers.IO) { current.api.finishPasskeyAdd(ceremony.sessionId, credential) }
        }
        return PasskeyResult.Saved
    }

    /** Offers this app's passkeys; null when there is none or the person backed out. */
    suspend fun signInWithPasskey(activity: Context): Redeem? {
        val ceremony = withContext(Dispatchers.IO) { anonymous.beginPasskeySignIn() }
        val credential = passkeys.get(activity, ceremony.publicKeyJson) ?: return null
        return redeem { anonymous.finishPasskey(ceremony.sessionId, credential) }
    }

    // Keys

    /** Whether the signed-in account needs setting up here, restoring, or nothing. */
    suspend fun standing(): Account.Standing = withContext(Dispatchers.IO) {
        val standing = requireAccount().standing()
        if (standing == Account.Standing.READY) _state.update { it.copy(status = AccountStatus.READY) }
        // The profile as the server has it, for a sign-in on a new phone.
        runCatching { requireAccount().api.me() }.getOrNull()?.let { me ->
            saveProfile(username = me.username, displayName = me.displayName.ifEmpty { null },
                gender = Gender.entries.firstOrNull { it.wire == me.gender })
        }
        standing
    }

    /** One key operation at a time: a set-up, a restore or a new code. */
    private val keyWork = Mutex()

    private val _shownCode = MutableStateFlow<String?>(null)
    /**
     * The recovery code being shown, until it is confirmed or saved: a screen
     * recreated on rotation gets this one back rather than making another.
     */
    val shownCode: StateFlow<String?> = _shownCode.asStateFlow()

    /**
     * Runs key work in this manager's scope, one at a time: a screen that goes
     * away (rotation) cancels only its wait, never the work half done.
     */
    private suspend fun <T> keyed(block: suspend () -> T): T =
        scope.async(Dispatchers.IO) { keyWork.withLock { block() } }.await()

    /**
     * Sets up keys on this first phone (or finishes an interrupted set-up, with
     * the same code); returns the recovery code to show once.
     */
    suspend fun setUpKeys(): String = keyed {
        _shownCode.value ?: run {
            val a = requireAccount()
            val code = try {
                // newRecoveryCode finishes a code left pending, rather than replacing it.
                if (a.hasKeys()) a.newRecoveryCode() else a.setUpFirstDevice()
            } finally {
                refreshPending()
            }
            prefs.edit { putBoolean(RECOVERY_UNCONFIRMED, true) }
            _shownCode.value = code
            _state.update { it.copy(status = AccountStatus.READY, recoveryUnconfirmed = true) }
            afterKeys(a)
            code
        }
    }

    /** False when the code does not open this account's recovery boxes. */
    suspend fun restore(code: String): Boolean = keyed {
        val a = requireAccount()
        val opened = try {
            if (!a.hasKeys()) a.restore(code)
            true
        } catch (e: AccountKeys.Error) {
            if (e.failure != AccountKeys.Failure.BAD_RECOVERY_CODE) throw e
            false
        }
        if (opened) {
            _state.update { it.copy(status = AccountStatus.READY) }
            afterKeys(a)
        }
        opened
    }

    /** A new recovery code, replacing the old one (which stops working); the one being shown, if any. */
    suspend fun newRecoveryCode(): String = keyed {
        _shownCode.value ?: run {
            val code = try { requireAccount().newRecoveryCode() } finally { refreshPending() }
            prefs.edit { putBoolean(RECOVERY_UNCONFIRMED, true) }
            _shownCode.value = code
            _state.update { it.copy(recoveryUnconfirmed = true) }
            code
        }
    }

    /** The code was written down and checked, or saved to the password manager. */
    fun confirmRecoveryCode() {
        _shownCode.value = null
        prefs.edit { putBoolean(RECOVERY_UNCONFIRMED, false) }
        _state.update { it.copy(recoveryUnconfirmed = false) }
    }

    /** Offers the code to Google Password Manager, named after the account; false when declined. */
    suspend fun saveRecoveryCode(activity: Context, code: String): Boolean {
        val s = _state.value
        val id = s.username ?: s.email ?: "Duongöndro recovery"
        return passkeys.savePassword(activity, id, code).also { if (it) confirmRecoveryCode() }
    }

    /** The fallback report, then the invitation's friendship, once keys exist. Failures here never fail the set-up. */
    private suspend fun afterKeys(a: Account) {
        a.deviceKeyFallback?.let { reason ->
            runCatching {
                a.api.reportClientError("android keystore refused a device key; weaker tier used", BuildConfig.VERSION_NAME,
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", mapOf("reason" to reason.take(256)))
            }
        }
        val checked = checkedInvite ?: return
        runCatching {
            val me = a.api.me()
            if (checked.inviter == me.id) return@runCatching
            val identity = a.identity()
            val payload = Statements.acceptance(checked.invite.id, me.id, identity.publicKey)
            val acceptance = SignedStatement.sign(StatementTypes.ACCEPTANCE, payload, identity)
            a.api.redeemInvite(checked.invite.id, checked.invite.proof.let { (it as SignUpProof.Invite).auth },
                SignedStatementDto(acceptance.payload, acceptance.signature))
            // The key the invite proved (MAC under the link's pin), not the server's word.
            val name = runCatching { a.api.friends().firstOrNull { it.userId == checked.inviter }?.displayName }.getOrNull().orEmpty()
            db.repin(checked.inviter, checked.inviterIdentityPk, name, Instant.now())
        }.onFailure { Log.w(TAG, "redeeming the invitation failed: ${it.javaClass.simpleName}") }
        clearInvitation()
    }

    // Sync

    /** Syncs a moment after the last change, so a burst of malas is one round trip. */
    fun scheduleSync(delayMs: Long = 2_000) {
        if (_state.value.status != AccountStatus.READY) return
        pendingSync?.cancel()
        pendingSync = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    suspend fun syncNow() {
        val a = account ?: return
        if (_state.value.status != AccountStatus.READY) return
        if (!syncLock.tryLock()) return
        // A sign-out or "Delete everything" that ran meanwhile ended this account
        // here: nothing this sync learns may touch the state or the token after it.
        val generation = db.generation
        val current = { account === a && db.generation == generation }
        try {
            _state.update { it.copy(syncing = true) }
            val result = withContext(Dispatchers.IO) { SyncEngine(a).sync() }
            if (current()) {
                val now = Instant.now()
                prefs.edit { putLong(LAST_SYNC, now.toEpochMilli()) }
                _state.update { it.copy(lastSync = now, offline = false, refused = result.refused, unreadable = result.unreadable) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError.Unauthorized) {
            // The session ended (removed elsewhere): keys stay, sign in again.
            if (current()) {
                withContext(Dispatchers.IO) { secrets.delete(TOKEN) }
                account = null
                _state.update { it.copy(status = AccountStatus.SIGNED_OUT) }
            }
        } catch (_: Erased) {
            // "Delete everything" or a sign-out ran meanwhile: nothing to keep.
        } catch (e: IOException) {
            _state.update { it.copy(offline = true) }
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.javaClass.simpleName}: ${e.message}")
            _state.update { it.copy(offline = true) }
        } finally {
            _state.update { it.copy(syncing = false) }
            syncLock.unlock()
        }
    }

    // Leaving

    /**
     * Ends the session here and forgets the account on this phone: the token,
     * the account's keys and the sync state. The practice data stays, as in
     * local mode. The device key stays too; it holds nothing without its wraps.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        check(_state.value.canSignOut) { "the recovery code is not finished; signing out would lose the keys" }
        pendingSync?.cancel()
        // After any sync in flight, so it cannot write the account back.
        syncLock.withLock { signOutLocked() }
    }

    private suspend fun signOutLocked() {
        val user = db.syncState()?.user
        account?.let { a -> runCatching { a.api.signOut() } }
        account = null
        db.clearSyncState()
        forgetSecrets()
        // Remembered, so signing in to another account asks before mixing this practice into it.
        prefs.edit {
            clear()
            user?.let { putString(LAST_USER, it.toString()) }
        }
        _state.value = AccountState()
    }

    /**
     * The server half of "Delete everything": it must succeed before the phone
     * wipes itself, or the server would keep data the person believes gone.
     */
    suspend fun deleteOnServer() = withContext(Dispatchers.IO) {
        pendingSync?.cancel()
        when (_state.value.status) {
            AccountStatus.NONE -> Unit
            AccountStatus.SIGNED_OUT -> throw SignInToDelete()
            AccountStatus.NEEDS_KEYS, AccountStatus.READY -> requireAccount().api.deleteMe()
        }
    }

    class SignInToDelete : Exception("signed out")

    /** After the local purge: back to local mode, with nothing of the account left. */
    fun forget() {
        pendingSync?.cancel()
        _shownCode.value = null
        account = null
        clearInvitation()
        runCatching { forgetSecrets() }
        prefs.edit { clear() }
        _state.value = AccountState()
    }

    private fun forgetSecrets() {
        SecretFiles.folder(app).deleteRecursively()
    }

    private companion object {
        const val TAG = "Account"
        const val TOKEN = "session-token"
        const val PREFS = "account"
        const val EMAIL = "email"
        const val USERNAME = "username"
        const val DISPLAY_NAME = "displayName"
        const val GENDER = "gender"
        const val RECOVERY_UNCONFIRMED = "recoveryUnconfirmed"
        const val PENDING_RECOVERY = app.duongondro.core.sync.SecretName.PENDING_RECOVERY
        const val LAST_SYNC = "lastSync"
        const val LAST_USER = "lastUser"
        val LINK_LIFETIME: java.time.Duration = java.time.Duration.ofMinutes(15)
    }
}
