package io.github.saigyo.aslan.check

import io.github.saigyo.aslan.check.AslProblem.DISTRIBUTED_MAP_OUTER_READ
import io.github.saigyo.aslan.check.AslProblem.INVALID_VARIABLE_NAME
import io.github.saigyo.aslan.check.AslProblem.OUTER_VARIABLE_ASSIGNED
import io.github.saigyo.aslan.check.AslProblem.PATH_VARIABLE_NOT_ASSIGNED
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_JSONATA_FUNCTION
import io.github.saigyo.aslan.check.AslProblem.UNUSUAL_VARIABLE_NAME
import io.github.saigyo.aslan.check.AslProblem.VARIABLE_NEVER_READ
import io.github.saigyo.aslan.check.AslProblem.VARIABLE_NOT_ASSIGNED
import io.github.saigyo.aslan.fixtures.CleanCoverageTest

class VariableChecksTest : CheckTestCase() {
    private val variableProblems = setOf(PATH_VARIABLE_NOT_ASSIGNED, DISTRIBUTED_MAP_OUTER_READ, VARIABLE_NOT_ASSIGNED,
        VARIABLE_NEVER_READ, INVALID_VARIABLE_NAME, UNUSUAL_VARIABLE_NAME, OUTER_VARIABLE_ASSIGNED)

    /** `$` written as a JSON escape, built so that no tool decodes it on the way. */
    private val escapedDollar = "\\" + "u0024"

    /** The findings of the variable checks in [text], in which `§` stands for `$`, as problem and marked text. */
    private fun variables(text: String, name: String = "test.asl.yaml"): List<Pair<AslProblem, String>> =
        marked(text.replace("§", "$"), name).filter { it.first in variableProblems }

    /** A machine whose `States` are [states] (YAML, indented here), starting at the first; JSONata unless [top] says otherwise. */
    private fun machine(states: String, top: String = "QueryLanguage: JSONata"): String {
        val body = states.trimIndent().trim('\n')
        val first = body.lineSequence().first().trim().removeSuffix(":")
        val head = if (top.isBlank()) "" else top + "\n"
        return head + "StartAt: $first\nStates:\n" + body.prependIndent("  ") + "\n"
    }

    /** A JSONata machine whose first state `Load` assigns `reel`, followed by [states]. */
    private fun loading(states: String): String = machine(
        "Load:\n  Type: Pass\n  Assign:\n    reel: 1\n  Next: Show\n" + states.trimIndent(),
    )

    fun `test a name with a supplementary letter after it does not silence never-read`() {
        val deseret = String(Character.toChars(0x10400))
        val text = loading("Show:\n  Type: Pass\n  Output: '{% §reel$deseret %}'\n  End: true")
        assertTrue(variables(text).toString(), VARIABLE_NEVER_READ to "reel" in variables(text))
    }

    // PATH_VARIABLE_NOT_ASSIGNED

    fun `test the Result of a Pass state is no read`() {
        // AWS does not evaluate Result (r3-AC-pass-result-key, PASS_RESULT_IS_STATIC)
        val text = machine("Seed:\n  Type: Pass\n  Result:\n    query.$: §reel\n  End: true", top = "")
        assertEquals(emptyList<Any>(), variables(text))
        // nor a mention: an assignment read only there is never read
        val assigned = machine("Load:\n  Type: Pass\n  Assign:\n    reel: 1\n  Next: Seed\nSeed:\n  Type: Pass\n  Result:\n    query.$: §reel\n  End: true", top = "")
        assertEquals(listOf(VARIABLE_NEVER_READ to "reel"), variables(assigned, "assigned.asl.yaml"))
    }

    fun `test a JSONPath key in a state whose query language is not known is no finding`() {
        val text = machine(
            """
            Tag:
              Type: Pass
              QueryLanguage: §{Lang}
              Assign:
                reel.$: §.title
              Next: Show
            Show:
              Type: Pass
              QueryLanguage: JSONPath
              InputPath: §reel
              End: true
            """,
        )
        assertEquals(emptyList<Any>(), variables(text))
    }

