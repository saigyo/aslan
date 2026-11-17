package io.github.saigyo.aslan.diagram

import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.diagram.DiagramLabels.Label
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslObject

class DiagramLabelsTest : AslTestCase() {
    /** The rules of the Choice state `Sort` (or the catchers of the Task `Sort`), written as the lines of a list. */
    private fun listed(field: String, vararg items: String, language: String = "JSONPath"): List<AslObject> {
        val text = "QueryLanguage: $language\nStartAt: Sort\nStates:\n  Sort:\n    Type: Choice\n    $field:\n" +
            items.joinToString("\n") { "      - " + it.replace("\n", "\n        ") }
        val state = AslModel.of(yaml(text.replace('§', '$')))!!.root!!.state("Sort")!!
        return (state.obj!![field]!!.value as AslArray).items.map { it as AslObject }
    }

    private fun rules(vararg items: String, language: String = "JSONPath") = listed("Choices", *items, language = language)

    private fun rule(item: String, index: Int = 0, language: QueryLanguage = QueryLanguage.JSONPATH) =
        DiagramLabels.rule(rules(item, language = language.text)[0], index, language)

    fun `test resource labels`() {
        val expected = mapOf(
            "arn:aws:states:::aws-sdk:s3:listObjectsV2" to "S3 listObjectsV2",
            "arn:aws:states:::lambda:invoke.waitForTaskToken" to "Lambda invoke",
            "arn:aws:states:::states:startExecution.sync:2" to "Step Functions startExecution",
            "arn:aws-cn:states:::sqs:sendMessage" to "SQS sendMessage",
            "arn:aws:states:::aws-sdk:frobnicate:doIt" to "frobnicate doIt",
            "arn:aws:states:eu-central-1:123456789012:activity:scan" to "Activity scan",
            "arn:aws:lambda:eu-central-1:123456789012:function:catalog-reader:live" to "Lambda catalog-reader",
            "arn:aws:lambda:§{Region}:§{Account}:function:scan" to "Lambda scan",
            // placeholders that hold colons themselves, as CloudFormation's pseudo parameters do
            "arn:aws:lambda:§{AWS::Region}:§{AWS::AccountId}:function:scan" to "Lambda scan",
            "arn:§{AWS::Partition}:states:::lambda:invoke" to "Lambda invoke",
            "§{ScanFunctionArn}" to "§{ScanFunctionArn}",
        )
        for ((resource, label) in expected) assertEquals(resource, label.replace('§', '$'), DiagramLabels.resource(resource.replace('§', '$')))
    }

    fun `test resources that are not settled are shown as written`() {
        for (text in listOf("", "arn:aws:states:::", "arn:aws:states:::lambda", "arn:aws:states:::§{Service}:invoke".replace('§', '$'),
            "arn:aws:lambda:eu-west-1:1:function:", "arn:aws:s3:::bucket", "not an arn")) {
            assertEquals(text, DiagramLabels.resource(text))
        }
    }

    fun `test a JSONata condition is shown without its delimiters`() {
        val single = rule("Condition: \"{% §states.input.status = 'split' %}\"\nNext: A", language = QueryLanguage.JSONATA)
        assertEquals(Label("\$states.input.status = 'split'", null, "\$states.input.status = 'split'"), single)
        val multiple = rule("Condition: |-\n  {% §a and\n     §b\n     and §c %}\nNext: A", language = QueryLanguage.JSONATA)
        assertEquals(Label("\$a and \$b and \$c", null, "\$a and \$b and \$c"), multiple)
    }

    fun `test shorten cuts by code points`() {
        val cut = DiagramLabels.shorten("abcdefghijklmnopqrstuvwxyz", 20)
        assertEquals(20, cut.codePointCount(0, cut.length))
        assertEquals("abcdefghijklmnopqrs…", cut)
        assertEquals("short", DiagramLabels.shorten("short", 20))
        val pairs = "🎞".repeat(10)
        assertEquals("🎞".repeat(4) + "…", DiagramLabels.shorten(pairs, 5))
        assertEquals(pairs, DiagramLabels.shorten(pairs, 10))
    }

    fun `test JSONPath rules`() {
        assertEquals(Label("status = \"split\"", "\$.status = \"split\"", "\$.status = \"split\""), rule("Variable: §.status\nStringEquals: split\nNext: A"))
        assertEquals("count >= 10", rule("Variable: §.count\nNumericGreaterThanEquals: 10\nNext: A").text)
        assertEquals("a = \$.b", rule("Variable: §.a\nStringEqualsPath: §.b\nNext: A").text)
        assertEquals("seal is not present", rule("Variable: §.seal\nIsPresent: false\nNext: A").text)
        assertEquals("seal is present", rule("Variable: §.seal\nIsPresent: true\nNext: A").text)
        assertEquals("name matches \"reel-*\"", rule("Variable: §.name\nStringMatches: \"reel-*\"\nNext: A").text)
        assertEquals("ok = true", rule("Variable: §.ok\nBooleanEquals: true\nNext: A").text)
        assertEquals("n < 3", rule("Variable: §.n\nNumericLessThan: 3\nNext: A").text)
        assertEquals("at <= \"2026-01-01T00:00:00Z\"", rule("Variable: §.at\nTimestampLessThanEquals: '2026-01-01T00:00:00Z'\nNext: A").text)
        assertEquals("x is null", rule("Variable: x\nIsNull: true\nNext: A").text)
    }

