package app.duongondro.core.qr

import kotlin.math.abs

/**
 * A QR Code symbol (ISO/IEC 18004, versions 1-10), ported from iOS's
 * DuongondroQR so both apps draw the same modules for the same link.
 *
 * Written rather than taken from a library: mixed segments keep an invite link
 * at version 3 (29 x 29), small enough for a phone camera to read across a
 * table, and the app draws the modules itself (dots, rounded eyes).
 */
class QRCode private constructor(
    val version: Int,
    val ecc: ECC,
    val mask: Int,
    private val modules: BooleanArray,
) {
    /** Modules per side: 17 + 4 * version. */
    val size: Int = 17 + 4 * version

    /** True for a dark module; (0, 0) is the top-left corner. */
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]

    /**
     * True inside the three 7x7 finder patterns, so the app can draw them as
     * rounded eyes and the rest as dots. Separators are not included.
     */
    fun isFinderModule(x: Int, y: Int): Boolean {
        val nearX = x < 7
        val nearY = y < 7
        val farX = x >= size - 7
        val farY = y >= size - 7
        return (nearX && nearY) || (farX && nearY) || (nearX && farY)
    }

    companion object {
        const val MAX_VERSION = 10

        fun encode(text: String, ecc: ECC = ECC.MEDIUM, minVersion: Int = 1): QRCode =
            encode(Segment.split(text), ecc, minVersion)

        fun encode(segments: List<Segment>, ecc: ECC, minVersion: Int = 1): QRCode {
            for (version in maxOf(1, minVersion)..MAX_VERSION) {
                val capacity = dataCodewords(version, ecc) * 8
                val used = bitLength(segments, version) ?: continue
                if (used > capacity) continue
                val data = padded(segments, version, capacity)
                return build(version, ecc, interleaved(data, version, ecc))
            }
            throw QRException(QRException.Reason.TOO_LONG)
        }

        // Capacity tables (versions 1-10, index = version - 1)

        private val eccPerBlock = mapOf(
            ECC.LOW to intArrayOf(7, 10, 15, 20, 26, 18, 20, 24, 30, 18),
            ECC.MEDIUM to intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26),
            ECC.QUALITY to intArrayOf(13, 22, 18, 26, 18, 24, 18, 22, 20, 24),
            ECC.HIGH to intArrayOf(17, 28, 22, 16, 22, 28, 26, 26, 24, 28),
        )
        private val blockCount = mapOf(
            ECC.LOW to intArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 4),
            ECC.MEDIUM to intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5),
            ECC.QUALITY to intArrayOf(1, 1, 2, 2, 4, 4, 6, 6, 8, 8),
            ECC.HIGH to intArrayOf(1, 1, 2, 4, 4, 4, 5, 6, 8, 8),
        )
        internal val alignmentCentres = arrayOf(
            intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30), intArrayOf(6, 34),
            intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
        )

        /** Modules left for data and ECC once function patterns are removed. */
        private fun rawModules(version: Int): Int {
            var n = (16 * version + 128) * version + 64
            if (version >= 2) {
                val a = version / 7 + 2
                n -= (25 * a - 10) * a - 55
                if (version >= 7) n -= 36
            }
            return n
        }

        private fun dataCodewords(version: Int, ecc: ECC): Int =
            rawModules(version) / 8 - eccPerBlock.getValue(ecc)[version - 1] * blockCount.getValue(ecc)[version - 1]

        // Bit stream

        /** Total bits of all segments at this version, or null if a count overflows its field. */
        private fun bitLength(segments: List<Segment>, version: Int): Int? {
            var total = 0
            for (s in segments) {
                val width = s.mode.countBits(version)
                if (s.count >= 1 shl width) return null
                total += 4 + width + s.bits.size
            }
            return total
        }

        /** Mode headers, data, terminator and the alternating 0xEC/0x11 pad bytes. */
        private fun padded(segments: List<Segment>, version: Int, capacityBits: Int): IntArray {
            val bits = BitList()
            for (s in segments) {
                bits.append(s.mode.indicator, 4)
                bits.append(s.count, s.mode.countBits(version))
                bits.addAll(s.bits)
            }
            repeat(minOf(4, capacityBits - bits.size)) { bits.add(false) }
            while (bits.size % 8 != 0) bits.add(false)
            val bytes = ArrayList<Int>(capacityBits / 8)
            for (i in 0 until bits.size step 8) {
                var b = 0
                for (j in 0 until 8) b = (b shl 1) or (if (bits[i + j]) 1 else 0)
                bytes += b
            }
            var pad = 0xEC
            while (bytes.size < capacityBits / 8) {
                bytes += pad
                pad = if (pad == 0xEC) 0x11 else 0xEC
            }
            return bytes.toIntArray()
        }

        // Reed-Solomon over GF(256), polynomial 0x11D

        private fun gfMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z and 0xFF
        }

        /** Coefficients (without the leading 1) of the product of (x - 2^i). */
        private fun generator(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = gfMultiply(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = gfMultiply(root, 2)
            }
            return result
        }

        private fun remainder(data: IntArray, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = b xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in divisor.indices) result[i] = result[i] xor gfMultiply(divisor[i], factor)
            }
            return result
        }

        /**
         * Splits data into blocks, appends ECC to each and interleaves them so a
         * local scratch damages many blocks a little instead of one a lot.
         */
        private fun interleaved(data: IntArray, version: Int, ecc: ECC): IntArray {
            val blocks = blockCount.getValue(ecc)[version - 1]
            val eccLen = eccPerBlock.getValue(ecc)[version - 1]
            val total = rawModules(version) / 8
            val shortBlocks = blocks - total % blocks
            val shortLen = total / blocks
            val divisor = generator(eccLen)

            val datas = ArrayList<IntArray>()
            val eccs = ArrayList<IntArray>()
            var k = 0
            for (i in 0 until blocks) {
                val len = shortLen - eccLen + (if (i < shortBlocks) 0 else 1)
                val d = data.copyOfRange(k, k + len)
                k += len
                datas += d
                eccs += remainder(d, divisor)
            }
            val out = ArrayList<Int>(total)
            for (i in 0 until shortLen - eccLen + 1) {
                for (d in datas) if (i < d.size) out += d[i]
            }
            for (i in 0 until eccLen) for (e in eccs) out += e[i]
            return out.toIntArray()
        }

        // Test seams for the spec's worked example.
        internal fun testPadded(s: List<Segment>, version: Int, ecc: ECC): IntArray = padded(s, version, dataCodewords(version, ecc) * 8)
        internal fun testInterleaved(d: IntArray, version: Int, ecc: ECC): IntArray = interleaved(d, version, ecc)

        // Matrix

        private fun build(version: Int, ecc: ECC, codewords: IntArray): QRCode {
            val m = Matrix(version)
            m.drawFunctionPatterns(ecc)
            m.placeCodewords(codewords)

            var bestMask = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                m.applyMask(mask)
                m.drawFormat(ecc, mask)
                val p = m.penalty()
                if (p < bestPenalty) {
                    bestMask = mask
                    bestPenalty = p
                }
                m.applyMask(mask) // XOR again to undo
            }
            m.applyMask(bestMask)
            m.drawFormat(ecc, bestMask)
            return QRCode(version, ecc, bestMask, m.dark)
        }
    }
}