    fun `test a JSONPath read of a name assigned nowhere is an error`() {
        val text = machine(
            """
            Show:
              Type: Pass
              Parameters:
                title.$: §reel
              OutputPath: §reel.title
              End: true
            """,
            top = "",
        )
        assertEquals(listOf(PATH_VARIABLE_NOT_ASSIGNED to "\$reel", PATH_VARIABLE_NOT_ASSIGNED to "\$reel"), variables(text))
    }

    fun `test a JSONPath read in a json file is marked on its name`() {
        val json = """{"StartAt": "Show", "States": {"Show": {"Type": "Pass", "InputPath": "${escapedDollar}reel", "End": true}}}"""
        assertTrue(json, escapedDollar + "reel" in json)
        assertEquals(listOf(PATH_VARIABLE_NOT_ASSIGNED to escapedDollar + "reel"), variables(json, "test.asl.json"))
    }

    fun `test a JSONPath read of a name assigned only in another branch is an error`() {
        val text = machine(
            """
            Split:
              Type: Parallel
              Branches:
                - StartAt: Tag
                  States:
                    Tag:
                      Type: Pass
                      Assign:
                        reel: 1
                      End: true
                - StartAt: Show
                  States:
                    Show:
                      Type: Pass
                      OutputPath: §reel
                      End: true
              End: true
            """,
            top = "",
        )
        assertEquals(listOf(VARIABLE_NEVER_READ to "reel", PATH_VARIABLE_NOT_ASSIGNED to "\$reel"), variables(text))
    }

    fun `test a branch that does not say every name it assigns cannot make a read in another branch open`() {
        val text = machine(
            """
            Split:
              Type: Parallel
              Branches:
                - StartAt: Extra
                  States:
                    Extra:
                      Type: Pass
                      Assign: §{Extra}
                      End: true
                - StartAt: Show
                  States:
                    Show:
                      Type: Pass
                      OutputPath: §reel
                      End: true
              End: true
            """,
            top = "",
        )
        assertEquals(listOf(PATH_VARIABLE_NOT_ASSIGNED to "\$reel"), variables(text))
    }

    fun `test a JSONPath read of a name assigned nowhere in a file with an open machine is no error`() {
        val text = machine(
            """
            Tag:
              Type: Pass
              Assign: §{Extra}
              Next: Show
            Show:
              Type: Pass
              OutputPath: §reel
              End: true
            """,
            top = "",
        )
        assertEquals(emptyList<Any>(), variables(text))
    }

    // VARIABLE_NOT_ASSIGNED

    fun `test a JSONata read of a name assigned nowhere is a warning`() {
        val text = machine("Show:\n  Type: Pass\n  Output: \"{% §reel + 1 %}\"\n  End: true")
        assertEquals(listOf(VARIABLE_NOT_ASSIGNED to "\$reel"), variables(text))
    }

    fun `test a JSONata read written with an escape is marked on its host text`() {
        val json = """{"QueryLanguage": "JSONata", "StartAt": "Show", "States": {"Show": {"Type": "Pass", "Output": "{% ${escapedDollar}reel %}", "End": true}}}"""
        assertTrue(json, escapedDollar + "reel" in json)
        assertEquals(listOf(VARIABLE_NOT_ASSIGNED to escapedDollar + "reel"), variables(json, "test.asl.json"))
    }

    fun `test a read of an assigned name is no finding`() {
        assertEquals(emptyList<Any>(), variables(loading("Show:\n  Type: Pass\n  Output: \"{% §reel %}\"\n  End: true")))
    }

    fun `test no unassigned read where an Assign is open`() {
        val text = machine(
            """
            Tag:
              Type: Pass
              Assign: §{Extra}
              Next: Show
            Show:
              Type: Pass
              Output: "{% §reel %}"
              End: true
            """,
        )
        assertEquals(emptyList<Any>(), variables(text))
        assertEquals(listOf(VARIABLE_NOT_ASSIGNED to "\$reel"), variables(text.replace("Assign: §{Extra}", "Assign: {}"), "closed.asl.yaml"))
    }

