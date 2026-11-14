package io.github.saigyo.aslan.highlight

import io.github.saigyo.aslan.AslTestCase

class AslHighlightingTest : AslTestCase() {
    private fun marks(text: String, name: String = "test.asl.yaml"): List<Pair<String, String>> {
        myFixture.configureByText(name, text.trimIndent())
        return myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey?.externalName?.startsWith("ASL_") == true }
            .sortedBy { it.startOffset }
            .map { it.forcedTextAttributesKey.externalName to it.text }
    }

    private val text = """
        StartAt: Load
        States:
          Load:
            Type: Task
            Resource: ${'$'}{LoadFn}
            Arguments:
              Type: free
              ${'$'}{ShelfTable}:
                Prefix: batches/${'$'}{Stage}/in
            Next: Done
          Done:
            Type: Succeed
    """

    fun `test fields state names references and placeholders`() {
        assertEquals(listOf(
            "ASL_FIELD" to "StartAt", "ASL_STATE_REFERENCE" to "Load", "ASL_FIELD" to "States",
            "ASL_STATE_NAME" to "Load", "ASL_FIELD" to "Type", "ASL_FIELD" to "Resource", "ASL_PLACEHOLDER" to "\${LoadFn}",
            "ASL_FIELD" to "Arguments", "ASL_PLACEHOLDER" to "\${ShelfTable}", "ASL_PLACEHOLDER" to "\${Stage}",
            "ASL_FIELD" to "Next", "ASL_STATE_REFERENCE" to "Done",
            "ASL_STATE_NAME" to "Done", "ASL_FIELD" to "Type"), marks(text))
    }

    fun `test json`() {
        val m = marks("""{"StartAt": "A", "States": {"A": {"Type": "Task", "Resource": "${'$'}{Fn}", "End": true}}}""", "test.asl.json")
        assertTrue(m.toString(), m.containsAll(listOf(
            "ASL_FIELD" to "\"StartAt\"", "ASL_STATE_REFERENCE" to "A", "ASL_STATE_NAME" to "A", "ASL_PLACEHOLDER" to "\${Fn}")))
        assertFalse(m.toString(), m.contains("ASL_FIELD" to "\"A\""))
    }

    fun `test quoted and tagged targets highlight the name only`() {
        val m = marks("StartAt: \"A\"\nStates:\n  'A':\n    Type: Pass\n    Next: !!str &x B\n  B:\n    Type: Succeed")
        assertTrue(m.toString(), m.containsAll(listOf(
            "ASL_STATE_REFERENCE" to "A", "ASL_STATE_NAME" to "A", "ASL_STATE_REFERENCE" to "B", "ASL_STATE_NAME" to "B")))
        assertTrue(m.toString(), m.none { it.first == "ASL_FIELD" && it.second.contains("A") && it.second.length == 3 })
    }

    fun `test a placeholder in a target or a state name key has only the placeholder colour`() {
        val m = marks("StartAt: \${First}\nStates:\n  \${First}:\n    Type: Pass\n    Next: x-\${Y}\n  Z:\n    Type: Succeed")
        assertEquals(listOf("ASL_PLACEHOLDER" to "\${First}", "ASL_PLACEHOLDER" to "\${First}", "ASL_PLACEHOLDER" to "\${Y}"),
            m.filter { it.first == "ASL_PLACEHOLDER" })
        assertEquals(listOf("Z"), m.filter { it.first == "ASL_STATE_NAME" }.map { it.second })
        assertTrue(m.toString(), m.none { it.first == "ASL_STATE_REFERENCE" })
    }

    fun `test placeholders in free-form data and not in comments`() {
        val m = marks("StartAt: A\nStates:\n  A:\n    Type: Pass\n    Result:\n      k: \${In}\n    # \${Comment}\n    End: true")
        assertEquals(listOf("\${In}"), m.filter { it.first == "ASL_PLACEHOLDER" }.map { it.second })
        assertTrue(m.toString(), m.none { it.first == "ASL_FIELD" && it.second == "k" })
    }

    fun `test nothing in other files`() =
        assertEquals(emptyList<Any>(), marks("Type: Task\nResource: ${'$'}{Fn}", "plain.yaml"))

    fun `test broken files do not throw`() {
        marks("States:\n  \${: x\n  ?\n  A: [1, \"\${Q\n  *nope: !!str\nNext: &a")
        marks("{\"States\": {\"A\": ", "test.asl.json")
        marks("", "test.asl.yaml")
    }

    fun `test caret on a placeholder highlights its other occurrences`() {
        myFixture.configureByText("test.asl.yaml", "StartAt: A\nStates:\n  A:\n    Type: Task\n    Resource: \${F<caret>n}\n    Arguments:\n      a: \${Fn}\n      b: x-\${Fn}-y\n      c: \${Other}\n    End: true")
        assertEquals(listOf("\${Fn}", "\${Fn}", "\${Fn}"), highlightedUsages())
    }

    fun `test caret on a placeholder in a key`() {
        myFixture.configureByText("test.asl.json", """{"StartAt": "A", "States": {"A": {"Type": "Pass", "Result": {"${'$'}{K<caret>}": "${'$'}{K}"}, "End": true}}}""")
        assertEquals(listOf("\${K}", "\${K}"), highlightedUsages())
    }

    fun `test caret outside a placeholder leaves the platform usages`() {
        myFixture.configureByText("test.asl.yaml", "StartAt: <caret>A\nStates:\n  A:\n    Type: Task\n    Resource: \${Fn}\n    End: true")
        assertEquals(listOf("A", "A"), highlightedUsages())
    }

    fun `test colour settings page lists the four keys`() {
        val page = AslColorSettingsPage()
        assertEquals(setOf("ASL_FIELD", "ASL_STATE_NAME", "ASL_STATE_REFERENCE", "ASL_PLACEHOLDER"), page.attributeDescriptors.map { it.key.externalName }.toSet())
        assertTrue(page.demoText.isNotBlank())
        for (tag in page.additionalHighlightingTagToDescriptorMap.keys) assertTrue(tag, page.demoText.contains("<$tag>"))
        assertEquals(4, page.additionalHighlightingTagToDescriptorMap.size)
    }
}
