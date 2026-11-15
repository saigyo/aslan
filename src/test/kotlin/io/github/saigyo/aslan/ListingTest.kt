package io.github.saigyo.aslan

import junit.framework.TestCase.assertEquals import junit.framework.TestCase.assertNotNull
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

    @Test
    fun `test the description opens with an English summary`() {
        val summary = descriptionText().take(40)
        assertEquals(40, summary.length)
        assertTrue("not plain ASCII text: $summary", summary.all { it in ' '..'~' })
        assertTrue("starts with: $summary", summary.startsWith("Editor support for AWS Step Functions"))
    }

    @Test
    fun `test every image of the description is a file of this repository`() {
        val sources = Regex("""<img\s[^>]*src="([^"]*)"""").findAll(description()).map { it.groupValues[1] }.toList()
        assertTrue("the description has no image", sources.isNotEmpty())
        for (source in sources) {
            assertTrue("not an image of the repository: $source", source.startsWith(RAW))
            val path = source.removePrefix(RAW)
            assertTrue("not under docs/images/: $path", path.startsWith("docs/images/"))
            assertTrue("missing: $path", File(path).isFile)
        }
    }

    @Test
    fun `test every local link and image of the README exists`() {
        val readme = File("README.md").readText()
        val targets = Regex("""\]\(([^)\s]+)\)""").findAll(readme).map { it.groupValues[1] } +
            Regex("""<img\s[^>]*src="([^"]*)"""").findAll(readme).map { it.groupValues[1] }
        val local = targets.filterNot { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("#") }.toList()
        assertTrue("the README has no local link", local.isNotEmpty())
        for (target in local) assertTrue("missing: $target", File(target.substringBefore('#')).exists())
    }

    @Test
    fun `test the description names the shortcuts`() {
        val text = description()
        for (name in listOf("Next Highlighted Usage", "Previous Highlighted Usage", "Ctrl+Option+Down", "Ctrl+Option+Up")) {
            assertTrue("the description does not name $name", name in text)
        }
    }

    @Test
    fun `test the vendor has an email and a website`() {
        val vendor = Regex("""<vendor\s([^>]*)>""").find(pluginXml())?.groupValues?.get(1).orEmpty()
        assertTrue("vendor email, was: $vendor", """email="aslan@kaixo.de"""" in vendor)
        assertTrue("vendor website, was: $vendor", """url="https://github.com/saigyo/aslan"""" in vendor)
    }

    @Test
    fun `test the change notes describe the version of the build`() {
        val line = File("gradle.properties").readLines().map { it.trim() }.firstOrNull { it.startsWith("version") }.orEmpty()
        val version = Regex("""^version = (\d+\.\d+\.\d+)$""").find(line)?.groupValues?.get(1)
        assertNotNull("the version has three numbers, was: $line", version)
        val notes = Regex("""<change-notes>(.*?)</change-notes>""", RegexOption.DOT_MATCHES_ALL).find(pluginXml())?.groupValues?.get(1)
        // the release workflow publishes the change notes as the text of the release of this version
        assertTrue("the change notes do not start with $version", text(notes.orEmpty()).startsWith("$version:"))
        assertTrue("the change notes are empty", !notes.isNullOrBlank() && text(notes).isNotEmpty())
    }

    @Test
    fun `test the licence and the notice are in the plugin's META-INF`() {
        for (name in listOf("LICENSE", "NOTICE")) {
            // other jars on the class path carry files of the same name; one of them is the repository's
            val found = javaClass.classLoader.getResources("META-INF/$name").toList()
            assertTrue("no /META-INF/$name on the class path", found.isNotEmpty())
            val expected = File(name).readText()
            assertTrue("no /META-INF/$name is the repository's, found: $found", found.any { it.readText() == expected })
        }
    }

    private fun pluginXml(): String = File("src/main/resources/META-INF/plugin.xml").readText()

    private fun description(): String =
        Regex("""<description><!\[CDATA\[(.*?)]]></description>""", RegexOption.DOT_MATCHES_ALL).find(pluginXml())?.groupValues?.get(1).orEmpty()

    /** The description as the reader sees it: without tags, with runs of white space as one space. */
    private fun descriptionText(): String = text(description())

    private fun text(html: String): String =
        html.replace(Regex("""<!\[CDATA\[|]]>"""), "").replace(Regex("<[^>]*>"), " ").replace(Regex("""\s+"""), " ").trim()

    private companion object {
        const val RAW = "https://raw.githubusercontent.com/saigyo/aslan/main/"
    }
}
