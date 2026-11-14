package io.github.saigyo.aslan.jsonata

import io.github.saigyo.aslan.highlight.AslHighlighting
import io.github.saigyo.aslan.jsonata.highlight.JsonataColorSettingsPage
import io.github.saigyo.aslan.jsonata.highlight.JsonataHighlighting
import io.github.saigyo.aslan.jsonata.highlight.JsonataSyntaxHighlighter
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens as T

class JsonataHighlightingTest : JsonataTestCase() {
    private val keys = listOf(
        JsonataHighlighting.KEYWORD, JsonataHighlighting.NUMBER, JsonataHighlighting.STRING, JsonataHighlighting.REGEX,
        JsonataHighlighting.COMMENT, JsonataHighlighting.OPERATOR, JsonataHighlighting.PARENTHESES, JsonataHighlighting.BRACKETS,
        JsonataHighlighting.BRACES, JsonataHighlighting.SEPARATOR, JsonataHighlighting.VARIABLE, JsonataHighlighting.FIELD,
        JsonataHighlighting.FUNCTION, JsonataHighlighting.STATES,
    )

    /** The semantic marks of the annotator on [text] as (key name, covered host text), in file order. */
    private fun marks(text: String, name: String = "test.asl.yaml"): List<Pair<String, String>> {
        myFixture.configureByText(name, text)
        return myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey?.externalName?.startsWith("ASL_") == true }
            .sortedBy { it.startOffset }
            .map { it.forcedTextAttributesKey.externalName to it.text }
            .distinct() // the host annotator marks a placeholder in the scalar as well
    }

    fun `test token colours`() {
        val highlighter = JsonataSyntaxHighlighter()
        fun colour(type: com.intellij.psi.tree.IElementType) = highlighter.getTokenHighlights(type).map { it.externalName }
        assertEquals(listOf("ASL_JSONATA_NUMBER"), colour(T.NUMBER))
        assertEquals(listOf("ASL_JSONATA_STRING"), colour(T.STRING))
        assertEquals(listOf("ASL_JSONATA_REGEX"), colour(T.REGEX))
        assertEquals(listOf("ASL_JSONATA_COMMENT"), colour(T.COMMENT))
        assertEquals(listOf("ASL_JSONATA_KEYWORD"), colour(T.AND))
        assertEquals(listOf("ASL_JSONATA_KEYWORD"), colour(T.TRUE))
        assertEquals(listOf("ASL_JSONATA_VARIABLE"), colour(T.VARIABLE))
        assertEquals(listOf("ASL_JSONATA_FIELD"), colour(T.NAME))
        assertEquals(listOf("ASL_JSONATA_OPERATOR"), colour(T.PLUS))
        assertEquals(listOf("ASL_JSONATA_PARENTHESES"), colour(T.LPAREN))
        assertEquals(listOf("ASL_JSONATA_BRACKETS"), colour(T.LBRACKET))
        assertEquals(listOf("ASL_JSONATA_BRACES"), colour(T.LBRACE))
        assertEquals(listOf("ASL_JSONATA_SEPARATOR"), colour(T.COMMA))
        assertEquals(listOf("ASL_JSONATA_STRING"), colour(T.BAD_STRING))
        assertEquals(listOf("ASL_JSONATA_REGEX"), colour(T.BAD_REGEX))
        assertEquals(listOf("ASL_PLACEHOLDER"), colour(T.PLACEHOLDER))
        assertEquals(emptyList<String>(), colour(T.WHITE_SPACE))
    }

    fun `test semantic colours in a host file`() {
        val expression = "\$sum(\$states.input.prices) & function(\$v){ \$v } & 'in \${Bucket}'"
        val found = marks(hostText(expression)).filter { it.first.startsWith("ASL_JSONATA_") || it.first == "ASL_PLACEHOLDER" }
        assertEquals(
            listOf(
                "ASL_JSONATA_FUNCTION" to "\$sum", "ASL_JSONATA_STATES" to "\$states", "ASL_JSONATA_KEYWORD" to "function",
                "ASL_PLACEHOLDER" to "\${Bucket}",
            ),
            found,
        )
    }

    fun `test semantic colours in a json string`() {
        val text = """{"QueryLanguage": "JSONata", "StartAt": "A", "States": {"A": {"Type": "Pass", "Output": "{% ${'$'}count(${'$'}states.input) & 'x${'$'}{P}' %}", "End": true}}}"""
        assertEquals(
            listOf("ASL_JSONATA_FUNCTION" to "\$count", "ASL_JSONATA_STATES" to "\$states", "ASL_PLACEHOLDER" to "\${P}"),
            marks(text, "test.asl.json").filter { it.first != "ASL_FIELD" && it.first != "ASL_STATE_NAME" && it.first != "ASL_STATE_REFERENCE" },
        )
    }

    fun `test a local variable named like a function is not coloured as one`() {
        val found = marks(hostText("(\$sum := 1; \$sum)"))
        assertEquals(emptyList<Pair<String, String>>(), found.filter { it.first.startsWith("ASL_JSONATA_") })
    }

    fun `test a lambda and an unknown variable`() {
        val found = marks(hostText("\$f(\$unknown) ~> λ(\$x){ \$x }")).filter { it.first.startsWith("ASL_JSONATA_") }
        assertEquals(listOf("ASL_JSONATA_KEYWORD" to "λ"), found)
    }

    fun `test broken expressions do not throw`() {
        for (e in listOf("function(", "\$sum(", "'open \${X}", "/re", "(\$a := ; \$a", "λ(\$x", "\$states.")) marks(hostText(e))
    }

    fun `test the colour page lists every key`() {
        val page = JsonataColorSettingsPage()
        assertEquals(keys.map { it.externalName }.toSet(), page.attributeDescriptors.map { it.key.externalName }.toSet())
        assertTrue(keys.all { it.externalName.startsWith("ASL_JSONATA_") })
        assertTrue(page.demoText.isNotBlank())
        for (tag in page.additionalHighlightingTagToDescriptorMap.keys) assertTrue(tag, page.demoText.contains("<$tag>"))
        val plain = page.demoText.replace(Regex("</?\\w+>"), "")
        val highlighter = page.highlighter
        val lexer = highlighter.highlightingLexer
        lexer.start(plain)
        val used = HashSet<String>()
        while (lexer.tokenType != null) {
            highlighter.getTokenHighlights(lexer.tokenType).forEach { used += it.externalName }
            lexer.advance()
        }
        used += page.additionalHighlightingTagToDescriptorMap.values.map { it.externalName }
        for (key in keys) assertTrue(key.externalName, key.externalName in used)
    }

    fun `test the demo text is coloured as the annotator colours it`() {
        val page = JsonataColorSettingsPage()
        val tags = page.additionalHighlightingTagToDescriptorMap
        val demo = page.demoText
        val expected = Regex("<(\\w+)>(.*?)</\\1>").findAll(demo).map { tags.getValue(it.groupValues[1]).externalName to it.groupValues[2] }.toList()
        val plain = demo.replace(Regex("</?\\w+>"), "")
        val found = marks(hostText(plain.replace("\n", " "))).filter { it.first.startsWith("ASL_JSONATA_") || it.first == "ASL_PLACEHOLDER" }
        assertEquals(expected, found)
    }

    fun `test the caret on a placeholder inside an expression highlights its other occurrences`() {
        myFixture.configureByText(
            "test.asl.yaml",
            machineText(
                "Type: Task\nResource: \${Bucket}\nArguments:\n  FunctionName: catalog-reel\nOutput: \"{% 'in \${Buck<caret>et}' %}\"\nEnd: true",
            ),
        )
        assertEquals(listOf("\${Bucket}", "\${Bucket}"), highlightedUsages())
    }
}
