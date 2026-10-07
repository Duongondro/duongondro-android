package app.duongondro.core.sync

/**
 * The 16-byte recovery secret as the person writes it down: Crockford base32,
 * 26 characters in groups of four joined by "-", no ambiguous letters; typing
 * tolerates case, spaces, hyphens, and O/I/L for 0/1/1. Identical to iOS's
 * RecoveryCode, so a code written down on one platform restores on the other.
 */
object RecoveryCode {
    internal const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(secret: ByteArray): String = Crockford.encode(secret).chunked(4).joinToString("-")

    /**
     * What was typed, as the code's characters: upper case, no separators, O read
     * as 0 and I or L as 1. Separators are any whitespace (a pasted tab or
     * non-breaking space) and any dash a keyboard or autocorrect may produce
     * (hyphen-minus, U+2010–U+2015, U+2212).
     */
    fun normalise(typed: String): String = buildString {
        for (c in typed.uppercase()) {
            when {
                c.isWhitespace() || c == '-' || c in '\u2010'..'\u2015' || c == '\u2212' -> {}
                else -> when (c) {
                    'O' -> append('0')
                    'I', 'L' -> append('1')
                    else -> append(c)
                }
            }
        }
    }

    /** The 16-byte secret, or null for anything that is not a whole code. */
    fun decode(code: String): ByteArray? {
        val cleaned = normalise(code)
        if (cleaned.length != 26) return null
        return Crockford.decodeBits(cleaned)?.takeIf { it.size == 16 }
    }
}

/**
 * Crockford base32 without separators, as invite links carry ids and secrets
 * (5 bytes → 8 characters, 10 → 16). A trailing partial group is zero-padded.
 */
object Crockford {
    fun encode(data: ByteArray): String = buildString {
        var bits = 0
        var value = 0
        for (byte in data) {
            value = (value shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                append(RecoveryCode.ALPHABET[(value shr (bits - 5)) and 31])
                bits -= 5
            }
            value = value and ((1 shl bits) - 1)
        }
        if (bits > 0) append(RecoveryCode.ALPHABET[(value shl (5 - bits)) and 31])
    }

    /** Bytes from a whole number of 8-character groups, in any case and with O/I/L forgiven; null otherwise. */
    fun decode(text: String): ByteArray? {
        val cleaned = RecoveryCode.normalise(text)
        if (cleaned.length % 8 != 0) return null
        return decodeBits(cleaned)
    }

    /** Every complete byte in the 5-bit groups of `cleaned`; leftover bits are dropped. */
    internal fun decodeBits(cleaned: String): ByteArray? {
        var bits = 0
        var value = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in cleaned) {
            val v = RecoveryCode.ALPHABET.indexOf(c)
            if (v < 0) return null
            value = (value shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((value shr (bits - 8)) and 0xFF)
                bits -= 8
            }
            value = value and ((1 shl bits) - 1)
        }
        return out.toByteArray()
    }
}
