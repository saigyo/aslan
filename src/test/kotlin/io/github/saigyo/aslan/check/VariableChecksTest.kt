package io.github.saigyo.aslan.check

import io.github.saigyo.aslan.check.AslProblem.INVALID_VARIABLE_NAME
import io.github.saigyo.aslan.check.AslProblem.OUTER_VARIABLE_ASSIGNED
import io.github.saigyo.aslan.check.AslProblem.PATH_VARIABLE_NOT_ASSIGNED
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_JSONATA_FUNCTION
import io.github.saigyo.aslan.check.AslProblem.VARIABLE_NEVER_READ
import io.github.saigyo.aslan.check.AslProblem.VARIABLE_NOT_ASSIGNED
import io.github.saigyo.aslan.fixtures.CleanCoverageTest

class VariableChecksTest : CheckTestCase() {
    private val variableProblems = setOf(PATH_VARIABLE_NOT_ASSIGNED, VARIABLE_NOT_ASSIGNED, VARIABLE_NEVER_READ, INVALID_VARIABLE_NAME, OUTER_VARIABLE_ASSIGNED)

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

    // PATH_VARIABLE_NOT_ASSIGNED

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

    fun `test a JSONPath read of a name assigned only in another branch is a warning`() {
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
        assertEquals(listOf(VARIABLE_NEVER_READ to "reel", VARIABLE_NOT_ASSIGNED to "\$reel"), variables(text))
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

    fun `test no variable finding in the processor of a Map whose Mode is a placeholder`() {
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
        assertEquals(emptyList<Any>(), variables(text))
        assertEquals(
            listOf(OUTER_VARIABLE_ASSIGNED to "reel", VARIABLE_NEVER_READ to "frame"),
            variables(text.replace("Mode: §{Mode}", "Mode: INLINE"), "inline.asl.yaml"),
        )
        assertEquals(
            listOf(VARIABLE_NEVER_READ to "reel", VARIABLE_NEVER_READ to "frame", VARIABLE_NOT_ASSIGNED to "\$title"),
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

    fun `test a Comment is no mention`() {
        val text = loading("Show:\n  Type: Pass\n  Comment: Shows §reel\n  End: true")
        assertEquals(listOf(VARIABLE_NEVER_READ to "reel"), variables(text))
    }

    // INVALID_VARIABLE_NAME

    fun `test an assignment of a name that is no variable name is a warning`() {
        val text = machine(
            """
            Tag:
              Type: Pass
              Assign:
                re-el: 1
                1reel: 2
                states: 3
                reel: 4
              Output: "{% §reel %}"
              End: true
            """,
        )
        assertEquals(listOf(INVALID_VARIABLE_NAME to "re-el", INVALID_VARIABLE_NAME to "1reel", INVALID_VARIABLE_NAME to "states"), variables(text))
    }

    // OUTER_VARIABLE_ASSIGNED

    fun `test an inner machine that assigns an outer variable is a warning`() {
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
    }

    fun `test a Distributed Map has its own variables`() {
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
        assertEquals(emptyList<Any>(), variables(text))
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
