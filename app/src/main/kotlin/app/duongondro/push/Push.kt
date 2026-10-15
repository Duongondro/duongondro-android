package app.duongondro.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.duongondro.BuildConfig
import app.duongondro.MainActivity
import app.duongondro.R
import app.duongondro.inAppLanguage
import app.duongondro.account.SESSION_TOKEN
import app.duongondro.core.PushKey
import app.duongondro.core.PushUpload
import app.duongondro.core.api.Api
import app.duongondro.core.sync.SecretName
import app.duongondro.keys.SecretFiles
import app.duongondro.reminders.Reminders
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * Friends' pushes over Firebase Cloud Messaging (design: Social › Nudges), as
 * iOS's PushModel. Local mode never touches Firebase: auto-init is off in the
 * manifest, and only an account with keys on this phone, with notifications
 * allowed, turns it on and fetches a token ([refresh]). Leaving the account
 * deletes the token and turns auto-init off again ([forget]).
 *
 * The server sends notification messages, which the system draws itself while
 * the app is in the background, from the string resource named by the loc-key.
 * Version 0.4 is always discreet: those strings name the sender at most, never
 * a practice or a day count. In the foreground the app draws the same text
 * ([show]). Version 0.5 moves to data-only messages rendered by the app.
 */
object Push {
    /** The "Friends" channel, also the manifest's default for messages the system draws. */
    const val CHANNEL = "friends"
    private const val TAG = "Push"
    private const val PREFS = "push"
    /** Firebase was turned on for an account here: leaving it must turn it off again. */
    private const val ENABLED = "enabled"
    private const val SENT = "sent"
    private const val SENT_AT = "sentAt"
    /** The system prompt was shown once (Android 13+); the app does not ask again. */
    private const val ASKED = "asked"
    /** Friends › "Not now" on the permission card. */
    private const val DISMISSED = "askDismissed"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _received = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A push arrived while the app runs: the friends list is worth fetching again. */
    val received: SharedFlow<Unit> = _received.asSharedFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Created (and renamed in the app's language) at every start; creating it is local. */
    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.inAppLanguage().getString(R.string.tab_friends), NotificationManager.IMPORTANCE_DEFAULT))
    }

    // The token

    /**
     * For an account with keys on this phone (the caller checks): with
     * notifications allowed, turns FCM on and sends its token when the server
     * may not have it. Called at launch, on return to the foreground, when the
     * account becomes ready and when the permission is granted, so an offline
     * attempt is simply tried again.
     */
    // FCM 26 deprecates getToken/deleteToken/onNewToken for register(), which
    // hands over a Firebase Installation ID instead of a registration token; the
    // server sends to registration tokens (duongondro-api internal/push/fcm.go),
    // so the token API stays until the server moves too.
    @Suppress("DEPRECATION")
    fun refresh(context: Context) {
        val app = context.applicationContext
        if (!Reminders.canNotify(app)) return
        prefs(app).edit { putBoolean(ENABLED, true) }
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        messaging.token
            .addOnSuccessListener { token -> scope.launch { upload(app, token) } }
            .addOnFailureListener { Log.w(TAG, "no push token: ${it.javaClass.simpleName}") }
    }

    /**
     * Sends [token] for the account on this phone, as iOS's PushModel.upload:
     * only while Firebase is turned on for it, with its session and the device
     * id its keys were set up under; only when [PushUpload.due]. Failures wait
     * for the next [refresh].
     */
    internal suspend fun upload(context: Context, token: String, now: Instant = Instant.now()) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(ENABLED, false)) return
        try {
            val secrets = SecretFiles(context)
            val session = secrets.read(SESSION_TOKEN)?.decodeToString() ?: return
            val device = secrets.read(SecretName.DEVICE_ID)?.decodeToString()?.let(UUID::fromString) ?: return
            val lastSentAt = prefs.getLong(SENT_AT, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
            if (!PushUpload.due(device, PushUpload.PLATFORM, token, prefs.getString(SENT, null), lastSentAt, now)) return
            Api(BuildConfig.API_BASE_URL, session).putPushToken(device, PushUpload.PLATFORM, token)
            prefs.edit {
                putString(SENT, PushUpload.sent(device, PushUpload.PLATFORM, token))
                putLong(SENT_AT, now.toEpochMilli())
            }
            Log.i(TAG, "push token sent (${token.take(8)}…)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, or the session ended (the sync notices that): tried again at the next refresh.
            Log.w(TAG, "push token not sent: ${e.javaClass.simpleName}")
        }
    }

    /** The server dropped the token with an ended session: send it again after the next sign-in. */
    fun forgetSent(context: Context) {
        prefs(context).edit { remove(SENT); remove(SENT_AT) }
    }

    /**
     * Leaving the account (signing out, switching, deleting everything): no
     * more pushes here even if the server kept the token, since FCM no longer
     * delivers to a deleted one; auto-init off again, so local mode stays
     * offline. The caller deletes the token on the server first if it can.
     * A phone that never turned Firebase on does not touch it now.
     */
    @Suppress("DEPRECATION") // deleteToken, as in refresh
    fun forget(context: Context) {
        val prefs = prefs(context)
        val enabled = prefs.getBoolean(ENABLED, false)
        prefs.edit { remove(ENABLED); remove(SENT); remove(SENT_AT) }
        if (!enabled) return
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = false
        messaging.deleteToken().addOnFailureListener { Log.w(TAG, "push token not deleted: ${it.javaClass.simpleName}") }
    }

    // The permission

    private fun granted(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The system prompt can still be shown: Android 13+, not granted, not asked before. */
    fun canAsk(context: Context): Boolean = !granted(context) && !prefs(context).getBoolean(ASKED, false)

    /** The Friends card: only while the prompt can still be shown and "Not now" was not chosen. */
    fun offersCard(context: Context): Boolean = canAsk(context) && !prefs(context).getBoolean(DISMISSED, false)

    fun asked(context: Context) = prefs(context).edit { putBoolean(ASKED, true) }

    fun dismissCard(context: Context) = prefs(context).edit { putBoolean(DISMISSED, true) }

    // Showing

    /**
     * A push that arrived while the app is in the foreground, where the system
     * does not draw notification messages: the same discreet text, drawn here
     * in the app's language. [tag] is the thread (the sender), so a friend's
     * pushes replace each other, as the system's do.
     */
    internal fun show(context: Context, key: String?, args: Array<out String>?, tag: String?) {
        _received.tryEmit(Unit)
        if (!Reminders.canNotify(context)) return
        createChannel(context)
        val res = context.inAppLanguage().resources
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentText(text(res, PushKey.of(key), args.orEmpty()))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(tag ?: "push-${System.currentTimeMillis()}", 0, n)
        } catch (_: SecurityException) {
        }
    }

    /** The key's string with the server's arguments; a key this version does not know gets a generic text. */
    internal fun text(res: Resources, key: PushKey?, args: Array<out String>): String {
        key ?: return res.getString(R.string.push_generic)
        // A friend with no display name: "A friend", as the Friends tab says.
        val shown = args.mapIndexed { i, a -> if (i == 0 && key != PushKey.STREAK_AT_RISK && a.isBlank()) res.getString(R.string.a_friend) else a }
        return runCatching { res.getString(string(key), *shown.toTypedArray()) }.getOrElse { res.getString(R.string.push_generic) }
    }

    private fun string(key: PushKey): Int = when (key) {
        PushKey.FRIEND_DONE -> R.string.FRIEND_DONE
        PushKey.FRIEND_DONE_MALE -> R.string.FRIEND_DONE_MALE
        PushKey.FRIEND_DONE_FEMALE -> R.string.FRIEND_DONE_FEMALE
        PushKey.POKE -> R.string.POKE
        PushKey.POKE_MALE -> R.string.POKE_MALE
        PushKey.POKE_FEMALE -> R.string.POKE_FEMALE
        PushKey.STREAK_AT_RISK -> R.string.STREAK_AT_RISK
    }
}
