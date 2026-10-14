package app.duongondro.core.sync

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The recovery code a set-up is showing until it is confirmed, and the account
 * it was made for (iOS's RecoveryCodeOnShow). A step shown again gets the same
 * code; any other account, or none, gets nothing, so it sets up its own keys
 * rather than being shown, and confirming, a code that does not open its boxes.
 */
class RecoveryCodeOnShow {
    private var user: UUID? = null
    private val code = MutableStateFlow<String?>(null)

    /** What the screens show; null once forgotten. */
    val shown: StateFlow<String?> = code.asStateFlow()

    /** The code on show for [user], the account this phone now syncs with. */
    @Synchronized
    fun codeFor(user: UUID?): String? = if (user != null && user == this.user) code.value else null

    /** Shows [code], kept for [user]; without a user it is shown but never handed out again. */
    @Synchronized
    fun show(code: String, user: UUID?) {
        this.user = user
        this.code.value = code
    }

    @Synchronized
    fun forget() {
        user = null
        code.value = null
    }
}
