package io.github.saigyo.aslan.jsonata

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.testFramework.PlatformTestUtil
import io.github.saigyo.aslan.jsonata.refs.JsonataInplaceRenameHandler
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.check.AslChecker
import io.github.saigyo.aslan.files.AslFiles
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

    fun `test toggling a comment in a quoted scalar over several lines changes nothing`() {
        for (quote in listOf("'", "\"")) {
            val text = yamlScalarHost("$quote{% \$x +\n      <caret>1 %}$quote")
            myFixture.configureByText("multi.asl.yaml", text)
            val plain = text.replace("<caret>", "")
            // a block comment of a selection inside the expression: the JSONata commenter does nothing here
            val editor = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor)
            editor.selectionModel.setSelection(plain.indexOf("1 %}"), plain.indexOf("1 %}") + 1)
            myFixture.performEditorAction(IdeActions.ACTION_COMMENT_BLOCK)
            assertEquals(quote, plain, hostDocText())
            // a line comment on the second line is the YAML plugin's: it puts `#` at the start of the line, which in a
            // quoted scalar is part of the value. Pinned here as a known limitation; the JSONata commenter is not asked.
            myFixture.configureByText("multi2.asl.yaml", text)
            assertEquals(quote, plain.replace("\n          1 %}", "\n#          1 %}"), toggle(text.indexOf("<caret>")))
        }
    }

    fun `test a quote typed outside the injected part of the host is not escaped`() {
        var counter = 0
        for ((style, quote, file, plain) in listOf(
            Quad("single-quoted YAML", "'", "asl", "plain.yaml"),
            Quad("double-quoted YAML", "\"", "asl", "plain2.yaml"),
            Quad("JSON", "\"", "asl", "plain.json"),
        )) {
            val json = plain.endsWith("json")
            val inner = "{% \$x %}"
            val positions = mapOf(
                "before the opening quote" to "<caret>$quote$inner$quote",
                "after the closing quote" to "$quote$inner$quote<caret>",
                "between the opening quote and the delimiter" to "$quote<caret>$inner$quote",
                "between the closing delimiter and the quote" to "$quote$inner<caret>$quote",
            )
            for ((where, value) in positions) {
                val doc = if (json) jsonHost(value) else yamlScalarHost(value)
                // the same typing in a file that is no ASL file: what the platform does without the handler
                val expected = typeIn(doc, plain, quote)
                // a name of its own for each case, ending in .asl.yaml or .asl.json
                val name = "case${counter++}.asl." + if (json) "json" else "yaml"
                myFixture.configureByText(name, doc)
                assertTrue(name, AslFiles.isAslFile(hostFile()))
                assertEquals(1, siteCount())
                myFixture.type(quote)
                val actual = hostDocText()
                // for a double quote the escaped form is distinguishable from the typed character; a single quote is not
                if (quote == "\"") assertFalse("$style $where: $actual", actual.contains("\\\""))
                // the ASL file differs from the plain file only in the file's own contents, which are the same here
                assertEquals("$style $where", expected, actual)
            }
        }
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    fun `test typing a quote in a plain file is left to the platform`() {
        for (name in listOf("plain.yaml", "plain.json", "plain.txt", "Plain.kt")) {
            myFixture.configureByText(name, "x: '{% \$x <caret>%}'")
            myFixture.type("'")
            val withQuote = myFixture.editor.document.text
            assertTrue(name + ": " + withQuote, withQuote.startsWith("x: '{% \$x '"))
        }
    }

    fun `test the host's own quote is escaped at every caret`() {
        myFixture.configureByText("multi.asl.yaml", yamlScalarHost("\"{% \$a & <caret>\$b & <caret>\$c %}\""))
        myFixture.type("\"")
        assertEquals(yamlScalarHost("\"{% \$a & \\\"\$b & \\\"\$c %}\""), hostDocText())
        assertEquals(1, siteCount())

        val two = machineText(
            "Type: Task\nResource: arn:aws:states:::lambda:invoke\nArguments:\n  FunctionName: \"{% <caret>'a' %}\"\nOutput: \"{% \$x & <caret>1 %}\"\nEnd: true",
        )
        myFixture.configureByText("multi2.asl.yaml", two)
        myFixture.type("\"")
        val result = hostDocText()
        assertEquals(two.replace("<caret>", "\\\""), result)
        assertEquals(2, siteCount())
    }

    fun `test one caret inside an expression and one outside it gets the plain quote at both`() {
        val text = machineText(
            "Type: Task\nResource: arn:aws:states:::lambda:invoke\nArguments:\n  FunctionName: <caret>\"{% \$y %}\"\nOutput: \"{% \$x & <caret>1 %}\"\nEnd: true",
        )
        // Without the handler the platform types at both carets through the injected editor of the first one, and puts
        // both characters into that expression, whatever the character is. So the reference is an ordinary character in the
        // same ASL file; the quote must land in the same places, with no exception.
        val ordinary = typeIn(text, "ordinary.asl.yaml", "~").replace("~", "\"")
        val actual = typeIn(text, "mixed.asl.yaml", "\"")
        assertEquals(ordinary, actual)
        assertFalse(actual.contains("\\\""))
    }

    fun `test the host's own quote typed twice with two carets inside works both times`() {
        myFixture.configureByText("twice.asl.yaml", yamlScalarHost("\"{% \$a & <caret>\$b & <caret>\$c %}\""))
        myFixture.type("\"")
        myFixture.type("\"")
        assertEquals(yamlScalarHost("\"{% \$a & \\\"\\\"\$b & \\\"\\\"\$c %}\""), hostDocText())
        // nothing is remembered after the keystrokes
        assertFalse(com.intellij.codeInsight.editorActions.TypedHandlerDelegate.EP_NAME.extensionList
            .filterIsInstance<io.github.saigyo.aslan.jsonata.edit.JsonataTypedHandler>().single().remembersDecision())
        assertEquals(1, siteCount())
        assertEquals(" \$a & \"\"\$b & \"\"\$c ", decoded())
    }

    fun `test typing the host's own quote during an in-place rename leaves the host intact`() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        for (cancel in listOf(true, false)) {
            val before = yamlScalarHost("'{% (\$sum := ''a''; ''b'' & \$s<caret>um) %}'")
            myFixture.configureByText("rename$cancel.asl.yaml", before)
            val host = hostFile()
            val editor = myFixture.editor
            val target = TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().allAccepted)!!
            JsonataInplaceRenameHandler().doRename(target, editor, null)
            myFixture.type("'")
            val top = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
            val state = TemplateManagerImpl.getTemplateState(top)
            if (cancel) state?.cancelTemplate() else state?.gotoEnd(false)
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val text = PsiDocumentManager.getInstance(project).getDocument(host)!!.text
            assertEquals(1, AslModel.of(host)?.jsonataSites?.all?.size)
            // the name typed with a quote is no variable name: the host is rolled back, finished or cancelled
            assertEquals(before.replace("<caret>", ""), text)
        }
    }

    fun `test a line that holds a comment is not nested and a comment is one from end to end`() {
        // two comments on a line are not one comment: toggling comments nothing and uncomments nothing
        val twoComments = blockHost("/*a*/ + <caret>/*b*/")
        myFixture.configureByText("two.asl.yaml", twoComments)
        val plain = twoComments.replace("<caret>", "")
        assertEquals(plain, toggle(twoComments.indexOf("<caret>")))
        assertEquals(plain, toggle(twoComments.indexOf("<caret>")))
        // a line with a comment inside is not wrapped again
        val inside = blockHost("\$x /* c */ + <caret>1")
        myFixture.configureByText("inside.asl.yaml", inside)
        assertEquals(inside.replace("<caret>", ""), toggle(inside.indexOf("<caret>")))
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