    fun `test no unassigned read in an expression that is not judged`() {
        val text = machine("Show:\n  Type: Pass\n  Output: \"{% §reel + §{Offset} %}\"\n  End: true")
        assertEquals(emptyList<Any>(), variables(text))
        assertEquals(listOf(VARIABLE_NOT_ASSIGNED to "\$reel"), variables(text.replace("§{Offset}", "1"), "judged.asl.yaml"))
    }

    fun `test a callee without an assignment is a function call`() {
        val text = machine("Show:\n  Type: Pass\n  Output: \"{% §frame(1) %}\"\n  End: true").replace("§", "$")
        assertEquals(listOf(UNKNOWN_JSONATA_FUNCTION to "\$frame"), marked(text))
    }

    fun `test no unassigned read in an expression with a syntax error`() {
        val text = machine("Show:\n  Type: Pass\n  Output: \"{% §reel + %}\"\n  End: true")
        assertEquals(emptyList<Any>(), variables(text))
        assertEquals(listOf(VARIABLE_NOT_ASSIGNED to "\$reel"), variables(text.replace("+ %}", "+ 1 %}"), "complete.asl.yaml"))
    }

    // the processor of a Map whose Mode is a placeholder (decision 4)

    fun `test only an outer assignment is reported in the processor of a Map whose Mode is a placeholder`() {
        val text = machine(
            """
            Load:
              Type: Pass
              Assign:
                reel: 1
                title: "{% §reel %}"
              Next: Each
            Each:
              Type: Map
              Items: "{% [1, 2] %}"
              ItemProcessor:
                ProcessorConfig:
                  Mode: §{Mode}
                StartAt: Inner
                States:
                  Inner:
                    Type: Pass
                    Assign:
                      reel: 2
                      frame: 3
                    Output: "{% §title %}"
                    End: true
              Next: Done
            Done:
              Type: Pass
              Output: "{% §title %}"
              End: true
            """,
        )
        // AWS rejects an inner assignment of an outer name in every kind of Map, so the unknown Mode does not matter there
        assertEquals(listOf(OUTER_VARIABLE_ASSIGNED to "reel"), variables(text))
        assertEquals(
            listOf(OUTER_VARIABLE_ASSIGNED to "reel", VARIABLE_NEVER_READ to "frame"),
            variables(text.replace("Mode: §{Mode}", "Mode: INLINE"), "inline.asl.yaml"),
        )
        assertEquals(
            listOf(OUTER_VARIABLE_ASSIGNED to "reel", VARIABLE_NEVER_READ to "reel", VARIABLE_NEVER_READ to "frame", DISTRIBUTED_MAP_OUTER_READ to "\$title"),
            variables(text.replace("Mode: §{Mode}", "Mode: DISTRIBUTED"), "distributed.asl.yaml"),
        )
    }

    // VARIABLE_NEVER_READ

    fun `test an assignment that is never read is a weak warning`() {
        assertEquals(listOf(VARIABLE_NEVER_READ to "reel"), variables(loading("Show:\n  Type: Pass\n  End: true")))
    }

    fun `test an assignment read through a JSONPath string is read`() {
        val text = machine(
            """
            Tag:
              Type: Pass
              Assign:
                reel.$: §.reel
              Next: Show
            Show:
              Type: Pass
              OutputPath: §reel
              End: true
            """,
            top = "",
        )
        assertEquals(emptyList<Any>(), variables(text))
    }

    fun `test never read is silent where a file does not say every name`() {
        val text = loading(
            """
            Show:
              Type: Pass
              Assign: §{Extra}
              End: true
            """,
        )
        assertEquals(emptyList<Any>(), variables(text))
        assertEquals(listOf(VARIABLE_NEVER_READ to "reel"), variables(text.replace("Assign: §{Extra}", "Assign: {}"), "closed.asl.yaml"))
    }

