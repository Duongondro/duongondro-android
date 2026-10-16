package app.duongondro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every language in the picker translates every string of `values/strings.xml`,
 * with the same format arguments, and its gendered forms take the arguments of
 * the neutral string they replace. Reads the resource files directly, so it runs
 * on the JVM without a device.
 */
class LocaleStringsTest {
    private val res = File("src/main/res")

    /** The picker's languages other than English (SettingsScreen.LANGUAGES, locales_config.xml). */
    private val locales = listOf("de", "ru", "uk", "pl", "cs", "sk", "hu", "es", "fr")

    /** A string's format arguments, numbered: "%s and %d" and "%1\$s and %2\$d" both give [1s, 2d]. */
    private fun arguments(text: String): List<String> {
        var next = 0
        return Regex("""%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])""").findAll(text)
            .filter { it.groupValues[2] != "%" }
            .map { (it.groupValues[1].ifEmpty { null }?.toInt() ?: ++next).toString() + it.groupValues[2] }
            .sorted().toList()
    }

    /** name to its text, or for plurals each quantity's text under "name#quantity". */
    private fun load(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = linkedMapOf<String, String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.getAttribute("translatable") == "false") continue
            val name = e.getAttribute("name")
            when (e.tagName) {
                "string" -> out[name] = e.textContent
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        out["$name#${item.getAttribute("quantity")}"] = item.textContent
                    }
                }
            }
        }
        return out
    }

    private fun base(key: String) = key.substringBefore('#')

    @Test fun everyLanguageIsInThePickerAndLocaleConfig() {
        val config = File(res, "xml/locales_config.xml").readText()
        for (locale in listOf("en") + locales) assertTrue("$locale missing from locales_config.xml", "\"$locale\"" in config)
        val picker = File("src/main/kotlin/app/duongondro/ui/settings/SettingsScreen.kt").readText()
        for (locale in locales) assertTrue("$locale missing from the language picker", "\"$locale\"" in picker)
    }

    @Test fun everyLocaleTranslatesEveryStringWithTheSameArguments() {
        val english = load(File(res, "values/strings.xml"))
        val englishNames = english.keys.map(::base).toSet()
        for (locale in locales) {
            val translated = load(File(res, "values-$locale/strings.xml"))
            val names = translated.keys.map(::base).toSet()
            assertEquals("$locale: untranslated", emptySet<String>(), englishNames - names)
            assertEquals("$locale: not in English", emptySet<String>(), names - englishNames)
            for ((key, text) in translated) {
                val source = english[key] ?: english["${base(key)}#other"]!!
                // A plural's "one" may spell the number out ("Ein Tag"), so only "other" must keep every argument.
                if (key.endsWith("#one")) {
                    assertTrue("$locale $key: $text", (arguments(text) - arguments(source).toSet()).isEmpty())
                } else {
                    assertEquals("$locale $key: $text", arguments(source), arguments(text))
                }
            }
            for (name in englishNames.filter { "$it#other" in english }) {
                assertTrue("$locale $name has no \"other\"", "$name#other" in translated)
            }
        }
    }

    @Test fun frenchPluralsHaveOneManyAndOther() {
        val french = load(File(res, "values-fr/strings.xml"))
        val plurals = french.keys.filter { '#' in it }.map(::base).toSet()
        assertTrue(plurals.isNotEmpty())
        for (name in plurals) for (q in listOf("one", "many", "other")) {
            assertTrue("fr $name has no \"$q\"", "$name#$q" in french)
        }
    }

    @Test fun genderedFormsTakeTheNeutralStringsArguments() {
        val defaults = load(File(res, "values/strings_gendered.xml"))
        for (locale in locales) {
            val neutral = load(File(res, "values-$locale/strings.xml"))
            for ((key, text) in load(File(res, "values-$locale/strings_gendered.xml"))) {
                assertTrue("$locale $key has no default alias", key in defaults)
                val of = key.removeSuffix("_male").removeSuffix("_female")
                assertEquals("$locale $key: $text", arguments(neutral.getValue(of)), arguments(text))
            }
        }
    }
}
