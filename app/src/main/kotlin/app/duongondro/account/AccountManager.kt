package app.duongondro.account

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import app.duongondro.BuildConfig
import app.duongondro.core.api.Api
import app.duongondro.core.api.ApiError
import app.duongondro.core.api.Prf
import app.duongondro.core.api.Profile
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.api.SignInResult
import app.duongondro.core.api.SignUp
import app.duongondro.core.api.SignUpProof
import app.duongondro.core.sync.Account
import app.duongondro.core.sync.AccountKeys
import app.duongondro.core.sync.Erased
import app.duongondro.core.sync.Invitation
import app.duongondro.core.sync.Invites
import app.duongondro.core.sync.RecoveryCodeOnShow
import app.duongondro.core.sync.MadeInvite
import app.duongondro.core.sync.Social
import app.duongondro.core.sync.SyncEngine
import app.duongondro.core.sync.SyncTime
import app.duongondro.data.DataExport
import app.duongondro.data.Exports
import app.duongondro.keys.KeystoreDeviceKeys
import app.duongondro.keys.SecretFiles
import app.duongondro.model.SqliteStore
import app.duongondro.push.Push
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
import java.util.UUID

/** The session token among the sealed secrets; the push token upload reads it too. */
internal const val SESSION_TOKEN = "session-token"

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

/** An invitation opened by an account that already has its keys, checked before it is accepted. */
sealed interface AcceptCheck {
    class Ready(val checked: Invitation.Checked) : AcceptCheck
    /** This account made it. */
    data object Own : AcceptCheck
    /** The inviter is a friend already; [name] when the server has one. */
    class AlreadyFriends(val name: String?) : AcceptCheck
    /** Not a code, or unknown, revoked or expired. */
    data object Gone : AcceptCheck
    /** Its signature or the link's MAC is wrong. */
    data object NotAuthentic : AcceptCheck
}

enum class LinkRequest {
    Sent, UnknownInvite, TooMany,
    /** A sign-up without an invitation, and the server makes no account without one (open sign-up off). */
    InvitationNeeded,
    /** Too many accounts made without an invitation from this network in the last hour; a code still works. */
    TooManySignUps,
}

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
    /** The link was a sign-up without an invitation, and sign-up closed since it was sent. */
    data object InvitationNeeded : Redeem
    /** Signed in, but this phone holds another account's practice: erase it, or cancel. */
    data object OtherAccountData : Redeem
}

/** Friends as last fetched, each streak verified against the pinned key; [loaded] once a fetch succeeded. */
data class FriendsState(
    val friends: List<Social.FriendView> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** The last fetch failed (offline, or the server away); what was fetched before stays. */
    val failed: Boolean = false,
    /** Friends poked from this phone, by the UTC day the server counts pokes in. */
    val poked: Map<java.util.UUID, java.time.LocalDate> = emptyMap(),
) {
    fun isPoked(friend: java.util.UUID, now: Instant = Instant.now()): Boolean = poked[friend] == Social.utcDay(now)
}

/** An invitation this account made that still works: not revoked, not expired. */
data class OpenInvite(val id: String, val expiresAt: Instant)

enum class EmailAdd { Sent, NotAnAddress, TooMany }
enum class EmailVerify { Added, Wrong, Taken, TooMany }

enum class PasskeyResult { Saved, Cancelled, Unavailable, UsernameTaken, InviteGone, InvitationNeeded, TooManySignUps, TooMany, OtherAccountData }

/**
 * Settings › Passkeys as last fetched (design decision 25), and whether this
 * phone holds the user key U, which a passkey's box needs.
 */
data class PasskeysState(
    val passkeys: List<app.duongondro.core.api.Passkey> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val holdsUserKey: Boolean = false,
    /** "Not now" on the card, until the app starts again. */
    val offerDismissed: Boolean = false,
) {
    /** A passkey with a box: signing in with it brings the counts to a new phone. */
    val unlocks: Boolean get() = passkeys.any { it.canUnlock }

    /**
     * The retrofit card: this phone holds U and no passkey of the account can
     * unlock yet. Once one can, the card goes, even if another passkey (a
     * security key, one only reachable by QR) never will: a card that cannot
     * be satisfied is no calm moment. Settings › Passkeys still says which unlock.
     */
    val offerUnlock: Boolean get() = loaded && holdsUserKey && !offerDismissed && passkeys.isNotEmpty() && !unlocks
}

/** What the retrofit ceremony gave. */
enum class UnlockSetUp {
    Done, Cancelled,
    /** The passkey gave no PRF output: a new passkey can unlock, the old one keeps signing in. */
    NoOutput,
    /** The passkey that answered is not this account's. */
    OtherAccount,
}

