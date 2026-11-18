package io.github.saigyo.aslan.fixtures

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.saigyo.aslan.diagram.DiagramBuilder
import io.github.saigyo.aslan.links.AslCodeLinkInspection
import io.github.saigyo.aslan.model.AslModel

/** The synthetic definitions link to the invented source files beside them, as the demo project shows them. */
class CleanLinksTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject("clean", "")
        myFixture.enableInspections(AslCodeLinkInspection())
    }

    /** The number of links to code in the details rows of [name]'s states; fails on a path or type that is not found. */
    private fun links(name: String): Int {
        myFixture.configureFromTempProjectFile(name)
        val warnings = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
            .filter { it.description?.startsWith("File not found") == true || it.description?.contains(" not found in ") == true }
        assertEquals(name, emptyList<String>(), warnings.map { it.description })
        val model = DiagramBuilder.build(AslModel.of(myFixture.file)!!)
        return model.details.values.flatten().filter { it.label == "Notes" || it.label == "Description" }.sumOf { row -> row.items.sumOf { it.links.size } }
    }

    fun `test the intake definition links its Lambda functions and their contracts`() =
        assertEquals(6, links("film-archive-intake.asl.yaml"))

    fun `test the restoration definition links its Lambda functions and their contracts`() =
        assertEquals(6, links("reel-restoration.asl.yaml"))

    fun `test the check definition in JSON links from a Comment field`() =
        assertEquals(3, links("reel-check.asl.json"))
}
