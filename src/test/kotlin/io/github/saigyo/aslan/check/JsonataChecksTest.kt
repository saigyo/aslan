package io.github.saigyo.aslan.check

import com.intellij.lang.annotation.HighlightSeverity
import io.github.saigyo.aslan.check.AslProblem.JSONATA_SYNTAX_ERROR
import java.io.File

class JsonataChecksTest : CheckTestCase() {
    /** A Pass state whose Output is [output] (YAML text, written as given after `Output:`). */
    private fun pass(output: String) =
        "QueryLanguage: JSONata\nStartAt: A\nStates:\n  A:\n    Type: Pass\n    Output: $output\n    End: true\n"

    private fun err(text: String) = JSONATA_SYNTAX_ERROR to text

    fun `test a syntax error is marked inside the string`() =
        assertEquals(listOf(err(")")), marked(edit("'{% \$states.input.ok %}'" to "'{% \$states.input.ok) %}'")))

    fun `test an error at the end marks the last token`() =
        assertEquals(listOf(err("+")), marked(pass("'{% 1 + %}'")))

    fun `test an error on a later line of a block scalar`() =
        assertEquals(
            listOf(err(")")),
            marked(edit("Condition: '{% \$states.input.ok %}'" to "Condition: |-\n          {% \$states.input.ok\n          and 1 )\n          %}")),
        )

    fun `test an error on the second line of a quoted scalar`() {
        assertEquals(listOf(err(")")), marked(pass("'{% 1 +\n      2 ) %}'")))
        assertEquals(listOf(err(")")), marked(pass("\"{% 1 +\n      2 ) %}\"")))
    }

    fun `test an error after an escaped quote`() {
        assertEquals(listOf(err(")")), marked(pass("'{% ''a'' ) %}'"), "single.asl.yaml"))
        assertEquals(listOf(err(")")), marked(pass("\"{% \\\"a\\\" ) %}\""), "double.asl.yaml"))
        val json = """{"QueryLanguage":"JSONata","StartAt":"A","States":{"A":{"Type":"Pass","Output":"{% \"a\" ) %}","End":true}}}"""
        assertEquals(listOf(err(")")), marked(json, "test.asl.json"))
    }

    fun `test an error in a json file`() {
        val json = """{"QueryLanguage":"JSONata","StartAt":"A","States":{"A":{"Type":"Pass","Output":"{% 1 + %}","End":true}}}"""
        assertEquals(listOf(err("+")), marked(json, "test.asl.json"))
    }

    fun `test an empty expression is a syntax error`() {
        assertEquals(listOf(err(" ")), marked(pass("'{% %}'")))
        assertEquals(listOf(err("%}")), marked(pass("'{%%}'")))
    }

    fun `test an expression with a placeholder outside a string is not judged`() {
        assertEquals(emptyList<Any>(), marked(pass("'{% \${Limit} + %}'")))
        assertEquals(emptyList<Any>(), marked(pass("'{% prefix\${X} + %}'")))
        assertEquals(emptyList<Any>(), marked(pass("'{% 1 + /* \${X} */ %}'")))
    }

    fun `test a placeholder inside a string changes nothing`() =
        assertEquals(listOf(err("+")), marked(pass("'{% \"\${Bucket}\" + %}'")))

    fun `test the operators of JSONata 2_1 are no syntax error`() {
        assertEquals(emptyList<Any>(), marked(pass("'{% \$a ?: \$b %}'")))
        assertEquals(emptyList<Any>(), marked(pass("'{% \$a ?? \$b %}'")))
    }

    fun `test a host used by several states is reported once`() {
        val text = "QueryLanguage: JSONata\nStartAt: A\nStates:\n" +
            "  A:\n    Type: Pass\n    Output: &o '{% 1 + %}'\n    Next: B\n" +
            "  B:\n    Type: Pass\n    Output: *o\n    End: true\n"
        assertEquals(listOf(err("+")), marked(text))
    }

    fun `test nothing is shown twice`() {
        myFixture.enableInspections(*AslInspections.all().toTypedArray())
        myFixture.configureByText("test.asl.yaml", pass("'{% 1 + %}'"))
        assertEquals(1, myFixture.doHighlighting(HighlightSeverity.ERROR).size)
    }

    fun `test the large clean definition is checked in reasonable time`() {
        val file = myFixture.configureByText("film-archive-intake.asl.yaml", File("src/test/testData/clean/film-archive-intake.asl.yaml").readText())
        val first = AslChecker.findings(file)
        assertEquals(emptyList<AslProblem>(), first.filter { it.problem == JSONATA_SYNTAX_ERROR }.map { it.problem })
        assertSame(first, AslChecker.findings(file))
    }
}
