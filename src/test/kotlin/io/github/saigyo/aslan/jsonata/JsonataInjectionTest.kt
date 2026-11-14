package io.github.saigyo.aslan.jsonata

import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.saigyo.aslan.check.AslChecker
import io.github.saigyo.aslan.check.AslProblem
import io.github.saigyo.aslan.fixtures.CleanCoverageTest
import io.github.saigyo.aslan.jsonata.parser.JsonataScanner
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.model.AslModel

class JsonataInjectionTest : JsonataTestCase() {
    /** [machineText] of a Task state in which [snippet] stands where [hostText] puts its `Output`. */
    private fun stateText(snippet: String): String = machineText(
        "Type: Task\nResource: arn:aws:states:::lambda:invoke\nArguments:\n  FunctionName: catalog-reel\n$snippet\n" +
            "Catch:\n  - ErrorEquals: [States.ALL]\n    Next: Failed\nEnd: true",
    )

    private val manager get() = InjectedLanguageManager.getInstance(project)
    private var files = 0

    /**
     * Each snippet in a file of its own: the fixture replaces the text of a file of the same name in place, and a quoted
     * scalar that changes its quotes that way keeps its old quote style (see the test of that case below).
     */
    private fun host(text: String): PsiFile = yaml(text, "test${files++}.asl.yaml")

    /**
     * The JSONata file at `<caret>` of the file just configured, or null. The fixture hands out the injected file and
     * editor when the caret is in one, so the lookup starts from the host file and the host editor.
     */
    private fun injectedAtCaret(file: PsiFile): JsonataFile? {
        val host = manager.getTopLevelFile(file)
        val editor = myFixture.editor.let { (it as? EditorWindow)?.delegate ?: it }
        return manager.findInjectedElementAt(host, editor.caretModel.offset)?.containingFile as? JsonataFile
    }

    /** The decoded text of the JSONata file at `<caret>` in [snippet], or null. */
    private fun injected(snippet: String): String? = injectedAtCaret(host(stateText(snippet)))?.let { manager.getUnescapedText(it) }

    /** The texts of the tokens of the JSONata file at `<caret>` in [snippet], without whitespace. */
    private fun tokens(snippet: String): List<String> = leaves(injectedAtCaret(host(stateText(snippet)))!!).map { it.second }

    /** The type and decoded text of every token of [file] that is not whitespace. */
    private fun leaves(file: PsiFile): List<Pair<String, String>> =
        PsiTreeUtil.collectElements(file) { it.firstChild == null && it !is PsiWhiteSpace && it !is PsiErrorElement }
            .map { it.node.elementType.toString() to manager.getUnescapedText(it) }

    private fun scanned(text: String): List<Pair<String, String>> =
        JsonataScanner.scan(text).tokens.filter { it.type != JsonataTokens.WHITE_SPACE }.map { it.type.toString() to text.substring(it.start, it.end) }

    fun `test each scalar style`() {
        assertEquals(" \$states.input.a & 'x' ", injected("Output: '{% <caret>\$states.input.a & ''x'' %}'"))
        assertEquals(" \$states.input.a & \"x\" ", injected("Output: \"{% <caret>\$states.input.a & \\\"x\\\" %}\""))
        val words = listOf("\$states", ".", "input", ".", "a", "and", "true")
        assertEquals(words, tokens("Output: >-\n  {% <caret>\$states.input.a\n  and true %}"))       // folded
        assertEquals(words, tokens("Output: |-\n  {% <caret>\$states.input.a\n  and true %}"))       // literal
        assertEquals(words, tokens("Output: '{% <caret>\$states.input.a\n  and true %}'"))            // quoted, two lines: "a" and "and" stay apart
        assertEquals(words, tokens("Output: \"{% <caret>\$states.input.a\n  and true %}\""))
        assertEquals(listOf("'abcd'"), tokens("Output: \"{% <caret>'ab\\\n  cd' %}\""))              // escaped line break: nothing in between, and no backslash
    }

