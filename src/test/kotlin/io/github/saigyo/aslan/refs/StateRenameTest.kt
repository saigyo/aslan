package io.github.saigyo.aslan.refs

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.tree.AslScalar
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

class StateRenameTest : AslTestCase() {
    /** The root machine's `X` names the root `Done` through an anchor that the nested `Y` reuses as an alias. */
    private val aliased = """
        StartAt: X
        States:
          X:
            Type: Pass
            Next: &d Done
          M:
            Type: Map
            ItemProcessor:
              StartAt: Y
              States:
                Y:
                  Type: Pass
                  Next: *d
                Done: { Type: Succeed }
            End: true
          Done: { Type: Succeed }
    """.trimIndent()

    private fun nestedDone(): YAMLKeyValue {
        myFixture.configureByText("test.asl.yaml", aliased)
        val document = AslModel.of(myFixture.file)!!
        return document.allStates().single { it.name == "Done" && it.machine.owner != null }.entry.entryPsi as YAMLKeyValue
    }

    private fun rootDone(): YAMLKeyValue {
        myFixture.configureByText("test.asl.yaml", aliased)
        return AslModel.of(myFixture.file)!!.root!!.state("Done")!!.entry.entryPsi as YAMLKeyValue
    }

    private fun renameIgnoringConflicts(element: YAMLKeyValue, newName: String) =
        ConflictsInTestsException.withIgnoredConflicts<Throwable> { myFixture.renameElement(element, newName) }

    private fun conflicts(action: () -> Unit): String =
        try {
            action()
            fail("expected a rename conflict")
            ""
        } catch (e: ConflictsInTestsException) {
            e.messages.joinToString("\n")
        }

    fun `test find usages of a nested state does not include an anchor of another machine`() {
        assertEmpty(myFixture.findUsages(nestedDone()))
    }

    fun `test rename of a nested state leaves the anchor of another machine alone`() {
        renameIgnoringConflicts(nestedDone(), "Finish")
        val text = myFixture.file.text
        assertTrue(text, "Next: &d Done" in text)
        assertTrue(text, "\n  Done: { Type: Succeed }" in text)
        assertTrue(text, "    Finish: { Type: Succeed }" in text)
    }

    fun `test an aliased transition that the rename cannot update is a conflict`() {
        val nested = nestedDone()
        assertTrue(conflicts { myFixture.renameElement(nested, "Finish") }.isNotEmpty())
        // Renaming the root Done rewrites the anchor, which the nested Y also uses to name its own Done.
        val root = rootDone()
        assertTrue(conflicts { myFixture.renameElement(root, "Finish") }.isNotEmpty())
    }

    fun `test an alias of an anchor in free-form data is a conflict`() {
        myFixture.configureByText("test.asl.yaml", """
            StartAt: A
            States:
              A: { Type: Pass, Parameters: { note: &b B }, Next: *b }
              B: { Type: Succeed }
        """.trimIndent())
        val entry = AslModel.of(myFixture.file)!!.root!!.state("B")!!.entry.entryPsi
        assertTrue(conflicts { myFixture.renameElement(entry, "Finish") }.isNotEmpty())
    }

    fun `test a target inside an aliased catcher list in free-form data is a conflict`() {
        myFixture.configureByText("test.asl.yaml", """
            StartAt: A
            States:
              A:
                Type: Pass
                Result:
                  handlers: &handlers
                    - ErrorEquals: [States.ALL]
                      Next: B
                Next: T
              T:
                Type: Task
                Resource: arn:aws:states:::sns:publish
                Catch: *handlers
                End: true
              B: { Type: Succeed }
        """.trimIndent())
        val entry = AslModel.of(myFixture.file)!!.root!!.state("B")!!.entry.entryPsi
        assertTrue(conflicts { myFixture.renameElement(entry, "Finish") }.isNotEmpty())
    }

    fun `test a target inside a catcher list anchored at its first use is renamed`() {
        myFixture.configureByText("test.asl.yaml", """
            StartAt: T
            States:
              T:
                Type: Task
                Resource: arn:aws:states:::sns:publish
                Catch: &handlers
                  - ErrorEquals: [States.ALL]
                    Next: B
                Next: U
              U:
                Type: Task
                Resource: arn:aws:states:::sns:publish
                Catch: *handlers
                End: true
              B: { Type: Succeed }
        """.trimIndent())
        myFixture.renameElement(AslModel.of(myFixture.file)!!.root!!.state("B")!!.entry.entryPsi, "Finish")
        val text = myFixture.file.text
        assertTrue(text, "Next: Finish" in text && "Finish: { Type: Succeed }" in text && "Next: B" !in text)
        assertEmpty(io.github.saigyo.aslan.check.AslChecker.findings(myFixture.file))
    }

