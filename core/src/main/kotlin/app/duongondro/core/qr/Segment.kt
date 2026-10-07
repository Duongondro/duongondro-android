package app.duongondro.core.qr

/** Error correction level. Higher levels survive more damage but hold less data. */
enum class ECC {
    LOW, MEDIUM, QUALITY, HIGH;

    /** The two format-information bits (not in rank order, per ISO/IEC 18004). */
    internal val formatBits: Int get() = intArrayOf(1, 0, 3, 2)[ordinal]
}

class QRException(val reason: Reason) : Exception(reason.name.lowercase()) {
    enum class Reason { INVALID_CHARACTERS, TOO_LONG }
}

/**
 * One run of text in a single encoding mode. A code may mix several segments
 * so that, for example, a URL stays in the dense alphanumeric mode except for
 * the one character that is not in its table. A port of iOS's DuongondroQR.
 */
class Segment internal constructor(val mode: Mode, /** Characters (bytes in byte mode) in the segment. */ val count: Int, internal val bits: BooleanArray) {
    enum class Mode(internal val indicator: Int, private val small: Int, private val large: Int) {
        NUMERIC(0b0001, 10, 12), ALPHANUMERIC(0b0010, 9, 11), BYTE(0b0100, 8, 16);

        /** Width of the character count field; it grows at version 10. */
        fun countBits(version: Int): Int = if (version <= 9) small else large
    }

    override fun equals(other: Any?) = other is Segment && mode == other.mode && count == other.count && bits.contentEquals(other.bits)
    override fun hashCode() = mode.hashCode() * 31 + count

    companion object {
        internal const val ALPHANUMERIC_TABLE = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

        fun numeric(digits: String): Segment {
            if (digits.any { it !in '0'..'9' }) throw QRException(QRException.Reason.INVALID_CHARACTERS)
            val bits = BitList()
            var i = 0
            while (i < digits.length) {
                val n = minOf(3, digits.length - i)
                bits.append(digits.substring(i, i + n).toInt(), n * 3 + 1) // 1, 2, 3 digits -> 4, 7, 10 bits
                i += n
            }
            return Segment(Mode.NUMERIC, digits.length, bits.toArray())
        }

        fun alphanumeric(text: String): Segment {
            val values = text.map { ch ->
                ALPHANUMERIC_TABLE.indexOf(ch).takeIf { it >= 0 } ?: throw QRException(QRException.Reason.INVALID_CHARACTERS)
            }
            val bits = BitList()
            var i = 0
            while (i + 1 < values.size) {
                bits.append(values[i] * 45 + values[i + 1], 11)
                i += 2
            }
            if (i < values.size) bits.append(values[i], 6)
            return Segment(Mode.ALPHANUMERIC, values.size, bits.toArray())
        }

        fun bytes(data: ByteArray): Segment {
            val bits = BitList()
            for (b in data) bits.append(b.toInt() and 0xFF, 8)
            return Segment(Mode.BYTE, data.size, bits.toArray())
        }

        /**
         * Splits text into segments with the fewest bits, by dynamic programming
         * over UTF-8 bytes. Switching modes costs a header, so a lone digit inside
         * a URL stays alphanumeric and a lone '#' becomes a tiny byte segment only
         * when that is cheaper than widening its neighbours.
         */
        fun split(text: String): List<Segment> {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) return emptyList()
            val modes = Mode.entries
            // Costs are scaled by 6 so 10/3 and 11/2 bits per character stay integral.
            val charCost = intArrayOf(20, 33, 48)
            val header = IntArray(3) { 6 * (4 + modes[it].countBits(1)) }

            fun allowed(m: Int, b: Int): Boolean = when (m) {
                0 -> b in 48..57
                1 -> b < 128 && ALPHANUMERIC_TABLE.indexOf(b.toChar()) >= 0
                else -> true
            }

            val inf = Int.MAX_VALUE / 2
            val cost = Array(bytes.size) { IntArray(3) { inf } }
            val from = Array(bytes.size) { IntArray(3) }
            for (i in bytes.indices) {
                val b = bytes[i].toInt() and 0xFF
                for (m in 0 until 3) {
                    if (!allowed(m, b)) continue
                    if (i == 0) {
                        cost[0][m] = header[m] + charCost[m]
                        continue
                    }
                    for (p in 0 until 3) {
                        if (cost[i - 1][p] >= inf) continue
                        val c = cost[i - 1][p] + (if (p == m) 0 else header[m]) + charCost[m]
                        if (c < cost[i][m]) {
                            cost[i][m] = c
                            from[i][m] = p
                        }
                    }
                }
            }

            val chosen = IntArray(bytes.size) { 2 }
            val last = bytes.size - 1
            var m = (0 until 3).minBy { cost[last][it] }
            for (i in last downTo 0) {
                chosen[i] = m
                m = from[i][m]
            }

            val result = mutableListOf<Segment>()
            var start = 0
            for (i in 1..bytes.size) {
                if (i != bytes.size && chosen[i] == chosen[start]) continue
                val run = bytes.copyOfRange(start, i)
                // The run is valid for its mode by construction of `allowed`.
                result += when (chosen[start]) {
                    0 -> numeric(String(run, Charsets.US_ASCII))
                    1 -> alphanumeric(String(run, Charsets.US_ASCII))
                    else -> bytes(run)
                }
                start = i
            }
            return result
        }
    }
}

/** A growable list of bits, most significant first. */
internal class BitList {
    private var bits = BooleanArray(64)
    var size = 0
        private set

    fun add(bit: Boolean) {
        if (size == bits.size) bits = bits.copyOf(size * 2)
        bits[size++] = bit
    }

    fun append(value: Int, length: Int) {
        for (i in length - 1 downTo 0) add((value shr i) and 1 == 1)
    }

    fun addAll(other: BooleanArray) = other.forEach(::add)

    operator fun get(i: Int) = bits[i]

    fun toArray(): BooleanArray = bits.copyOf(size)
}
