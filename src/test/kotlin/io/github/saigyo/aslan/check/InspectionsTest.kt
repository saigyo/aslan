package io.github.saigyo.aslan.check

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity

class InspectionsTest : CheckTestCase() {
    /** Highlights [text] with exactly [tools] of our inspections switched on; the others are switched off explicitly. */
    private fun highlights(text: String, name: String = "test.asl.yaml",
                           tools: List<LocalInspectionTool> = AslInspections.all()): List<Pair<HighlightSeverity, String>> {
        myFixture.configureByText(name, text)
        val on = tools.map { it.shortName }.toSet()
        myFixture.disableInspections(*AslInspections.all().filter { it.shortName !in on }.toTypedArray())
        myFixture.enableInspections(*tools.toTypedArray())
        return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).map { it.severity to it.text }
    }

    fun `test an error finding surfaces as an error on its element`() =
        assertEquals(listOf(HighlightSeverity.ERROR to "Tsak"), highlights(edit("Type: Task" to "Type: Tsak")))

    fun `test a finding in a json file surfaces too`() =
        assertEquals(listOf(HighlightSeverity.ERROR to "\"Tsak\""),
            highlights("""{"StartAt": "A", "States": {"A": {"Type": "Tsak"}}}""", "test.asl.json"))

    fun `test one inspection per problem with its level and a description`() {
        val ours = LocalInspectionEP.LOCAL_INSPECTION.extensionList.filter { it.pluginDescriptor.pluginId.idString == "io.github.saigyo.aslan" }
        val names = AslProblem.entries.map { it.shortName }.toSet()
        assertEquals(AslProblem.entries.size, names.size)
        assertEquals(names, ours.map { it.shortName }.toSet())
        assertEquals(names, AslInspections.all().map { it.shortName }.toSet())
        for (p in AslProblem.entries) {
            val ep = ours.single { it.shortName == p.shortName }
            assertEquals(p.name, p.level, ep.defaultLevel)
            assertNull(p.name, ep.language)
            assertEquals(p.name, "Amazon States Language", ep.groupDisplayName)
            assertNotNull(p.name, javaClass.getResource("/inspectionDescriptions/${p.shortName}.html"))
        }
    }

    fun `test switching one inspection off silences only its problem`() {
        val text = edit("Type: Succeed" to "Type: Sucked", "MaxAttempts: 2" to "MaxAttempts: many")
        assertEquals(listOf("many", "Sucked"), highlights(text).map { it.second })
        val withoutType = AslInspections.all().filter { it.problem != AslProblem.UNKNOWN_STATE_TYPE }
        assertEquals(listOf("many"), highlights(text, tools = withoutType).map { it.second })
    }

    fun `test a warning finding surfaces as a warning`() =
        assertEquals(listOf(HighlightSeverity.WARNING to "Retries"), highlights(edit("    Next: Decide\n" to "    Next: Decide\n    Retries: 3\n")))

    fun `test a Choice without Default surfaces as a weak warning`() =
        assertEquals(listOf(HighlightSeverity.WEAK_WARNING to "Decide"), highlights(edit("    Default: Rejected\n" to "")))

    fun `test nothing in other files`() =
        assertEquals(emptyList<Any>(), highlights(edit("Type: Task" to "Type: Tsak"), "plain.yaml"))

    fun `test clean definitions show nothing`() {
        for (name in listOf("film-archive-intake.asl.yaml", "reel-check.asl.json", "legacy-loan.asl.yaml")) {
            myFixture.configureByFile("clean/$name")
            myFixture.enableInspections(*AslInspections.all().toTypedArray())
            assertEquals(name, emptyList<String>(), myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).map { "${it.severity}: ${it.description} at ${it.text}" })
        }
    }
}