enum class PasskeyRemoval {
    Removed,
    /** Removed, and it could unlock the counts: offer to replace the unlock key (removal is not revocation). */
    RemovedUnlocking,
    LastWay, SignedOut,
}

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
    /** Called once a sign-up's invitation has been redeemed (or tried), so the opened link is not offered again. */
    private val onInvitationUsed: () -> Unit = {},
) {
    private val app = context.applicationContext
    private val secrets = SecretFiles(app)
    private val deviceKeys = KeystoreDeviceKeys(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val anonymous = Api(baseUrl)
    private val credentials = Passkeys(app)

    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private var account: Account? = null
    private var social: Social? = null
    private val syncLock = Mutex()
    private var pendingSync: Job? = null

    /** The invitation a sign-up started from, checked; kept until the account exists and has keys. */
    private var invitation: Invitation? = null
    private var checkedInvite: Invitation.Checked? = null
        set(value) {
            field = value
            _signingUpWithInvite.value = value != null
        }
    private val _signingUpWithInvite = MutableStateFlow(false)
    /** A sign-up holds a checked invitation not yet redeemed: an opened link belongs to it, not to accepting. */
    val signingUpWithInvite: StateFlow<Boolean> = _signingUpWithInvite.asStateFlow()

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
        account = Account(anonymous.withToken(token), secrets, deviceKeys, db).also { social = Social(it, db) }
    }

    private fun profileState() = AccountState(
        email = prefs.getString(EMAIL, null),
        username = prefs.getString(USERNAME, null),
        displayName = prefs.getString(DISPLAY_NAME, null)?.ifEmpty { null },
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
    /** The address the held sign-in redeemed a link for, stored once it goes on. */
    private var heldEmail: String? = null

    /**
     * Keeps the session, unless this phone holds practice from another account
     * (the one it syncs with, or the last one signed out here): then nothing is
     * kept or uploaded until the person chooses, and this returns false.
     */
    private suspend fun signedIn(result: SignInResult): Boolean = withContext(Dispatchers.IO) {
        // The account this phone's keys and sync state belong to, if any: they can
        // outlive a session the server ended (a 401 deletes only the token).
        val owner = db.syncState()?.user
            ?: runCatching { secrets.read(SET_UP_USER)?.decodeToString()?.let(java.util.UUID::fromString) }.getOrNull()
        val other = owner ?: prefs.getString(LAST_USER, null)?.let(java.util.UUID::fromString)
        val holdsPractice = db.snapshot.value.let { it.sessions.isNotEmpty() || it.practices.isNotEmpty() }
        if (other != null && other != result.userId && holdsPractice) {
            heldSignIn = result
            return@withContext false
        }
        // Another account's keys, sync state and device key never carry over to
        // this one; with no practice here, there is nothing to ask about.
        if (owner != null && owner != result.userId) {
            db.clearSyncState()
            forgetOtherAccount()
        }
        keep(result)
        true
    }

    /**
     * Forgets what this phone kept for the account it is switching away from:
     * its keys and device key, its profile and settings in the preferences, a
     * recovery code on show and a pending one (the sign-in's own address is
     * stored after, once the sign-in is kept). The sign-in's own invitation, if any, stays: it is the new
     * account's, and redeeming it waits for the new keys.
     */
    private fun forgetOtherAccount() {
        _friends.value = FriendsState()
        // Its session is gone, so the server keeps its token, but FCM stops delivering to a deleted one.
        Push.forget(app)
        forgetSecrets()
        deviceKeys.delete()
        prefs.edit { clear() }
        onShow.forget()
        _state.update { profileState().copy(status = it.status) }
        refreshPending()
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
        forgetOtherAccount()
        keep(result)
        heldEmail?.let { saveProfile(email = it) }
        heldEmail = null
        markSignUp(result.created)
        result.created
    }

    /**
     * A sign-up made its account on this phone and has not finished the
     * optional steps: a relaunch resumes them (at the name) rather than starting over.
     */
    val signUpUnfinished: Boolean
        get() {
            if (!prefs.getBoolean(SIGN_UP_UNFINISHED, false)) return false
            // Only while the session lives: after a 401 (or none) there is nothing to resume.
            val live = _state.value.status.let { it == AccountStatus.NEEDS_KEYS || it == AccountStatus.READY }
            if (!live) signUpFinished()
            return live
        }

    private fun markSignUp(created: Boolean) {
        if (created) prefs.edit { putBoolean(SIGN_UP_UNFINISHED, true) }
    }

    /** The sign-up's last step is done. */
    fun signUpFinished() = prefs.edit { remove(SIGN_UP_UNFINISHED) }

    /** "Cancel": the held session ends on the server, and nothing was uploaded. */
    suspend fun abandonHeldSignIn() = withContext(Dispatchers.IO) {
        heldPrf = null
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

    /** A sign-up with the invitation held, if any; consent (GDPR Art. 9) is given before any sign-up call. */
    private fun signUpWith(consent: Boolean): SignUp {
        check(consent) { "a sign-up needs consent first" }
        return SignUp(consent = true, proof = proof)
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

    /** Whether the link asked for last makes an account: its 403 then means sign-up closed, not "no account". */
    @Volatile private var linkSignsUp = false

    /** A sign-up ([signUp]) sends the flag, the invitation if any, and [consent], which it needs. */
    suspend fun requestMagicLink(email: String, signUp: Boolean, consent: Boolean = false): LinkRequest = withContext(Dispatchers.IO) {
        try {
            anonymous.requestMagicLink(email, if (signUp) signUpWith(consent) else null)
            linkRequestedAt = Instant.now()
            linkSignsUp = signUp
            // Stored only once a link or code redeems: asking proves nothing.
            requestedEmail = email
            LinkRequest.Sent
        } catch (_: ApiError.NotFound) {
            LinkRequest.UnknownInvite
        } catch (_: ApiError.Forbidden) {
            LinkRequest.InvitationNeeded
        } catch (e: ApiError.TooManyRequests) {
            if (e.signUps) LinkRequest.TooManySignUps else LinkRequest.TooMany
        }
    }

    /** The address the last magic link was asked for. */
    @Volatile private var requestedEmail: String? = null

    suspend fun redeemCode(email: String, code: String): Redeem {
        // A sign-in by e-mail: no passkey's output belongs to it.
        heldPrf = null
        return redeem(linkSignsUp, email) { anonymous.redeemMagicLinkCode(email, code) }
    }

    /** Redeems an opened link's token, only while [expectsLink]; otherwise the token is dropped unused and null returned. */
    suspend fun redeemLink(token: String): Redeem? {
        if (!expectsLink()) return null
        heldPrf = null
        return redeem(linkSignsUp, requestedEmail) { anonymous.redeemMagicLink(token) }.also { if (it is Redeem.SignedIn) linkRequestedAt = null }
    }

    private suspend fun redeem(signUp: Boolean, email: String? = null, call: () -> SignInResult): Redeem = withContext(Dispatchers.IO) {
        try {
            val result = call()
            if (signedIn(result)) {
                email?.let { saveProfile(email = it.trim().lowercase()) }
                Redeem.SignedIn(result.created).also { markSignUp(result.created) }
            } else {
                heldEmail = email?.trim()?.lowercase()
                Redeem.OtherAccountData
            }
        } catch (e: ApiError.Status) {
            if (e.code == 400) Redeem.Wrong else throw e
        } catch (_: ApiError.Forbidden) {
            if (signUp) Redeem.InvitationNeeded else Redeem.NoAccount
        } catch (_: ApiError.NotFound) {
            Redeem.InviteGone
        } catch (_: ApiError.TooManyRequests) {
            Redeem.TooMany
        }
    }

    // Profile

    /**
     * The name the server gave a new account (a random one, or none), for the
     * sign-up's name step to show; empty when there is none.
     */
    suspend fun serverDisplayName(): String = withContext(Dispatchers.IO) {
        val me = requireAccount().api.me()
        // The server's word on the address, so a stale one from an earlier account never skips the e-mail step.
        if (me.email == null) prefs.edit { remove(EMAIL) }
        saveProfile(displayName = me.displayName, email = me.email)
        me.displayName
    }

    /**
     * PATCH /api/me during sign-up: only what was given changes (null leaves it);
     * an empty name clears it, which friends see as "A friend".
     */
    suspend fun setProfile(name: String?, gender: Gender?) = withContext(Dispatchers.IO) {
        if (name == null && gender == null) return@withContext
        val trimmed = name?.let(::trimName)
        requireAccount().api.updateMe(displayName = trimmed, gender = gender?.wire)
        saveProfile(displayName = trimmed, gender = gender)
    }

    /** The server counts code points, at most 64. */
    private fun trimName(name: String): String =
        name.trim().let { it.substring(0, it.offsetByCodePoints(0, minOf(64, it.codePointCount(0, it.length)))) }

    suspend fun setDisplayName(name: String) = withContext(Dispatchers.IO) {
        val trimmed = trimName(name)
        requireAccount().api.updateMe(displayName = trimmed)
        saveProfile(displayName = trimmed)
    }

    // E-mail, added after sign-up

    /** Mails a code to [email]; the address is added once [verifyEmail] gets it back. */
    suspend fun requestEmail(email: String): EmailAdd = withContext(Dispatchers.IO) {
        try {
            requireAccount().api.requestEmail(email)
            EmailAdd.Sent
        } catch (e: ApiError.Status) {
            if (e.code == 400) EmailAdd.NotAnAddress else throw e
        } catch (_: ApiError.TooManyRequests) {
            EmailAdd.TooMany
        }
    }

    suspend fun verifyEmail(email: String, code: String): EmailVerify = withContext(Dispatchers.IO) {
        try {
            requireAccount().api.verifyEmail(code)
            saveProfile(email = email.trim().lowercase())
            EmailVerify.Added
        } catch (e: ApiError.Status) {
            if (e.code == 400) EmailVerify.Wrong else throw e
        } catch (_: ApiError.Conflict) {
            EmailVerify.Taken
        } catch (_: ApiError.TooManyRequests) {
            EmailVerify.TooMany
        }
    }

    // Passkeys

    /**
     * Without a session, makes the account with a passkey (the first step of
     * sign-up; the profile, all optional, follows); signed in, adds a passkey to the account. [activity] must
     * be the Activity, which the system sheet attaches to.
     */
    suspend fun createPasskey(activity: Context, profile: Profile?, consent: Boolean = false): PasskeyResult {
        val current = account
        if (current == null) {
            val signUp = signUpWith(consent)
            val ceremony = try {
                withContext(Dispatchers.IO) { anonymous.beginPasskeySignUp(signUp, profile ?: Profile()) }
            } catch (_: ApiError.Conflict) {
                return PasskeyResult.UsernameTaken
            } catch (_: ApiError.NotFound) {
                return PasskeyResult.InviteGone
            } catch (_: ApiError.Forbidden) {
                return PasskeyResult.InvitationNeeded
            } catch (e: ApiError.TooManyRequests) {
                return if (e.signUps) PasskeyResult.TooManySignUps else PasskeyResult.TooMany
            }
            val credential = try {
                credentials.create(activity, Prf.withEval(ceremony.publicKeyJson))
            } catch (_: Passkeys.Unavailable) {
                return PasskeyResult.Unavailable
            } ?: return PasskeyResult.Cancelled
            val made = Prf.read(credential)
            val result = try {
                withContext(Dispatchers.IO) { anonymous.finishPasskey(ceremony.sessionId, Prf.stripped(credential)) }
            } catch (_: ApiError.Conflict) {
                return PasskeyResult.UsernameTaken
            } catch (_: ApiError.NotFound) {
                return PasskeyResult.InviteGone
            } catch (_: ApiError.Forbidden) {
                return PasskeyResult.InvitationNeeded
            } catch (e: ApiError.TooManyRequests) {
                return if (e.signUps) PasskeyResult.TooManySignUps else PasskeyResult.TooMany
            }
            if (!signedIn(result)) return PasskeyResult.OtherAccountData
            markSignUp(result.created)
            // Kept until the keys exist (afterKeys), when U is sealed for this passkey.
            heldPrf = made?.let { prfAfterCreation(activity, it) }
            profile?.let { saveProfile(username = it.username, displayName = it.displayName, gender = Gender.entries.firstOrNull { g -> g.wire == it.gender }) }
        } else {
            val ceremony = withContext(Dispatchers.IO) { current.api.beginPasskeyAdd() }
            val credential = try {
                credentials.create(activity, Prf.withEval(ceremony.publicKeyJson))
            } catch (_: Passkeys.Unavailable) {
                return PasskeyResult.Unavailable
            } ?: return PasskeyResult.Cancelled
            val made = Prf.read(credential)
            withContext(Dispatchers.IO) { current.api.finishPasskeyAdd(ceremony.sessionId, Prf.stripped(credential)) }
            made?.let { prfAfterCreation(activity, it) }?.let { sealForPasskey(it) }
            refreshPasskeys()
        }
        return PasskeyResult.Saved
    }

    /**
     * The PRF output from a passkey's creation, or, when the password manager
     * gave none there but did not say it lacks PRF, from a sign-in with that
     * passkey right after (docs/crypto.md: "at creation where supported, else a
     * sign-in right after"). That ceremony proves nothing to the server; it
     * only reads the output. Null when there is none.
     */
    private suspend fun prfAfterCreation(activity: Context, made: Prf.Result): Prf.Result? {
        if (made.output != null) return made
        if (made.enabled == false) return null
        val asked = try {
            credentials.get(activity, Prf.assertionOptions(E2EE.randomBytes(32), listOf(made.credentialId)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the PRF sign-in after creation failed: ${e.javaClass.simpleName}")
            null
        }
        return asked?.let(Prf::read)?.takeIf { it.output != null && it.credentialId.contentEquals(made.credentialId) }
    }

    /**
     * The PRF output of the passkey this phone last signed up or in with, in
     * memory only: it opens the counts on a new phone, or seals U for that
     * passkey once the keys exist. Forgotten once used.
     */
    @Volatile private var heldPrf: Prf.Result? = null

    /** Seals U for a passkey that gave its PRF output; failures leave it for the Settings card. */
    private suspend fun sealForPasskey(prf: Prf.Result) {
        val output = prf.output ?: return
        val a = account ?: return
        runCatching { withContext(Dispatchers.IO) { a.sealUserKeyForPasskey(output, prf.credentialId) } }
            .onFailure { Log.w(TAG, "sealing U for a passkey failed: ${it.javaClass.simpleName}") }
    }

    /**
     * A new phone signed in with a passkey that gave its PRF output: brings the
     * keys here from its box, without the recovery code. False when it cannot
     * (no output, no box, or one that does not open): the recovery code then.
     */
    suspend fun unlockWithPasskey(): Boolean {
        val prf = heldPrf ?: return false
        val output = prf.output ?: return false
        return keyed {
            val a = requireAccount()
            val opened = try {
                a.hasKeys() || a.unlockWithPasskey(output, prf.credentialId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything at all (a box that does not open, a wrap that will not, the network):
                // nothing from the response is kept, and the recovery code is the way in.
                Log.w(TAG, "passkey unlock failed: ${(e as? AccountKeys.Error)?.failure ?: e.javaClass.simpleName}")
                false
            }
            if (opened) {
                heldPrf = null
                _state.update { it.copy(status = AccountStatus.READY) }
                afterKeys(a)
            }
            opened
        }
    }

    /** Offers this app's passkeys; null when there is none or the person backed out. */
    suspend fun signInWithPasskey(activity: Context): Redeem? {
        val ceremony = withContext(Dispatchers.IO) { anonymous.beginPasskeySignIn() }
        val credential = credentials.get(activity, Prf.withEval(ceremony.publicKeyJson)) ?: return null
        heldPrf = Prf.read(credential)?.takeIf { it.output != null }
        return redeem(signUp = false) { anonymous.finishPasskey(ceremony.sessionId, Prf.stripped(credential)) }
    }

    // Keys

    /** Whether the signed-in account needs setting up here, restoring, or nothing. */
    suspend fun standing(): Account.Standing = withContext(Dispatchers.IO) {
        val standing = requireAccount().standing()
        if (standing == Account.Standing.READY) {
            _state.update { it.copy(status = AccountStatus.READY) }
            // Signed in again on a phone with the keys: the passkey that answered gets its box.
            heldPrf?.let { prf -> heldPrf = null; scope.launch { sealForPasskey(prf) } }
        }
        // The profile as the server has it, for a sign-in on a new phone.
        runCatching { requireAccount().api.me() }.getOrNull()?.let { me ->
            if (me.email == null) prefs.edit { remove(EMAIL) }
            saveProfile(email = me.email, username = me.username, displayName = me.displayName.ifEmpty { null },
                gender = Gender.entries.firstOrNull { it.wire == me.gender })
        }
        standing
    }

    /** One key operation at a time: a set-up, a restore or a new code. */
    private val keyWork = Mutex()

    private val onShow = RecoveryCodeOnShow()
    /**
     * The recovery code being shown, until it is confirmed or saved: a screen
     * recreated on rotation gets this one back rather than making another. Only
     * for the account it was made for: another one signed in during the same
     * onboarding gets none, and sets up its own keys.
     */
    val shownCode: StateFlow<String?> = onShow.shown

    /** The code on show, if it was made for the account this phone now syncs with. */
    private suspend fun shownForThisAccount(): String? =
        onShow.codeFor(db.syncState()?.user) ?: run { onShow.forget(); null }

    /** Set-up and new codes both leave this account's sync state behind, so the user is known. */
    private suspend fun show(code: String) = onShow.show(code, db.syncState()?.user)

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
        shownForThisAccount() ?: run {
            val a = requireAccount()
            val code = try {
                // newRecoveryCode finishes a code left pending, rather than replacing it.
                if (a.hasKeys()) a.newRecoveryCode() else a.setUpFirstDevice()
            } finally {
                refreshPending()
            }
            prefs.edit { putBoolean(RECOVERY_UNCONFIRMED, true) }
            show(code)
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
        shownForThisAccount() ?: run {
            val code = try { requireAccount().newRecoveryCode() } finally { refreshPending() }
            prefs.edit { putBoolean(RECOVERY_UNCONFIRMED, true) }
            show(code)
            _state.update { it.copy(recoveryUnconfirmed = true) }
            code
        }
    }

    /** The code was written down and checked, or saved to the password manager. */
    fun confirmRecoveryCode() {
        onShow.forget()
        prefs.edit { putBoolean(RECOVERY_UNCONFIRMED, false) }
        _state.update { it.copy(recoveryUnconfirmed = false) }
    }

    /** Offers the code to Google Password Manager, named after the account; false when declined. */
    suspend fun saveRecoveryCode(activity: Context, code: String): Boolean {
        // One entry per account, from stable parts only (a name or address can change):
        // a second account's code never overwrites the first's.
        val account = db.syncState()?.user?.toString()?.take(8)
        val id = listOfNotNull("Duongöndro", account).joinToString(" · ")
        return credentials.savePassword(activity, id, code).also { if (it) confirmRecoveryCode() }
    }

    /**
     * The fallback report, U and the passkey's box, then the invitation's
     * friendship, once keys exist. Failures here never fail the set-up.
     */
    private suspend fun afterKeys(a: Account) {
        heldPrf?.let { heldPrf = null; sealForPasskey(it) }
        refreshPasskeys()
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
            Invites.redeem(a.api, checked, me.id, a.identity())
            // The key the invite proved (MAC under the link's pin), not the server's word.
            val name = runCatching { a.api.friends().firstOrNull { it.userId == checked.inviter }?.displayName }.getOrNull().orEmpty()
            db.repin(checked.inviter, checked.inviterIdentityPk, name, Instant.now())
        }.onFailure { Log.w(TAG, "redeeming the invitation failed: ${it.javaClass.simpleName}") }
        clearInvitation()
        // The link that started this sign-up is used: never offer it again for accepting.
        withContext(Dispatchers.Main) { onInvitationUsed() }
    }

    // Being invited, with an account

    /** Checks an invitation opened while signed in with keys, as iOS's Social.check: the signature, then the link's MAC. */
    suspend fun checkToAccept(code: String): AcceptCheck = withContext(Dispatchers.IO) {
        val invite = Invitation.parse(code) as? Invitation.Invite ?: return@withContext AcceptCheck.Gone
        val checked = try {
            Invitation.check(anonymous, invite)
        } catch (_: ApiError.NotFound) {
            return@withContext AcceptCheck.Gone
        } catch (e: Invitation.Error) {
            return@withContext if (e.failure == Invitation.Failure.EXPIRED) AcceptCheck.Gone else AcceptCheck.NotAuthentic
        }
        if (checked.inviter == db.syncState()?.user) return@withContext AcceptCheck.Own
        // Already friends: nothing to accept. Unknown (offline) counts as not.
        val friend = runCatching { requireAccount().api.friends().firstOrNull { it.userId == checked.inviter } }.getOrNull()
        if (friend != null) AcceptCheck.AlreadyFriends(friend.displayName.takeIf { it.isNotBlank() }) else AcceptCheck.Ready(checked)
    }

    /**
     * Accepts a checked invitation (iOS's AccountModel.accept): a signed
     * acceptance makes the two accounts friends, and the inviter's key is pinned
     * as the invitation proved it. Returns the new friend's name, if the server
     * has one. A revoked or expired invitation is the API's NotFound.
     */
    suspend fun accept(checked: Invitation.Checked): String? = scope.async(Dispatchers.IO) {
        // In this manager's scope, so a screen that goes away (rotation) cannot
        // stop it between the redemption and the re-pin.
        val a = requireAccount()
        val user = db.syncState()?.user ?: throw IllegalStateException("no keys on this phone")
        Invites.redeem(a.api, checked, user, a.identity())
        val name = runCatching { a.api.friends().firstOrNull { it.userId == checked.inviter }?.displayName }.getOrNull()
        // The key the invitation proved wins over anything pinned on the server's word.
        db.repin(checked.inviter, checked.inviterIdentityPk, name.orEmpty(), Instant.now())
        scope.launch { refreshFriends() }
        name?.takeIf { it.isNotBlank() }
    }.await()

    // Inviting

    /**
     * The invitation on show: the one made here before while it has more than a
     * day left and the server still has it open, else a new one, so reopening
     * the Invite screen does not mint one each time. iOS keeps it in memory;
     * here it persists, sealed with the other secrets in no_backup.
     */
    suspend fun invite(now: Instant = Instant.now()): MadeInvite = withContext(Dispatchers.IO) {
        inviteLock.withLock {
            val a = requireAccount()
            // The cached one only while the server still has it open: another phone
            // may have revoked it, and a dead QR code must not be shown for days.
            shownInvite(now)?.takeIf { cached ->
                // Offline, the cached one is shown as it is; it is dropped only when the
                // server answers and does not list it as open.
                val listed = try { a.api.invites() } catch (_: IOException) { return@takeIf true }
                listed.any { it.id == cached.id && it.revokedAt == null && SyncTime.parse(it.expiresAt)?.isAfter(now) == true }
            } ?: run {
                secrets.delete(SHOWN_INVITE)
                val user = db.syncState()?.user ?: throw IllegalStateException("no keys on this phone")
                Invites.create(a.api, user, a.identity(), now).also {
                    secrets.write(SHOWN_INVITE, it.serialised(user))
                    prefs.edit { putBoolean(INVITE_WITHDRAWN, false) }
                }
            }
        }
    }

    /** The cached invitation, if it still has more than a day to run; never asks the server. */
    suspend fun shownInvite(now: Instant = Instant.now()): MadeInvite? = withContext(Dispatchers.IO) {
        val user = db.syncState()?.user ?: return@withContext null
        runCatching { secrets.read(SHOWN_INVITE) }.getOrNull()?.let { MadeInvite.deserialised(it, user) }
            ?.takeIf { java.time.Duration.between(now, it.expiresAt) > INVITE_REUSE }
    }

    private val inviteLock = Mutex()

    /**
     * The invitation on show was revoked from the Invite screen: it offers "Make
     * an invitation" rather than minting one, until that is tapped. Kept with the
     * account's preferences, so it is forgotten on sign-out.
     */
    val inviteWithdrawn: Boolean get() = prefs.getBoolean(INVITE_WITHDRAWN, false)

    /** This account's invitations that still work, the soonest to expire first. */
    suspend fun openInvites(now: Instant = Instant.now()): List<OpenInvite> = withContext(Dispatchers.IO) {
        requireAccount().api.invites()
            .filter { it.revokedAt == null }
            .mapNotNull { i -> SyncTime.parse(i.expiresAt)?.takeIf { it.isAfter(now) }?.let { OpenInvite(i.id, it) } }
            .sortedBy { it.expiresAt }
    }

    /** Stops an invitation from working; friends it made stay. One already gone counts as revoked. */
    suspend fun revokeInvite(id: String) = withContext(Dispatchers.IO) {
        try {
            requireAccount().api.revokeInvite(id)
        } catch (_: ApiError.NotFound) {
        }
        inviteLock.withLock {
            val shown = runCatching { secrets.read(SHOWN_INVITE) }.getOrNull()?.decodeToString()?.split(' ')?.getOrNull(1)
            if (shown == id) {
                secrets.delete(SHOWN_INVITE)
                prefs.edit { putBoolean(INVITE_WITHDRAWN, true) }
            }
        }
    }

    // Friends

    private val _friends = MutableStateFlow(FriendsState(poked = storedPokes()))
    val friends: StateFlow<FriendsState> = _friends.asStateFlow()

    private fun storedPokes(): Map<java.util.UUID, java.time.LocalDate> =
        prefs.getStringSet(POKED, emptySet()).orEmpty().mapNotNull { e ->
            runCatching { e.split(' ').let { (u, d) -> java.util.UUID.fromString(u) to java.time.LocalDate.parse(d) } }.getOrNull()
        }.toMap()

    /** Fetches the friend list and verifies their streaks; only for an account with keys. */
    suspend fun refreshFriends() {
        val s = social ?: return
        if (_state.value.status != AccountStatus.READY) return
        val a = account
        _friends.update { it.copy(loading = true) }
        try {
            val list = withContext(Dispatchers.IO) { s.friends() }
            if (account === a) _friends.update { it.copy(friends = list, loaded = true, failed = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError.Unauthorized) {
            sessionEnded(a)
        } catch (e: Exception) {
            Log.w(TAG, "friends failed: ${e.javaClass.simpleName}")
            _friends.update { it.copy(failed = true) }
        } finally {
            _friends.update { it.copy(loading = false) }
        }
    }

    /** One poke per friend per UTC day; a second is answered "already" by the server and counts as sent. */
    suspend fun poke(friend: java.util.UUID) {
        val s = social ?: return
        val day = withContext(Dispatchers.IO) { s.poke(friend) }
        val pokes = _friends.value.poked.filterValues { it == day } + (friend to day)
        prefs.edit { putStringSet(POKED, pokes.map { (u, d) -> "$u $d" }.toSet()) }
        _friends.update { it.copy(poked = pokes) }
    }

    suspend fun setNotifyDone(friend: java.util.UUID, on: Boolean) {
        val s = social ?: return
        withContext(Dispatchers.IO) { s.setNotifyDone(friend, on) }
        refreshFriends()
    }

    suspend fun unfriend(friend: java.util.UUID) {
        val s = social ?: return
        withContext(Dispatchers.IO) { s.unfriend(friend) }
        _friends.update { f -> f.copy(friends = f.friends.filter { it.user != friend }) }
        refreshFriends()
    }

    suspend fun block(friend: java.util.UUID) {
        val s = social ?: return
        withContext(Dispatchers.IO) { s.block(friend) }
        _friends.update { f -> f.copy(friends = f.friends.filter { it.user != friend }) }
        refreshFriends()
    }

    /**
     * Makes a practice's streak public (published at once) or private (removed
     * from the server). Throws when the server cannot be reached; private stays
     * public here then, so the switch shows what friends still see.
     */
    suspend fun setPublic(practice: String, on: Boolean) {
        val s = social ?: return
        withContext(Dispatchers.IO) { s.setPublic(practice, on) }
    }

    suspend fun report(friend: java.util.UUID, reason: String) {
        val s = social ?: return
        withContext(Dispatchers.IO) { s.report(friend, reason) }
    }

    // Settings › Passkeys, and passkey unlock (design decisions 23 and 25)

    private val _passkeys = MutableStateFlow(PasskeysState())
    val passkeys: StateFlow<PasskeysState> = _passkeys.asStateFlow()

    /** The key version U was last checked at in this launch. */
    @Volatile private var userKeyCheckedAt: Long? = null

    private val passkeyLock = Mutex()

    /**
     * Makes sure U is here and holds the keys (publishing it only when the
     * account has none), then fetches the account's passkeys. Only for an
     * account with keys on this phone; failures are kept in [passkeys].
     */
    suspend fun refreshPasskeys() {
        val a = account ?: return
        if (_state.value.status != AccountStatus.READY) return
        passkeyLock.withLock {
            _passkeys.update { it.copy(loading = true) }
            try {
                val holds = withContext(Dispatchers.IO) {
                    runCatching { a.ensureUserKey() != null }
                        .onFailure { e -> if (e is ApiError.Unauthorized || e is CancellationException) throw e; Log.w(TAG, "user key: ${e.javaClass.simpleName}") }
                        .getOrDefault(false)
                }
                val list = withContext(Dispatchers.IO) { a.api.passkeys() }
                if (account === a) _passkeys.update { it.copy(passkeys = list, loaded = true, failed = false, holdsUserKey = holds) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiError.Unauthorized) {
                sessionEnded(a)
            } catch (e: Exception) {
                Log.w(TAG, "passkeys failed: ${e.javaClass.simpleName}")
                _passkeys.update { it.copy(failed = true) }
            } finally {
                _passkeys.update { it.copy(loading = false) }
            }
        }
    }

    /** "Not now" on the Settings card: it comes back the next time the app starts. */
    fun dismissUnlockOffer() = _passkeys.update { it.copy(offerDismissed = true) }

    /**
     * The retrofit (design: Keys › Passkeys that unlock the counts): one
     * ceremony without allowCredentials, which only reads the PRF output of
     * whichever passkey answers, then U sealed for it. The system shows its
     * own prompt; nothing is done silently.
     */
    suspend fun letPasskeyUnlock(activity: Context): UnlockSetUp {
        val a = requireAccount()
        val response = credentials.get(activity, Prf.assertionOptions(E2EE.randomBytes(32))) ?: return UnlockSetUp.Cancelled
        val prf = Prf.read(response) ?: return UnlockSetUp.Cancelled
        val output = prf.output ?: return UnlockSetUp.NoOutput
        val result = try {
            // False: this phone lost U meanwhile; nothing to say about the passkey.
            if (withContext(Dispatchers.IO) { a.sealUserKeyForPasskey(output, prf.credentialId) }) UnlockSetUp.Done else UnlockSetUp.Cancelled
        } catch (_: ApiError.NotFound) {
            UnlockSetUp.OtherAccount
        }
        refreshPasskeys()
        return result
    }

    /** Names a passkey; a blank name clears it, and the password manager's name shows again. */
    suspend fun renamePasskey(id: String, name: String) {
        val trimmed = trimName(name).ifBlank { null }
        withContext(Dispatchers.IO) { requireAccount().api.renamePasskey(id, trimmed) }
        refreshPasskeys()
    }

    /**
     * Removes a passkey, its box and the sessions it signed in with. The
     * account's last way to sign in stays ([PasskeyRemoval.LastWay]); removing
     * the one this phone signed in with signs it out here, keys and data kept.
     */
    suspend fun removePasskey(passkey: app.duongondro.core.api.Passkey): PasskeyRemoval {
        val a = requireAccount()
        try {
            withContext(Dispatchers.IO) { a.api.deletePasskey(passkey.id) }
        } catch (_: ApiError.Conflict) {
            return PasskeyRemoval.LastWay
        } catch (_: ApiError.NotFound) {
            // Removed already, elsewhere.
        }
        if (passkey.current) {
            sessionEnded(a)
            return PasskeyRemoval.SignedOut
        }
        refreshPasskeys()
        return if (passkey.canUnlock && _passkeys.value.holdsUserKey) PasskeyRemoval.RemovedUnlocking else PasskeyRemoval.Removed
    }

    /**
     * "Replace the unlock key", only when the person asks, after removing a
     * passkey that could unlock. Android neither removes devices nor rotates
     * the practice key, so this is what it can do: sign out every other
     * session (a phone that passkey unlocked loses its sign-in), then a new U
     * with `replace: true`, the keys wrapped to it and U sent again to the
     * listed phones. A phone it already unlocked keeps what it has. Every
     * remaining passkey then needs a new box: the card offers it.
     */
    suspend fun replaceUnlockKey() {
        val a = requireAccount()
        withContext(Dispatchers.IO) {
            a.api.signOutOthers()
            a.replaceUserKey()
        }
        _passkeys.update { it.copy(offerDismissed = false) }
        refreshPasskeys()
    }

    // The server

    private val _serverVersion = MutableStateFlow<app.duongondro.core.api.ServerVersion?>(null)
    /** The commit the server was built from, for About; null until fetched. */
    val serverVersion: StateFlow<app.duongondro.core.api.ServerVersion?> = _serverVersion.asStateFlow()

    /** Fetches the server's commit, only with an account: local mode makes no network call at all. */
    suspend fun loadServerVersion() {
        if (_state.value.status == AccountStatus.NONE) return
        runCatching { withContext(Dispatchers.IO) { anonymous.version() } }.getOrNull()?.let { _serverVersion.value = it }
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

    /** A sync was asked for while one ran: it runs once more after, to send what changed meanwhile. */
    @Volatile private var rerun = false

    suspend fun syncNow() {
        while (true) {
            if (account == null || _state.value.status != AccountStatus.READY) return
            if (!syncLock.tryLock()) {
                rerun = true
                return
            }
            rerun = false
            try {
                syncOnce()
            } finally {
                syncLock.unlock()
            }
            if (!rerun) return
        }
    }

    private suspend fun syncOnce() {
        val a = account ?: return
        // A sign-out or "Delete everything" that ran meanwhile ended this account
        // here: nothing this sync learns may touch the state or the token after it.
        val generation = db.generation
        val current = { account === a && db.generation == generation }
        try {
            _state.update { it.copy(syncing = true) }
            val result = withContext(Dispatchers.IO) { SyncEngine(a).sync() }
            // Public streaks follow the sessions just synced; a failure here never fails the sync.
            social?.takeIf { current() }?.let { s ->
                try {
                    withContext(Dispatchers.IO) { s.publishStreaks() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "publishing streaks failed: ${e.javaClass.simpleName}")
                }
            }
            if (current()) {
                val now = Instant.now()
                prefs.edit { putLong(LAST_SYNC, now.toEpochMilli()) }
                _state.update { it.copy(lastSync = now, offline = false, refused = result.refused, unreadable = result.unreadable) }
                // Once per launch, and after a key rotation: U and the passkeys' boxes.
                val version = db.syncState()?.keyVersion
                if (version != null && version != userKeyCheckedAt) {
                    userKeyCheckedAt = version
                    scope.launch { refreshPasskeys() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError.Unauthorized) {
            // The session ended (removed elsewhere): keys stay, sign in again.
            if (current()) sessionEnded(a)
        } catch (_: Erased) {
            // "Delete everything" or a sign-out ran meanwhile: nothing to keep.
        } catch (e: IOException) {
            _state.update { it.copy(offline = true) }
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.javaClass.simpleName}: ${e.message}")
            _state.update { it.copy(offline = true) }
        } finally {
            _state.update { it.copy(syncing = false) }
        }
    }

    /** The server ended the session (removed elsewhere): keys stay, sign in again. */
    private suspend fun sessionEnded(a: Account?) {
        if (a == null || account !== a) return
        withContext(Dispatchers.IO) { secrets.delete(TOKEN) }
        // The server drops the push token only on its own sign-out, not when it ends a
        // session otherwise (signed out elsewhere, expired): delete it in FCM, so this
        // phone gets no pushes for an account it is no longer signed in to.
        Push.forget(app)
        account = null
        social = null
        heldPrf = null
        _passkeys.value = PasskeysState()
        signUpFinished()
        _state.update { it.copy(status = AccountStatus.SIGNED_OUT) }
    }

    // Leaving

    /**
     * Ends the session here and forgets the account on this phone: the token,
     * the account's keys and the sync state. The practice data stays, as in
     * local mode. The device key goes too, so another account gets its own.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        check(_state.value.canSignOut) { "the recovery code is not finished; signing out would lose the keys" }
        pendingSync?.cancel()
        // After any sync in flight, so it cannot write the account back.
        syncLock.withLock { signOutLocked() }
    }

    private suspend fun signOutLocked() {
        val user = db.syncState()?.user
        account?.let { a ->
            // No more pushes to this phone for this account, while the session can still say so.
            runCatching { a.deviceId()?.let { a.api.deletePushToken(it) } }
            runCatching { a.api.signOut() }
        }
        Push.forget(app)
        account = null
        social = null
        heldPrf = null
        _passkeys.value = PasskeysState()
        userKeyCheckedAt = null
        _friends.value = FriendsState()
        db.clearSyncState()
        forgetSecrets()
        // A new device key for whichever account comes next: two accounts never share one.
        deviceKeys.delete()
        // Remembered, so signing in to another account asks before mixing this practice into it.
        prefs.edit {
            clear()
            user?.let { putString(LAST_USER, it.toString()) }
        }
        // A finished export holds the account's data: it goes with the account.
        Exports.folder(app).deleteRecursively()
        _state.value = AccountState()
    }

    /** Whether an account is on this phone, ready or not: an export without its server part then says so. */
    fun hasAccount(): Boolean = _state.value.status != AccountStatus.NONE

    /** Every session id this phone has, deleted ones included (see [DataExport.merged]). */
    suspend fun knownSessionIds(): Set<UUID> = db.allSessionIds()

    /**
     * The server half of Your data › Export, with its sealed sessions opened on
     * this phone. Null without a ready account (local mode makes no request).
     */
    suspend fun serverExport(): DataExport.Server? = withContext(Dispatchers.IO) {
        val a = account
        if (a == null || _state.value.status != AccountStatus.READY) null else DataExport.Server(a.serverExport())
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
        onShow.forget()
        // The server deleted the token with the account; FCM's goes too.
        Push.forget(app)
        Push.reset(app)
        account = null
        social = null
        heldPrf = null
        _passkeys.value = PasskeysState()
        userKeyCheckedAt = null
        _friends.value = FriendsState()
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
        const val TOKEN = SESSION_TOKEN
        const val PREFS = "account"
        const val EMAIL = "email"
        const val USERNAME = "username"
        const val DISPLAY_NAME = "displayName"
        const val GENDER = "gender"
        const val RECOVERY_UNCONFIRMED = "recoveryUnconfirmed"
        const val SIGN_UP_UNFINISHED = "signUpUnfinished"
        const val PENDING_RECOVERY = app.duongondro.core.sync.SecretName.PENDING_RECOVERY
        const val LAST_SYNC = "lastSync"
        const val LAST_USER = "lastUser"
        /** The invitation on the Invite screen, among the sealed secrets: it is erased with them. */
        const val SHOWN_INVITE = "invite-shown"
        const val INVITE_WITHDRAWN = "inviteWithdrawn"
        /** Friends poked today, as "<user> <UTC day>". */
        const val POKED = "poked"
        const val SET_UP_USER = app.duongondro.core.sync.SecretName.SET_UP_USER
        /** An invitation is shown again while it has more than a day to run. */
        val INVITE_REUSE: java.time.Duration = java.time.Duration.ofDays(1)
        val LINK_LIFETIME: java.time.Duration = java.time.Duration.ofMinutes(15)
    }
}
