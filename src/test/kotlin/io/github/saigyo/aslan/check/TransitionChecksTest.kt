package io.github.saigyo.aslan.check

import io.github.saigyo.aslan.check.AslProblem.DUPLICATE_STATE_NAME
import io.github.saigyo.aslan.check.AslProblem.MISSING_TRANSITION_TARGET
import io.github.saigyo.aslan.check.AslProblem.NO_TERMINAL_STATE
import io.github.saigyo.aslan.check.AslProblem.STATE_NAME_TOO_LONG

class TransitionChecksTest : CheckTestCase() {
    private val dollar = '$'

    fun `test Next to a missing state`() =
        assertEquals(listOf(MISSING_TRANSITION_TARGET to "Decid"), found(edit("Next: Decide" to "Next: Decid")))

    fun `test StartAt to a missing state`() =
        assertEquals(listOf(MISSING_TRANSITION_TARGET to "Nowhere"), found(edit("StartAt: Inspect" to "StartAt: Nowhere")))

    fun `test Default to a missing state`() =
        assertEquals(listOf(MISSING_TRANSITION_TARGET to "Nowhere"), found(edit("Default: Rejected" to "Default: Nowhere")))

    fun `test catcher Next to a missing state`() =
        assertEquals(listOf(MISSING_TRANSITION_TARGET to "Nowhere"), found(edit("        Next: Rejected\n" to "        Next: Nowhere\n")))

    fun `test choice rule Next to a missing state`() =
        assertEquals(listOf(MISSING_TRANSITION_TARGET to "Nowhere"), found(edit("        Next: Accepted\n" to "        Next: Nowhere\n")))

    fun `test target in another machine`() {
        val text = edit(
            "  Accepted:\n    Type: Succeed\n" to """
                  Accepted:
                    Type: Map
                    End: true
                    ItemProcessor:
                      StartAt: Inner
                      States:
                        Inner:
                          Type: Pass
                          Next: Rejected
                        InnerDone:
                          Type: Succeed
            """.trimIndent().prependIndent("  ") + "\n",
        )
        assertEquals(listOf(MISSING_TRANSITION_TARGET to "Rejected"), found(text))
    }

    fun `test placeholder target`() = assertEquals(emptyList<Any>(), found(edit("Next: Decide" to "Next: ${dollar}{NextState}")))

    fun `test placeholder state name`() {
        val text = edit("Default: Rejected" to "Default: Elsewhere") + "  ${dollar}{Extra}:\n    Type: Succeed\n"
        assertEquals(emptyList<Any>(), found(text))
    }

    fun `test no terminal state`() {
        val text = edit(
            "  Accepted:\n    Type: Succeed\n" to "  Accepted:\n    Type: Pass\n    Next: Inspect\n",
            "  Rejected:\n    Type: Fail\n" to "  Rejected:\n    Type: Pass\n    Next: Inspect\n",
        )
        assertEquals(listOf(NO_TERMINAL_STATE to "States"), found(text))
    }

    fun `test no terminal state is not reported when a type is unknown or End is uncertain`() {
        val pass = "  Accepted:\n    Type: Pass\n    Next: Inspect\n"
        val rejected = "  Rejected:\n    Type: Pass\n    Next: Inspect\n"
        for (last in listOf("    Type: ${dollar}{Kind}\n", "    Type: Pass\n    End: ${dollar}{Last}\n", "    Type: Pass\n    End: yes\n", "    Type: Pass\n    End: true\n")) {
            val text = edit("  Accepted:\n    Type: Succeed\n" to pass, "  Rejected:\n    Type: Fail\n" to "  Rejected:\n$last")
            assertEquals(last, emptyList<Any>(), found(text).filter { it.first == NO_TERMINAL_STATE })
        }
        assertEquals(emptyList<Any>(), found(edit("  Accepted:\n    Type: Succeed\n" to pass, "  Rejected:\n    Type: Fail\n" to rejected + "  Odd:\n    Type: Nope\n")).filter { it.first == NO_TERMINAL_STATE })
    }

    fun `test duplicate across nesting levels`() {
        val text = edit(
            "  Accepted:\n    Type: Succeed\n" to """
                  Accepted:
                    Type: Map
                    End: true
                    ItemProcessor:
                      StartAt: Rejected
                      States:
                        Rejected:
                          Type: Succeed
            """.trimIndent().prependIndent("  ") + "\n",
        )
        assertEquals(listOf(DUPLICATE_STATE_NAME to "Rejected", DUPLICATE_STATE_NAME to "Rejected"), found(text))
    }

    private fun renamed(name: String) = edit("  Accepted:" to "  $name:", "Next: Accepted" to "Next: $name")

    fun `test name of 81 characters`() {
        val name = "A".repeat(81)
        assertEquals(listOf(STATE_NAME_TOO_LONG to name), found(renamed(name)))
    }

    fun `test name of 80 characters`() = assertEquals(emptyList<Any>(), found(renamed("A".repeat(80))))

    fun `test name of 80 code points outside the basic plane`() =
        assertEquals(emptyList<Any>(), found(renamed("\uD835\uDD38".repeat(80))))

    fun `test uncertain targets are not missing`() {
        for (target in listOf("*gone", "yes", "5", "[a]")) {
            val text = edit("Next: Decide" to "Next: $target")
            assertEquals(target, emptyList<Any>(), found(text).filter { it.first == MISSING_TRANSITION_TARGET })
        }
    }

    fun `test open machine skips target and terminal checks`() {
        val text = edit("Next: Decide" to "Next: Gone") + "  ${dollar}{Extra}:\n    Type: Succeed\n"
        assertEquals(emptyList<Any>(), found(text))
    }

