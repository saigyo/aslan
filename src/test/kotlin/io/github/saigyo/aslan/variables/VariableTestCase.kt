package io.github.saigyo.aslan.variables

import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.AslState

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

    /** The model of [text], a YAML definition in which `§` stands for `$`, configured as `test.asl.yaml`. */
    fun yamlModel(text: String): AslDocument = AslModel.of(yaml(text.replace("§", "$")))!!

    /** The model of [text], a JSON definition in which `§` stands for `$`, configured as `test.asl.json`. */
    fun jsonModel(text: String): AslDocument = AslModel.of(json(text.replace("§", "$")))!!

    /** The only state named [name]. */
    fun AslDocument.state(name: String): AslState = allStates().single { it.name == name }
}
