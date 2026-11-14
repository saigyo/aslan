package io.github.saigyo.aslan.check

import io.github.saigyo.aslan.check.AslProblem.EMPTY_CHOICES
import io.github.saigyo.aslan.check.AslProblem.JSONPATH_STATE_IN_JSONATA_MACHINE
import io.github.saigyo.aslan.check.AslProblem.MISSING_REQUIRED_FIELD
import io.github.saigyo.aslan.check.AslProblem.MISPLACED_JSONATA_DELIMITER
import io.github.saigyo.aslan.check.AslProblem.MISSING_TRANSITION
import io.github.saigyo.aslan.check.AslProblem.NULL_VALUE
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_ENUM_VALUE
import io.github.saigyo.aslan.check.AslProblem.ROOT_NOT_MAPPING
import io.github.saigyo.aslan.check.AslProblem.UNCLOSED_JSONATA_DELIMITER
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_STATE_TYPE
import io.github.saigyo.aslan.check.AslProblem.WRONG_QUERY_LANGUAGE_FIELD
import io.github.saigyo.aslan.check.AslProblem.WRONG_VALUE_KIND

class StructureChecksTest : CheckTestCase() {
    private val retryBlock = "    Retry:\n      - ErrorEquals: [States.ALL]\n        MaxAttempts: 2\n"
    private val condition = "'{% ${'$'}states.input.ok %}'"

    fun `test BASE is clean`() = assertEquals(emptyList<Any>(), found(BASE))

    fun `test root that is a list`() = assertEquals(listOf(ROOT_NOT_MAPPING to "- a"), found("- a"))

    fun `test missing StartAt`() =
        assertEquals(listOf(MISSING_REQUIRED_FIELD to "QueryLanguage"), found(edit("StartAt: Inspect\n" to "")))

    fun `test missing Type`() =
        assertEquals(listOf(MISSING_REQUIRED_FIELD to "Inspect"), found(edit("    Type: Task\n" to "")))

    fun `test unknown type`() =
        assertEquals(listOf(UNKNOWN_STATE_TYPE to "Tsak"), found(edit("Type: Task" to "Type: Tsak")))

    fun `test missing Resource`() =
        assertEquals(listOf(MISSING_REQUIRED_FIELD to "Inspect"), found(edit("    Resource: arn:aws:states:::lambda:invoke\n" to "")))

    fun `test empty Choices`() = assertEquals(
        listOf(EMPTY_CHOICES to "Choices"),
        found(edit("    Choices:\n      - Condition: $condition\n        Next: Accepted\n" to "    Choices: []\n",
            "Default: Rejected" to "Default: Accepted")),
    )

    fun `test rule without Condition`() = assertEquals(
        listOf(MISSING_REQUIRED_FIELD to "Next"),
        found(edit("      - Condition: $condition\n        Next: Accepted" to "      - Next: Accepted")),
    )

    fun `test catcher without Next`() =
        assertEquals(listOf(MISSING_REQUIRED_FIELD to "ErrorEquals"), found(edit("        Next: Rejected\n" to "")))

    fun `test no transition`() =
        assertEquals(listOf(MISSING_TRANSITION to "Inspect"), found(edit("    Next: Decide\n" to "")))

    fun `test End false counts as absent`() =
        assertEquals(emptyList<Any>(), found(edit("    Next: Decide\n" to "    Next: Decide\n    End: false\n")))

    fun `test placeholder End`() = assertEquals(
        emptyList<Any>(),
        found(edit("  Accepted:\n    Type: Succeed" to "  Accepted:\n    Type: Pass\n    End: ${'$'}{Last}")),
    )

    fun `test ambiguous End`() = assertEquals(
        emptyList<Any>(),
        found(edit("  Accepted:\n    Type: Succeed" to "  Accepted:\n    Type: Pass\n    End: yes")),
    )

    fun `test JSONPath field in JSONata state`() =
        assertEquals(listOf(WRONG_QUERY_LANGUAGE_FIELD to "Parameters"), found(edit("Arguments:" to "Parameters:")))

    fun `test JSONata fields in a JSONPath machine`() = assertEquals(
        listOf(WRONG_QUERY_LANGUAGE_FIELD to "Arguments", WRONG_QUERY_LANGUAGE_FIELD to "Condition"),
        found(edit("QueryLanguage: JSONata\n" to "")),
    )

    fun `test placeholder query language`() =
        assertEquals(emptyList<Any>(), found(edit("QueryLanguage: JSONata" to "QueryLanguage: ${'$'}{Language}")))

