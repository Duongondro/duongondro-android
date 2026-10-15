package app.duongondro.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/**
 * FCM's entry point. Runs only once Firebase was turned on for an account
 * with keys ([Push.refresh]): before that there is no token and nothing to
 * receive.
 */
class PushService : FirebaseMessagingService() {
    /** A new or rotated token; sent if the account is still here. Called on a background thread. */
    @Deprecated("FCM 26 moves to register() and Installation IDs; the server still sends to tokens (Push.refresh)")
    override fun onNewToken(token: String) {
        runBlocking { Push.upload(applicationContext, token) }
    }

    /** Called for notification messages only while the app is in the foreground; the system draws them otherwise. */
    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification ?: return
        Push.show(applicationContext, n.bodyLocalizationKey, n.bodyLocalizationArgs, n.tag)
    }
}
