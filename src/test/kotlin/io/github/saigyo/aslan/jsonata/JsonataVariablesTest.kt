package io.github.saigyo.aslan.jsonata

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector.Access
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.LanguageNamesValidation
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.testFramework.fixtures.CodeInsightTestUtil
import io.github.saigyo.aslan.check.AslChecker
import io.github.saigyo.aslan.check.AslProblem
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import io.github.saigyo.aslan.jsonata.refs.JsonataInplaceRenameHandler
import io.github.saigyo.aslan.jsonata.refs.JsonataVariableRenameProcessor
import io.github.saigyo.aslan.model.AslModel

class JsonataVariablesTest : JsonataTestCase() {
    /** The start offset of what the reference at the caret resolves to, or null when there is no reference there. */
    private fun resolvedOffset(): Int? {
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) ?: return null
        return reference.resolve()!!.textRange.startOffset
    }

    private fun variables(file: PsiFile): List<JsonataVariableRef> =
        PsiTreeUtil.findChildrenOfType(file, JsonataVariableRef::class.java).toList()

    /** The variables of the expression injected into the only JSONata host of the configured file. */
    private fun injectedVariables(): List<JsonataVariableRef> {
        val host = AslModel.of(hostFile())!!.jsonataSites.all.map { it.scalar.psi }.distinct().single()
        return variables(JsonataPsi.injectedFile(host)!!)
    }

    fun `test a use resolves to the assignment`() {
        jsonata("(\$total := 1; \$to<caret>tal + 2)")
        assertEquals(1, resolvedOffset())
    }

    fun `test a parameter hides an outer variable`() {
        val text = "(\$x := 1; function(\$x){ \$<caret>x }(2))"
        jsonata(text)
        assertEquals(text.indexOf("(\$x){") + 1, resolvedOffset())
    }

    fun `test a variable of an inner block is not visible outside`() {
        jsonata("((\$y := 1); \$<caret>y)")
        assertNull(resolvedOffset())
    }

    fun `test a function may call itself`() {
        val file = jsonata("(\$f := function(\$n){ \$n = 0 ? 0 : \$<caret>f(\$n - 1) }; \$f(3))")
        assertEquals(1, resolvedOffset())
        val parameter = variables(file)[1]
        val uses = variables(file).filter { it.name == "n" }.drop(1)
        assertEquals(listOf(parameter, parameter), uses.map { it.reference!!.resolve() })
    }

    fun `test names that are not local have no reference`() {
        for (text in listOf("\$sta<caret>tes.input.ok", "\$su<caret>m(items)", "(\$a := 1; \$or<caret>der.total + \$a)")) {
            val file = jsonata(text)
            assertNull(text, myFixture.file.findReferenceAt(myFixture.caretOffset))
            val variable = PsiTreeUtil.getParentOfType(file.findElementAt(myFixture.caretOffset), JsonataVariableRef::class.java)!!
            assertEmpty(JsonataScopes.declarations(variable))
            assertEmpty(JsonataScopes.occurrences(variable))
            assertFalse(LanguageFindUsages.canFindUsagesFor(variable))
            assertNull(text, TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.getInstance().allAccepted))
        }
    }

    fun `test focus and index variables`() {
        val text = "items@\$it#\$i.{\"v\": \$<caret>it.name, \"n\": \$i}"
        val file = jsonata(text)
        assertEquals(text.indexOf("\$it"), resolvedOffset())
        val index = variables(file).filter { it.name == "i" }
        assertEquals(index.first(), index.last().reference!!.resolve())
        assertTrue(JsonataScopes.isDeclaration(index.first()))
        assertFalse(JsonataScopes.isDeclaration(index.last()))
    }

    fun `test assignments are writes and uses are reads`() {
        val variables = variables(jsonata("(\$n := 1; \$n := \$n + 1; \$n)"))
        val detector = ReadWriteAccessDetector.findDetector(variables[0])!!
        assertEquals(listOf(Access.Write, Access.Write, Access.Read, Access.Read), variables.map { detector.getExpressionAccess(it) })
        assertTrue(detector.isDeclarationWriteAccess(variables[0]))
        assertNull(variables[0].reference)
        assertEquals(List(3) { variables[0] }, variables.drop(1).map { it.reference!!.resolve() })
        assertEquals(variables, JsonataScopes.occurrences(variables[2]))
    }

    fun `test usages are highlighted in the host file`() {
        hosted("(\$total := 1; \$to<caret>tal + \$total)")
        assertEquals(listOf("\$total", "\$total", "\$total"), highlightedUsages())
    }

    fun `test find usages lists every occurrence`() {
        val variables = variables(jsonata("(\$n := 1; \$n := \$n + 1; \$n)"))
        val usages = myFixture.findUsages(variables[0])
        assertEquals(3, usages.size)
        assertEquals(variables.drop(1).map { it.textRange }, usages.map { it.element!!.textRange }.sortedBy { it.startOffset })
    }

    /** The configured file; the fixture hands out the injected file when the caret is in one. */
    private fun hostFile(): PsiFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file)

    /** A Pass state whose Output is [output], written as given after `Output:`. */
    private fun passText(output: String): String = machineText("Type: Pass\nOutput: $output\nEnd: true")

    /** Renames at the caret of [before] (a file named [name]) and checks the whole result and that the file still reads the same. */
    private fun assertRename(before: String, after: String, name: String = "test.asl.yaml", newName: String = "sum2") {
        myFixture.configureByText(name, before)
        assertTrue(myFixture.file is JsonataFile)
        val host = hostFile()
        val sites = AslModel.of(host)!!.jsonataSites.all.size
        assertTrue(sites > 0)
        myFixture.renameElementAtCaret(newName)
        assertResult(host, after, sites, newName)
    }

    private fun assertResult(host: PsiFile, after: String, sites: Int, newName: String = "sum2") {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(after, PsiDocumentManager.getInstance(project).getDocument(host)!!.text)
        assertEquals(after, host.text)
        assertEquals(sites, AslModel.of(host)?.jsonataSites?.all?.size)
        assertEquals(emptyList<AslProblem>(), AslChecker.findings(host).map { it.problem }.filter { it == AslProblem.JSONATA_SYNTAX_ERROR })
        val renamed = injectedVariables().filter { it.name == newName }
        assertTrue(renamed.isNotEmpty())
        assertEquals(renamed, JsonataScopes.occurrences(renamed.first()))
    }

    fun `test rename changes every occurrence`() {
        // a block scalar
        assertRename(
            hostText("(\$sum := 1; \$sum := \$s<caret>um + 1; \$sum)"),
            hostText("(\$sum2 := 1; \$sum2 := \$sum2 + 1; \$sum2)"),
        )
        // a single-quoted scalar with escaped quotes around the variables
        assertRename(
            passText("'{% (\$sum := ''a''; ''b'' & \$s<caret>um & ''c'' & \$sum) %}'"),
            passText("'{% (\$sum2 := ''a''; ''b'' & \$sum2 & ''c'' & \$sum2) %}'"),
        )
        // a double-quoted scalar on two lines
        assertRename(
            passText("\"{% (\$sum := 1;\n  \$s<caret>um + \$sum) %}\""),
            passText("\"{% (\$sum2 := 1;\n  \$sum2 + \$sum2) %}\""),
        )
    }

    fun `test rename in a double-quoted scalar with escaped quotes`() {
        assertRename(
            passText("\"{% (\$sum := \\\"a\\\"; \\\"b\\\" & \$s<caret>um & \\\"c\\\") %}\""),
            passText("\"{% (\$sum2 := \\\"a\\\"; \\\"b\\\" & \$sum2 & \\\"c\\\") %}\""),
        )
    }

    fun `test rename in a json host`() {
        val json = """{"QueryLanguage":"JSONata","StartAt":"A","States":{"A":{"Type":"Pass","Output":"{% (${'$'}sum := \"a\"; \"b\" & ${'$'}s<caret>um & ${'$'}sum) %}","End":true}}}"""
        assertRename(json, json.replace("<caret>", "").replace("\$sum", "\$sum2"), "test.asl.json")
    }

    fun `test rename of a parameter leaves an outer variable of the same name alone`() {
        assertRename(
            hostText("(\$sum := 1; function(\$sum){ \$s<caret>um + 1 }(\$sum))"),
            hostText("(\$sum := 1; function(\$sum2){ \$sum2 + 1 }(\$sum))"),
        )
    }

    private fun assertInplaceRename(before: String, after: String) {
        myFixture.configureByText("test.asl.yaml", before)
        val host = hostFile()
        val sites = AslModel.of(host)!!.jsonataSites.all.size
        val editor = myFixture.editor
        assertTrue(editor is EditorWindow)
        val target = TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().allAccepted)!!
        CodeInsightTestUtil.doInlineRename(JsonataInplaceRenameHandler(), "sum2", editor, target)
        finishTemplate()
        assertResult(host, after, sites)
    }

    fun `test in-place rename keeps the dollar sign`() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        assertInplaceRename(hostText("(\$sum := 1; \$s<caret>um + \$sum)"), hostText("(\$sum2 := 1; \$sum2 + \$sum2)"))
        assertInplaceRename(
            passText("'{% (\$s<caret>um := ''a''; ''b'' & \$sum) %}'"),
            passText("'{% (\$sum2 := ''a''; ''b'' & \$sum2) %}'"),
        )
        assertInplaceRename(
            passText("\"{% (\$sum := 1;\n  \$s<caret>um + \$sum) %}\""),
            passText("\"{% (\$sum2 := 1;\n  \$sum2 + \$sum2) %}\""),
        )
    }

    fun `test rename from the declaration and in a standalone file`() {
        assertRename(
            hostText("(\$s<caret>um := 1; \$sum := \$sum + 1; \$sum)"),
            hostText("(\$sum2 := 1; \$sum2 := \$sum2 + 1; \$sum2)"),
        )
        jsonata("(\$n := 1; function(\$n){ \$n }(\$<caret>n))")
        myFixture.renameElementAtCaret("tally")
        myFixture.checkResult("(\$tally := 1; function(\$n){ \$n }(\$tally))")
    }

    fun `test rename rejects a name that is no identifier`() {
        val variable = variables(jsonata("(\$sum := 1; \$sum)")).first()
        for (name in listOf("", "a b", "a.b", "a+b", "a'b", "1a", "states")) {
            assertFalse(name, RenameUtil.isValidName(project, variable, name))
        }
        for (name in listOf("sum2", "_x", "A_1", "statesX")) {
            assertTrue(name, RenameUtil.isValidName(project, variable, name))
            assertTrue(name, LanguageNamesValidation.isIdentifier(JsonataLanguage, name, project))
        }
    }

    fun `test scopes on a half-typed expression`() {
        val variables = variables(jsonata("(\$a := 1; function(\$b){ \$a + "))
        for (variable in variables) {
            JsonataScopes.declarations(variable)
            JsonataScopes.occurrences(variable)
            JsonataScopes.isDeclaration(variable)
            JsonataScopes.visibleNames(variable)
        }
        assertEquals(variables[0], variables[2].reference!!.resolve())
        assertEquals(listOf("b", "a"), JsonataScopes.visibleNames(variables[2]))
    }

    fun `test the target of a filtered assignment is declared`() {
        val file = jsonata("(\$x[] := 1; \$<caret>x)")
        assertEquals(1, resolvedOffset())
        assertTrue(JsonataScopes.isDeclaration(variables(file).first()))
    }

    fun `test visible names innermost first`() {
        val variables = variables(jsonata("(\$a := 1; \$c := 2; function(\$b){ (\$d := \$a; \$d) })"))
        val inner = variables.last()
        assertEquals(listOf("d", "b", "a", "c"), JsonataScopes.visibleNames(inner))
        // at the first assignment nothing is assigned yet
        assertEquals(emptyList<String>(), JsonataScopes.visibleNames(variables[0]))
        // the right side of an assignment does not see the name it assigns
        assertEquals(listOf("b", "a", "c"), JsonataScopes.visibleNames(variables[variables.size - 2]))
    }

    fun `test an expression used through an anchor is one file`() {
        val text = machineText(
            "Type: Pass\nOutput: &shared '{% (\$sum := 1; \$s<caret>um) %}'\nNext: Again",
        ).replace("  Failed:\n    Type: Fail\n", "  Again:\n    Type: Pass\n    Output: *shared\n    End: true\n")
        myFixture.configureByText("test.asl.yaml", text)
        val host = hostFile()
        val sites = AslModel.of(host)!!.jsonataSites.all.size
        assertEquals(2, sites)
        myFixture.renameElementAtCaret("sum2")
        assertResult(host, text.replace("<caret>", "").replace("\$sum", "\$sum2"), sites)
    }

    /** For each variable of [text] in file order, the index of the variable it resolves to, -1 for the first declaration, or null without one. */
    private fun resolution(text: String): List<Int?> {
        val variables = variables(jsonata(text))
        return variables.map { v ->
            val target = v.reference?.resolve()
            when {
                target != null -> variables.indexOf(target)
                JsonataScopes.declarations(v).isNotEmpty() -> -1
                else -> null
            }
        }
    }

    fun `test the scope rule`() {
        // a block variable used after the block is not local there
        assertEquals(listOf(-1, null), resolution("((\$a := 1); \$a)"))
        // a use before the assignment in the same block does not refer to it
        assertEquals(listOf(null, -1), resolution("(\$b; \$b := 1)"))
        // a variable assigned in a block that is a lambda body is local to that block
        assertEquals(listOf(-1, 0, null), resolution("(function(){ (\$c := 1; \$c) }; \$c)"))
        // focus and index variables are visible after their binding up to the end of the path (jsonata-js 2.0.6 gives
        // undefined for `(items@$v; $v)`)
        assertEquals(listOf(-1, -1, 0, 1, null), resolution("(items@\$v#\$i.{\"n\": \$v.name, \"i\": \$i}; \$v)"))
        // ... but not before it
        assertEquals(listOf(null, -1), resolution("\$w.(items@\$w)"))
        // an assignment at the top level is declared by the file, but not for its own right side
        assertEquals(listOf(-1, null), resolution("\$e := \$e + 1"))
        // a right side reads the outer variable of the same name
        assertEquals(listOf(-1, -1, 0, -1, 0), resolution("(\$a := 1; (\$b := \$a; \$a := 2); \$a)"))
    }

    fun `test an inner assignment reading the outer variable`() {
        val text = "(\$x := 1; (\$x := \$x + 1; \$x) + \$x)"
        val variables = variables(jsonata(text))
        assertEquals(listOf(0, 2, 4), JsonataScopes.occurrences(variables[0]).map { variables.indexOf(it) })
        assertEquals(listOf(1, 3), JsonataScopes.occurrences(variables[1]).map { variables.indexOf(it) })
        assertEquals(listOf(variables[1]), JsonataScopes.declarations(variables[3]))
        assertEquals(listOf(variables[0]), JsonataScopes.declarations(variables[2]))
    }

    fun `test rename of an outer and an inner variable of the same name`() {
        val text = "(\$x := 1; (\$x := \$x + 1; \$x) + \$x)"
        // caret on the outer one, on the right side of the inner assignment
        jsonata(text.replace("(\$x := \$x + 1", "(\$x := \$<caret>x + 1"))
        myFixture.renameElementAtCaret("y")
        myFixture.checkResult("(\$y := 1; (\$x := \$y + 1; \$x) + \$y)")
        // caret on the inner one
        jsonata(text.replace("; \$x) +", "; \$<caret>x) +"))
        myFixture.renameElementAtCaret("y")
        myFixture.checkResult("(\$x := 1; (\$y := \$x + 1; \$y) + \$x)")
        // the same in a host
        assertRename(
            hostText("(\$sum := 1; (\$sum := \$s<caret>um + 1; \$sum) + \$sum)"),
            hostText("(\$sum2 := 1; (\$sum := \$sum2 + 1; \$sum) + \$sum2)"),
        )
    }

    fun `test a lambda refers to an assignment after it`() {
        // $f, $x in the lambda, the target $x, the call of $f
        assertEquals(listOf(-1, 2, -1, 0), resolution("(\$f := function(){ \$x }; \$x := 1; \$f())"))
    }

    fun `test assignments and bindings directly in a lambda body`() {
        val body = "function(\$i){ \$i.reels@\$r.{\"t\": \$r.title} }"
        val alone = variables(jsonata("\$map(items, $body)"))
        val r = alone.filter { it.name == "r" }
        assertEquals(r.first(), r.last().reference!!.resolve())
        val wrapped = variables(jsonata("(\$r := 1; \$map(items, $body); \$r)"))
        val inner = wrapped.filter { it.name == "r" }
        assertEquals(inner[1], inner[2].reference!!.resolve())
        assertEquals(inner[0], inner[3].reference!!.resolve())
        assertEquals(listOf(inner[0], inner[3]), JsonataScopes.occurrences(inner[0]))
        // `:=` directly in a lambda body declares the name in the lambda; a block body sees it
        val assigned = variables(jsonata("function(\$a){ \$d := \$a }"))
        assertTrue(JsonataScopes.isDeclaration(assigned[1]))
        assertEquals(listOf(assigned[1]), JsonataScopes.declarations(assigned[1]))
        assertEquals(assigned[0], assigned[2].reference!!.resolve())
        val block = variables(jsonata("function(\$a){ (\$d := \$a; \$d) }"))
        assertEquals(block[1], block[3].reference!!.resolve())
    }

    fun `test a use before the assignment has no reference`() {
        jsonata("(\$<caret>a; \$a := 1)")
        assertNull(resolvedOffset())
    }

    fun `test find usages from the host editor`() {
        hosted("(\$n := 1; \$n := \$<caret>n + 1; \$n)")
        val target = myFixture.elementAtCaret as JsonataVariableRef
        assertEquals(target, JsonataScopes.occurrences(target).first())
        val usages = myFixture.findUsages(target)
        assertEquals(3, usages.size)
        assertEquals(JsonataScopes.occurrences(target).drop(1).map { it.textRange }, usages.map { it.element!!.textRange }.sortedBy { it.startOffset })
    }

    fun `test undo after a rename restores the host`() {
        val before = passText("'{% (\$sum := ''a''; ''b'' & \$s<caret>um) %}'")
        assertRename(before, passText("'{% (\$sum2 := ''a''; ''b'' & \$sum2) %}'"))
        val editor = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor)
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(editor))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(before.replace("<caret>", ""), editor.document.text)
        assertEquals(AslModel.of(hostFile())!!.jsonataSites.all.size, 1)
    }

    /** Runs what the in-place renamer leaves to the event queue after the template ends: the name check and the rename. */
    private fun finishTemplate() {
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    /** In-place rename at the caret of [before] to [newName]; returns the host text after the template ended. */
    private fun inplaceRenameTo(before: String, newName: String, name: String): String {
        myFixture.configureByText(name, before)
        val host = hostFile()
        val sites = AslModel.of(host)!!.jsonataSites.all.size
        val editor = myFixture.editor
        val target = TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().allAccepted)!!
        CodeInsightTestUtil.doInlineRename(JsonataInplaceRenameHandler(), newName, editor, target)
        finishTemplate()
        assertEquals(sites, AslModel.of(host)?.jsonataSites?.all?.size)
        return PsiDocumentManager.getInstance(project).getDocument(host)!!.text
    }

    fun `test in-place rename to a name with a quote leaves the host intact`() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        val single = passText("'{% (\$sum := ''a''; ''b'' & \$s<caret>um) %}'")
        assertEquals(single.replace("<caret>", ""), inplaceRenameTo(single, "su'm", "test.asl.yaml"))
        val json = """{"QueryLanguage":"JSONata","StartAt":"A","States":{"A":{"Type":"Pass","Output":"{% (${'$'}sum := \"a\"; ${'$'}s<caret>um) %}","End":true}}}"""
        assertEquals(json.replace("<caret>", ""), inplaceRenameTo(json, "su\"m", "test.asl.json"))
    }

    /** Starts an in-place rename at the caret of [before], types [typed], presses Escape, and returns the host text after. */
    private fun cancelInplaceRename(before: String, typed: String): String {
        myFixture.configureByText("test.asl.yaml", before)
        val host = hostFile()
        val editor = myFixture.editor
        val target = TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().allAccepted)!!
        JsonataInplaceRenameHandler().doRename(target, editor, null)
        val top = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        val range = TemplateManagerImpl.getTemplateState(top)!!.currentVariableRange!!
        WriteCommandAction.runWriteCommandAction(project) { top.document.replaceString(range.startOffset, range.endOffset, typed) }
        TemplateManagerImpl.getTemplateState(top)!!.cancelTemplate()
        finishTemplate()
        return PsiDocumentManager.getInstance(project).getDocument(host)!!.text
    }

    fun `test Escape in an in-place rename restores the old name`() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        val before = hostText("(\$a := 1; \$b := 2; \$<caret>a + \$b)")
        // a valid name that collides
        assertEquals(before.replace("<caret>", ""), cancelInplaceRename(before, "b"))
        // a fresh valid name
        assertEquals(before.replace("<caret>", ""), cancelInplaceRename(before, "total"))
    }

    fun `test cancelling an in-place rename with a quote restores the host`() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        val before = passText("'{% (\$sum := ''a''; ''b'' & \$s<caret>um) %}'")
        myFixture.configureByText("test.asl.yaml", before)
        val host = hostFile()
        val editor = myFixture.editor
        val target = TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().allAccepted)!!
        JsonataInplaceRenameHandler().doRename(target, editor, null)
        val top = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        val range = TemplateManagerImpl.getTemplateState(top)!!.currentVariableRange!!
        WriteCommandAction.runWriteCommandAction(project) { top.document.replaceString(range.startOffset, range.endOffset, "su'm") }
        // while the template runs, the host holds what was typed, in every occurrence
        assertEquals(before.replace("<caret>", "").replace("\$sum", "\$su'm"), top.document.text)
        TemplateManagerImpl.getTemplateState(top)!!.cancelTemplate()
        finishTemplate()
        assertEquals(before.replace("<caret>", ""), PsiDocumentManager.getInstance(project).getDocument(host)!!.text)
        assertEquals(1, AslModel.of(host)!!.jsonataSites.all.size)
    }

    fun `test the rename processor refuses what the validator refuses`() {
        val variable = variables(jsonata("(\$sum := 1; \$sum)")).first()
        for (name in listOf("states", "1a", "a b", "a'b", "")) {
            assertThrows(IncorrectOperationException::class.java) {
                WriteCommandAction.runWriteCommandAction(project) {
                    JsonataVariableRenameProcessor().renameElement(variable, name, emptyArray(), null)
                }
            }
        }
        assertEquals("(\$sum := 1; \$sum)", myFixture.editor.document.text)
    }

    /** Renames at the caret of [before] to [newName], expects a reported conflict, and checks that the host is unchanged. */
    private fun assertRenameConflict(before: String, newName: String) {
        myFixture.configureByText("test.asl.yaml", before)
        val host = hostFile()
        try {
            myFixture.renameElementAtCaret(newName)
            fail("expected a rename conflict for $newName")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertFalse(e.messages.isEmpty())
        }
        assertEquals(before.replace("<caret>", ""), PsiDocumentManager.getInstance(project).getDocument(host)!!.text)
    }

    fun `test rename to the name of another visible variable is a conflict`() {
        assertRenameConflict(hostText("(\$a := 1; \$b := 2; \$<caret>a + \$b)"), "b")
        // the other variable is a parameter of a lambda the renamed one is used in
        assertRenameConflict(hostText("(\$a := 1; function(\$b){ \$b + \$<caret>a })"), "b")
        // the renamed variable would hide an outer one read in its scope
        assertRenameConflict(hostText("(\$b := 1; (\$<caret>a := 2; \$a + \$b))"), "b")
    }

    fun `test rename to a built-in name is a conflict only where the expression calls it`() {
        assertRenameConflict(hostText("(\$x := [1]; \$sum(\$<caret>x))"), "sum")
        assertRename(hostText("(\$x := 1; \$<caret>x + 1)"), hostText("(\$count := 1; \$count + 1)"), newName = "count")
    }

    fun `test rename to a name read as no local variable is a conflict`() {
        assertRenameConflict(hostText("(\$<caret>n := 1; \$n + \$limit)"), "limit")
    }

    fun `test a forced rename into a conflict and a rename to a fresh name`() {
        val before = hostText("(\$a := 1; \$b := 2; \$<caret>a + \$b)")
        myFixture.configureByText("test.asl.yaml", before)
        val host = hostFile()
        BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<Throwable> { myFixture.renameElementAtCaret("b") }
        assertEquals(hostText("(\$b := 1; \$b := 2; \$b + \$b)"), PsiDocumentManager.getInstance(project).getDocument(host)!!.text)
        assertRename(hostText("(\$a := 1; \$b := 2; \$<caret>a + \$b)"), hostText("(\$total := 1; \$b := 2; \$total + \$b)"), newName = "total")
    }

    fun `test in-place rename into a conflict is refused`() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        for ((before, name) in listOf(
            hostText("(\$a := 1; \$b := 2; \$<caret>a + \$b)") to "b",
            hostText("(\$x := [1]; \$sum(\$<caret>x))") to "sum",
            hostText("(\$<caret>n := 1; \$n + \$limit)") to "limit",
            hostText("(\$<caret>a := 1; true ? \$a : \$b := 2; \$exists(\$b))") to "b",
            hostText("(\$f := function(){ \$sum([1, 2]) }; \$f(); \$s<caret>um := 0)") to "total",
        )) {
            assertEquals(name, before.replace("<caret>", ""), inplaceRenameTo(before, name, "test.asl.yaml"))
        }
        val fresh = hostText("(\$a := 1; \$b := 2; \$<caret>a + \$b)")
        assertEquals(hostText("(\$total := 1; \$b := 2; \$total + \$b)"), inplaceRenameTo(fresh, "total", "test.asl.yaml"))
    }

    fun `test rename to the name of a later declaration in the scope is a conflict`() {
        // the skipped assignment leaves $b undefined; after the rename $b would be 1
        assertRenameConflict(hostText("(\$<caret>a := 1; true ? \$a : \$b := 2; \$exists(\$b))"), "b")
        assertRename(
            hostText("(\$<caret>a := 1; true ? \$a : \$b := 2; \$exists(\$b))"),
            hostText("(\$total := 1; true ? \$total : \$b := 2; \$exists(\$b))"),
            newName = "total",
        )
    }

    fun `test a use in a function that may run before the assignment blocks the rename`() {
        // $f() runs before $sum := 0, so its $sum is the built-in function
        assertRenameConflict(hostText("(\$f := function(){ \$sum([1, 2]) }; \$f(); \$s<caret>um := 0)"), "total")
        // renaming $a to b would let the early $b of the function read $a
        assertRenameConflict(hostText("(\$<caret>a := 5; (\$f := function(){ \$b }; \$f(); \$b := 0); \$a)"), "b")
        // a function that calls itself exists only once it is assigned
        assertRename(
            hostText("(\$f := function(\$n){ \$n = 0 ? 0 : \$<caret>f(\$n - 1) }; \$f(3))"),
            hostText("(\$sum2 := function(\$n){ \$n = 0 ? 0 : \$sum2(\$n - 1) }; \$sum2(3))"),
        )
    }

    fun `test a transform refers to a later assignment like a function`() {
        // jsonata-js 2.0.6: ($t := |$|{"x": $a}|; $a := 1; $t({})) gives {"x": 1}
        val variables = variables(jsonata("(\$t := |\$|{\"x\": \$a}|; \$a := 1; \$t({}))"))
        val a = variables.filter { it.name == "a" }
        assertEquals(a[1], a[0].reference!!.resolve())
        assertRenameConflict(hostText("(\$t := |\$|{\"x\": \$a}|; \$<caret>a := 1; \$t({}))"), "b")
    }

    fun `test an assignment that may not run blocks the rename`() {
        // jsonata-js 2.0.6: (false ? $sum := 0 : 1; $sum([1, 2])) gives 3
        assertRenameConflict(hostText("(false ? \$s<caret>um := 0 : 1; \$sum([1, 2]))"), "total")
        // in the first step of a path that binds with #, the assignment binds in the block: ([$a := 1]#$i.$; $a) gives 1
        val variables = variables(jsonata("([\$a := 1]#\$i.\$; \$a)"))
        assertEquals(variables[0], variables.last().reference!!.resolve())
        assertRenameConflict(hostText("([\$<caret>a := 1]#\$i.\$; \$a)"), "b")
        // renaming another variable to a name assigned that way is refused too
        assertRenameConflict(hostText("(\$<caret>b := 2; [\$a := 1]#\$i.\$; \$b)"), "a")
        // an assignment that is a statement, or the right side of one, is certain
        assertRename(hostText("(\$x := \$<caret>y := 1; \$y + \$x)"), hostText("(\$x := \$sum2 := 1; \$sum2 + \$x)"))
    }

    fun `test a name written with an escape is the same name`() {
        val u = "\\" + "u0061"
        val json = """{"QueryLanguage":"JSONata","StartAt":"A","States":{"A":{"Type":"Pass","Output":"{% (${'$'}<caret>a := 1; ${'$'}""" + u + """) %}","End":true}}}"""
        myFixture.configureByText("escaped.asl.json", json)
        val variables = variables(myFixture.file)
        assertEquals(listOf("a", "a"), variables.map { it.name })
        assertEquals(variables[0], variables[1].reference!!.resolve())
        assertRename(json, json.replace("<caret>", "").replace("${'$'}a", "${'$'}sum2").replace("${'$'}$u", "${'$'}sum2"), "escaped.asl.json")
    }

    fun `test a focus or index binding ends with its path`() {
        assertRename(
            hostText("(\$states.input.items@\$s<caret>um.name; \$sum([1, 2]))"),
            hostText("(\$states.input.items@\$sum2.name; \$sum([1, 2]))"),
        )
        val text = "(\$states.input.items#\$i[\$i > 0].(\$i); \$<caret>i)"
        val file = jsonata(text)
        val uses = variables(file).filter { it.name == "i" }
        assertEquals(listOf(uses[0], uses[0]), uses.subList(1, 3).map { it.reference!!.resolve() })
        assertNull(uses[3].reference)
    }
}