    fun `test the remaining scalar styles`() {
        assertEquals(" \$states.input.a ", injected("Output: \"{% <caret>\$states.input.a %}\""))
        assertEquals(" \$states.input.a ", injected("Output: !!str '{% <caret>\$states.input.a %}'"))
        assertEquals(" \$states.input.a ", injected("Output: &shared '{% <caret>\$states.input.a %}'"))
        // The YAML plugin decodes a single-quoted scalar with an anchor in front as a double-quoted one.
        assertNull(injected("Output: &shared '{% <caret>''x'' %}'"))
        assertEquals(" \"x\" ", injected("Output: &shared \"{% <caret>\\\"x\\\" %}\""))
        val words = listOf("\$states", ".", "input", ".", "a", "and", "true")
        assertEquals(words, tokens("Output: >- # a comment {% 0 %}\n  {% <caret>\$states.input.a\n  and true %}"))
        assertEquals(" 1 ", injected("Output: '{% <caret>1 %}'"))
        assertEquals(" ", injected("Output: '{%<caret> %}'"))
    }

    fun `test block scalars keep the tokens`() {
        val words = listOf("\$states", ".", "input", ".", "a", "and", "true")
        assertEquals(words, tokens("Output: |-\n  {% <caret>\$states.input.a\n      and\n        true %}"))  // deeper-indented lines
        assertEquals(words, tokens("Output: >-\n  {% <caret>\$states.input.a\n\n  and true %}"))           // an empty line
        assertEquals(words, tokens("Output: |-\n  {% <caret>\$states.input.a\n\n  and true %}"))
        // A string literal that spans two lines of a folded scalar: the value has "ab cd", the injected token the line break.
        assertEquals(listOf("'ab\ncd'"), tokens("Output: >-\n  {% <caret>'ab\n  cd' %}"))
        assertEquals(listOf("'ab\ncd'"), tokens("Output: |-\n  {% <caret>'ab\n  cd' %}"))
    }

    fun `test quoted scalars over several lines fold as YAML does`() {
        assertEquals(" 'ab cd' ", injected("Output: '{% <caret>''ab\n  cd'' %}'"))
        assertEquals(" 'ab\ncd' ", injected("Output: '{% <caret>''ab\n\n  cd'' %}'"))
        assertEquals(" 'ab\n\ncd' ", injected("Output: \"{% <caret>'ab\n\n\n  cd' %}\""))
        assertEquals(" 'a  b' ", injected("Output: \"{% <caret>'a\\ \n  b' %}\""))                // an escaped space before the break
        assertEquals(" 'a\\ b' ", injected("Output: \"{% <caret>'a\\\\\n  b' %}\""))                // an escaped backslash ends the line
        assertEquals(listOf("1", "+", "2"), tokens("Output: '{%\n  <caret>1 +\n  2\n  %}'"))
    }

    fun `test a json string`() {
        val file = json(
            """
            {"QueryLanguage": "JSONata", "StartAt": "Work", "States": {
              "Work": {"Type": "Pass", "Output": {"a": "{% <caret>${'$'}states.input.a & \"x\" %}", "b": "{% 1 +\n 2 %}"}, "End": true}
            }}
            """,
        )
        assertEquals(" \$states.input.a & \"x\" ", manager.getUnescapedText(injectedAtCaret(file)!!))
        val second = AslModel.of(manager.getTopLevelFile(file))!!.jsonataSites.all[1].scalar.psi
        assertEquals(" 1 +\n 2 ", manager.getUnescapedText(JsonataPsi.injectedFile(second)!!))
    }

    fun `test a json string whose delimiters are escapes gets no injection`() {
        val file = json(
            """
            {"QueryLanguage": "JSONata", "StartAt": "Work", "States": {
              "Work": {"Type": "Pass", "Output": "\u007b% 1 %}", "End": true}
            }}
            """,
        )
        val site = AslModel.of(file)!!.jsonataSites.all.single()
        assertEquals("{% 1 %}", site.scalar.text)
        assertNull(JsonataPsi.injectedFile(site.scalar.psi))
    }

