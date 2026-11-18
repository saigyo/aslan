package io.github.saigyo.aslan.links

import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.AslTestCase

class CodeLinkReferencesTest : AslTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("lambdas/src/handler.ts", "export interface Handler {}\n")
        myFixture.addFileToProject("lambdas/src/notes.txt", "The Intake contract.\n")
        myFixture.addFileToProject("lambdas/src/contracts.json", "{\"IntakeInput\": {}}")
    }

    private fun machine(comment: String, field: String = "") = """
        StartAt: Scan
        States:
          $comment
          Scan:
            Type: Pass$field
            End: true
    """

    /** What the reference at the caret of [text] resolves to; null for no reference or none that resolves. */
    private fun resolved(text: String, name: String = "sm.asl.yaml"): PsiElement? {
        myFixture.configureByText(name, text.trimIndent())
        return myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve()
    }

    fun `test a path in a comment resolves to its file`() {
        val target = resolved(machine("# Function: lambdas/src/hand<caret>ler.ts"))
        assertEquals("handler.ts", (target as PsiFile).name)
    }

    fun `test a type after the path resolves to its declaration`() {
        val target = resolved(machine("# Input: lambdas/src/contracts.json#Intake<caret>Input"))
        assertEquals("IntakeInput", (target as JsonProperty).name)
    }

    fun `test a type in TypeScript resolves to its declaration`() {
        val target = resolved(machine("# lambdas/src/handler.ts#Hand<caret>ler"))
        assertNotNull(target)
        assertFalse(target is PsiFile)
        assertTrue(target!!.text.contains("interface Handler"))
    }

    fun `test a type found only as text resolves to its place in the file`() {
        val target = resolved(machine("# lambdas/src/notes.txt#Int<caret>ake"))
        assertNotNull(target)
        (target as Navigatable).navigate(true)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("notes.txt", editor.virtualFile!!.name)
        assertEquals(editor.document.text.indexOf("Intake"), editor.caretModel.offset)
    }

    fun `test a path in a Comment field resolves, in YAML and JSON`() {
        val yaml = resolved(machine("", "\n            Comment: See lambdas/src/hand<caret>ler.ts"))
        assertEquals("handler.ts", (yaml as PsiFile).name)
        val json = resolved(
            """{"StartAt": "Scan", "States": {"Scan": {"Type": "Pass", "Comment": "See lambdas/src/hand<caret>ler.ts", "End": true}}}""",
            "sm.asl.json",
        )
        assertEquals("handler.ts", (json as PsiFile).name)
    }

    fun `test a word that is no path and a missing file resolve to nothing`() {
        assertNull(resolved(machine("# and/o<caret>r")))
        assertNull(resolved(machine("# lambdas/no<caret>ne.ts")))
    }

    fun `test a hash in a quoted string is no comment`() {
        assertNull(resolved(machine("", "\n            Parameters:\n              note: 'x # lambdas/src/hand<caret>ler.ts'")))
        assertTrue("the definition reads as meant", myFixture.file.text.contains("\n    Parameters:\n      note: 'x #"))
    }

    fun `test a comment in a YAML file that is no ASL file carries no reference`() {
        assertNull(resolved("# lambdas/src/hand<caret>ler.ts\nkey: value", "other.yaml"))
    }

    fun `test renaming the target file leaves the comment as it is`() {
        myFixture.configureByText("sm.asl.yaml", machine("# See lambdas/src/notes.txt").trimIndent())
        val asl = myFixture.file
        val notes = myFixture.findFileInTempDir("lambdas/src/notes.txt")!!
        myFixture.renameElement(myFixture.psiManager.findFile(notes)!!, "readme.txt")
        assertEquals("readme.txt", notes.name)
        assertTrue(asl.text.contains("# See lambdas/src/notes.txt"))
    }

    fun `test a type that is not in the file goes to the start of the file`() {
        val target = resolved(machine("# lambdas/src/notes.txt#Miss<caret>ing"))
        assertNotNull(target)
        (target as Navigatable).navigate(true)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("notes.txt", editor.virtualFile!!.name)
        assertEquals(0, editor.caretModel.offset)
    }

    fun `test a Comment key in payload data is no place for links`() {
        assertNull(resolved(machine("", "\n            Parameters:\n              Comment: lambdas/src/hand<caret>ler.ts")))
        assertTrue("the definition reads as meant", myFixture.file.text.contains("\n    Parameters:\n      Comment: lambdas"))
    }

    fun `test a path right after the comment's hash resolves`() {
        assertEquals("handler.ts", (resolved(machine("#lambdas/src/hand<caret>ler.ts")) as PsiFile).name)
        assertEquals("IntakeInput", (resolved(machine("#lambdas/src/contracts.json#Intake<caret>Input")) as JsonProperty).name)
    }

    fun `test a Comment key in payload data beside a Type key is no place for links`() {
        assertNull(resolved(machine("", "\n            Parameters:\n              Type: report\n              Comment: lambdas/src/hand<caret>ler.ts")))
        assertTrue("the definition reads as meant", myFixture.file.text.contains("\n      Type: report\n      Comment: lambdas"))
    }

    fun `test the Comment field of the machine is a place for links`() {
        val target = resolved("Comment: See lambdas/src/hand<caret>ler.ts\nStartAt: Scan\nStates:\n  Scan:\n    Type: Succeed\n")
        assertEquals("handler.ts", (target as PsiFile).name)
    }
}
