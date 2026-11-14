package io.github.saigyo.aslan.jsonata

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import io.github.saigyo.aslan.check.AslChecker
import io.github.saigyo.aslan.check.AslProblem
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.model.AslModel

/** Enter inside a JSONata expression gives the new line the leading white space of the line the caret is on. */
class JsonataEnterTest : JsonataTestCase() {
    private fun state(output: String): String = machineText(
        "Type: Task\nResource: arn:aws:states:::lambda:invoke\nArguments:\n  FunctionName: catalog-reel\nOutput: $output\nEnd: true",
    )

    private var counter = 0

    private class Result(val text: String, val caret: Int, val sites: Int?, val errors: Int, val decoded: String?)

    private fun hostFile(): PsiFile {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return PsiDocumentManager.getInstance(project).getPsiFile(InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor).document)!!
    }

    private fun snapshot(): Result {
        val host = hostFile()
        val editor = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor)
        val model = AslModel.of(host)
        val decoded = model?.jsonataSites?.all?.singleOrNull()?.scalar?.psi
            ?.let { JsonataPsi.injectedFile(it) }?.let { InjectedLanguageManager.getInstance(project).getUnescapedText(it) }
        return Result(
            editor.document.text, editor.caretModel.offset, model?.jsonataSites?.all?.size,
            AslChecker.findings(host).count { it.problem == AslProblem.JSONATA_SYNTAX_ERROR }, decoded,
        )
    }

    /** Types Enter at `<caret>` of [text] in a file called [name]; returns the state before and after. */
    private fun enter(text: String, name: String): Pair<Result, Result> {
        myFixture.configureByText(name, text)
        val before = snapshot()
        myFixture.type("\n")
        return before to snapshot()
    }

    private fun asl(extension: String) = "enter${counter++}.asl.$extension"

    // machineText indents every line by four more spaces: the block's content is at six, the deeper lines at fourteen
    private val head = "\$exists(\$v)"
    private val deeper = "or (\$type(\$v) = \"string\" and "
    private val tail = "\$contains(\$v, /x/)"

    private fun assertMoved(text: String, name: String, indent: String, collapse: Boolean = false) {
        val (before, after) = enter(text, name)
        val plain = text.replace("<caret>", "")
        val at = text.indexOf("<caret>")
        assertEquals(name, plain.substring(0, at) + "\n" + indent + plain.substring(at), after.text)
        assertEquals(name, at + 1 + indent.length, after.caret)
        assertEquals(name, before.sites, after.sites)
        assertEquals(name, 1, after.sites)
        assertEquals(name, before.errors, after.errors)
        assertEquals(name, 0, after.errors)
        fun tokens(s: String?) = s.orEmpty().trim().split(Regex("\\s+"))
        assertEquals(name, tokens(before.decoded), tokens(after.decoded))
    }

    fun `test Enter in the middle of a deeper line in each block scalar style`() {
        for (style in listOf("|-", ">-")) {
            val text = state("$style\n  {% $head\n          $deeper<caret>$tail) %}")
            assertMoved(text, asl("yaml"), " ".repeat(14))
        }
    }

    fun `test Enter in a block scalar that keeps its final line break is the platform's`() {
        // `|` and `>` keep the final line break: the value does not end in `%}`, so it is no JSONata string and no site
        for (style in listOf("|", ">")) {
            val text = state("$style\n  {% $head\n          $deeper<caret>$tail) %}")
            val (before, after) = enter(text, asl("yaml"))
            assertEquals(0, before.sites)
            val plain = text.replace("<caret>", "")
            val at = text.indexOf("<caret>")
            assertEquals(plain.substring(0, at) + "\n      " + plain.substring(at), after.text)
        }
    }

    fun `test Enter in the middle of a deeper line of a quoted scalar`() {
        assertMoved(state("'{% $head\n          or (\$type(\$v) = ''string'' and <caret>$tail) %}'"), asl("yaml"), " ".repeat(14))
        assertMoved(state("\"{% $head\n          or (\$type(\$v) = \\\"string\\\" and <caret>$tail) %}\""), asl("yaml"), " ".repeat(14))
    }

    fun `test Enter at the end of a deeper line gives an empty line at that depth`() {
        val text = state("|-\n  {% $head\n          or (\$type(\$v) = \"string\")<caret>\n          and $tail %}")
        val (_, after) = enter(text, asl("yaml"))
        val plain = text.replace("<caret>", "")
        val at = text.indexOf("<caret>")
        assertEquals(plain.substring(0, at) + "\n" + " ".repeat(14) + plain.substring(at), after.text)
        assertEquals(at + 15, after.caret)
        assertEquals(1, after.sites)
        assertEquals(0, after.errors)
    }

    fun `test Enter on a line at the base indentation behaves as before`() {
        val text = state("|-\n  {% $head<caret>\n          or (\$type(\$v) = \"string\") %}")
        val (before, after) = enter(text, asl("yaml"))
        val plain = text.replace("<caret>", "")
        val at = text.indexOf("<caret>")
        assertEquals(plain.substring(0, at) + "\n      " + plain.substring(at), after.text)
        assertEquals(at + 7, after.caret)
        assertEquals(before.sites, after.sites)
    }

    fun `test Enter outside an expression is unchanged`() {
        // a plain value of the same file, compared with the same typing in a file that is no ASL file
        val text = state("|-\n  {% 1 %}").replace("FunctionName: catalog-reel", "FunctionName: catalog-<caret>reel")
        val (_, aslResult) = enter(text, asl("yaml"))
        val (_, plainResult) = enter(text, "plain${counter++}.yaml")
        assertEquals(plainResult.text, aslResult.text)
        assertEquals(plainResult.caret, aslResult.caret)
    }

    fun `test Enter in a file that is no ASL file is unchanged`() {
        // the platform's own behaviour: the new line starts at the indentation of the block scalar
        val text = state("|-\n  {% $head\n          $deeper<caret>$tail) %}")
        val (_, after) = enter(text, "plain${counter++}.yaml")
        val plain = text.replace("<caret>", "")
        val at = text.indexOf("<caret>")
        assertEquals(plain.substring(0, at) + "\n      " + plain.substring(at), after.text)
    }

    fun `test Enter in a JSON string is the platform's`() {
        val json = """{"QueryLanguage": "JSONata", "StartAt": "Work", "States": {"Work": {"Type": "Task", "Resource": "arn:aws:states:::lambda:invoke", "Arguments": {"FunctionName": "catalog-reel"}, "Output": "{% $head or <caret>${'$'}x %}", "End": true}}}"""
        val (_, aslResult) = enter(json, asl("json"))
        val (_, plainResult) = enter(json, "plain${counter++}.json")
        assertEquals(plainResult.text, aslResult.text)
        assertEquals(plainResult.caret, aslResult.caret)
    }
}
