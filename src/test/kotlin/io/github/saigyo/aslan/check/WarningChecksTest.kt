package io.github.saigyo.aslan.check

import io.github.saigyo.aslan.check.AslProblem.CHOICE_WITHOUT_DEFAULT
import io.github.saigyo.aslan.check.AslProblem.EMPTY_LIST
import io.github.saigyo.aslan.check.AslProblem.INAPPLICABLE_FIELD
import io.github.saigyo.aslan.check.AslProblem.INCOMPLETE_CONFIGURATION
import io.github.saigyo.aslan.check.AslProblem.INVALID_MAP_LABEL
import io.github.saigyo.aslan.check.AslProblem.INVALID_PATH
import io.github.saigyo.aslan.check.AslProblem.INVALID_TIMESTAMP
import io.github.saigyo.aslan.check.AslProblem.MALFORMED_CHOICE_RULE
import io.github.saigyo.aslan.check.AslProblem.EXCLUSIVE_FIELDS
import io.github.saigyo.aslan.check.AslProblem.HEARTBEAT_NOT_BELOW_TIMEOUT
import io.github.saigyo.aslan.check.AslProblem.JSONATA_IN_STATIC_FIELD
import io.github.saigyo.aslan.check.AslProblem.MISPLACED_FIELD
import io.github.saigyo.aslan.check.AslProblem.MISSING_TRANSITION
import io.github.saigyo.aslan.check.AslProblem.MISSING_TRANSITION_TARGET
import io.github.saigyo.aslan.check.AslProblem.STATES_ALL_PLACEMENT
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_ENUM_VALUE
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_FIELD
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_PREDEFINED_ERROR
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_STATE_TYPE
import io.github.saigyo.aslan.check.AslProblem.UNREACHABLE_STATE
import io.github.saigyo.aslan.check.AslProblem.VALUE_OUT_OF_RANGE

class WarningChecksTest : CheckTestCase() {
    private val dollar = '$'
    private val inspectNext = "    Next: Decide\n"
    private val retrier = "      - ErrorEquals: [States.ALL]\n        MaxAttempts: 2\n"

    fun `test unknown field`() =
        assertEquals(listOf(UNKNOWN_FIELD to "Retries"), found(edit(inspectNext to "$inspectNext    Retries: 3\n")))

    fun `test field on the wrong state type`() =
        assertEquals(listOf(MISPLACED_FIELD to "Next"), found(edit("    Type: Succeed\n" to "    Type: Succeed\n    Next: Rejected\n")))

    fun `test placeholder key in a structure`() =
        assertEquals(emptyList<Any>(), found(edit(inspectNext to "$inspectNext    ${dollar}{Extra}: 1\n")))

    fun `test negative MaxAttempts`() =
        assertEquals(listOf(VALUE_OUT_OF_RANGE to "-1"), found(edit("MaxAttempts: 2" to "MaxAttempts: -1")))

    fun `test BackoffRate below one`() =
        assertEquals(listOf(VALUE_OUT_OF_RANGE to "0.5"), found(edit(retrier to "$retrier        BackoffRate: 0.5\n")))

    fun `test unknown JitterStrategy`() =
        assertEquals(listOf(UNKNOWN_ENUM_VALUE to "SOME"), found(edit(retrier to "$retrier        JitterStrategy: SOME\n")))

    fun `test two time fields on Wait`() {
        val text = edit("  Accepted:\n    Type: Succeed\n" to
            "  Accepted:\n    Type: Wait\n    Seconds: 5\n    Timestamp: '2030-01-01T00:00:00Z'\n    End: true\n")
        assertEquals(listOf(EXCLUSIVE_FIELDS to "Timestamp"), found(text))
    }

    fun `test Next and End`() =
        assertEquals(listOf(EXCLUSIVE_FIELDS to "End"), found(edit(inspectNext to "$inspectNext    End: true\n")))

    fun `test JSONata in a field that does not evaluate it`() =
        assertEquals(listOf(JSONATA_IN_STATIC_FIELD to "'{% 2 %}'"), found(edit("MaxAttempts: 2" to "MaxAttempts: '{% 2 %}'")))

    fun `test States ALL with another name`() =
        assertEquals(listOf(STATES_ALL_PLACEMENT to "States.ALL"),
            found(edit(retrier to retrier.replace("[States.ALL]", "[States.ALL, States.Timeout]"))))

