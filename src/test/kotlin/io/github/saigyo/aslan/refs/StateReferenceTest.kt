package io.github.saigyo.aslan.refs

import com.intellij.json.psi.JsonProperty
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameUtil
import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.tree.AslScalar
import org.jetbrains.yaml.psi.YAMLKeyValue

class StateReferenceTest : AslTestCase() {
    private val text = """
        StartAt: Inspect
        States:
          Inspect:
            Type: Task
            Resource: x
            Catch:
              - ErrorEquals: [States.ALL]
                Next: Rejected
            Next: Decide
          Decide:
            Type: Choice
            Choices:
              - Variable: $.ok
                BooleanEquals: true
                Next: Accepted
            Default: Rejected
          Accepted:
            Type: Succeed
          Rejected:
            Type: Fail
    """.trimIndent()
    private fun at(marker: String, replacement: String): PsiFile =
        myFixture.configureByText("test.asl.yaml", text.replaceFirst(marker, replacement))

    fun `test Next resolves to the state key`() {
        at("Next: Decide", "Next: Dec<caret>ide")
        val target = myFixture.elementAtCaret
        assertTrue(target is YAMLKeyValue)
        assertEquals("Decide", (target as YAMLKeyValue).keyText)
    }
    fun `test StartAt Default and catcher Next resolve`() {
        for ((marker, replacement, name) in listOf(
            Triple("StartAt: Inspect", "StartAt: Insp<caret>ect", "Inspect"),
            Triple("Default: Rejected", "Default: Rej<caret>ected", "Rejected"),
            Triple("Next: Rejected", "Next: Rej<caret>ected", "Rejected"))) {
            at(marker, replacement)
            assertEquals(name, (myFixture.elementAtCaret as YAMLKeyValue).keyText)
        }
    }
    fun `test unknown target does not resolve`() {
        at("Next: Decide", "Next: No<caret>where")
        assertNull(myFixture.getReferenceAtCaretPosition()!!.resolve())
    }
    fun `test no reference inside free-form data or in other files`() {
        myFixture.configureByText("test.asl.yaml", "StartAt: A\nStates:\n  A:\n    Type: Pass\n    Output:\n      Next: <caret>A\n    End: true")
        assertNull(myFixture.getReferenceAtCaretPosition())
        myFixture.configureByText("plain.yaml", "StartAt: <caret>A\nStates:\n  A:\n    Type: Succeed")
        assertFalse(myFixture.getReferenceAtCaretPosition() is AslStateReference)
    }
    fun `test find usages of a state`() {
        at("  Rejected:", "  Rej<caret>ected:")
        val usages = myFixture.findUsages(myFixture.elementAtCaret)
        assertEquals(listOf("Rejected", "Rejected"), usages.map { it.element!!.text })
    }
    fun `test usages are highlighted from a reference`() {
        at("Default: Rejected", "Default: Rej<caret>ected")
        assertEquals(listOf("Rejected", "Rejected", "Rejected"), highlightedUsages())
    }
    fun `test rename from the declaration changes key and references`() {
        at("  Rejected:", "  Rej<caret>ected:")
        myFixture.renameElementAtCaret("Refused")
        val result = myFixture.file.text
        assertEquals(0, Regex("Rejected").findAll(result).count())
        assertEquals(3, Regex("Refused").findAll(result).count())
    }
    fun `test rename from a reference`() {
        at("Next: Decide", "Next: Dec<caret>ide")
        myFixture.renameElementAtCaret("Sort")
        assertTrue("StartAt: Inspect" in myFixture.file.text && "  Sort:" in myFixture.file.text && "Next: Sort" in myFixture.file.text)
    }
    fun `test json`() {
        myFixture.configureByText("test.asl.json", """{"StartAt": "<caret>A", "States": {"A": {"Type": "Pass", "Next": "B"}, "B": {"Type": "Succeed"}}}""")
        assertEquals("A", (myFixture.elementAtCaret as JsonProperty).name)
        myFixture.renameElementAtCaret("First")
        assertTrue(""""StartAt": "First"""" in myFixture.file.text && """"First": {""" in myFixture.file.text)
    }
    fun `test a state name used in a nested machine is a different state`() {
        myFixture.configureByText("test.asl.yaml", """
            StartAt: Each
            States:
              Each:
                Type: Map
                ItemProcessor:
                  StartAt: Do<caret>ne
                  States:
                    Done: { Type: Succeed }
                Next: Finish
              Finish: { Type: Succeed }
        """.trimIndent())
        val target = myFixture.elementAtCaret as YAMLKeyValue
        assertEquals("Done", target.keyText)
        assertEquals(1, myFixture.findUsages(target).size)
    }

