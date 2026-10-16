package app.duongondro.data

import app.duongondro.core.export.CsvExport
import app.duongondro.core.export.PdfReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ExportStrings (generated from iOS's Export.xcstrings by Scripts/export-strings-from-ios.py)
 * has a resource for every key the export looks up, so none fails at export time with
 * "no string for …": every CSV column, every privacy list line, and every literal key
 * PdfReport (core) and PdfExport (app) pass to word() or plural().
 */
class ExportStringsTest {
    private fun missing(keys: Iterable<String>, map: Map<String, Int>) = keys.filterNot { it in map }

    @Test fun everyCsvColumn() {
        assertEquals(emptyList<String>(), missing(CsvExport.Column.entries.map { it.english }, ExportStrings.words))
    }

    @Test fun everyPrivacyLine() {
        val keys = PdfReport.PRIVACY.flatMap { listOfNotNull(it.title, it.intro) + it.items + it.after }
        assertEquals(emptyList<String>(), missing(keys, ExportStrings.words))
    }

    @Test fun pdfReportConstants() {
        assertTrue(PdfReport.SERVER_UNREACHABLE in ExportStrings.words)
        assertTrue(PdfReport.UNREADABLE in ExportStrings.plurals)
    }

    /** The literal keys in the sources, as `l("…")`, `word("…")` and `plural("…", n)` calls. */
    private fun literals(call: String, vararg sources: File): Set<String> {
        val pattern = Regex("""\b$call\("((?:[^"\\]|\\.)*)"""")
        return sources.flatMap { f -> pattern.findAll(f.readText()).map { it.groupValues[1].replace("\\\"", "\"").replace("\\$", "$") }.toList() }.toSet()
    }

    // Unit tests run in the app module's folder.
    private val pdfReport = File("../core/src/main/kotlin/app/duongondro/core/export/PdfReport.kt")
    private val pdfExport = File("src/main/kotlin/app/duongondro/data/PdfExport.kt")

    @Test fun everyWordThePdfUses() {
        val words = literals("l", pdfReport) + literals("word", pdfReport, pdfExport)
        assertTrue("found only ${words.size} keys in the sources", words.size > 30)
        assertEquals(emptyList<String>(), missing(words, ExportStrings.words))
    }

    @Test fun everyPluralThePdfUses() {
        val plurals = literals("plural", pdfReport)
        assertTrue("found only ${plurals.size} plural keys in the sources", plurals.size >= 5)
        assertEquals(emptyList<String>(), missing(plurals, ExportStrings.plurals))
    }
}