    fun `test nothing is injected where JSONata is not evaluated`() {
        val cases = listOf(
            stateText("Comment: '{% <caret>1 %}'"),
            "StartAt: Work\nStates:\n  Work:\n    Type: Pass\n    Parameters:\n      a: '{% <caret>1 %}'\n    End: true\n",
            "QueryLanguage: JSONata\nComment: '{% <caret>1 %}'\nStartAt: Work\nStates:\n  Work:\n    Type: Succeed\n",
            "QueryLanguage: \${Lang}\nStartAt: Work\nStates:\n  Work:\n    Type: Pass\n    Output: '{% <caret>1 %}'\n    End: true\n",
            stateText("Output: '{% <caret>1'"),
            stateText("Output: |\n  {% <caret>1 %}"),
            stateText("Output: |\n  {% <caret>1\n  + 2 %}"),
            stateText("Output: >\n  {% <caret>1 %}"),
            stateText("Output: plain {% <caret>1 %}"),
            stateText("Output: !Sub '{% <caret>1 %}'"),
            "QueryLanguage: JSONata\nStartAt: Work\nStates:\n  Work:\n    Type: \${Kind}\n    Output: '{% <caret>1 %}'\n    End: true\n",
            stateText("Output: '{%<caret>}'"),
            stateText("Output: '{%<caret>%}'"),
        )
        for (case in cases) assertNull(case, injectedAtCaret(host(case)))
    }

    fun `test the empty expression has a site but no injected file`() {
        val file = host(stateText("Output: '{%%}'"))
        val site = AslModel.of(file)!!.jsonataSites.all.single()
        assertNull(JsonataPsi.injectedFile(site.scalar.psi))
    }

    fun `test the sites of an injected file`() {
        val file = host(stateText("Output: '{% <caret>1 %}'"))
        val injected = injectedAtCaret(file)!!
        assertEquals(listOf("Work"), JsonataPsi.sites(injected).map { it.state.name })
        assertSame(injected, JsonataPsi.injectedFile(JsonataPsi.sites(injected).single().scalar.psi))
        assertEquals(emptyList<Any>(), JsonataPsi.sites(manager.getTopLevelFile(file)))
        assertEquals(emptyList<Any>(), JsonataPsi.sites(jsonata("1")))
    }

    fun `test ranges in the host`() {
        val file = host(stateText("Output: '{% <caret>\$states.input.a & ''x'' %}'"))
        val injected = injectedAtCaret(file)!!
        fun host(range: TextRange): String {
            val (host, inHost) = JsonataPsi.rangeInHost(injected, range)!!
            return inHost.substring(host.text)
        }
        val text = injected.text  // the host's text as written
        assertEquals(" \$states.input.a & ''x'' ", text)
        assertEquals("\$states", host(TextRange.from(text.indexOf('$'), 7)))
        assertEquals("''x''", host(TextRange.from(text.indexOf("''x''"), 5)))
        assertEquals("", host(TextRange.from(text.indexOf('&'), 0)))
        val (_, empty) = JsonataPsi.rangeInHost(injected, TextRange.from(text.indexOf('&'), 0))!!
        val (_, amp) = JsonataPsi.rangeInHost(injected, TextRange.from(text.indexOf('&'), 1))!!
        assertEquals(amp.startOffset, empty.startOffset)

        val multi = host(stateText("Output: '{% <caret>\$states.input.a\n  and true %}'"))
        val two = injectedAtCaret(multi)!!
        assertEquals(" \$states.input.a and true ", two.text)
        val (multiHost, spanned) = JsonataPsi.rangeInHost(two, TextRange(two.text.indexOf(".a") + 1, two.text.indexOf("and") + 3))!!
        assertEquals("a\n      and", spanned.substring(multiHost.text))
        // the suffix that stands for the line break maps to the end of the first line
        val (_, gap) = JsonataPsi.rangeInHost(two, TextRange.from(two.text.indexOf(" and"), 1))!!
        assertEquals(TextRange.from(multiHost.text.indexOf("a\n") + 1, 0), gap)
    }