    fun `test JSONPath state in JSONata machine`() = assertEquals(
        listOf(JSONPATH_STATE_IN_JSONATA_MACHINE to "JSONPath"),
        found(edit("    Type: Succeed" to "    Type: Succeed\n    QueryLanguage: JSONPath")),
    )

    fun `test text for a number`() =
        assertEquals(listOf(WRONG_VALUE_KIND to "many"), found(edit("MaxAttempts: 2" to "MaxAttempts: many")))

    fun `test quoted number`() =
        assertEquals(listOf(WRONG_VALUE_KIND to "'2'"), found(edit("MaxAttempts: 2" to "MaxAttempts: '2'")))

    fun `test mapping for a list`() = assertEquals(
        listOf(WRONG_VALUE_KIND to "{MaxAttempts: 2}"),
        found(edit(retryBlock to "    Retry: {MaxAttempts: 2}\n")),
    )

    fun `test empty value`() = assertEquals(listOf(WRONG_VALUE_KIND to "Next"), found(edit("Next: Decide" to "Next:")))

    fun `test placeholder for a number`() =
        assertEquals(emptyList<Any>(), found(edit("MaxAttempts: 2" to "MaxAttempts: ${'$'}{Attempts}")))

    fun `test number written the YAML 1_1 way`() =
        assertEquals(emptyList<Any>(), found(edit("MaxAttempts: 2" to "MaxAttempts: 1_0")))

    fun `test unresolved alias`() = assertEquals(emptyList<Any>(), found(edit(retryBlock to "    Retry: *missing\n")))

    fun `test JSONata where allowed`() = assertEquals(
        emptyList<Any>(),
        found(edit("    Next: Decide\n" to "    Next: Decide\n    TimeoutSeconds: '{% ${'$'}t %}'\n")),
    )

    fun `test unclosed delimiter`() = assertEquals(
        listOf(UNCLOSED_JSONATA_DELIMITER to "'{% ${'$'}states.input.ok'"),
        found(edit(condition to "'{% ${'$'}states.input.ok'")),
    )

    fun `test folded scalar that keeps its line break`() = assertEquals(
        listOf(UNCLOSED_JSONATA_DELIMITER),
        found(edit("Condition: $condition" to "Condition: >\n          {% ${'$'}states.input.ok %}")).map { it.first },
    )

    fun `test unclosed delimiter inside free-form data`() = assertEquals(
        listOf(UNCLOSED_JSONATA_DELIMITER),
        found(edit("FunctionName: ${'$'}{InspectFn}" to "FunctionName: '{% ${'$'}x'")).map { it.first },
    )

    fun `test placeholder as Type`() = assertEquals(emptyList<Any>(), found(edit("Type: Succeed" to "Type: ${'$'}{Kind}")))

    fun `test placeholder as whole state`() = assertEquals(
        emptyList<Any>(),
        found(edit("Accepted:\n    Type: Succeed" to "Accepted: ${'$'}{AcceptedState}")),
    )

    fun `test unknown keys in free-form data`() = assertEquals(
        emptyList<Any>(),
        found(edit("    Arguments:\n" to "    Arguments:\n      Next: 5\n      Whatever: [1]\n")),
    )

    fun `test wrong kind in an aliased retrier is reported once`() = assertEquals(
        listOf(WRONG_VALUE_KIND to "many"),
        found(edit(
            "      - ErrorEquals: [States.ALL]\n        MaxAttempts: 2" to "      - &r\n        ErrorEquals: [States.ALL]\n        MaxAttempts: many",
            "  Accepted:\n    Type: Succeed" to "  Accepted:\n    Type: Task\n    Resource: x\n    Retry: [*r]\n    End: true",
        )),
    )

    fun `test JSON file`() {
        val json = """
            {
              "QueryLanguage": "JSONata",
              "StartAt": "Inspect",
              "States": {
                "Inspect": {
                  "Type": "Tsak",
                  "Resource": "arn:aws:states:::lambda:invoke",
                  "Arguments": {"FunctionName": "${'$'}{InspectFn}"},
                  "Retry": [{"ErrorEquals": ["States.ALL"], "MaxAttempts": 2}],
                  "Catch": [{"ErrorEquals": ["States.ALL"], "Next": "Rejected"}],
                  "Next": "Decide"
                },
                "Decide": {
                  "Type": "Choice",
                  "Choices": [{"Condition": "{% ${'$'}states.input.ok %}", "Next": "Accepted"}],
                  "Default": "Rejected"
                },
                "Accepted": {"Type": "Succeed"},
                "Rejected": {"Type": "Fail"}
              }
            }
        """.trimIndent()
        assertEquals(listOf(UNKNOWN_STATE_TYPE to "\"Tsak\""), found(json, "test.asl.json"))
    }

