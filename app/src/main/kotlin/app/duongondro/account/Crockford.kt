package app.duongondro.account

/** Typing rules for the codes people copy by hand: Crockford base32, forgiving about case, look-alikes and spacing. An invitation is 24 characters, an admission code 16. */
object Crockford {
    const val INVITE_LENGTH = 24
    const val ADMISSION_LENGTH = 16
    const val SIGN_IN_LENGTH = 8
    const val RECOVERY_LENGTH = 26
    const val GROUP = 4

    /**
     * Uppercases, reads O as 0 and I or L as 1, and drops everything that is not a letter or digit.
     * A pasted link keeps only what follows its last slash or equals sign.
     */
    fun normalise(raw: String, maxLength: Int): String {
        val tail = raw.trim().substringAfterLast('/').substringAfterLast('=')
        return buildString {
            for (ch in tail.uppercase()) {
                when {
                    ch == 'O' -> append('0')
                    ch == 'I' || ch == 'L' -> append('1')
                    ch in '0'..'9' || ch in 'A'..'Z' -> append(ch)
                }
            }
        }.take(maxLength)
    }

    /** "7K2MQ9XA" as "7K2M Q9XA". */
    fun grouped(code: String): List<String> = code.chunked(GROUP)
}