/** Working grid; `isFunction` marks modules that data and masks must not touch. */
private class Matrix(val version: Int) {
    val size = 17 + 4 * version
    val dark = BooleanArray(size * size)
    val isFunction = BooleanArray(size * size)

    fun setFunction(x: Int, y: Int, value: Boolean) {
        dark[y * size + x] = value
        isFunction[y * size + x] = true
    }

    fun drawFunctionPatterns(ecc: ECC) {
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        // Each finder plus its one-module light separator, as a 9x9 square.
        for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val d = maxOf(abs(dx), abs(dy))
                setFunction(x, y, d != 2 && d != 4)
            }
        }
        val centres = QRCode.alignmentCentres[version - 1]
        for ((i, cy) in centres.withIndex()) {
            for ((j, cx) in centres.withIndex()) {
                // The three corners where an alignment pattern would hit a finder.
                if ((i == 0 && j == 0) || (i == 0 && j == centres.size - 1) || (i == centres.size - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) setFunction(cx + dx, cy + dy, maxOf(abs(dx), abs(dy)) != 1)
            }
        }
        drawFormat(ecc, 0) // reserve the area; redrawn per mask
        drawVersion()
    }

    /** BCH(15,5) format word, XORed with 0x5412 so it is never all zero. */
    fun drawFormat(ecc: ECC, mask: Int) {
        val data = (ecc.formatBits shl 3) or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 == 1

        for (i in 0..5) setFunction(8, i, bit(i))
        setFunction(8, 7, bit(6))
        setFunction(8, 8, bit(7))
        setFunction(7, 8, bit(8))
        for (i in 9 until 15) setFunction(14 - i, 8, bit(i))

        for (i in 0 until 8) setFunction(size - 1 - i, 8, bit(i))
        for (i in 8 until 15) setFunction(8, size - 15 + i, bit(i))
        setFunction(8, size - 8, true) // the always-dark module
    }

    /** BCH(18,6) version word, only present from version 7. */
    fun drawVersion() {
        if (version < 7) return
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val b = (bits ushr i) and 1 == 1
            val a = size - 11 + i % 3
            val c = i / 3
            setFunction(a, c, b)
            setFunction(c, a, b)
        }
    }

    /** Zigzag up and down two-column strips from the bottom right, skipping the timing column. */
    fun placeCodewords(data: IntArray) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0 until 2) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y * size + x] && i < data.size * 8) {
                        dark[y * size + x] = (data[i ushr 3] ushr (7 - (i and 7))) and 1 == 1
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    fun applyMask(mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            if (isFunction[y * size + x]) continue
            val flip = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (flip) dark[y * size + x] = !dark[y * size + x]
        }
    }

    /** The four standard penalty rules; the lowest total picks the mask. */
    fun penalty(): Int {
        var score = 0
        val finderLike = arrayOf(
            booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false),
            booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true),
        )
        val line = BooleanArray(size)
        for (transpose in listOf(false, true)) {
            for (a in 0 until size) {
                for (b in 0 until size) line[b] = if (transpose) dark[b * size + a] else dark[a * size + b]
                // Rule 1: runs of five or more same-coloured modules.
                var run = 1
                for (i in 1..size) {
                    if (i < size && line[i] == line[i - 1]) {
                        run++
                    } else {
                        if (run >= 5) score += run - 2
                        run = 1
                    }
                }
                // Rule 3: patterns that look like a finder.
                if (size >= 11) {
                    for (i in 0..size - 11) {
                        if (finderLike.any { p -> p.indices.all { k -> line[i + k] == p[k] } }) score += 40
                    }
                }
            }
        }
        // Rule 2: 2x2 blocks of one colour.
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = dark[y * size + x]
            if (c == dark[y * size + x + 1] && c == dark[(y + 1) * size + x] && c == dark[(y + 1) * size + x + 1]) score += 3
        }
        // Rule 4: balance of dark and light.
        val total = size * size
        val darkCount = dark.count { it }
        val k = (abs(darkCount * 20 - total * 10) + total - 1) / total - 1
        return score + k * 10
    }
}