    fun `test States ALL retrier not last`() =
        assertEquals(listOf(STATES_ALL_PLACEMENT to "States.ALL"), found(edit(retrier to "$retrier      - ErrorEquals: [States.Timeout]\n")))

    fun `test heartbeat not below timeout`() =
        assertEquals(listOf(HEARTBEAT_NOT_BELOW_TIMEOUT to "30"),
            found(edit(inspectNext to "$inspectNext    TimeoutSeconds: 30\n    HeartbeatSeconds: 30\n")))

    fun `test unreachable state`() =
        assertEquals(listOf(UNREACHABLE_STATE to "Orphan"), found(BASE + "  Orphan:\n    Type: Succeed\n"))

    fun `test no reachability verdict with a placeholder target`() =
        assertEquals(emptyList<Any>(), found(edit("Next: Decide" to "Next: ${dollar}{NextState}")))

    fun `test no reachability verdict with a missing target`() =
        assertEquals(listOf(MISSING_TRANSITION_TARGET to "Decid"), found(edit("Next: Decide" to "Next: Decid")))

    fun `test no reachability verdict with a missing transition`() =
        assertEquals(listOf(MISSING_TRANSITION to "Inspect"), found(edit(inspectNext to "")))

    fun `test no unknown-field warnings while the type is unknown`() =
        assertEquals(listOf(UNKNOWN_STATE_TYPE to "Tsak"), found(edit("Type: Task" to "Type: Tsak")))

    fun `test Choice without Default`() =
        assertEquals(listOf(CHOICE_WITHOUT_DEFAULT to "Decide"), found(edit("    Default: Rejected\n" to "")))

    // Rules found by going through the specification and the developer guide

    /** A JSONPath machine with a Distributed Map, clean like BASE. */
    private val paths = """
        StartAt: Check
        States:
          Check:
            Type: Choice
            Choices:
              - Variable: ${'$'}.size
                NumericGreaterThan: 10
                Next: Split
            Default: Hold
          Hold:
            Type: Wait
            Timestamp: '2030-01-01T00:00:00Z'
            Next: Split
          Split:
            Type: Map
            ItemProcessor:
              ProcessorConfig:
                Mode: DISTRIBUTED
                ExecutionType: STANDARD
              StartAt: Scan
              States:
                Scan:
                  Type: Pass
                  End: true
            ItemReader:
              Resource: arn:aws:states:::s3:getObject
              ReaderConfig:
                InputType: CSV
                CSVHeaderLocation: FIRST_ROW
            ItemBatcher:
              MaxItemsPerBatch: 10
            ResultWriter:
              Resource: arn:aws:states:::s3:putObject
            Label: Reels
            ItemsPath: ${'$'}.reels
            ResultPath: ${'$'}.scan
            End: true
    """.trimIndent() + "\n"

    private fun paths(vararg replacements: Pair<String, String>): String =
        replacements.fold(paths) { text, (from, to) ->
            assertTrue("not in the JSONPath machine: $from", text.contains(from))
            text.replace(from, to)
        }

    fun `test the JSONPath machine is clean`() = assertEquals(emptyList<Any>(), found(paths))

    fun `test empty ErrorEquals`() =
        assertEquals(listOf(EMPTY_LIST to "ErrorEquals"), found(edit(retrier to retrier.replace("[States.ALL]", "[]"))))

    fun `test mistyped predefined error name`() =
        assertEquals(listOf(UNKNOWN_PREDEFINED_ERROR to "States.Timout"), found(edit(retrier to retrier.replace("States.ALL", "States.Timout"))))

    fun `test rule with a Variable but no operator`() =
        assertEquals(listOf(MALFORMED_CHOICE_RULE to "Variable"), found(paths("        NumericGreaterThan: 10\n" to "")))

    fun `test rule with two operators`() =
        assertEquals(listOf(MALFORMED_CHOICE_RULE to "Variable"),
            found(paths("        NumericGreaterThan: 10\n" to "        NumericGreaterThan: 10\n        NumericLessThan: 99\n")))

    fun `test Next in a nested rule`() {
        val text = paths("      - Variable: ${dollar}.size\n        NumericGreaterThan: 10\n" to
            "      - Not:\n          Variable: ${dollar}.size\n          NumericGreaterThan: 10\n          Next: Hold\n")
        assertEquals(listOf(MISPLACED_FIELD to "Next"), found(text))
    }