    fun `test never read is silent where an expression with a syntax error mentions the name`() {
        val text = loading("Show:\n  Type: Pass\n  Output: \"{% §reel + %}\"\n  End: true")
        assertEquals(emptyList<Any>(), variables(text))
        assertEquals(listOf(VARIABLE_NEVER_READ to "reel"), variables(text.replace("§reel + %}", "1 + %}"), "unmentioned.asl.yaml"))
    }

    fun `test never read is silent where a use of the name is uncertain`() {
        val outputs = listOf(
            // a use in a function that may run before the local it refers to is assigned
            "\"{% (§f := function(){ §reel }; §f(); §reel := 0) %}\"",
            // a use of a local whose first assignment may not run
            "\"{% (true ? 0 : §reel := 2; §reel) %}\"",
        )
        for (output in outputs) {
            assertEquals(output, emptyList<Any>(), variables(loading("Show:\n  Type: Pass\n  Output: $output\n  End: true")))
        }
    }

    fun `test never read is silent where the model drops a mention`() {
        val cases = listOf(
            // an expression whose delimiters come from an escape, so no JSONata is injected into it
            "Pass" to "\"\\x7b% §reel %}\"",
            // a state whose type is being typed, so its strings are no sites
            "Pas" to "\"{% §reel %}\"",
        )
        for ((type, output) in cases) {
            assertEquals(output, emptyList<Any>(), variables(loading("Show:\n  Type: $type\n  Output: $output\n  End: true")))
        }
    }

    fun `test never read is silent where a placeholder may stand for an expression`() {
        // JSONata: the whole value of a field that takes an expression
        val jsonata = loading("Show:\n  Type: Pass\n  Output: §{OutputExpr}\n  End: true")
        assertEquals(emptyList<Any>(), variables(jsonata))
        // control: a placeholder inside free-form data is no expression of its own
        assertEquals(listOf(VARIABLE_NEVER_READ to "reel"), variables(jsonata.replace("Output: §{OutputExpr}", "Output:\n      title: §{Title}"), "nested.asl.yaml"))
        // JSONPath: the whole value of a path field, or of a key that ends in `.$`
        val path = machine("Load:\n  Type: Pass\n  Assign:\n    reel: 1\n  Next: Show\nShow:\n  Type: Pass\n  OutputPath: §{Path}\n  End: true", top = "")
        assertEquals(emptyList<Any>(), variables(path, "path.asl.yaml"))
        val dollarKey = path.replace("OutputPath: §{Path}", "Parameters:\n      title.$: §{Path}")
        assertEquals(emptyList<Any>(), variables(dollarKey, "key.asl.yaml"))
        assertEquals(listOf(VARIABLE_NEVER_READ to "reel"), variables(path.replace("OutputPath: §{Path}", "OutputPath: §.title"), "plain.asl.yaml"))
    }

    fun `test a Comment is no mention`() {
        val text = loading("Show:\n  Type: Pass\n  Comment: Shows §reel\n  End: true")
        assertEquals(listOf(VARIABLE_NEVER_READ to "reel"), variables(text))
    }

    // INVALID_VARIABLE_NAME and UNUSUAL_VARIABLE_NAME

    fun `test a name that AWS rejects in a JSONata state is an error`() {
        val text = machine(
            """
            Tag:
              Type: Pass
              Assign:
                re-el: 1
                1reel: 2
                states: 3
                _reel: 4
                re.el: 5
                re el: 6
                ${"r".repeat(81)}: 7
                ${"r".repeat(80)}: 8
                reel_nº: 9
                reel: 10
              Output: "{% §reel & §${"r".repeat(80)} & §reel_nº %}"
              End: true
            """,
        )
        assertEquals(
            listOf("re-el", "1reel", "states", "_reel", "re.el", "re el", "r".repeat(81)).map { INVALID_VARIABLE_NAME to it },
            variables(text),
        )
    }

