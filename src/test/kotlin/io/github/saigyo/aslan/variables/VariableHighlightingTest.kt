package io.github.saigyo.aslan.variables

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.IdentifierHighlighterPassFactory
import com.intellij.codeInsight.daemon.impl.IdentifierHighlightingComputer
import com.intellij.codeInsight.daemon.impl.IdentifierHighlightingResult
import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.util.ProperTextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import io.github.saigyo.aslan.highlight.ElementUnderCaretUsageAction
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import java.io.File

class VariableHighlightingTest : VariableTestCase() {
    /**
     * `reel` is assigned in Load and in the Catcher of Load, and read in a block scalar, a single-quoted scalar and a
     * JSONPath string; `shelf` is assigned and read once. The second branch of the Parallel state assigns its own
     * `reel` and reads it. `§` stands for `$`, `@@` marks the caret.
     */
    private val archive = """
        StartAt: Load
        States:
          Load:
            Type: Task
            QueryLanguage: JSONata
            Resource: arn:aws:states:::lambda:invoke
            Arguments:
              FunctionName: catalog-intake
            Assign:
              reel: "{% §states.result.Payload.reel %}"
              shelf: 4
            Catch:
              - ErrorEquals: [States.ALL]
                Assign:
                  reel: none
                Next: Check
            Next: Check
          Check:
            Type: Pass
            QueryLanguage: JSONata
            Output:
              block: |-
                {% §reel & '-block' %}
              quoted: '{% §reel & §shelf %}'
              local: '{% (§total := 1; §total + §total) %}'
            Next: Shelve
          Shelve:
            Type: Pass
            InputPath: §reel.title
            End: true
    """.trimIndent()

    private fun configure(marker: String, withCaret: String, text: String = archive) {
        yaml(text.replaceFirst(marker, withCaret).replace("§", "$"))
    }

    private val reelHighlights = listOf(
        "reel" to true, "reel" to true,
        "\$reel" to false, "\$reel" to false, "\$reel" to false,
    )

    fun `test the caret on an assignment highlights writes and reads`() {
        configure("  reel: none", "  re<caret>el: none")
        assertEquals(reelHighlights, highlightedAccesses())
    }

    fun `test the caret on a JSONata read in a block scalar highlights the same`() {
        configure("{% §reel & '-block'", "{% §re<caret>el & '-block'")
        assertEquals(reelHighlights, highlightedAccesses())
    }

    fun `test the caret on a JSONata read in a single-quoted scalar highlights the same`() {
        configure("{% §reel & §shelf", "{% §re<caret>el & §shelf")
        assertEquals(reelHighlights, highlightedAccesses())
    }

    fun `test the caret on a JSONata read in a JSON string highlights the same`() {
        val text = """
            {
              "StartAt": "Load",
              "States": {
                "Load": {
                  "Type": "Pass",
                  "QueryLanguage": "JSONata",
                  "Assign": { "reel": 1 },
                  "Next": "Show"
                },
                "Show": {
                  "Type": "Pass",
                  "QueryLanguage": "JSONata",
                  "Output": "{% §re<caret>el %}",
                  "End": true
                }
              }
            }
        """.trimIndent().replace("§", "$")
        json(text)
        assertEquals(listOf("reel" to true, "\$reel" to false), highlightedAccesses())
    }

    fun `test the caret on a JSONPath read highlights the same`() {
        configure("InputPath: §reel.title", "InputPath: §re<caret>el.title")
        assertEquals(reelHighlights, highlightedAccesses())
    }

    fun `test only the variable under the caret`() {
        val text = """
            StartAt: Fan
            States:
              Fan:
                Type: Parallel
                Assign:
                  shelf: 2
                Branches:
                  - StartAt: One
                    States:
                      One:
                        Type: Pass
                        QueryLanguage: JSONata
                        Assign:
                          reel: 5
                        Output: "{% §re<caret>el + §shelf %}"
                        End: true
                  - StartAt: Two
                    States:
                      Two:
                        Type: Pass
                        QueryLanguage: JSONata
                        Assign:
                          reel: 6
                        Output: "{% §reel %}"
                        End: true
                End: true
        """.trimIndent().replace("§", "$")
        yaml(text)
        // the second branch's own reel and the outer shelf stay unmarked
        assertEquals(listOf("reel" to true, "\$reel" to false), highlightedAccesses())
    }