    fun `test decoded names match across quoting styles`() {
        myFixture.configureByText("test.asl.yaml", """
            StartAt: "Director's <caret>cut"
            States:
              'Director''s cut': { Type: Succeed }
        """.trimIndent())
        assertEquals("'Director''s cut'", (myFixture.elementAtCaret as YAMLKeyValue).key!!.text)
        val reference = myFixture.getReferenceAtCaretPosition()!!
        assertEquals("Director's cut", reference.rangeInElement.substring(reference.element.text))
    }

    fun `test no reference on a placeholder`() {
        myFixture.configureByText("test.asl.yaml", "StartAt: A\nStates:\n  A:\n    Type: Pass\n    Next: <caret>\${NextState}")
        assertNull(myFixture.getReferenceAtCaretPosition())
    }

    fun `test rename to a name that needs quoting in yaml`() {
        at("  Rejected:", "  Rej<caret>ected:")
        val before = summary(myFixture.file).replace("Rejected", "Needs: review")
        myFixture.renameElementAtCaret("Needs: review")
        assertNull(PsiTreeUtil.findChildOfType(myFixture.file, PsiErrorElement::class.java))
        assertEquals(before, summary(myFixture.file))
        assertEquals(3, Regex("\"Needs: review\"").findAll(myFixture.file.text).count())
    }

    fun `test rename to a name that needs quoting in json`() {
        myFixture.configureByText("test.asl.json", """{"StartAt": "A", "States": {"<caret>A": {"Type": "Pass", "Next": "B"}, "B": {"Type": "Pass", "Next": "A"}}}""")
        val before = summary(myFixture.file).replace("A", "Needs: review")
        myFixture.renameElementAtCaret("Needs: review")
        assertNull(PsiTreeUtil.findChildOfType(myFixture.file, PsiErrorElement::class.java))
        assertEquals(before, summary(myFixture.file))
    }

    fun `test half-typed files give no exception`() {
        for (broken in listOf("StartAt:", "StartAt: A\nStates:\n  A:\n    Next:", "StartAt: [A\nStates: {A: {Next: ", "States:\n  A:\n    Choices:\n      - Next: B\n  B")) {
            val file = myFixture.configureByText("test.asl.yaml", broken)
            for (element in PsiTreeUtil.collectElements(file) { true }) element.references.forEach { it.resolve() }
        }
        val file = myFixture.configureByText("test.asl.json", "{\"StartAt\": \"A\", \"States\": {\"A\": {\"Next\": ")
        for (element in PsiTreeUtil.collectElements(file) { true }) element.references.forEach { it.resolve() }
    }

    fun `test the rename dialog accepts a name that needs quoting`() {
        at("  Rejected:", "  Rej<caret>ected:")
        assertTrue(RenameUtil.isValidName(project, myFixture.elementAtCaret, "Needs: review"))
    }

    /** The start state, then each state with the kind and target of its transitions, as the model reads them. */
    private fun summary(file: PsiFile): String {
        val document = AslModel.of(file)!!
        val machine = document.root!!
        return buildString {
            appendLine("start ${machine.startAt?.value?.let { (it as? AslScalar)?.text }}")
            for (state in document.allStates()) appendLine("${state.name}: ${state.transitions.map { "${it.kind} ${it.targetName}" }}")
        }
    }
}