    private fun renameDone(startAt: String): String {
        myFixture.configureByText("test.asl.yaml", "StartAt: $startAt\nStates:\n  Done:\n    Type: Pass\n    Next: Other\n  Other:\n    Type: Succeed\n")
        myFixture.renameElement(AslModel.of(myFixture.file)!!.root!!.state("Done")!!.entry.entryPsi, "Finished")
        return myFixture.file.text.lines().first()
    }

    fun `test a value with a deployment tag is not a state reference and is not renamed`() {
        assertEquals("StartAt: !Ref Done", renameDone("!Ref Done"))
        assertEquals("StartAt: !Sub 'Done'", renameDone("!Sub 'Done'"))
        myFixture.configureByText("test.asl.yaml", "StartAt: !Ref Do<caret>ne\nStates:\n  Done:\n    Type: Succeed\n")
        assertNull(myFixture.getReferenceAtCaretPosition())
    }

    fun `test a value with the string tag is renamed`() {
        assertEquals("StartAt: !!str Finished", renameDone("!!str Done"))
    }

    fun `test an anchor that names the renamed state itself is no conflict`() {
        myFixture.configureByText("test.asl.yaml", """
            StartAt: A
            States:
              A: { Type: Pass, Next: &b B }
              C: { Type: Pass, Next: *b }
              B: { Type: Succeed }
        """.trimIndent())
        val anchored = AslModel.of(myFixture.file)!!.root!!.state("A")!!.transitions.single().target!!.psi
        val reference = anchored.references.filterIsInstance<AslStateReference>().single()
        assertEquals("B", reference.rangeInElement.substring(anchored.text))
        myFixture.renameElement(AslModel.of(myFixture.file)!!.root!!.state("B")!!.entry.entryPsi, "Finish")
        assertTrue(myFixture.file.text, "Next: &b Finish" in myFixture.file.text && "Finish: { Type: Succeed }" in myFixture.file.text)
    }

    fun `test the validator rejects blank, long and multi-line names`() {
        val entry = rootDone()
        assertFalse(RenameUtil.isValidName(project, entry, "   "))
        assertFalse(RenameUtil.isValidName(project, entry, "two\nlines"))
        assertTrue(RenameUtil.isValidName(project, entry, "\uD83C\uDF9E".repeat(80)))
        assertFalse(RenameUtil.isValidName(project, entry, "\uD83C\uDF9E".repeat(81)))
    }