    fun `test a rule the label cannot say is numbered`() {
        val and = rule("And:\n  - Variable: §.a\n    IsPresent: true\n  - Variable: §.b\n    IsPresent: true\nNext: A", index = 1)
        assertEquals("Rule 2", and.text)
        assertTrue(and.title!!, and.title!!.startsWith("And:"))
        assertFalse(and.title!!.contains('\n'))
        assertEquals("Rule 1", rule("Variable: §{Path}\nStringEquals: x\nNext: A").text)
        assertEquals("Rule 1", rule("Variable: §.a\nStringEquals: x\nStringEqualsPath: §.b\nNext: A").text)
        assertEquals("Rule 1", rule("Variable: §.a\nNext: A").text)
        assertEquals("Rule 1", rule("Variable: §.a\nIsPresent: maybe\nNext: A").text)
        assertEquals("Rule 3", rule("Condition: true\nNext: A", index = 2, language = QueryLanguage.JSONATA).text)
        assertEquals("Rule 1", rule("Condition: '{% %}'\nNext: A", language = QueryLanguage.JSONATA).text)
    }

    fun `test the title of a long rule is cut and an empty rule has none`() {
        val long = rule("And:\n  - Variable: §.a\n    StringEquals: " + "x".repeat(300) + "\nNext: A")
        assertEquals(200, long.title!!.codePointCount(0, long.title!!.length))
        assertTrue(long.title!!.endsWith("…"))
        assertNull(rule("{}").title)
    }

    fun `test a catcher is labelled Catch with its error names`() {
        val catchers = listed("Catch", "ErrorEquals: [States.Timeout, States.TaskFailed]\nNext: A", "Next: A", "ErrorEquals: []\nNext: A", "ErrorEquals: oops")
        assertEquals(Label("Catch", "States.Timeout, States.TaskFailed"), DiagramLabels.catcher(catchers[0]))
        assertEquals(Label("Catch", null), DiagramLabels.catcher(catchers[1]))
        assertEquals(Label("Catch", null), DiagramLabels.catcher(catchers[2]))
        assertEquals(Label("Catch", null), DiagramLabels.catcher(catchers[3]))
    }

    fun `test a placeholder as operand is not shown as a value`() {
        assertEquals("Rule 1", rule("Variable: §.count\nNumericGreaterThan: §{Threshold}\nNext: A").text)
        assertEquals("Rule 1", rule("Variable: §.name\nStringEquals: \"reel-§{Suffix}\"\nNext: A").text)
        assertEquals("count > 3", rule("Variable: §.count\nNumericGreaterThan: 3\nNext: A").text)
    }

    private fun condition(expression: String) =
        rule("Condition: \"{% " + expression.replace("\\", "\\\\").replace("\"", "\\\"") + " %}\"\nNext: A", language = QueryLanguage.JSONATA).text

    fun `test white space inside a quoted literal is kept`() {
        assertEquals("\$states.input.code = 'A  B'", condition("§states.input.code   =   'A  B'"))
        assertEquals("\$x = \"A   B\" and \$y", condition("§x =  \"A   B\"  and   §y"))
        assertEquals("\$x = `A   B` and \$y", condition("§x =  `A   B`  and   §y"))
        assertEquals("\$x = 'it\\'s  here' and \$y", condition("§x = 'it\\'s  here'    and §y"))
        assertEquals("a b", condition("   a      b  "))
    }

    fun `test a regular expression of a JSONata condition keeps its white space`() {
        assertEquals("\$contains(\$x, /A  B/)", condition("§contains(§x,   /A  B/)"))
        assertEquals("\$a / \$b and \$c", condition("§a   /  §b   and  §c"))
        assertEquals("\$x = 'A  B' and \$y", condition("§x =   'A  B'   and   §y"))
        val multiple = rule("Condition: |-\n  {% §contains(§x,\n       /A  B/)\n     and §y %}\nNext: A", language = QueryLanguage.JSONATA)
        assertEquals("\$contains(\$x, /A  B/) and \$y", multiple.text)
    }

    fun `test a half-typed JSONata condition does not throw`() {
        assertEquals("\$x = 'abc  d", condition("§x  =  'abc  d"))
        assertTrue(condition("§contains(§x,   /ab  c").startsWith("\$contains("))
        assertTrue(condition("§x  /* open  comment").startsWith("\$x"))
    }

    fun `test an unclosed quote keeps the rest as written`() {
        assertEquals("\$x = 'A   B", condition("§x  =  'A   B"))
        assertEquals("\$x = 'A \\", condition("§x  =  'A \\"))
    }

    fun `test the written text of a rule keeps white space inside literals`() {
        val label = rule("And:\n  - Variable: §.a\n    StringEquals: 'x    y'\nNext: A")
        assertTrue(label.title!!, label.title!!.contains("'x    y'"))
    }
}
