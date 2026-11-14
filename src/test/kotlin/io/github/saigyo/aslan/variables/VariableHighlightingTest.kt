package io.github.saigyo.aslan.variables

import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.psi.PsiElement

class VariableHighlightingTest : VariableTestCase() {
    /**
     * `reel` is assigned in Load and in the Catcher of Load, and read in a block scalar, a single-quoted scalar and a
     * JSONPath string; `shelf` is assigned and read once. The second branch of the Parallel state assigns its own
     * `reel` and reads it. `§` stands for `$`, `@@` marks the caret.
     */
    private val archive = """
        StartAt: Load
        States:
          Load:
            Type: Task
            QueryLanguage: JSONata
            Resource: arn:aws:states:::lambda:invoke
            Arguments:
              FunctionName: catalog-intake
            Assign:
              reel: "{% §states.result.Payload.reel %}"
              shelf: 4
            Catch:
              - ErrorEquals: [States.ALL]
                Assign:
                  reel: none
                Next: Check
            Next: Check
          Check:
            Type: Pass
            QueryLanguage: JSONata
            Output:
              block: |-
                {% §reel & '-block' %}
              quoted: '{% §reel & §shelf %}'
              local: '{% (§total := 1; §total + §total) %}'
            Next: Shelve
          Shelve:
            Type: Pass
            InputPath: §reel.title
            End: true
    """.trimIndent()

    private fun configure(marker: String, withCaret: String, text: String = archive) {
        yaml(text.replaceFirst(marker, withCaret).replace("§", "$"))
    }

    private val reelHighlights = listOf(
        "reel" to true, "reel" to true,
        "\$reel" to false, "\$reel" to false, "\$reel" to false,
    )

    fun `test the caret on an assignment highlights writes and reads`() {
        configure("  reel: none", "  re<caret>el: none")
        assertEquals(reelHighlights, highlightedAccesses())
    }

    fun `test the caret on a JSONata read in a block scalar highlights the same`() {
        configure("{% §reel & '-block'", "{% §re<caret>el & '-block'")
        assertEquals(reelHighlights, highlightedAccesses())
    }

    fun `test the caret on a JSONata read in a single-quoted scalar highlights the same`() {
        configure("{% §reel & §shelf", "{% §re<caret>el & §shelf")
        assertEquals(reelHighlights, highlightedAccesses())
    }

    fun `test the caret on a JSONata read in a JSON string highlights the same`() {
        val text = """
            {
              "StartAt": "Load",
              "States": {
                "Load": {
                  "Type": "Pass",
                  "QueryLanguage": "JSONata",
                  "Assign": { "reel": 1 },
                  "Next": "Show"
                },
                "Show": {
                  "Type": "Pass",
                  "QueryLanguage": "JSONata",
                  "Output": "{% §re<caret>el %}",
                  "End": true
                }
              }
            }
        """.trimIndent().replace("§", "$")
        json(text)
        assertEquals(listOf("reel" to true, "\$reel" to false), highlightedAccesses())
    }

    fun `test the caret on a JSONPath read highlights the same`() {
        configure("InputPath: §reel.title", "InputPath: §re<caret>el.title")
        assertEquals(reelHighlights, highlightedAccesses())
    }

    fun `test only the variable under the caret`() {
        val text = """
            StartAt: Fan
            States:
              Fan:
                Type: Parallel
                Assign:
                  shelf: 2
                Branches:
                  - StartAt: One
                    States:
                      One:
                        Type: Pass
                        QueryLanguage: JSONata
                        Assign:
                          reel: 5
                        Output: "{% §re<caret>el + §shelf %}"
                        End: true
                  - StartAt: Two
                    States:
                      Two:
                        Type: Pass
                        QueryLanguage: JSONata
                        Assign:
                          reel: 6
                        Output: "{% §reel %}"
                        End: true
                End: true
        """.trimIndent().replace("§", "$")
        yaml(text)
        // the second branch's own reel and the outer shelf stay unmarked
        assertEquals(listOf("reel" to true, "\$reel" to false), highlightedAccesses())
    }

    fun `test a local JSONata variable keeps stage 2's highlighting`() {
        configure("§total := 1", "§to<caret>tal := 1")
        val handled = HighlightUsagesHandler.createCustomHandler<PsiElement>(myFixture.editor, myFixture.file)
        assertNull(handled)
        // the platform's own usage highlighting marks the three occurrences of the local, none of them a state variable
        assertEquals(listOf("\$total", "\$total", "\$total"), highlightedAccesses().map { it.first })
    }
}
