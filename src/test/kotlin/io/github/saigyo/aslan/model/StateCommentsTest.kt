package io.github.saigyo.aslan.model

import io.github.saigyo.aslan.AslTestCase

class StateCommentsTest : AslTestCase() {
    /** The comments of the state named [name] in the YAML [text] (its first state of that name anywhere). */
    private fun of(text: String, name: String): StateComments.Comments {
        val doc = AslModel.of(yaml(text))!!
        return StateComments.of(doc).of(doc.allStates().first { it.name == name })
    }

    private fun description(text: String, name: String) = of(text, name).description
    private fun notes(text: String, name: String) = of(text, name).notes

    fun `test the block above the key is the description, joined into a paragraph`() = assertEquals(
        listOf("Scans the reels of one delivery."),
        description("""
            StartAt: Scan
            States:
              # Scans the reels
              # of one delivery.
              Scan:
                Type: Succeed
        """, "Scan"),
    )

    fun `test a lone hash line starts a new paragraph`() = assertEquals(
        listOf("First.", "Second."),
        description("""
            StartAt: Scan
            States:
              # First.
              #
              # Second.
              Scan:
                Type: Succeed
        """, "Scan"),
    )

    fun `test a blank line ends the block`() = assertEquals(
        listOf("Near."),
        description("""
            StartAt: Scan
            States:
              # Far.

              # Near.
              Scan:
                Type: Succeed
        """, "Scan"),
    )

    fun `test a line in another column is not part of the block`() {
        val text = """
            StartAt: Sort
            States:
              Sort:
                Type: Pass
                Next: Scan
                # deeper
              Scan:
                Type: Succeed
        """
        assertEquals(emptyList<String>(), description(text, "Scan"))
        assertEquals(listOf("deeper"), notes(text, "Sort"))
    }

    fun `test the Comment field comes first`() = assertEquals(
        listOf("Short.", "Block text."),
        description("""
            StartAt: Scan
            States:
              # Block text.
              Scan:
                Type: Succeed
                Comment: Short.
        """, "Scan"),
    )

    fun `test comment lines inside the state are notes, as written`() = assertEquals(
        listOf("Function: lambdas/src/handler.ts", "Output:  x"),
        notes("""
            StartAt: Scan
            States:
              Scan:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                # Function: lambdas/src/handler.ts
                Arguments:
                  FunctionName: scan
                # Output:  x
                Output: '{% ${'$'}states.result %}'
                End: true
        """, "Scan"),
    )

    fun `test a comment after the last field is a note`() = assertEquals(
        listOf("TODO: retry on throttling"),
        notes("""
            StartAt: Scan
            States:
              Scan:
                Type: Pass
                Next: Done
                # TODO: retry on throttling
              Done:
                Type: Succeed
        """, "Scan"),
    )

    fun `test a section heading between states belongs to no state`() {
        val text = """
            StartAt: Scan
            States:
              Scan:
                Type: Pass
                Next: Done

              # ---- Phase 2 ----

              Done:
                Type: Succeed
        """
        assertEquals(emptyList<String>(), notes(text, "Scan"))
        assertEquals(emptyList<String>(), description(text, "Done"))
        assertEquals(emptyList<String>(), notes(text, "Done"))
    }

    fun `test the next state's description is not a note`() {
        val text = """
            StartAt: Scan
            States:
              Scan:
                Type: Pass
                Next: Done
              # Ends it.
              Done:
                Type: Succeed
        """
        assertEquals(emptyList<String>(), notes(text, "Scan"))
        assertEquals(listOf("Ends it."), description(text, "Done"))
    }

    fun `test a comment at the end of a line is a note`() = assertEquals(
        listOf("the key", "calls the scanner"),
        notes("""
            StartAt: Scan
            States:
              Scan:   # the key
                Type: Succeed  # calls the scanner
        """, "Scan"),
    )

    fun `test notes of a nested state stay with it`() {
        val text = """
            StartAt: Fan
            States:
              Fan:
                Type: Parallel
                # outer note
                Branches:
                  - StartAt: Inner
                    States:
                      # Inner description.
                      Inner:
                        Type: Succeed
                        # inner note
                      # inner heading
                End: true
        """
        assertEquals(listOf("outer note"), notes(text, "Fan"))
        assertEquals(listOf("Inner description."), description(text, "Inner"))
        assertEquals(listOf("inner note"), notes(text, "Inner"))
    }

    fun `test a hash in a quoted string or a block scalar is no comment`() = assertEquals(
        emptyList<String>(),
        notes("""
            StartAt: Scan
            States:
              Scan:
                Type: Pass
                Parameters:
                  quoted: 'x # not a comment'
                  block: |
                    # not a comment either
                End: true
        """, "Scan"),
    )

    fun `test a state in JSON has the Comment field as its description and no notes`() {
        val doc = AslModel.of(json("""{"StartAt": "Scan", "States": {"Scan": {"Type": "Succeed", "Comment": "Scans."}}}"""))!!
        val comments = StateComments.of(doc).of(doc.allStates().single())
        assertEquals(listOf("Scans."), comments.description)
        assertEquals(emptyList<String>(), comments.notes)
    }

    fun `test a half-typed state has comments and no exception`() {
        assertEquals(listOf("Empty."), description("""
            StartAt: Scan
            States:
              # Empty.
              Scan:
        """, "Scan"))
        assertEquals(listOf("Odd."), description("""
            StartAt: Scan
            States:
              # Odd.
              Scan: oops
        """, "Scan"))
    }

    fun `test a state without comments has none`() =
        assertEquals(StateComments.Comments(emptyList(), emptyList()), of("StartAt: A\nStates:\n  A:\n    Type: Succeed\n", "A"))

    fun `test a note of the parent after a nested machine stays with the parent`() {
        val text = """
            StartAt: Fan
            States:
              Fan:
                Type: Parallel
                Branches:
                  - StartAt: Inner
                    States:
                      Inner:
                        Type: Succeed
                        # inner note
                # outer note after the branches
                End: true
        """
        assertEquals(listOf("outer note after the branches"), notes(text, "Fan"))
        assertEquals(listOf("inner note"), notes(text, "Inner"))
    }
}