    fun `test not an ASL file`() = assertEquals(emptyList<Any>(), found(edit("Type: Task" to "Type: Tsak"), "plain.yaml"))

    fun `test half-typed file`() {
        found("StartAt:\nStates:\n  A:\n    Type")
        found("States:\n  A:\n  - x")
    }

    fun `test End false without Next is a missing transition`() =
        assertEquals(listOf(MISSING_TRANSITION to "Inspect"), found(edit("    Next: Decide\n" to "    End: false\n")))

    fun `test unclosed delimiter in a field that is never evaluated`() = assertEquals(
        listOf(AslProblem.JSONATA_IN_STATIC_FIELD to "'{% todo'"),
        found(edit("    Type: Succeed" to "    Type: Succeed\n    Comment: '{% todo'")),
    )

    fun `test unclosed delimiter in a field that evaluates JSONata`() = assertEquals(
        listOf(UNCLOSED_JSONATA_DELIMITER to "'{% ${'$'}t'"),
        found(edit("    Next: Decide\n" to "    Next: Decide\n    TimeoutSeconds: '{% ${'$'}t'\n")),
    )

    fun `test unclosed delimiter with a placeholder`() = assertEquals(
        emptyList<Any>(),
        found(edit("FunctionName: ${'$'}{InspectFn}" to "FunctionName: '{% ${'$'}x ${'$'}{Tail}'")),
    )

    fun `test no delimiter check inside a field of the other query language`() = assertEquals(
        listOf(WRONG_QUERY_LANGUAGE_FIELD to "Parameters"),
        found(edit("Arguments:" to "Parameters:", "FunctionName: ${'$'}{InspectFn}" to "FunctionName: '{% ${'$'}x'")),
    )

    fun `test no delimiter check inside a value of the wrong kind`() = assertEquals(
        listOf(WRONG_VALUE_KIND to "{X: '{% a'}"),
        found(edit(retryBlock to "    Retry: {X: '{% a'}\n")),
    )

    fun `test every prefix of BASE is checked without an exception`() {
        for (end in BASE.indices) found(BASE.substring(0, end))
    }

    fun `test the kind of a lenient field is not checked`() =
        assertEquals(emptyList<Any>(), found(edit("StartAt: Inspect\n" to "StartAt: Inspect\nVersion: 1.0\n")))

    fun `test an unapplied merge key is not reported and may supply required fields`() = assertEquals(
        emptyList<Any>(),
        found(edit("      - ErrorEquals: [States.ALL]\n        MaxAttempts: 2" to "      - <<: *missing\n        MaxAttempts: 2")),
    )

    // Null values (A2)

    private fun withSucceedField(line: String) = edit("    Type: Succeed" to "    Type: Succeed\n    $line")

    fun `test empty optional field`() = assertEquals(listOf(NULL_VALUE to "Comment"), found(withSucceedField("Comment:")))

    fun `test null optional field`() = assertEquals(listOf(NULL_VALUE to "null"), found(withSucceedField("Comment: null")))

    fun `test empty optional list`() = assertEquals(listOf(NULL_VALUE to "Retry"), found(edit(retryBlock to "    Retry:\n")))

    fun `test tilde in an optional number`() = assertEquals(
        listOf(NULL_VALUE to "~"),
        found(edit("    Next: Decide\n" to "    Next: Decide\n    TimeoutSeconds: ~\n")),
    )

    fun `test JSON null in an optional field`() = assertEquals(
        listOf(NULL_VALUE to "null"),
        found("""{"StartAt": "A", "States": {"A": {"Type": "Succeed", "Comment": null}}}""", "test.asl.json"),
    )

    fun `test null in a required field stays a wrong kind`() = assertEquals(
        listOf(WRONG_VALUE_KIND to "Resource"),
        found(edit("Resource: arn:aws:states:::lambda:invoke" to "Resource:")),
    )

    fun `test null as a state stays a wrong kind`() =
        assertEquals(listOf(WRONG_VALUE_KIND to "Accepted"), found(edit("  Accepted:\n    Type: Succeed" to "  Accepted:")))

    fun `test null in a nullable field is clean`() = assertEquals(
        emptyList<Any>(),
        found("StartAt: A\nStates:\n  A:\n    Type: Pass\n    ResultPath: null\n    End: true\n"),
    )

    // Findings that differ only in message (A4)

    fun `test root with neither StartAt nor States reports both`() = assertEquals(
        listOf(MISSING_REQUIRED_FIELD to "Comment", MISSING_REQUIRED_FIELD to "Comment"),
        found("Comment: nothing yet\n"),
    )

