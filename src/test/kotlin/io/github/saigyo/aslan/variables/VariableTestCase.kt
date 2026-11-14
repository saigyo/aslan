package io.github.saigyo.aslan.variables

import io.github.saigyo.aslan.AslTestCase

abstract class VariableTestCase : AslTestCase() {
    /**
     * A state machine whose `States` are [states] (YAML, indented here) and whose `StartAt` is the first of them. [top]
     * holds further top-level lines, such as `QueryLanguage: JSONata`; without it the machine is JSONPath.
     */
    fun machine(states: String, top: String = ""): String {
        val body = states.trimIndent().trim('\n')
        val first = body.lineSequence().first().trim().removeSuffix(":")
        val head = if (top.isBlank()) "" else top.trimIndent().trim('\n') + "\n"
        return head + "StartAt: $first\nStates:\n" + body.prependIndent("  ") + "\n"
    }
}
