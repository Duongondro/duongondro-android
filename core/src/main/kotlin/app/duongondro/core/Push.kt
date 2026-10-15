package app.duongondro.core

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The server's push loc-keys (duongondro-api internal/service/nudges.go), as
 * iOS's PushKey (design: Social › Nudges, Localisation › Localised pushes).
 * FCM sends them as notification messages, which the system draws from the
 * app's string resource of the same name when the app is in the background,
 * so every key is a string in every language.
 *
 * Version 0.4 is always discreet (the owner, 15 October 2026): no string names
 * a practice or gives a day count, so the strings use at most the sender's
 * name, `%1$s`, and leave the practice id and day count unused. Version 0.5
 * moves to data-only messages rendered by the app, with practice names and the
 * Discreet notifications setting.
 */
enum class PushKey(val wire: String) {
    /** Args: name, practice id, day count. */
    FRIEND_DONE("FRIEND_DONE"),
    FRIEND_DONE_MALE("FRIEND_DONE_MALE"),
    FRIEND_DONE_FEMALE("FRIEND_DONE_FEMALE"),
    /** Args: name. */
    POKE("POKE"),
    POKE_MALE("POKE_MALE"),
    POKE_FEMALE("POKE_FEMALE"),
    /** Args: practice id, day count. */
    STREAK_AT_RISK("STREAK_AT_RISK");

    /**
     * The arguments its strings may use, `%1$s` … : the sender's name for a
     * friend's push, nothing for streak at risk, whose arguments are the
     * practice and the day count.
     */
    val allowedArguments: Set<Int>
        get() = if (this == STREAK_AT_RISK) emptySet() else setOf(1)

    companion object {
        fun of(wire: String?): PushKey? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * When to send the push token to the server, as iOS's PushModel.upload: when
 * the server may not have it. That is a new token, another device id (after a
 * sign-in or a restore), another platform, or a day since it was last sent,
 * in case the server dropped it.
 */
object PushUpload {
    const val PLATFORM = "fcm"
    val RESEND: Duration = Duration.ofDays(1)

    /** What was sent, remembered after a successful upload. */
    fun sent(device: UUID, platform: String, token: String): String = "$device|$platform|$token"

    fun due(device: UUID, platform: String, token: String, lastSent: String?, lastSentAt: Instant?, now: Instant): Boolean =
        lastSent != sent(device, platform, token) || lastSentAt == null || Duration.between(lastSentAt, now) > RESEND
}