    fun `test path without dollar`() = assertEquals(listOf(INVALID_PATH to "reels"), found(paths("ItemsPath: ${dollar}.reels" to "ItemsPath: reels")))

    fun `test ResultPath to a variable`() =
        assertEquals(listOf(INVALID_PATH to "${dollar}scan"), found(paths("ResultPath: ${dollar}.scan" to "ResultPath: ${dollar}scan")))

    fun `test timestamp without T and Z`() =
        assertEquals(listOf(INVALID_TIMESTAMP to "'2030-01-01 00:00:00'"),
            found(paths("'2030-01-01T00:00:00Z'" to "'2030-01-01 00:00:00'")))

    fun `test timestamp with an offset and fractional seconds`() =
        assertEquals(emptyList<Any>(), found(paths("'2030-01-01T00:00:00Z'" to "'2030-01-01T00:00:00.250+02:00'")))

    fun `test label with a space`() = assertEquals(listOf(INVALID_MAP_LABEL to "Reel batches"), found(paths("Label: Reels" to "Label: Reel batches")))

    fun `test label too long`() {
        val long = "R".repeat(41)
        assertEquals(listOf(INVALID_MAP_LABEL to long), found(paths("Label: Reels" to "Label: $long")))
    }

    fun `test label used twice`() {
        val text = paths("          Type: Pass\n          End: true\n" to """
            |          Type: Map
            |          Label: Reels
            |          ItemProcessor:
            |            StartAt: Frame
            |            States:
            |              Frame:
            |                Type: Succeed
            |          End: true
            |""".trimMargin())
        val finding = AslChecker.findings(myFixture.configureByText("test.asl.yaml", text)).single()
        assertEquals(AslProblem.DUPLICATE_MAP_LABEL, finding.problem)
        assertEquals("the later use is reported", text.lastIndexOf("Label: Reels") + "Label: ".length, finding.element.textRange.startOffset)
    }

    fun `test ItemBatcher without a limit`() =
        assertEquals(listOf(INCOMPLETE_CONFIGURATION to "ItemBatcher"), found(paths("MaxItemsPerBatch: 10" to "BatchInput: {}")))

    fun `test ResultWriter with neither Resource nor WriterConfig`() =
        assertEquals(listOf(INCOMPLETE_CONFIGURATION to "ResultWriter"),
            found(paths("    ResultWriter:\n      Resource: arn:aws:states:::s3:putObject\n" to "    ResultWriter: {}\n")))

    fun `test Distributed mode without ExecutionType`() =
        assertEquals(listOf(INCOMPLETE_CONFIGURATION to "ProcessorConfig"), found(paths("        ExecutionType: STANDARD\n" to "")))

    fun `test given CSV headers missing`() =
        assertEquals(listOf(INCOMPLETE_CONFIGURATION to "ReaderConfig"), found(paths("FIRST_ROW" to "GIVEN")))

    fun `test CSV header location for a JSON file`() =
        assertEquals(listOf(INAPPLICABLE_FIELD to "CSVHeaderLocation"), found(paths("InputType: CSV" to "InputType: JSON")))

    fun `test half-typed files`() {
        found("StartAt: A\nStates:\n  A:\n    Type: Map\n    ItemBatcher:\n    Label:\n    ItemProcessor:\n      ProcessorConfig:\n        Mode: DISTRIBUTED")
        found("StartAt: A\nStates:\n  A:\n    Type: Choice\n    Choices:\n      - Not:\n        - Next")
        found("StartAt: A\nStates:\n  A:\n    Type: Task\n    Retry:\n      - ErrorEquals: [States.\n    HeartbeatSeconds: 5\n    TimeoutSeconds:")
    }

    fun `test a placeholder label is not compared`() {
        val text = paths("Label: Reels" to "Label: ${dollar}{Label}", "          Type: Pass\n          End: true\n" to """
            |          Type: Map
            |          Label: ${dollar}{Label}
            |          ItemProcessor:
            |            StartAt: Frame
            |            States:
            |              Frame:
            |                Type: Succeed
            |          End: true
            |""".trimMargin())
        assertEquals(emptyList<Any>(), found(text))
    }

    fun `test Choice state with a placeholder key and no Default`() =
        assertEquals(emptyList<Any>(), found(edit("    Default: Rejected\n" to "    ${dollar}{DefaultKey}: Rejected\n")))
}