    fun `test half-typed files do not throw`() {
        found("StartAt: A\nStates:\n  A:\n    Type: Pass\n    Next:")
        found("StartAt:\nStates:\n  A:\n  - x")
        found("States:\n  <<: *x\n  A: {Type: Pass}")
    }

    private fun quotedName(next: String) = edit("  Accepted:" to "  'Director''s cut':", "Next: Accepted" to "Next: $next")

    fun `test quoted state name is found by a double-quoted target`() =
        assertEquals(emptyList<Any>(), found(quotedName("\"Director's cut\"")))

    fun `test quoted state name is found by a single-quoted target`() =
        assertEquals(emptyList<Any>(), found(quotedName("'Director''s cut'")))

    fun `test a quoted target is decoded again after an edit in the same file`() {
        assertEquals(emptyList<Any>(), found(quotedName("\"Director's cut\"")))
        assertEquals(emptyList<Any>(), found(quotedName("'Director''s cut'")))
    }

    fun `test name length counts the decoded name`() {
        // raw text has 2 extra characters per quote pair, decoded is 80 or 81 code points
        val name80 = "'" .repeat(40) + "a".repeat(40)
        val quoted80 = "'" + name80.replace("'", "''") + "'"
        assertEquals(emptyList<Any>(), found(edit("  Accepted:" to "  $quoted80:", "Next: Accepted" to "Next: $quoted80")))
        val name81 = name80 + "b"
        val quoted81 = "'" + name81.replace("'", "''") + "'"
        assertEquals(
            listOf(STATE_NAME_TOO_LONG to quoted81),
            found(edit("  Accepted:" to "  $quoted81:", "Next: Accepted" to "Next: $quoted81")),
        )
    }

    fun `test json names are decoded like json values`() {
        val json = """
            {"StartAt": "A\u0042", "States": {
              "AB": {"Type": "Pass", "Next": "Say \"hi\""},
              "Say \"hi\"": {"Type": "Succeed"}}}
        """.trimIndent()
        assertEquals(emptyList<Any>(), found(json, "test.asl.json"))
        assertEquals(emptyList<Any>(), found(json.replace("A\\u0042", "AB"), "test.asl.json"))
    }

    private fun flow(text: String): Boolean {
        val file = myFixture.configureByText("test.asl.yaml", text)
        return io.github.saigyo.aslan.model.AslModel.of(file)!!.root!!.hasUnknownFlow()
    }

    fun `test a target with an anchor in front is found`() {
        assertEquals(emptyList<Any>(), found(edit("Next: Decide" to "Next: &n 'Decide'")))
        assertEquals(emptyList<Any>(), found(edit("Next: Decide" to "Next: !!str \"Decide\"")))
    }

    fun `test hasUnknownFlow is false for BASE`() = assertFalse(flow(BASE))

    fun `test hasUnknownFlow cases`() {
        val cases = mapOf(
            "open machine" to edit("Next: Decide" to "Next: Decide") + "  ${dollar}{X}:\n    Type: Succeed\n",
            "StartAt absent" to edit("StartAt: Inspect\n" to ""),
            "StartAt placeholder" to edit("StartAt: Inspect" to "StartAt: ${dollar}{S}"),
            "StartAt unknown" to edit("StartAt: Inspect" to "StartAt: Nowhere"),
            "no target" to edit("        Next: Accepted\n" to "        Comment: x\n"),
            "placeholder target" to edit("Next: Decide" to "Next: ${dollar}{N}"),
            "unknown target" to edit("Next: Decide" to "Next: Nowhere"),
            "no Next or End" to edit("  Accepted:\n    Type: Succeed\n" to "  Accepted:\n    Type: Pass\n"),
            "End false" to edit("  Accepted:\n    Type: Succeed\n" to "  Accepted:\n    Type: Pass\n    End: false\n"),
            "End placeholder" to edit("  Accepted:\n    Type: Succeed\n" to "  Accepted:\n    Type: Pass\n    End: ${dollar}{E}\n"),
            "End ambiguous" to edit("  Accepted:\n    Type: Succeed\n" to "  Accepted:\n    Type: Pass\n    End: yes\n"),
            "state not a mapping" to edit("  Accepted:\n    Type: Succeed\n" to "  Accepted: ${dollar}{Whole}\n"),
            "state with unapplied merge" to edit("  Accepted:\n    Type: Succeed\n" to "  Accepted:\n    <<: *missing\n    Type: Succeed\n"),
            "Choices not a list" to edit("    Choices:\n      - Condition: '{% ${dollar}states.input.ok %}'\n        Next: Accepted\n" to "    Choices: ${dollar}{C}\n"),
            "Choices item not a mapping" to edit("      - Condition: '{% ${dollar}states.input.ok %}'\n        Next: Accepted\n" to "      - plain\n"),
            "Catch item not a mapping" to edit("      - ErrorEquals: [States.ALL]\n        Next: Rejected\n" to "      - plain\n"),
            "Choices item with unapplied merge" to edit("        Next: Accepted\n" to "        Next: Accepted\n        <<: *missing\n"),
            "Catch item with unapplied merge" to edit("        Next: Rejected\n" to "        Next: Rejected\n        <<: *missing\n"),
            "StartAt ambiguous" to edit("StartAt: Inspect" to "StartAt: yes"),
            "StartAt not a string" to edit("StartAt: Inspect" to "StartAt: 5"),
            "ambiguous target" to edit("Next: Decide" to "Next: yes"),
            "target not a string" to edit("Next: Decide" to "Next: 5"),
            "unresolved alias target" to edit("Next: Decide" to "Next: *missing"),
            "Catch not a list" to edit("    Catch:\n      - ErrorEquals: [States.ALL]\n        Next: Rejected\n" to "    Catch: ${dollar}{C}\n"),
        )
        for ((name, text) in cases) assertTrue(name, flow(text))
    }
}
