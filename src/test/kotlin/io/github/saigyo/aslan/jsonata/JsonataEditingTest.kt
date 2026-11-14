package io.github.saigyo.aslan.jsonata

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.check.AslChecker
import io.github.saigyo.aslan.check.AslProblem
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.model.AslModel

class JsonataEditingTest : JsonataTestCase() {
    private fun blockHost(expression: String): String = hostText(expression)

    private fun yamlScalarHost(scalar: String): String =
        machineText("Type: Task\nResource: arn:aws:states:::lambda:invoke\nArguments:\n  FunctionName: catalog-reel\nOutput: $scalar\nEnd: true")

    private fun jsonHost(string: String): String =
        """
        {"QueryLanguage": "JSONata", "StartAt": "Work", "States": {"Work": {"Type": "Task",
        "Resource": "arn:aws:states:::lambda:invoke", "Arguments": {"FunctionName": "catalog-reel"},
        "Output": $string, "End": true}}}
        """.trimIndent()

    /** With the caret in an injected fragment the fixture's editor is the injected one; the host text is what counts. */
    private fun hostDocText(): String = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor).document.text

    private fun hostFile(): PsiFile {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return PsiDocumentManager.getInstance(project).getPsiFile(InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor).document)!!
    }

    private fun siteCount(): Int? = AslModel.of(hostFile())?.jsonataSites?.all?.size

    /** The decoded text of the expression of the only site of the host file. */
    private fun decoded(): String? {
        val host = AslModel.of(hostFile())!!.jsonataSites.all.single().scalar.psi
        return JsonataPsi.injectedFile(host)?.let { InjectedLanguageManager.getInstance(project).getUnescapedText(it) }
    }

    private fun syntaxErrors(): Int = AslChecker.findings(hostFile()).count { it.problem == AslProblem.JSONATA_SYNTAX_ERROR }

    private fun typeIn(text: String, name: String, chars: String): String {
        myFixture.configureByText(name, text)
        myFixture.type(chars)
        return hostDocText()
    }

    fun `test typing an opening bracket adds the closing one`() {
        for ((open, pair) in listOf("(" to "()", "[" to "[]", "{" to "{}")) {
            val result = typeIn(blockHost("\$count<caret>"), "test.asl.yaml", open)
            assertTrue("$open: $result", result.contains("{% \$count$pair %}"))
        }
    }

    fun `test typing an opening bracket directly before an expression does not pair it`() {
        val result = typeIn(blockHost("<caret>\$x"), "test.asl.yaml", "(")
        assertTrue(result, result.contains("{% (\$x %}"))
    }

    fun `test typing a closing bracket before one types over it`() {
        val result = typeIn(blockHost("\$count(<caret>)"), "test.asl.yaml", ")")
        assertTrue(result, result.contains("{% \$count() %}"))
    }

    fun `test typing a double quote in a block scalar adds the closing one`() {
        val result = typeIn(blockHost("<caret>"), "test.asl.yaml", "\"")
        assertTrue(result, result.contains("{% \"\" %}"))
    }

    fun `test the host's own quote is escaped in the host and the expression stays a site`() {
        // (host text with the caret, typed quote, expected host text, expected decoded expression, syntax errors)
        class Case(val name: String, val file: String, val before: String, val quote: String, val after: String, val decoded: String, val errors: Int)
        val cases = listOf(
            // typed quote closes an open string literal
            Case("single-quoted closing", "test.asl.yaml", yamlScalarHost("'{% \$x & ''abc<caret> %}'"), "'",
                yamlScalarHost("'{% \$x & ''abc'' %}'"), " \$x & 'abc' ", 0),
            Case("double-quoted closing", "test.asl.yaml", yamlScalarHost("\"{% \$x & \\\"abc<caret> %}\""), "\"",
                yamlScalarHost("\"{% \$x & \\\"abc\\\" %}\""), " \$x & \"abc\" ", 0),
            Case("JSON closing", "test.asl.json", jsonHost("\"{% \$x & \\\"abc<caret> %}\""), "\"",
                jsonHost("\"{% \$x & \\\"abc\\\" %}\""), " \$x & \"abc\" ", 0),
            // typed quote opens a string literal, which stays unclosed
            Case("single-quoted opening", "test.asl.yaml", yamlScalarHost("'{% \$x & <caret>%}'"), "'",
                yamlScalarHost("'{% \$x & ''%}'"), " \$x & '", 1),
            Case("double-quoted opening", "test.asl.yaml", yamlScalarHost("\"{% \$x & <caret>%}\""), "\"",
                yamlScalarHost("\"{% \$x & \\\"%}\""), " \$x & \"", 1),
            Case("JSON opening", "test.asl.json", jsonHost("\"{% \$x & <caret>%}\""), "\"",
                jsonHost("\"{% \$x & \\\"%}\""), " \$x & \"", 1),
        )
        // each case in a file of its own: the YAML plugin keeps the quote style of an earlier text of the same file
        for ((index, case) in cases.withIndex()) {
            myFixture.configureByText("case$index." + case.file.removePrefix("test."), case.before)
            val sitesBefore = siteCount()
            assertEquals(case.name, 1, sitesBefore)
            myFixture.type(case.quote)
            assertEquals(case.name, case.after.replace("<caret>", ""), hostDocText())
            assertEquals(case.name, sitesBefore, siteCount())
            assertEquals(case.name, case.decoded, decoded())
            assertEquals(case.name, case.errors, syntaxErrors())
        }
    }

    fun `test the host's own quote replaces a selection`() {
        myFixture.configureByText("selection.asl.yaml", yamlScalarHost("\"{% \$x & <selection>abc</selection> %}\""))
        myFixture.type("\"")
        assertEquals(yamlScalarHost("\"{% \$x & \\\" %}\""), hostDocText())
        assertEquals(1, siteCount())
        assertEquals(" \$x & \" ", decoded())
    }

    fun `test the other quote is paired and a block scalar's quote is paired`() {
        var result = typeIn(yamlScalarHost("'{% \$x <caret>%}'"), "test.asl.yaml", "\"")
        assertTrue(result, result.contains("'{% \$x \"\"%}'"))
        result = typeIn(yamlScalarHost("\"{% \$x <caret>%}\""), "test.asl.yaml", "'")
        assertTrue(result, result.contains("\"{% \$x ''%}\""))
        result = typeIn(blockHost("\$x & <caret>"), "test.asl.yaml", "'")
        assertTrue(result, result.contains("{% \$x & '' %}"))
    }

    /** Toggles the line comment with the caret at [at] in the host, and returns the host text. */
    private fun toggle(at: Int): String {
        InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor).caretModel.moveToOffset(at)
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        return hostDocText()
    }

    private fun roundTrip(original: String, afterFirst: String) {
        myFixture.configureByText("test.asl.yaml", original)
        val plain = original.replace("<caret>", "")
        val at = original.indexOf("<caret>")
        assertEquals(afterFirst, toggle(at))
        assertEquals(plain, toggle(at))
    }

    fun `test toggling a comment wraps the line in a block comment and removes it again`() {
        val block = blockHost("\$x + <caret>1")
        roundTrip(block, block.replace("<caret>", "").replace("{% \$x + 1 %}", "{% /* \$x + 1 */ %}"))
    }

    fun `test toggling a comment on one line of a multi-line expression`() {
        val text = yamlScalarHost(">-\n      {% \$x +\n      <caret>1 %}")
        roundTrip(text, text.replace("<caret>", "").replace("      1 %}", "      /* 1 */ %}"))
    }

    fun `test toggling a comment in a single-quoted scalar`() {
        // The line starts in YAML, not in the expression, so the YAML comment applies to the line.
        val text = yamlScalarHost("'{% \$x + <caret>1 %}'")
        roundTrip(text, text.replace("<caret>", "").replace("    Output:", "#    Output:"))
    }

    fun `test Enter inside a multi-line expression keeps the indentation of the block scalar`() {
        val text = machineText(
            "Type: Task\nResource: arn:aws:states:::lambda:invoke\nArguments:\n  FunctionName: catalog-reel\nOutput: >-\n  {% \$x +<caret>\n  1 %}\nEnd: true",
        )
        myFixture.configureByText("test.asl.yaml", text)
        val before = siteCount()
        myFixture.type("\n")
        val lines = hostDocText().lines()
        val first = lines.indexOfFirst { it.contains("{% \$x +") }
        val indent = lines[first].takeWhile { it == ' ' }
        assertEquals(indent, lines[first + 1].takeWhile { it == ' ' })
        assertEquals("", lines[first + 1].trim())
        assertEquals(before, siteCount())
    }
}