    fun `test another name that is no plain variable name is a warning`() {
        val combining = String(Character.toChars(0x301))
        val jsonata = machine("Tag:\n  Type: Pass\n  Assign:\n    re${combining}el: 1\n  End: true")
        assertEquals(listOf(UNUSUAL_VARIABLE_NAME to "re${combining}el"), variables(jsonata))
        // in a JSONPath state too
        val jsonPath = machine("Tag:\n  Type: Pass\n  Assign:\n    re${combining}el: 1\n  End: true", top = "")
        assertEquals(listOf(UNUSUAL_VARIABLE_NAME to "re${combining}el"), variables(jsonPath, "path.asl.yaml"))
    }

    fun `test a name that AWS rejects is an error whatever the query language`() {
        val jsonPath = machine("Tag:\n  Type: Pass\n  Assign:\n    1reel: 1\n    re-el: 2\n    _reel: 3\n    states: 4\n  End: true", top = "")
        assertEquals(listOf("1reel", "re-el", "_reel", "states").map { INVALID_VARIABLE_NAME to it }, variables(jsonPath, "path.asl.yaml"))
        // a state whose QueryLanguage is not known
        val unknown = machine("Tag:\n  Type: Pass\n  QueryLanguage: §{Language}\n  Assign:\n    1reel: 1\n  End: true", top = "")
        assertEquals(listOf(INVALID_VARIABLE_NAME to "1reel"), variables(unknown, "unknown.asl.yaml"))
    }

    fun `test a name is judged where the machine does not say every name`() {
        // the unread `reel` shows that the machine is open
        val text = machine("Tag:\n  Type: Pass\n  Assign:\n    re-el: 1\n    reel: 2\n    <<: §{Extra}\n  End: true")
        assertEquals(listOf(INVALID_VARIABLE_NAME to "re-el"), variables(text))
        assertEquals(listOf(INVALID_VARIABLE_NAME to "re-el", VARIABLE_NEVER_READ to "reel"), variables(text.replace(Regex(" *<<: §\\{Extra}\n"), ""), "closed.asl.yaml"))
    }

    // OUTER_VARIABLE_ASSIGNED

    fun `test an inner machine that assigns an outer variable is an error`() {
        val text = machine(
            """
            Load:
              Type: Pass
              Assign:
                reel: 1
              Next: Split
            Split:
              Type: Parallel
              Branches:
                - StartAt: Tag
                  States:
                    Tag:
                      Type: Pass
                      Assign:
                        reel: 2
                      End: true
              Next: Show
            Show:
              Type: Pass
              Output: "{% §reel %}"
              End: true
            """,
        )
        assertEquals(listOf(OUTER_VARIABLE_ASSIGNED to "reel"), variables(text))
        // a known outer assignment is enough, whatever the file leaves unsaid
        val open = Regex("( *)reel: 2\n").replace(text) { it.value + it.groupValues[1] + "<<: §{Extra}\n" }
        assertTrue(open, "<<: §{Extra}" in open)
        assertEquals(listOf(OUTER_VARIABLE_ASSIGNED to "reel"), variables(open, "open.asl.yaml"))
    }

    // OUTER_VARIABLE_ASSIGNED follows the flow: an outer assignment counts where it applies before the flow enters the
    // state that holds the nested machine (rounds 12 and 13)

    /** `Split`, a Parallel state whose branch assigns `reel`, with [tail] (YAML at the state's indentation) as its further lines. */
    private fun split(tail: String) =
        "Split:\n  Type: Parallel\n  Branches:\n    - StartAt: Tag\n      States:\n        Tag:\n          Type: Pass\n" +
            "          Assign:\n            reel: 2\n          End: true\n" + tail.trimIndent().prependIndent("  ") + "\n"

    private val done = "Done:\n  Type: Pass\n  End: true\n"

    /** The outer assignments reported in a JSONata machine whose `States` are [states]. */
    private fun outer(states: String, name: String = "test.asl.yaml") =
        variables(machine(states), name).filter { it.first == OUTER_VARIABLE_ASSIGNED }

    private val reelOne = "  Assign:\n    reel: 1\n"