    fun `test the rename dialog judges a name by the rules for state names only`() {
        val entry = rootDone()
        val dialog = RenamePsiElementProcessor.forElement(entry).createRenameDialog(project, entry, null, null)
        try {
            // The platform's dialog would show the YAML plugin's "not a valid key name" for a name that needs quotes.
            assertInstanceOf(dialog, AslStateRenameDialog::class.java)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
        assertNull(stateNameProblem("Needs: review"))
        assertEquals("A state name must fit on one line", stateNameProblem("two\nlines"))
        assertEquals("A state name can have at most 80 characters", stateNameProblem("x".repeat(81)))
        assertEquals("A state name cannot be blank", stateNameProblem("  "))
    }

    fun `test renaming onto an existing state name is a conflict`() {
        val root = rootDone()
        assertTrue(conflicts { myFixture.renameElement(root, "X") }.isNotEmpty())
        // The nested Y counts too: names are unique across the whole definition.
        val again = rootDone()
        assertTrue(conflicts { myFixture.renameElement(again, "Y") }.isNotEmpty())
    }

    fun `test a multi-line name replaces a single-quoted value`() {
        myFixture.configureByText("test.asl.yaml", "StartAt: A\nStates:\n  A: { Type: Pass, Next: 'B' }\n  B: { Type: Succeed }")
        val document = AslModel.of(myFixture.file)!!
        val scalar = document.root!!.state("A")!!.transitions.single().target!!.psi as YAMLScalar
        val key = document.root!!.state("B")!!.entry.entryPsi
        WriteCommandAction.runWriteCommandAction(project) {
            NameWriter.writeValue(scalar, "two\nlines")
            NameWriter.writeKey(key, "two\nlines")
        }
        val after = AslModel.of(myFixture.file)!!.root!!
        assertEquals(myFixture.file.text, "two\nlines", after.state("A")!!.transitions.single().targetName)
        assertNotNull(myFixture.file.text, after.state("two\nlines"))
    }

    fun `test a name with quotes and a backslash in yaml and json`() {
        val name = """It's "the" \cut"""
        myFixture.configureByText("test.asl.yaml", "StartAt: A\nStates:\n  A: { Type: Pass, Next: B }\n  B: { Type: Pass, Next: 'A' }\n  C:\n    Type: Choice\n    Choices: [{Variable: $.x, IsNull: true, Next: \"A\"}]\n    Default: A")
        renameState("A", name)
        myFixture.configureByText("test.asl.json", """{"StartAt": "A", "States": {"A": {"Type": "Pass", "Next": "B"}, "B": {"Type": "Pass", "Next": "A"}}}""")
        renameState("A", name)
    }

    /** Renames the root state [old] and checks the file reads as the same model with [new] in its place. */
    private fun renameState(old: String, new: String) {
        val before = summary(myFixture.file).replace(Regex("\\b$old\\b"), Regex.escapeReplacement(new))
        myFixture.renameElement(AslModel.of(myFixture.file)!!.root!!.state(old)!!.entry.entryPsi, new)
        assertNull(PsiTreeUtil.findChildOfType(myFixture.file, PsiErrorElement::class.java))
        assertEquals(before, summary(myFixture.file))
    }

    fun `test quote style of targets is kept`() {
        myFixture.configureByText("test.asl.yaml", """
            StartAt: 'Rejected'
            States:
              A: { Type: Pass, Next: "Rejected" }
              Rejected: { Type: Fail }
        """.trimIndent())
        myFixture.renameElement(AslModel.of(myFixture.file)!!.root!!.state("Rejected")!!.entry.entryPsi, "Refused")
        val text = myFixture.file.text
        assertTrue(text, "StartAt: 'Refused'" in text && "Next: \"Refused\"" in text && "  Refused: { Type: Fail }" in text)
    }

    fun `test rename in a nested machine changes only that machine`() {
        myFixture.configureByText("test.asl.yaml", """
            StartAt: Each
            States:
              Each:
                Type: Map
                ItemProcessor:
                  StartAt: Done
                  States:
                    Done: { Type: Succeed }
                Next: Done
              Done: { Type: Succeed }
        """.trimIndent())
        val nested = AslModel.of(myFixture.file)!!.allStates().single { it.name == "Done" && it.machine.owner != null }
        myFixture.renameElement(nested.entry.entryPsi, "Finish")
        val text = myFixture.file.text
        assertTrue(text, "StartAt: Finish" in text && "    Finish: { Type: Succeed }" in text)
        assertTrue(text, "    Next: Done" in text && "\n  Done: { Type: Succeed }" in text)
    }

    fun `test equal strings in Arguments and JSONata are not renamed`() {
        myFixture.configureByText("test.asl.yaml", """
            QueryLanguage: JSONata
            StartAt: Work
            States:
              Work:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                Arguments:
                  Name: Rejected
                Output: '{% ${'$'}x = "Rejected" %}'
                Next: Rejected
              Rejected: { Type: Fail }
        """.trimIndent())
        myFixture.renameElement(AslModel.of(myFixture.file)!!.root!!.state("Rejected")!!.entry.entryPsi, "Refused")
        val text = myFixture.file.text
        assertTrue(text, "Name: Rejected" in text && "\"Rejected\" %}'" in text)
        assertTrue(text, "Next: Refused" in text && "  Refused: { Type: Fail }" in text)
    }

    private fun summary(file: PsiFile): String {
        val document = AslModel.of(file)!!
        return buildString {
            appendLine("start ${(document.root!!.startAt?.value as? AslScalar)?.text}")
            for (state in document.allStates()) appendLine("${state.name}: ${state.transitions.map { "${it.kind} ${it.targetName}" }}")
        }
    }

    /**
     * Definitions in which the state `A` refers to `B` although the reference data does not know its `Next` (or its
     * catcher) as a field: its type is a placeholder, unknown or missing, or it is a Pass state with a `Catch`.
     */
    private val unknownTypeCases = listOf(
        "    Type: ${'$'}{Kind}\n    Next: B\n",
        "    Type: Tsak\n    Next: B\n",
        "    Comment: no type yet\n    Next: B\n",
        "    Type: Pass\n    Catch:\n      - ErrorEquals: [States.ALL]\n        Next: B\n    Next: B\n",
    ).map { "StartAt: A\nStates:\n  A:\n$it  B:\n    Type: Succeed\n" }

    fun `test rename reaches transitions of states the reference data does not describe`() {
        for (text in unknownTypeCases) {
            myFixture.configureByText("test.asl.yaml", text)
            myFixture.renameElement(AslModel.of(myFixture.file)!!.root!!.state("B")!!.entry.entryPsi, "C")
            assertEquals(text, text.replace("B", "C"), myFixture.file.text)
        }
    }
}