    fun `test a second configured file is highlighted too`() {
        configure("  reel: none", "  re<caret>el: none")
        assertEquals(reelHighlights, highlightedAccesses())
        configure("{% §reel & §shelf", "{% §re<caret>el & §shelf")
        assertEquals(reelHighlights, highlightedAccesses())
    }

    fun `test reads in multi-line quoted and folded scalars`() {
        val text = """
            QueryLanguage: JSONata
            StartAt: Load
            States:
              Load:
                Type: Pass
                Assign:
                  re<caret>el: 1
                Output:
                  quoted: "{% §reel &
                    §reel %}"
                  folded: >-
                    {% §reel
                    & §reel %}
                End: true
        """.trimIndent().replace("§", "$")
        yaml(text)
        assertEquals(listOf("reel" to true) + List(4) { "\$reel" to false }, highlightedAccesses())
    }

    fun `test the dollar suffix of a JSONPath key is not highlighted`() {
        val text = """
            StartAt: Load
            States:
              Load:
                Type: Pass
                Assign:
                  re<caret>el.§: §.reel
                Next: Show
              Show:
                Type: Pass
                InputPath: §reel
                End: true
        """.trimIndent().replace("§", "$")
        yaml(text)
        assertEquals(listOf("reel" to true, "\$reel" to false), highlightedAccesses())
    }

    fun `test the Result of a Pass state is not highlighted`() {
        val text = """
            StartAt: Load
            States:
              Load:
                Type: Pass
                Assign:
                  re<caret>el: 1
                Next: Show
              Show:
                Type: Pass
                Result:
                  query.§: §reel
                InputPath: §reel
                End: true
        """.trimIndent().replace("§", "$")
        yaml(text)
        assertEquals(listOf("reel" to true, "\$reel" to false), highlightedAccesses())
    }

    /**
     * The automatic highlighting of the element under the caret, as the platform's background highlighter computes it:
     * with the fixture's file and editor, the injected ones when the caret is in an expression. The platform reads the
     * occurrences in host coordinates in either case, as it does for its own highlighting of a local JSONata variable;
     * each is given as its host start offset, its text in the host document and `true` for a write.
     */
    private fun automaticHighlights(): List<Triple<Int, String, Boolean>> {
        val file = myFixture.file
        val editor = myFixture.editor
        val caret = editor.caretModel.offset
        val length = editor.document.textLength
        val result = ApplicationManager.getApplication().executeOnPooledThread<IdentifierHighlightingResult> {
            runReadActionBlocking { IdentifierHighlightingComputer(file, editor, ProperTextRange(0, length), caret).computeRanges() }
        }.get()
        val host = InjectedLanguageEditorUtil.getTopLevelEditor(editor).document.charsSequence
        return result.occurrences.map {
            val range = it.range
            Triple(range.startOffset, host.subSequence(range.startOffset, range.endOffset).toString(), it.highlightInfoType == HighlightInfoType.ELEMENT_UNDER_CARET_WRITE)
        }.sortedBy { it.first }
    }

    private val intake by lazy { File("src/test/testData/clean/film-archive-intake.asl.yaml").readText() }

    /** Configures the film archive fixture with the caret one character into the [index]th occurrence of [word]. */
    private fun intakeAt(word: String, index: Int = 0) {
        var at = -1
        repeat(index + 1) { at = intake.indexOf(word, at + 1) }
        assertTrue(at >= 0)
        yaml(intake.substring(0, at + 1) + "<caret>" + intake.substring(at + 1), "film-archive-intake.asl.yaml")
    }

    /** The host offset and text of every assignment key and read of `rejection` in the fixture, with `true` for a write. */
    private fun everyRejection(): List<Triple<Int, String, Boolean>> {
        val keys = Regex("""(?m)^\s+(rejection):""").findAll(intake).map { Triple(it.groups[1]!!.range.first, "rejection", true) }
        val reads = Regex("""\${'$'}rejection\b""").findAll(intake).map { Triple(it.range.first, "\$rejection", false) }
        return (keys + reads).sortedBy { it.first }.toList()
    }

