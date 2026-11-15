package io.github.saigyo.aslan

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

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

    @Test
    fun `test the media screenshots share one size of at least 1200 by 760`() {
        val names = listOf("01-overview", "02-completion", "03-validation", "04-jsonata", "05-variables", "06-diagram")
        val sizes = names.map { name ->
            val file = File("docs/images/media/$name.png")
            assertTrue("$name.png is missing", file.isFile)
            val image = ImageIO.read(file)
            image.width to image.height
        }
        assertEquals("every media screenshot has the same size", 1, sizes.toSet().size)
        val (width, height) = sizes.first()
        assertTrue("at least 1200 by 760, was $width by $height", width >= 1200 && height >= 760)
        assertEquals(2560, width)
        assertEquals(1600, height)
    }

    @Test
    fun `test the detail pictures are at most 1600 pixels wide`() {
        for (name in listOf("completion", "jsonata", "variables", "diagram")) {
            val file = File("docs/images/$name.png")
            assertTrue("$name.png is missing", file.isFile)
            val width = ImageIO.read(file).width
            assertTrue("$name.png is $width pixels wide", width <= 1600)
        }
    }
}
