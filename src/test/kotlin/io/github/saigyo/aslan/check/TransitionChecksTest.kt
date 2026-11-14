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
}
