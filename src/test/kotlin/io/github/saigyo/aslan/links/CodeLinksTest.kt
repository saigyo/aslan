package io.github.saigyo.aslan.links

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.AslTestCase

class CodeLinksTest : AslTestCase() {
    private lateinit var asl: VirtualFile

    override fun setUp() {
        super.setUp()
        asl = myFixture.addFileToProject("infra/sm.asl.yaml", "StartAt: A\nStates:\n  A:\n    Type: Succeed\n").virtualFile
        myFixture.addFileToProject("infra/local/b.txt", "local")
        myFixture.addFileToProject("lambdas/src/a.ts", "import { IntakeInput } from './old';\nexport interface IntakeInputs {}\nexport interface IntakeInput {}\n")
        myFixture.addFileToProject("lambdas/src/notes.txt", "See IntakeInputs and IntakeInput.")
        myFixture.addFileToProject("lambdas/src/contracts.json", "{\"Other\": {}, \"IntakeInput\": {}}")
        myFixture.addFileToProject("lambdas/pack.zip", "IntakeInput")
    }

    private fun resolve(path: String): VirtualFile? = runReadAction { CodeLinks.resolve(project, asl, path) }

    private fun psi(path: String): PsiFile = myFixture.psiManager.findFile(resolve(path)!!)!!

    fun `test a path is resolved against the ASL file's folder first`() =
        assertEquals("b.txt", resolve("local/b.txt")?.name)

    fun `test a path is resolved against the content root next`() =
        assertEquals("a.ts", resolve("lambdas/src/a.ts")?.name)

    fun `test a path starting with a slash skips the ASL file's folder`() {
        assertEquals("b.txt", resolve("/infra/local/b.txt")?.name)
        assertNull(resolve("/local/b.txt"))
    }

    fun `test a folder or a missing file does not resolve`() {
        assertNull(resolve("lambdas/src"))
        assertNull(resolve("lambdas/src/none.ts"))
    }

    fun `test the ASL file's folder comes before the content root`() {
        val bases: List<VirtualFile> = runReadAction { CodeLinks.bases(project, asl, absolute = false) }
        assertEquals("infra", bases.first().name)
        assertTrue(bases.drop(1).any { asl.path.startsWith(it.path + "/") })
        assertEquals(bases.size, bases.toSet().size)
        assertFalse(runReadAction { CodeLinks.bases(project, asl, absolute = true) }.any { it.name == "infra" })
    }

    fun `test a type declared in a file the IDE reads is found as a declaration`() {
        val file = psi("lambdas/src/contracts.json")
        val target = runReadAction { CodeLinks.findType(file, "IntakeInput") }
        assertTrue(target is CodeLinks.TypeTarget.Declared)
        val at = file.text.substring(target!!.offset)
        assertTrue(at, at.startsWith("\"IntakeInput\"") || at.startsWith("IntakeInput"))
    }

    fun `test a type in a TypeScript file is found at its declaration, not at an import`() {
        val file = psi("lambdas/src/a.ts")
        val target = runReadAction { CodeLinks.findType(file, "IntakeInput") }
        assertTrue(target is CodeLinks.TypeTarget.Declared)
        assertEquals(file.text.indexOf("IntakeInput {}"), target!!.offset)
    }

    fun `test a type in a file without declarations is found as a whole word`() {
        val file = psi("lambdas/src/notes.txt")
        val target = runReadAction { CodeLinks.findType(file, "IntakeInput") }
        assertTrue(target is CodeLinks.TypeTarget.Text)
        assertEquals(file.text.indexOf("IntakeInput."), target!!.offset)
    }

    fun `test a missing type and a binary file give null`() {
        assertNull(runReadAction { CodeLinks.findType(psi("lambdas/src/a.ts"), "Missing") })
        assertNull(runReadAction { CodeLinks.findType(psi("lambdas/pack.zip"), "IntakeInput") })
    }

    fun `test open goes to the type in a tab of its own`() {
        myFixture.openFileInEditor(asl)
        assertTrue(CodeLinks.open(project, asl, "lambdas/src/a.ts", "IntakeInput"))
        val manager = FileEditorManager.getInstance(project)
        val editor = manager.selectedTextEditor!!
        assertEquals("a.ts", editor.virtualFile!!.name)
        assertEquals(editor.document.text.indexOf("IntakeInput {}"), editor.caretModel.offset)
        assertTrue(asl in manager.openFiles)
    }

    fun `test open without a type goes to the start of the file`() {
        myFixture.openFileInEditor(asl)
        assertTrue(CodeLinks.open(project, asl, "lambdas/src/contracts.json", null))
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("contracts.json", editor.virtualFile!!.name)
        assertEquals(0, editor.caretModel.offset)
    }

    fun `test open does nothing for a path that no longer resolves`() {
        myFixture.openFileInEditor(asl)
        val before = FileEditorManager.getInstance(project).openFiles.toList()
        assertFalse(CodeLinks.open(project, asl, "lambdas/src/none.ts", "X"))
        assertEquals(before, FileEditorManager.getInstance(project).openFiles.toList())
    }
}