    fun `test catcher with neither ErrorEquals nor Next reports both`() = assertEquals(
        listOf(MISSING_REQUIRED_FIELD to "Comment", MISSING_REQUIRED_FIELD to "Comment"),
        found(edit("      - ErrorEquals: [States.ALL]\n        Next: Rejected" to "      - Comment: later")),
    )

    // Placeholder keys (A5)

    fun `test Task with a placeholder key and no Resource`() = assertEquals(
        emptyList<Any>(),
        found(edit("    Resource: arn:aws:states:::lambda:invoke\n" to "    ${'$'}{ResourceKey}: arn:aws:states:::lambda:invoke\n")),
    )

    fun `test state with a placeholder key and neither Next nor End`() =
        assertEquals(emptyList<Any>(), found(edit("    Next: Decide\n" to "    ${'$'}{NextKey}: Decide\n")))

    fun `test Choice rule with a placeholder key and no Next`() =
        assertEquals(emptyList<Any>(), found(edit("        Next: Accepted\n" to "        ${'$'}{NextKey}: Accepted\n")))

    fun `test JSONata Choice rule with a placeholder key and no Condition`() =
        assertEquals(emptyList<Any>(), found(edit("      - Condition: $condition\n" to "      - ${'$'}{ConditionKey}: $condition\n")))

    // Tags (A6)

    fun `test tagged quoted number`() = assertEquals(
        emptyList<Any>(),
        found(edit("    Next: Decide\n" to "    Next: Decide\n    TimeoutSeconds: !!int \"12\"\n")),
    )

    fun `test tagged block scalar`() =
        assertEquals(emptyList<Any>(), found(edit("MaxAttempts: 2" to "MaxAttempts: !!int |-\n          2")))

    // Query language being typed (C1)

    fun `test mistyped top-level query language gives only the enumeration warning`() =
        assertEquals(listOf(UNKNOWN_ENUM_VALUE to "JSONat"), found(edit("QueryLanguage: JSONata" to "QueryLanguage: JSONat")))

    fun `test mistyped state query language gives only the enumeration warning`() = assertEquals(
        listOf(UNKNOWN_ENUM_VALUE to "JSONPat"),
        found(edit("QueryLanguage: JSONata\n" to "", "    Type: Task\n" to "    Type: Task\n    QueryLanguage: JSONPat\n")).filter { it.first != WRONG_QUERY_LANGUAGE_FIELD || it.second != "Condition" },
    )

    // Misplaced delimiters (C2)

    fun `test delimiters that do not start the value in free-form data`() = assertEquals(
        listOf(MISPLACED_JSONATA_DELIMITER to "' {% ${'$'}x %}'"),
        found(edit("FunctionName: ${'$'}{InspectFn}" to "FunctionName: ' {% ${'$'}x %}'")),
    )

    fun `test delimiters that do not start a text field that evaluates JSONata`() = assertEquals(
        listOf(MISPLACED_JSONATA_DELIMITER to "'Bad {% ${'$'}x %}'"),
        found(edit("    Type: Fail" to "    Type: Fail\n    Error: 'Bad {% ${'$'}x %}'")),
    )

    fun `test misplaced delimiters with a placeholder`() =
        assertEquals(emptyList<Any>(), found(edit("FunctionName: ${'$'}{InspectFn}" to "FunctionName: 'x {% ${'$'}{Tail} %}'")))

    fun `test misplaced delimiters in a field that is never evaluated`() =
        assertEquals(emptyList<Any>(), found(withSucceedField("Comment: 'uses {% here %}'")))

    fun `test misplaced delimiters in a JSONPath state`() = assertEquals(
        emptyList<Any>(),
        found("StartAt: A\nStates:\n  A:\n    Type: Pass\n    Parameters:\n      x: ' {% 1 %}'\n    End: true\n"),
    )

    fun `test the unclosed message names the line break of a block scalar`() {
        val finding = AslChecker.findings(myFixture.configureByText("test.asl.yaml",
            edit("Condition: $condition" to "Condition: >\n          {% ${'$'}states.input.ok %}"))).single()
        val plain = AslChecker.findings(myFixture.configureByText("test.asl.yaml", edit(condition to "'{% ${'$'}states.input.ok'"))).single()
        assertEquals(UNCLOSED_JSONATA_DELIMITER, finding.problem)
        assertFalse("the block scalar case has its own message", finding.message == plain.message)
    }

    // Guards of errors (C15)

    fun `test placeholder in an unclosed delimiter of a field that evaluates JSONata`() = assertEquals(
        emptyList<Any>(),
        found(edit("    Next: Decide\n" to "    Next: Decide\n    TimeoutSeconds: '{% ${'$'}x ${'$'}{Tail}'\n")),
    )
}
