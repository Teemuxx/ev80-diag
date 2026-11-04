package io.github.ev80diag.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringsParityTest {
    // Gradle runs unit tests with the module directory (app/) as working directory.
    private fun keys(path: String): Set<String> =
        Regex("""<string\s+name="([^"]+)"""").findAll(File(path).readText()).map { it.groupValues[1] }.toSet()

    private val en = keys("src/main/res/values/strings.xml")
    private val de = keys("src/main/res/values-de/strings.xml")

    @Test fun everyEnglishStringHasAGermanTranslation() {
        assertTrue("EN has strings", en.size > 30)
        assertEquals("missing in DE", emptySet<String>(), en - de)
    }

    @Test fun germanHasNoStrayKeys() {
        assertEquals("only in DE", emptySet<String>(), de - en)
    }

    @Test fun placeholdersMatch() {
        fun placeholders(path: String) = Regex("""<string\s+name="([^"]+)"[^>]*>([^<]*)</string>""")
            .findAll(File(path).readText()).associate { m -> m.groupValues[1] to Regex("%\\d\\$[.\\d]*[sdf]").findAll(m.groupValues[2]).map { it.value }.toList().sorted() }
        val e = placeholders("src/main/res/values/strings.xml"); val d = placeholders("src/main/res/values-de/strings.xml")
        for ((k, v) in e) assertEquals("placeholders of $k", v, d[k])
    }
}
