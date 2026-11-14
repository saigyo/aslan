package io.github.saigyo.aslan.jsonata

import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.jsonata.psi.JsonataFile

abstract class JsonataTestCase : AslTestCase() {
    /** A standalone file of [JsonataFileType]; `<caret>` is honoured. */
    fun jsonata(text: String): JsonataFile =
        myFixture.configureByText(JsonataFileType, text) as JsonataFile

    /**
     * A JSONata machine with the single state "Work", whose body is [state] (YAML, indented here), and a second state
     * "Failed" of type Fail for catchers.
     */
    fun machineText(state: String): String =
        "QueryLanguage: JSONata\nStartAt: Work\nStates:\n  Work:\n" +
            state.trimIndent().trim('\n').prependIndent("    ") +
            "\n  Failed:\n    Type: Fail\n"

    /**
     * [machineText] of a Task state in which [field] is a `>-` block scalar holding `{% expression %}`; for the field
     * "Arguments" the block scalar is the value of Arguments.
     */
    fun hostText(expression: String, field: String = "Output"): String {
        val scalar = "$field: >-\n" + "{% $expression %}".prependIndent("  ")
        val arguments = if (field == "Arguments") scalar else "Arguments:\n  FunctionName: catalog-reel"
        val extra = if (field == "Arguments") "" else "\n$scalar"
        return machineText(
            "Type: Task\nResource: arn:aws:states:::lambda:invoke\n$arguments$extra\nCatch:\n  - ErrorEquals: [States.ALL]\n    Next: Failed\nEnd: true",
        )
    }

    /** [hostText] configured as `test.asl.yaml`. */
    fun hosted(expression: String, field: String = "Output"): PsiFile = yaml(hostText(expression, field))
}