    fun `test an outer assignment before the nested machine is an error`() =
        assertEquals(listOf(OUTER_VARIABLE_ASSIGNED to "reel"), outer("Load:\n  Type: Pass\n" + reelOne + "  Next: Split\n" + split("End: true")))

    fun `test an outer assignment after the nested machine is none`() =
        assertEquals(emptyList<Any>(), outer(split("Next: Load") + "Load:\n  Type: Pass\n" + reelOne + "  End: true\n"))

    fun `test the own Assign of the state that holds the nested machine is none`() =
        assertEquals(emptyList<Any>(), outer(split("Assign:\n  reel: 1\nEnd: true")))

    fun `test a Catcher of the state that holds the nested machine is none`() =
        assertEquals(emptyList<Any>(), outer(split("Catch:\n  - ErrorEquals: [States.ALL]\n    Assign:\n      reel: 1\n    Next: Done\nEnd: true") + done))

    private fun fetch(catcher: String, own: String = "") =
        "Fetch:\n  Type: Task\n  Resource: arn:aws:states:::lambda:invoke\n  Arguments:\n    FunctionName: catalog\n" + own +
            "  Catch:\n    - ErrorEquals: [States.ALL]\n" + catcher + "      Next: Split\n  Next: Done\n"

    fun `test a state Assign whose only path to the nested machine is its Catcher is none`() =
        assertEquals(emptyList<Any>(), outer(fetch(catcher = "", own = reelOne) + split("End: true") + done))

    fun `test a Catcher Assign whose Next reaches the nested machine is an error`() =
        assertEquals(listOf(OUTER_VARIABLE_ASSIGNED to "reel"), outer(fetch(catcher = "      Assign:\n        reel: 1\n") + split("End: true") + done))

    private fun route(assignedRuleNext: String, default: String) =
        "Route:\n  Type: Choice\n  Choices:\n    - Condition: '{% true %}'\n      Assign:\n        reel: 1\n      Next: $assignedRuleNext\n" +
            "  Default: $default\n"

    fun `test a Choice rule Assign whose Next reaches the nested machine is an error`() =
        assertEquals(listOf(OUTER_VARIABLE_ASSIGNED to "reel"), outer(route("Split", "Done") + split("End: true") + done))

    fun `test a Choice rule Assign on another rule's path is none`() =
        assertEquals(emptyList<Any>(), outer(route("Done", "Split") + split("End: true") + done))

    fun `test an outer assignment that a loop leads back from is an error`() {
        val again = "Set:\n  Type: Pass\n" + reelOne + "  Next: Again\n" +
            "Again:\n  Type: Choice\n  Choices:\n    - Condition: '{% true %}'\n      Next: Split\n  Default: Done\n"
        assertEquals(listOf(OUTER_VARIABLE_ASSIGNED to "reel"), outer(split("Next: Set") + again + done))
        // control: without the way back, the assignment comes after
        assertEquals(emptyList<Any>(), outer(split("Next: Set") + again.replace("Next: Split", "Next: Done"), "control.asl.yaml"))
    }

    fun `test two levels of nesting report the innermost assignment`() {
        val states = "Load:\n  Type: Pass\n" + reelOne + "  Next: Outer\n" +
            "Outer:\n  Type: Parallel\n  Branches:\n    - StartAt: Mid\n      States:\n" + split("End: true").trimEnd('\n').replace("Split:", "Mid:").prependIndent("        ") + "\n  End: true\n"
        assertEquals(listOf(OUTER_VARIABLE_ASSIGNED to "reel"), outer(states))
    }

    fun `test an outer machine whose flow is not known reports nothing`() {
        val states = "Load:\n  Type: Pass\n" + reelOne + "  Next: §{After}\n" + split("End: true")
        assertEquals(emptyList<Any>(), outer(states))
        assertEquals(listOf(OUTER_VARIABLE_ASSIGNED to "reel"), outer(states.replace("§{After}", "Split"), "known.asl.yaml"))
    }