    fun `test the automatic highlighting from inside an expression marks every usage in host coordinates`() {
        intakeAt("\$rejection.code")
        assertTrue(myFixture.file is JsonataFile)
        val expected = everyRejection()
        assertEquals(24, expected.size)
        assertEquals(expected, automaticHighlights())
        // the action marks the same
        assertEquals(expected.map { it.second to it.third }, highlightedAccesses())
    }

    fun `test the automatic highlighting from an assignment marks every usage`() {
        intakeAt("rejection:")
        assertEquals(everyRejection(), automaticHighlights())
    }

    /**
     * Runs the automatic highlighting of the element under the caret as the daemon does, then [action] (Next or
     * Previous Highlighted Usage) [times] times, and gives the host line (1-based) the caret ends on.
     */
    private fun lineAfter(action: String, times: Int = 1): Int {
        var line = -1
        IdentifierHighlighterPassFactory.doWithIdentifierHighlightingEnabled(project) {
            myFixture.doHighlighting()
            // the background highlighter starts on a caret move
            val host = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor)
            val caret = host.caretModel.offset
            host.caretModel.moveToOffset(caret - 1)
            host.caretModel.moveToOffset(caret)
            IdentifierHighlighterPassFactory.waitForIdentifierHighlighting(host)
            assertTrue(usageHighlights(host) > 0)
            repeat(times) { myFixture.performEditorAction(action) }
            line = host.document.getLineNumber(host.caretModel.offset) + 1
        }
        return line
    }

    /** How many highlights of the usages of the element under the caret the markup of [editor] holds. */
    private fun usageHighlights(editor: Editor): Int {
        var count = 0
        DaemonCodeAnalyzerEx.processHighlights(editor.markupModel as MarkupModelEx, project, null, 0, editor.document.textLength) {
            if (it.type == HighlightInfoType.ELEMENT_UNDER_CARET_READ || it.type == HighlightInfoType.ELEMENT_UNDER_CARET_WRITE) count++
            true
        }
        return count
    }

    private fun lineOf(word: String, index: Int = 0): Int {
        var at = -1
        repeat(index + 1) { at = intake.indexOf(word, at + 1) }
        return intake.substring(0, at).count { it == '\n' } + 1
    }

    fun `test next and previous highlighted usage from inside an expression step through the whole definition`() {
        val reads = (0 until 5).map { lineOf("\$rejection", it) }
        // the first read of the Output of RejectBatch, then the next one in it
        intakeAt("\$rejection")
        assertEquals(reads[1], lineAfter("GotoNextElementUnderCaretUsage"))
        // from the last read of that expression on to the next read outside it
        intakeAt("\$rejection", 2)
        assertEquals(reads[3], lineAfter("GotoNextElementUnderCaretUsage"))
        // and back from the first read to the last assignment before it
        intakeAt("\$rejection")
        assertEquals(lineOf("rejection:", 18), lineAfter("GotoPrevElementUnderCaretUsage"))
    }

    fun `test the two actions are ours and keep the platform's text`() {
        for (id in listOf("GotoNextElementUnderCaretUsage", "GotoPrevElementUnderCaretUsage")) {
            val action = ActionManager.getInstance().getAction(id)
            assertInstanceOf(action, ElementUnderCaretUsageAction::class.java)
            assertFalse(id, action.templatePresentation.text.isNullOrBlank())
        }
    }

    fun `test next highlighted usage on a key steps as the platform's action does`() {
        intakeAt("rejection:", 1)
        assertEquals(lineOf("rejection:", 2), lineAfter("GotoNextElementUnderCaretUsage"))
    }

    fun `test a local JSONata variable keeps stage 2's highlighting`() {
        configure("§total := 1", "§to<caret>tal := 1")
        val handled = HighlightUsagesHandler.createCustomHandler<PsiElement>(myFixture.editor, myFixture.file)
        assertNull(handled)
        // the platform's own usage highlighting marks the three occurrences of the local, none of them a state variable
        assertEquals(listOf("\$total", "\$total", "\$total"), highlightedAccesses().map { it.first })
    }
}
