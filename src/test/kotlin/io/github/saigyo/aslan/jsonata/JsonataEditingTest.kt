package io.github.saigyo.aslan.jsonata

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
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

    private fun siteCount(): Int? {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val host = PsiDocumentManager.getInstance(project).getPsiFile(InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor).document)
        return host?.let { AslModel.of(it)?.jsonataSites?.all?.size }
    }

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

    fun `test typing a closing bracket before one types over it`() {
        val result = typeIn(blockHost("\$count(<caret>)"), "test.asl.yaml", ")")
        assertTrue(result, result.contains("{% \$count() %}"))
        assertFalse(result, result.contains("))"))
    }

    fun `test typing a double quote in a block scalar adds the closing one`() {
        val result = typeIn(blockHost("<caret>"), "test.asl.yaml", "\"")
        assertTrue(result, result.contains("{% \"\" %}") || result.contains("{%\"\" %}") || result.contains("\"\""))
    }

    fun `test a quote that is the host string's own is not paired`() {
        // single-quoted YAML: ' is the host's quote
        var text = yamlScalarHost("'{% \$x <caret>%}'")
        val before = text.replace("<caret>", "")
        var result = typeIn(text, "test.asl.yaml", "'")
        assertTrue(result, result.contains("Output: '{% \$x '%}'"))
        assertEquals(before.length + 1, result.length)
        

        // double-quoted YAML: " is the host's quote
        text = yamlScalarHost("\"{% \$x <caret>%}\"")
        result = typeIn(text, "test.asl.yaml", "\"")
        assertTrue(result, result.contains("Output: \"{% \$x \"%}\""))
        assertEquals(text.replace("<caret>", "").length + 1, result.length)
        

        // JSON string
        text = jsonHost("\"{% \$x <caret>%}\"")
        myFixture.configureByText("test.asl.json", text)
        val sitesBefore = siteCount()
        assertEquals(1, sitesBefore)
        result = typeIn(text, "test.asl.json", "\"")
        assertTrue(result, result.contains("\"Output\": \"{% \$x \"%}\""))
        assertEquals(text.replace("<caret>", "").length + 1, result.length)
        // Pinned: the typed quote is not escaped, so it ends the JSON string and the expression is no longer a site.
        assertEquals(0, siteCount())

        // the other quote is paired
        result = typeIn(yamlScalarHost("'{% \$x <caret>%}'"), "test.asl.yaml", "\"")
        assertTrue(result, result.contains("\$x \"\"%}"))
        result = typeIn(yamlScalarHost("\"{% \$x <caret>%}\""), "test.asl.yaml", "'")
        assertTrue(result, result.contains("\$x ''%}"))
    }

    fun `test toggling a comment wraps the line in a block comment and removes it again`() {
        // The platform finds the comment to remove again only when no white space follows it on the line, so the
        // closing delimiter sits directly behind the expression here.
        myFixture.configureByText("test.asl.yaml", blockHost("\$x + <caret>1").replace("1 %}", "1%}"))
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        val commented = hostDocText()
        assertTrue(commented, commented.contains("/*") && commented.contains("*/"))
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        val restored = hostDocText()
        assertFalse(restored, restored.contains("/*") || restored.contains("*/"))
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