    fun `test the injected tokens are the tokens of the value`() {
        var count = 0
        for (name in CleanCoverageTest.cleanDefinitions()) {
            val file = myFixture.configureByFile("clean/$name")
            for (site in AslModel.of(file)!!.jsonataSites.all) {
                val text = site.scalar.text
                val expected = scanned(text.substring(2, text.length - 2))
                val injected = JsonataPsi.injectedFile(site.scalar.psi)
                if (expected.isEmpty() && text.length == 4) { assertNull(injected); continue }
                assertNotNull("$name: $text", injected)
                assertEquals("$name: $text", expected, leaves(injected!!))
                count++
            }
        }
        assertTrue("sites in the clean definitions: $count", count > 100)
    }

    fun `test each injected token maps back to its text in the host`() {
        for (name in CleanCoverageTest.cleanDefinitions()) {
            val file = myFixture.configureByFile("clean/$name")
            for (site in AslModel.of(file)!!.jsonataSites.all) {
                val injected = JsonataPsi.injectedFile(site.scalar.psi) ?: continue
                val leaves = PsiTreeUtil.collectElements(injected) { it.firstChild == null && it !is PsiWhiteSpace && it.textLength > 0 }
                for (leaf in leaves) {
                    val (host, range) = JsonataPsi.rangeInHost(injected, leaf.textRange)!!
                    assertSame(site.scalar.psi, host)
                    assertEquals("$name: ${site.scalar.text}", leaf.text, range.substring(host.text))
                    if (leaf.text.none { it == '\'' || it == '"' || it == '\\' }) assertEquals(leaf.text, manager.getUnescapedText(leaf))
                }
            }
        }
    }

    fun `test a scalar whose quote style changes in place is never decoded by its old quotes`() {
        val styles = listOf("'{% ''x'' %}'" to " 'x' ", "\"{% \\\"x\\\" %}\"" to " \"x\" ")
        for ((from, to) in listOf(0 to 1, 1 to 0, 0 to 1)) {
            yaml(stateText("Output: ${styles[from].first}"), "same.asl.yaml")
            val file = manager.getTopLevelFile(yaml(stateText("Output: ${styles[to].first}"), "same.asl.yaml"))
            val host = AslModel.of(file)!!.jsonataSites.all.single().scalar.psi
            val injected = JsonataPsi.injectedFile(host)
            // Pinned behaviour, and a defect of the YAML plugin (it decodes a scalar by its old quote style): this may be
            // relaxed to "decoded correctly" when a platform update fixes it. From single quotes to double quotes with escapes nothing is injected (no stale decoding,
            // no error); from double to single quotes the new text is decoded by the new quotes.
            if (to == 1) assertNull("style $from to $to", injected)
            else assertEquals(styles[to].second, manager.getUnescapedText(injected!!))
            assertEquals(emptyList<Any>(), AslChecker.findings(file).filter { it.problem == AslProblem.JSONATA_SYNTAX_ERROR })
        }
    }

    fun `test the injector copes with half-typed files`() {
        val texts = listOf(
            stateText("Output: '{% <caret>1 %}"),
            stateText("Output: \"{% <caret>1 %}"),
            stateText("Output: \"{% <caret>1 \\"),
            stateText("Output: >-\n<caret>"),
            stateText("Output: '{% <caret>"),
            "QueryLanguage: JSONata\nStates:\n  Work:\n    Output: '{% <caret>1 %}'\n",
            "<caret>'{% 1 %}'",
        )
        for (text in texts) {
            val file = host(text)
            myFixture.doHighlighting()
            injectedAtCaret(file)
        }
        myFixture.configureByText("plain.yaml", "Output: '{% <caret>1 %}'")
        assertNull(injectedAtCaret(myFixture.file))
    }
}
