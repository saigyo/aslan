package io.github.saigyo.aslan

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.io.File

/** Checks of what the plugin listing shows: logo, screenshots, description and version. */
class ListingTest {
    @Test
    fun `test the logo is a small SVG of 40 by 40`() {
        val file = File("src/main/resources/META-INF/pluginIcon.svg")
        assertTrue("the logo is missing", file.isFile)
        assertTrue("the logo is 3 kB or more", file.length() < 3 * 1024)
        val root = file.readText().substringBefore('>')
        assertEquals("40", Regex("""\swidth="([^"]*)"""").find(root)?.groupValues?.get(1))
        assertEquals("40", Regex("""\sheight="([^"]*)"""").find(root)?.groupValues?.get(1))
    }
}