    /** A Choice state whose own `Assign` assigns `reel`, whose rule leads to [rule] and whose `Default` to [default]. */
    private fun choiceOwnAssign(rule: String, default: String) =
        "Route:\n  Type: Choice\n  Choices:\n    - Condition: '{% true %}'\n      Next: $rule\n  Default: $default\n" + reelOne

    fun `test the own Assign of a Choice state goes along its Default only`() {
        // AWS documents that a Choice state's own Assign applies only when no rule matches
        assertEquals(emptyList<Any>(), outer(choiceOwnAssign("Split", "Done") + split("End: true") + done))
        assertEquals(listOf(OUTER_VARIABLE_ASSIGNED to "reel"), outer(choiceOwnAssign("Done", "Split") + split("End: true") + done, "default.asl.yaml"))
    }

    fun `test two branches that assign the same name are no outer assignment`() {
        val text = machine(
            """
            Split:
              Type: Parallel
              Branches:
                - StartAt: Left
                  States:
                    Left:
                      Type: Pass
                      Assign:
                        reel: 1
                      Output: "{% §reel %}"
                      End: true
                - StartAt: Right
                  States:
                    Right:
                      Type: Pass
                      Assign:
                        reel: 2
                      Output: "{% §reel %}"
                      End: true
              End: true
            """,
        )
        assertEquals(emptyList<Any>(), variables(text))
    }

    // DISTRIBUTED_MAP_OUTER_READ

    /** A Distributed Map `Each` whose processor reads `$reel`, with [before] ahead of it and [tail] as its further lines. */
    private fun distributed(before: String, tail: String, after: String = "") = machine(
        before + "Each:\n  Type: Map\n  Items: \"{% [1, 2] %}\"\n  ItemProcessor:\n    ProcessorConfig:\n      Mode: DISTRIBUTED\n" +
            "      ExecutionType: STANDARD\n    StartAt: Show\n    States:\n      Show:\n        Type: Pass\n" +
            "        Output: \"{% §reel %}\"\n        End: true\n" + tail.trimIndent().prependIndent("  ") + "\n" + after,
    )

    fun `test an outer read inside a Distributed Map follows the flow`() {
        // before the Map: the error
        assertEquals(
            listOf(VARIABLE_NEVER_READ to "reel", DISTRIBUTED_MAP_OUTER_READ to "\$reel"),
            variables(distributed("Load:\n  Type: Pass\n" + reelOne + "  Next: Each\n", "End: true")),
        )
        // only after the Map, or only in its own Assign: the warning
        assertEquals(
            listOf(VARIABLE_NOT_ASSIGNED to "\$reel", VARIABLE_NEVER_READ to "reel"),
            variables(distributed("", "Next: Load", "Load:\n  Type: Pass\n" + reelOne + "  End: true\n"), "after.asl.yaml"),
        )
        assertEquals(
            listOf(VARIABLE_NOT_ASSIGNED to "\$reel", VARIABLE_NEVER_READ to "reel"),
            variables(distributed("", "Assign:\n  reel: 1\nEnd: true"), "own.asl.yaml"),
        )
    }

    fun `test a JSONata read of an outer name inside a Distributed Map is an error`() {
        val text = machine(
            """
            Load:
              Type: Pass
              Assign:
                reel: 1
              Next: Each
            Each:
              Type: Map
              Items: "{% [1, 2] %}"
              ItemProcessor:
                ProcessorConfig:
                  Mode: DISTRIBUTED
                  ExecutionType: STANDARD
                StartAt: Show
                States:
                  Show:
                    Type: Pass
                    Output: "{% §reel & §frame %}"
                    End: true
              End: true
            """,
        )
        assertEquals(listOf(VARIABLE_NEVER_READ to "reel", DISTRIBUTED_MAP_OUTER_READ to "\$reel", VARIABLE_NOT_ASSIGNED to "\$frame"), variables(text))
        // inline, the processor reads the outer variable
        assertEquals(listOf(VARIABLE_NOT_ASSIGNED to "\$frame"), variables(text.replace("Mode: DISTRIBUTED", "Mode: INLINE"), "inline.asl.yaml"))
        // the exceptions of an unassigned read hold for it too; the outer variable stays unread
        assertEquals(emptyList<Any>(), variables(text.replace("Mode: DISTRIBUTED", "Mode: §{Mode}"), "mode.asl.yaml"))
        assertEquals(listOf(VARIABLE_NEVER_READ to "reel"), variables(text.replace("§reel & §frame", "§reel & §frame + §{Offset}"), "unjudged.asl.yaml"))
        assertEquals(
            listOf(VARIABLE_NEVER_READ to "reel", VARIABLE_NOT_ASSIGNED to "\$frame"),
            variables(text.replace("§reel & §frame", "§reel() & §frame"), "callee.asl.yaml"),
        )
    }

