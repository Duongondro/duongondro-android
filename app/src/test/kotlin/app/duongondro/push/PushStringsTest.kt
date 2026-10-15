package app.duongondro.push

import app.duongondro.core.PushKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every server loc-key (PushKey, a copy of duongondro-api
 * internal/service/nudges.go checked by core's PushTest) is a string resource
 * in every language the app ships, because FCM draws notification messages
 * from them when the app is in the background. And 0.4 is always discreet:
 * a friend's push uses only the name, `%1$s`; streak at risk uses no argument,
 * so neither ever shows a practice or a day count.
 */
class PushStringsTest {
    private val res = File("src/main/res")

    /** The languages of locales_config.xml, by their values folder. */
    private val folders = listOf("values", "values-cs", "values-de", "values-es", "values-hu", "values-pl", "values-ru", "values-sk", "values-uk")

    private fun strings(folder: String): Map<String, String> =
        File(res, folder).listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            (0 until nodes.length).map { nodes.item(it) }.map { it.attributes.getNamedItem("name").nodeValue to it.textContent }
        }.toMap()

    @Test fun everyLanguageIsChecked() {
        val shipped = Regex("""android:name="([a-z]+)"""").findAll(File(res, "xml/locales_config.xml").readText())
            .map { it.groupValues[1] }.map { if (it == "en") "values" else "values-$it" }.toSet()
        assertEquals(shipped, folders.toSet())
    }

    @Test fun everyKeyInEveryLanguage() {
        for (folder in folders) {
            val s = strings(folder)
            for (key in PushKey.entries) {
                assertTrue("$folder has no ${key.wire}", s[key.wire]?.isNotBlank() == true)
            }
        }
    }

    @Test fun discreetArgumentsOnly() {
        val placeholder = Regex("""%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])""")
        for (folder in folders) {
            val s = strings(folder)
            for (key in PushKey.entries) {
                val text = s[key.wire] ?: continue
                val used = placeholder.findAll(text).filter { it.groupValues[2] != "%" }.map { m ->
                    // Unnumbered placeholders are refused: a translation could reorder them.
                    val n = m.groupValues[1].toIntOrNull()
                    assertTrue("$folder ${key.wire} has an unnumbered placeholder: $text", n != null)
                    assertEquals("$folder ${key.wire} formats the name as text: $text", "s", m.groupValues[2])
                    n!!
                }.toSet()
                assertTrue("$folder ${key.wire} uses $used, allowed ${key.allowedArguments}: $text", key.allowedArguments.containsAll(used))
                if (key != PushKey.STREAK_AT_RISK) assertEquals("$folder ${key.wire} must name the sender: $text", setOf(1), used)
            }
        }
    }
}
