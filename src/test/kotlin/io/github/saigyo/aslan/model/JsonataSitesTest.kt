package io.github.saigyo.aslan.model

import com.intellij.lang.injection.InjectedLanguageManager
import io.github.saigyo.aslan.jsonata.JsonataTestCase
import io.github.saigyo.aslan.spec.StatesField
import io.github.saigyo.aslan.spec.StatesField.CONTEXT
import io.github.saigyo.aslan.spec.StatesField.ERROR_OUTPUT
import io.github.saigyo.aslan.spec.StatesField.INPUT
import io.github.saigyo.aslan.spec.StatesField.RESULT

class JsonataSitesTest : JsonataTestCase() {
    private fun sites(text: String): List<JsonataSite> = AslModel.of(yaml(text))!!.jsonataSites.all

    /** The state name and the value of each site, in the order of [JsonataSites.all]. */
    private fun siteTexts(text: String): List<Pair<String, String>> = sites(text).map { it.state.name to it.scalar.text }

    /** The value and what it provides, of each site. */
    private fun provided(text: String): Map<String, Set<StatesField>> = sites(text).associate { it.scalar.text to it.provides }

    private fun taskWith(snippet: String): String = machineText(
        "Type: Task\nResource: arn:aws:states:::lambda:invoke\nArguments:\n  FunctionName: catalog-reel\n$snippet\nEnd: true",
    )

    fun `test where an expression is a site`() {
        val text = """
            QueryLanguage: JSONata
            StartAt: Work
            x-shared: &extras
              MaxConcurrency: '{% 2 %}'
            States:
              Work:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                Arguments:
                  Payload:
                    reels:
                      - plain
                      - '{% ${'$'}states.input.reel %}'
                Output: '{% ${'$'}states.result %}'
                Assign:
                  reel: '{% ${'$'}states.result.reel %}'
                TimeoutSeconds: '{% 30 %}'
                Catch:
                  - ErrorEquals: [States.ALL]
                    Output: '{% ${'$'}states.errorOutput %}'
                    Next: Failed
                Next: Sort
              Sort:
                Type: Choice
                Choices:
                  - Condition: '{% ${'$'}states.input.ok %}'
                    Next: Each
                Default: Failed
              Each:
                Type: Map
                Items: '{% ${'$'}states.input.reels %}'
                ItemProcessor:
                  StartAt: One
                  States:
                    One:
                      Type: Pass
                      Output: '{% ${'$'}states.input.inner %}'
                      End: true
                <<: *extras
                End: true
              Failed:
                Type: Fail
        """
        assertEquals(
            listOf(
                "Work" to "{% \$states.input.reel %}",
                "Work" to "{% \$states.result %}",
                "Work" to "{% \$states.result.reel %}",
                "Work" to "{% 30 %}",
                "Work" to "{% \$states.errorOutput %}",
                "Sort" to "{% \$states.input.ok %}",
                "Each" to "{% \$states.input.reels %}",
                "Each" to "{% 2 %}",
                "One" to "{% \$states.input.inner %}",
            ),
            siteTexts(text),
        )
    }

    fun `test where an expression is no site`() {
        val cases = listOf(
            taskWith("Comment: '{% 1 %}'"),
            // a JSONPath state
            "StartAt: Work\nStates:\n  Work:\n    Type: Pass\n    Parameters:\n      a: '{% 1 %}'\n    Output: '{% 1 %}'\n    End: true\n",
            // a top-level field
            "QueryLanguage: JSONata\nComment: '{% 1 %}'\nStartAt: Work\nStates:\n  Work:\n    Type: Succeed\n",
            "QueryLanguage: \${Lang}\nStartAt: Work\nStates:\n  Work:\n    Type: Pass\n    Output: '{% 1 %}'\n    End: true\n",
            taskWith("Output: '{% 1'"),
            taskWith("Output: |\n  {% 1 %}"),
            taskWith("Output: plain {% 1 %}"),
            taskWith("Output: !Sub '{% 1 %}'"),
            "QueryLanguage: JSONata\nStartAt: Work\nStates:\n  Work:\n    Type: \${Kind}\n    Output: '{% 1 %}'\n    End: true\n",
            taskWith("Output: '{%}'"),
        )
        for (case in cases) assertEquals(case, emptyList<Pair<String, String>>(), siteTexts(case))
    }

    fun `test the shortest site`() {
        assertEquals(listOf("Work" to "{%%}"), siteTexts(taskWith("Output: '{%%}'")))
    }

    fun `test what a site provides`() {
        val text = """
            QueryLanguage: JSONata
            StartAt: Work
            States:
              Work:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                Arguments:
                  a: '{% 1 %}'
                Output: '{% 2 %}'
                Assign:
                  b: '{% 3 %}'
                Catch:
                  - ErrorEquals: [States.ALL]
                    Output: '{% 4 %}'
                    Assign:
                      c: '{% 5 %}'
                    Next: Failed
                Next: Hand
              Hand:
                Type: Pass
                Output: '{% 6 %}'
                Next: Sort
              Sort:
                Type: Choice
                Choices:
                  - Condition: '{% 7 %}'
                    Output: '{% 8 %}'
                    Next: Each
                Default: Failed
              Each:
                Type: Map
                Items: '{% 9 %}'
                ItemProcessor:
                  StartAt: One
                  States:
                    One: { Type: Succeed }
                Output: '{% 10 %}'
                End: true
              Failed:
                Type: Fail
        """
        val plain = setOf(INPUT, CONTEXT)
        val withResult = setOf(INPUT, CONTEXT, RESULT)
        val withError = setOf(INPUT, CONTEXT, ERROR_OUTPUT)
        assertEquals(
            mapOf(
                "{% 1 %}" to plain, "{% 2 %}" to withResult, "{% 3 %}" to withResult,
                "{% 4 %}" to withError, "{% 5 %}" to withError,
                "{% 6 %}" to plain, "{% 7 %}" to plain, "{% 8 %}" to plain,
                "{% 9 %}" to plain, "{% 10 %}" to withResult,
            ),
            provided(text),
        )
    }

    fun `test an anchored expression used by two states has two sites at one element`() {
        val file = yaml(
            """
            QueryLanguage: JSONata
            StartAt: Work
            States:
              Work:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                Arguments: {}
                Output: &out '{% ${'$'}states.input.reel %}'
                Next: Hand
              Hand:
                Type: Pass
                Output: *out
                End: true
            """,
        )
        val sites = AslModel.of(file)!!.jsonataSites
        assertEquals(listOf("Work", "Hand"), sites.all.map { it.state.name })
        val element = sites.all[0].scalar.psi
        assertSame(element, sites.all[1].scalar.psi)
        assertEquals(listOf("Work", "Hand"), sites.at(element).map { it.state.name })
        assertEquals(setOf(INPUT, CONTEXT, RESULT), sites.all[0].provides)
        assertEquals(setOf(INPUT, CONTEXT), sites.all[1].provides)
        assertEquals(1, InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(element)!!.map { it.first }.distinct().size)
    }

    fun `test a json file`() {
        val file = json(
            """
            {"QueryLanguage": "JSONata", "StartAt": "Work", "States": {
              "Work": {"Type": "Pass", "Comment": "{% 0 %}", "Output": {"a": ["{% 1 %}", "{% 2"]}, "End": true}
            }}
            """,
        )
        assertEquals(listOf("{% 1 %}"), AslModel.of(file)!!.jsonataSites.all.map { it.scalar.text })
    }
}
