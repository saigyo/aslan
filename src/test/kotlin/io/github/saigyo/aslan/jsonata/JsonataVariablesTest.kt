package io.github.saigyo.aslan.jsonata

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector.Access
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.LanguageNamesValidation
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.testFramework.fixtures.CodeInsightTestUtil
import io.github.saigyo.aslan.check.AslChecker
import io.github.saigyo.aslan.check.AslProblem
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import io.github.saigyo.aslan.jsonata.refs.JsonataInplaceRenameHandler
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
        assertResult(host, after, sites)
    }

    private fun assertResult(host: PsiFile, after: String, sites: Int) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(after, PsiDocumentManager.getInstance(project).getDocument(host)!!.text)
        assertEquals(after, host.text)
        assertEquals(sites, AslModel.of(host)?.jsonataSites?.all?.size)
        assertEquals(emptyList<AslProblem>(), AslChecker.findings(host).map { it.problem }.filter { it == AslProblem.JSONATA_SYNTAX_ERROR })
        val renamed = injectedVariables().filter { it.name == "sum2" }
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
        myFixture.renameElementAtCaret("count")
        myFixture.checkResult("(\$count := 1; function(\$n){ \$n }(\$count))")
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
        assertEquals(listOf("a", "c"), JsonataScopes.visibleNames(variables[0]))
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
        // a use before the assignment in the same block refers to it
        assertEquals(listOf(1, -1), resolution("(\$b; \$b := 1)"))
        // a variable assigned in a block that is a lambda body is local to that block
        assertEquals(listOf(-1, 0, null), resolution("(function(){ (\$c := 1; \$c) }; \$c)"))
        // an assignment directly in a lambda body declares nothing; the lambda declares only its parameters
        assertEquals(listOf(null), resolution("function(){ \$d := 1 }"))
        // focus and index variables belong to the enclosing block: visible in later steps and after the path
        assertEquals(listOf(-1, -1, 0, 1, 0), resolution("(items@\$v#\$i.{\"n\": \$v.name, \"i\": \$i}; \$v)"))
        // an assignment at the top level is declared by the file, also for its own right side
        assertEquals(listOf(-1, 0), resolution("\$e := \$e + 1"))
    }
}
