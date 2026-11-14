package io.github.saigyo.aslan.jsonata

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.util.PsiTreeUtil
import io.github.saigyo.aslan.check.AslChecker
import io.github.saigyo.aslan.jsonata.parser.JsonataScanner
import io.github.saigyo.aslan.jsonata.psi.JsonataCall
import io.github.saigyo.aslan.jsonata.psi.JsonataLambda
import io.github.saigyo.aslan.jsonata.psi.JsonataPath

class JsonataPsiTest : JsonataTestCase() {
    private fun errors(file: PsiFile): List<PsiErrorElement> = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).toList()

    fun `test the tree of an expression`() {
        val file = jsonata("(\$t := 1; \$f(\$t, ?).a[0])")
        assertEquals(EXPECTED, DebugUtil.psiToString(file, false, false).trim())
    }

    fun `test one error element where the scanner reports an error`() {
        assertEquals(1, errors(jsonata("1 +")).size)
        assertEquals(0, errors(jsonata("1 + 2")).size)
    }

    fun `test the psi of every suite expression has an error exactly when the scan has`() {
        val suite = JsonataConformanceTest.suite
        assertEquals(1625, suite.size)
        for (case in suite) {
            // in memory: a file on disk would turn the lone surrogates of two cases into question marks
            val file = PsiFileFactory.getInstance(project).createFileFromText("expr", JsonataFileType, case.expr)
            assertEquals("text of ${case.id}", case.expr, file.text)
            val expected = if (JsonataScanner.scan(case.expr).error != null) 1 else 0
            assertEquals("errors of ${case.id}: ${case.expr}", expected, errors(file).size)
        }
    }

    fun `test accessors`() {
        val call = PsiTreeUtil.findChildOfType(jsonata("\$sum(1, 2)"), JsonataCall::class.java)!!
        assertEquals("\$sum", call.callee!!.text)
        assertEquals(listOf("1", "2"), call.arguments.map { it.text })

        val lambda = PsiTreeUtil.findChildOfType(jsonata("function(\$a, \$b){ \$a }"), JsonataLambda::class.java)!!
        assertEquals(listOf("a", "b"), lambda.parameters.map { it.variableName })
        assertEquals("\$a", lambda.body!!.text)

        val path = PsiTreeUtil.findChildOfType(jsonata("a.b"), JsonataPath::class.java)!!
        assertEquals("a", path.lhs!!.text)
        assertEquals("b", path.rhs!!.text)
    }

    fun `test an empty and a blank file`() {
        assertEquals(1, errors(jsonata("")).size)
        assertEquals(1, errors(jsonata("  ")).size)
    }

    fun `test an error after parsing keeps the tree`() {
        val file = jsonata("a.1")
        assertEquals(1, errors(file).size)
        assertNotNull(PsiTreeUtil.findChildOfType(file, JsonataPath::class.java))
    }

    fun `test a deep tree is built`() {
        assertEquals("(".repeat(200), jsonata("(".repeat(200)).text)
        val path = "a" + ".a".repeat(2000)
        assertEquals(path, jsonata(path).text)
    }

    fun `test the host text passes the checker`() {
        assertEquals(emptyList<Any>(), AslChecker.findings(hosted("\$states.input.title")).map { it.problem })
    }

    private companion object {
        val EXPECTED = """
            FILE
              JsonataElement(BLOCK)
                PsiElement(LPAREN)('(')
                JsonataElement(ASSIGNMENT)
                  JsonataVariableRef(VARIABLE_REF)
                    PsiElement(VARIABLE)('${'$'}t')
                  PsiElement(ASSIGN)(':=')
                  JsonataElement(LITERAL)
                    PsiElement(NUMBER)('1')
                PsiElement(SEMICOLON)(';')
                JsonataPath(PATH)
                  JsonataCall(CALL)
                    JsonataVariableRef(VARIABLE_REF)
                      PsiElement(VARIABLE)('${'$'}f')
                    PsiElement(LPAREN)('(')
                    JsonataVariableRef(VARIABLE_REF)
                      PsiElement(VARIABLE)('${'$'}t')
                    PsiElement(COMMA)(',')
                    JsonataElement(PARTIAL_ARGUMENT)
                      PsiElement(QUESTION)('?')
                    PsiElement(RPAREN)(')')
                  PsiElement(DOT)('.')
                  JsonataElement(FILTER)
                    JsonataElement(FIELD)
                      PsiElement(NAME)('a')
                    PsiElement(LBRACKET)('[')
                    JsonataElement(LITERAL)
                      PsiElement(NUMBER)('0')
                    PsiElement(RBRACKET)(']')
                PsiElement(RPAREN)(')')
        """.trimIndent()
    }
}
