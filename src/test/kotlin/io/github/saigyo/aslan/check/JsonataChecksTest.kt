package io.github.saigyo.aslan.check

import com.intellij.lang.annotation.HighlightSeverity
import io.github.saigyo.aslan.check.AslProblem.JSONATA_SYNTAX_ERROR
import io.github.saigyo.aslan.check.AslProblem.JSONATA_WITHOUT_INPUT
import io.github.saigyo.aslan.check.AslProblem.NEWER_JSONATA_OPERATOR
import io.github.saigyo.aslan.check.AslProblem.STATES_FIELD_UNAVAILABLE
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_JSONATA_FUNCTION
import io.github.saigyo.aslan.check.AslProblem.UNSUPPORTED_JSONATA_FUNCTION
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

    fun `test a placeholder inside a string changes nothing`() {
        assertEquals(listOf(err("+")), marked(pass("'{% \"\${Bucket}\" + %}'")))
        assertEquals(listOf(err("+")), marked(pass("'{% \"\${ssm:/catalog/limit}\" + %}'"), "inside.asl.yaml"))
    }

    fun `test deployment tool forms of placeholders are not judged`() {
        val forms = listOf("\${ssm:/catalog/limit} + 1", "\${opt:stage, ''dev''} + ", "\${jsonencode(x)} + ", "prefix\${X} + ", "\${file(./x.yml):k} + ")
        forms.forEachIndexed { i, form ->
            assertEquals(form, emptyList<Any>(), marked(pass("'{% $form %}'"), "form$i.asl.yaml"))
        }
    }

    fun `test an empty expression on a later line is reported`() {
        assertEquals(listOf(err("%}")), marked(pass(">-\n      {%%}"), "block.asl.yaml"))
        val quoted = marked(pass("'{%\n      %}'"), "quoted.asl.yaml")
        assertEquals(1, quoted.size)
        assertEquals(JSONATA_SYNTAX_ERROR, quoted.single().first)
    }

    fun `test the operators of JSONata 2_1 are no syntax error`() {
        assertEquals(emptyList<Any>(), marked(pass("'{% \$a ?: \$b %}'"), "elvis.asl.yaml").filter { it.first == JSONATA_SYNTAX_ERROR })
        assertEquals(emptyList<Any>(), marked(pass("'{% \$a ?? \$b %}'"), "coalesce.asl.yaml").filter { it.first == JSONATA_SYNTAX_ERROR })
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

    fun `test the findings of the large clean definition are cached and hold no syntax error`() {
        val file = myFixture.configureByText("film-archive-intake.asl.yaml", File("src/test/testData/clean/film-archive-intake.asl.yaml").readText())
        val first = AslChecker.findings(file)
        assertEquals(emptyList<AslProblem>(), first.filter { it.problem == JSONATA_SYNTAX_ERROR }.map { it.problem })
        assertSame(first, AslChecker.findings(file))
    }

    private var files = 0

    private val expressionProblems = setOf(STATES_FIELD_UNAVAILABLE, UNKNOWN_JSONATA_FUNCTION, UNSUPPORTED_JSONATA_FUNCTION,
        NEWER_JSONATA_OPERATOR, JSONATA_WITHOUT_INPUT, JSONATA_SYNTAX_ERROR)

    /** The findings of the expression checks (and syntax errors) in [text], each in a file of its own. */
    private fun inside(text: String): List<Pair<AslProblem, String>> =
        marked(text, "expr${files++}.asl.yaml").filter { it.first in expressionProblems }

    /** [expression] as the Output of a Pass state, in a block scalar so that it needs no quoting. */
    private fun expr(expression: String) = inside(pass(">-\n      {% $expression %}"))

    /** A Task state with a catcher; [field] is `Arguments`, `Output`, `Assign`, `CatchOutput` or `CatchAssign`. */
    private fun task(field: String, expression: String): List<Pair<AslProblem, String>> {
        val value = ">-\n          {% $expression %}"
        val arguments = if (field == "Arguments") "    Arguments:\n      Payload: $value\n" else "    Arguments:\n      FunctionName: catalog-reel\n"
        val own = when (field) {
            "Output" -> "    Output: $value\n"
            "Assign" -> "    Assign:\n      reel: $value\n"
            else -> ""
        }
        val catcher = when (field) {
            "CatchOutput" -> "        Output: $value\n"
            "CatchAssign" -> "        Assign:\n          reel: $value\n"
            else -> ""
        }
        return inside(
            "QueryLanguage: JSONata\nStartAt: A\nStates:\n  A:\n    Type: Task\n    Resource: arn:aws:states:::lambda:invoke\n" +
                arguments + own + "    Catch:\n      - ErrorEquals: [States.ALL]\n        Next: B\n" + catcher + "    End: true\n" +
                "  B:\n    Type: Fail\n",
        )
    }

    private fun field(text: String) = listOf(STATES_FIELD_UNAVAILABLE to text)
    private fun noInput(text: String) = listOf(JSONATA_WITHOUT_INPUT to text)
    private val nothing = emptyList<Pair<AslProblem, String>>()

    fun `test result and errorOutput outside their fields`() {
        assertEquals(field("result"), task("Arguments", "\$states.result"))
        assertEquals(nothing, task("Output", "\$states.result"))
        assertEquals(nothing, task("Assign", "\$states.result.reel"))
        assertEquals(field("result"), task("CatchOutput", "\$states.result"))
        assertEquals(nothing, task("CatchOutput", "\$states.errorOutput"))
        assertEquals(nothing, task("CatchAssign", "\$states.errorOutput.Cause"))
        assertEquals(field("errorOutput"), task("Output", "\$states.errorOutput"))
        assertEquals(field("errorOutput"), expr("\$states.errorOutput"))
        assertEquals(field("result"), expr("\$states.result"))
        assertEquals(field("`result`"), task("Arguments", "\$states.`result`"))
    }

    fun `test unknown fields of states`() {
        assertEquals(field("nope"), expr("\$states.nope"))
        assertEquals(field("nope"), expr("\$states.nope.reel"))
        assertEquals(nothing, expr("\$states.context.Nope"))
        assertEquals(nothing, expr("\$states.context.Execution.Id"))
        assertEquals(nothing, expr("\$states.input.reel"))
        assertEquals(nothing, expr("\$states"))
    }

    fun `test a local variable named states is not checked`() {
        assertEquals(nothing, task("Arguments", "(\$states := {\"result\": 1}; \$states.result)"))
        assertEquals(nothing, expr("function(\$states){ \$states.nope }({\"nope\": 1})"))
    }

    fun `test an anchored expression is reported when one of its uses is wrong`() {
        val task = "    Type: Task\n    Resource: arn:aws:states:::lambda:invoke\n    Arguments:\n      FunctionName: catalog-reel\n"
        val wrong = "QueryLanguage: JSONata\nStartAt: A\nStates:\n" +
            "  A:\n$task    Output: &o '{% \$states.result %}'\n    Next: B\n" +
            "  B:\n    Type: Pass\n    Output: *o\n    End: true\n"
        assertEquals(field("result"), inside(wrong))
        val right = "QueryLanguage: JSONata\nStartAt: A\nStates:\n" +
            "  A:\n$task    Output: &o '{% \$states.result %}'\n    Next: B\n" +
            "  B:\n$task    Output: *o\n    End: true\n"
        assertEquals(nothing, inside(right))
    }

    fun `test a call of an unknown function`() {
        assertEquals(listOf(UNKNOWN_JSONATA_FUNCTION to "\$nope"), expr("\$nope(1)"))
        assertEquals(nothing, expr("(\$f := function(\$x){\$x}; \$f(1))"))
        for (known in listOf("\$uuid()", "\$partition([1,2,3], 2)", "\$range(1, 3, 1)", "\$hash(\"a\", \"SHA-256\")", "\$parse(\"{}\")",
                "\$random(1)", "\$now()", "\$millis()", "\$sum(1, 2, 3)", "\$substring()", "\$uppercase(1)")) {
            assertEquals(known, nothing, expr(known))
        }
        assertEquals(nothing, expr("\$x"))
        assertEquals(nothing, expr("\$x := 1"))
        assertEquals(nothing, expr("\$states := 1"))
    }

    fun `test eval`() {
        assertEquals(listOf(UNSUPPORTED_JSONATA_FUNCTION to "\$eval"), expr("\$eval(\"1\")"))
        assertEquals(nothing, expr("(\$eval := function(\$s){\$s}; \$eval(\"1\"))"))
    }

    fun `test the operators of JSONata 2_1`() {
        assertEquals(listOf(NEWER_JSONATA_OPERATOR to "?:"), expr("\$states.input.a ?: 1"))
        assertEquals(listOf(NEWER_JSONATA_OPERATOR to "??"), expr("\$states.input.a ?? 1"))
        assertEquals(nothing, expr("function(\$a)<s?:s>{\$a}"))
    }

    fun `test names without an input`() {
        val reported = listOf(
            "foo" to "foo", "\$" to "\$", "\$\$" to "\$\$", "foo.bar" to "foo", "\$.a" to "\$", "(foo)" to "foo", "[foo]" to "foo",
            "{\"a\": foo}" to "foo", "\$string(foo)" to "foo", "function(\$v){ foo }(1)" to "foo",
            "\$map(\$states.input.items, function(\$v){ foo })" to "foo", "foo ? 1 : 2" to "foo", "-foo" to "foo",
            "foo & \"x\"" to "foo", "foo.bar[baz > 1]" to "foo", "\$states.input.items[\$\$.a]" to "\$\$",
            "and" to "and", "or.x" to "or", "`reel title`" to "`reel title`",
        )
        for ((expression, text) in reported) assertEquals(expression, noInput(text), expr(expression))
    }

    fun `test names with an input`() {
        val silent = listOf(
            "*", "%", "\$states.input.items[price > 1]", "\$states.input.items.\$", "\$states.input.{\"a\": b}",
            "\$states.input.items^(price)", "\$states.input.items{category: price}", "\$states.input.items.\$string(name)",
            "\$states.input ~> |items|{\"a\": b}|", "\$states.input.orders.items.%.id", "\$states.input.*", "\$states.input.**.id",
            "\$states.input.items#\$i.{\"n\": \$i, \"v\": name}", "\$states.input.items@\$it.{\"v\": \$it.name}",
            "\$states.input.name ~> \$uppercase() ~> \$trim()", "\$substring(?, 0, 2)(\"hello\")", "\$states.input.`reel title`",
        )
        for (expression in silent) assertEquals(expression, nothing, expr(expression))
    }

    fun `test a re-bound context reference is not reported`() {
        assertEquals(nothing, expr("(\$\$ := \$states.input; \$\$.a)"))
        assertEquals(nothing, expr("function(\$\$){ \$\$ }(1)"))
        assertEquals(noInput("\$\$"), expr("(\$x := \$states.input; \$\$.a)"))
    }

    fun `test nothing in an expression with a syntax error but the error`() =
        assertEquals(listOf(JSONATA_SYNTAX_ERROR to "+"), expr("foo + \$nope(1) +"))

    fun `test nothing in an expression with a placeholder outside a string`() {
        assertEquals(nothing, expr("foo & \${Suffix}"))
        assertEquals(nothing, expr("\$nope(\${Limit})"))
        assertEquals(nothing, task("Arguments", "\$states.result & \${Suffix}"))
    }
}