    /** A machine that assigns `reel`, with a Distributed Map whose processor assigns `frame` and holds a Map of [innerMode] that reads both. */
    private fun nestedMaps(innerMode: String) = machine(
        """
        Load:
          Type: Pass
          Assign:
            reel: 1
          Next: Outer
        Outer:
          Type: Map
          Items: "{% [1, 2] %}"
          ItemProcessor:
            ProcessorConfig:
              Mode: DISTRIBUTED
              ExecutionType: STANDARD
            StartAt: Tag
            States:
              Tag:
                Type: Pass
                Assign:
                  frame: 2
                Next: Inner
              Inner:
                Type: Map
                Items: "{% [1, 2] %}"
                ItemProcessor:
                  ProcessorConfig:
                    Mode: $innerMode
                    ExecutionType: STANDARD
                  StartAt: Show
                  States:
                    Show:
                      Type: Pass
                      Output: "{% §reel & §frame %}"
                      End: true
                End: true
          End: true
        """,
    )

    fun `test a read inside a Distributed Map inside a Distributed Map`() {
        // the inner processor reads neither the top-level variable nor the one of the outer processor
        assertEquals(
            listOf(VARIABLE_NEVER_READ to "reel", VARIABLE_NEVER_READ to "frame", DISTRIBUTED_MAP_OUTER_READ to "\$reel", DISTRIBUTED_MAP_OUTER_READ to "\$frame"),
            variables(nestedMaps("DISTRIBUTED")),
        )
    }

    fun `test a read inside an inline Map inside a Distributed Map`() {
        // the inline processor reads the variable of the Distributed Map's processor, not the top-level one
        assertEquals(
            listOf(VARIABLE_NEVER_READ to "reel", DISTRIBUTED_MAP_OUTER_READ to "\$reel"),
            variables(nestedMaps("INLINE"), "inline.asl.yaml"),
        )
    }

    fun `test an assignment of an outer name inside a Distributed Map is an error too`() {
        val text = machine(
            """
            Load:
              Type: Pass
              Assign:
                reel: 1
              Next: Each
            Each:
              Type: Map
              Items: "{% [1, 2] %}"
              ItemProcessor:
                ProcessorConfig:
                  Mode: DISTRIBUTED
                  ExecutionType: STANDARD
                StartAt: Tag
                States:
                  Tag:
                    Type: Pass
                    Assign:
                      reel: 2
                    Output: "{% §reel %}"
                    End: true
              Next: Show
            Show:
              Type: Pass
              Output: "{% §reel %}"
              End: true
            """,
        )
        // each machine reads its own variable, but AWS rejects the inner assignment of an outer name
        assertEquals(listOf(OUTER_VARIABLE_ASSIGNED to "reel"), variables(text))
    }

    // clean definitions

    fun `test the clean definitions show no variable finding`() {
        for (name in CleanCoverageTest.cleanDefinitions()) {
            val file = myFixture.configureByFile("clean/$name")
            val found = AslChecker.findings(file).filter { it.problem in variableProblems }.map { "${it.problem} at ${it.element.text}" }
            assertEquals(name, emptyList<String>(), found)
        }
    }
}
