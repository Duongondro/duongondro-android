package app.duongondro.core.export

/**
 * RFC 4180 tables for the CSV export: a header row, CRLF line ends, a field
 * quoted when it holds a comma, a quote, CR or LF (quotes doubled). Numbers are
 * written as they are, unformatted; text that a spreadsheet would run as a
 * formula (starting with `=`, `+`, `-`, `@`, tab or CR) gets a leading `'`
 * (design: Data export and deletion; OWASP's CSV injection guard).
 */
object Csv {
    const val CRLF = "\r\n"
    const val BOM = "\uFEFF"

    /** One field: a [Number] as is, null as empty, a Boolean as `true`/`false`, anything else as guarded text. */
    fun field(value: Any?): String = when (value) {
        null -> ""
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> quoted(guarded(value.toString()))
    }

    /** Text a spreadsheet would read as a formula, made literal. */
    fun guarded(text: String): String =
        if (text.isNotEmpty() && text[0] in FORMULA_STARTS) "'$text" else text

    /** Quoted when RFC 4180 needs it: a comma, a quote or any line break (as iOS's `isNewline`). */
    fun quoted(text: String): String =
        if (text.any { it == ',' || it == '"' || it in LINE_BREAKS }) "\"" + text.replace("\"", "\"\"") + "\"" else text

    private const val LINE_BREAKS = "\n\r\u000B\u000C\u0085\u2028\u2029"

    /**
     * A whole table: the header row, then one line per row, each ended by CRLF. With a UTF-8
     * byte order mark, so spreadsheets do not read Cyrillic or Polish as Latin-1 (as on iOS).
     */
    fun table(header: List<String>, rows: List<List<Any?>>): String = buildString {
        append(BOM)
        append(header.joinToString(",") { quoted(guarded(it)) }).append(CRLF)
        for (row in rows) {
            require(row.size == header.size) { "a row of ${row.size} fields under ${header.size} headers" }
            append(row.joinToString(",", transform = ::field)).append(CRLF)
        }
    }

    private val FORMULA_STARTS = charArrayOf('=', '+', '-', '@', '\t', '\r')
}
