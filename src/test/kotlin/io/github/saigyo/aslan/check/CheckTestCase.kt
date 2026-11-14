package io.github.saigyo.aslan.check

import io.github.saigyo.aslan.AslTestCase

abstract class CheckTestCase : AslTestCase() {
    val BASE: String = """
        QueryLanguage: JSONata
        StartAt: Inspect
        States:
          Inspect:
            Type: Task
            Resource: arn:aws:states:::lambda:invoke
            Arguments:
              FunctionName: ${'$'}{InspectFn}
            Retry:
              - ErrorEquals: [States.ALL]
                MaxAttempts: 2
            Catch:
              - ErrorEquals: [States.ALL]
                Next: Rejected
            Next: Decide
          Decide:
            Type: Choice
            Choices:
              - Condition: '{% ${'$'}states.input.ok %}'
                Next: Accepted
            Default: Rejected
          Accepted:
            Type: Succeed
          Rejected:
            Type: Fail
    """.trimIndent() + "\n"

    /** Each finding of [text] as its problem and the text of the element it is reported on. */
    fun found(text: String, name: String = "test.asl.yaml"): List<Pair<AslProblem, String>> =
        AslChecker.findings(myFixture.configureByText(name, text)).map { it.problem to it.element.text }

    /** Each finding of [text] as its problem and the text of its range, or of its element when it has none. */
    fun marked(text: String, name: String = "test.asl.yaml"): List<Pair<AslProblem, String>> =
        AslChecker.findings(myFixture.configureByText(name, text)).map {
            it.problem to (it.range?.substring(it.element.text) ?: it.element.text)
        }

    /** [BASE] with each literal replacement applied; fails when a search string does not occur. */
    fun edit(vararg replacements: Pair<String, String>): String =
        replacements.fold(BASE) { text, (from, to) ->
            assertTrue("not in BASE: $from", text.contains(from))
            text.replace(from, to)
        }
}
