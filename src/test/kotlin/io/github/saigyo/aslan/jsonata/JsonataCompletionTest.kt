package io.github.saigyo.aslan.jsonata

class JsonataCompletionTest : JsonataTestCase() {
    private val caret = "<caret>"

    private fun inOutput(expression: String): List<String> = complete(hostText(expression))

    private fun taskState(extra: String): String =
        "Type: Task\nResource: arn:aws:states:::lambda:invoke\nArguments:\n  FunctionName: catalog-reel\n$extra\nEnd: true"

    fun `test functions and states after a dollar sign`() {
        val items = inOutput("\$$caret")
        assertTrue(items.containsAll(listOf("\$sum", "\$states", "\$uuid", "\$partition")))
        assertFalse(items.contains("\$eval"))
    }

    fun `test local names`() {
        val items = inOutput("(\$total := 1; \$t$caret)")
        assertTrue(items.toString(), items.containsAll(listOf("\$total", "\$trim", "\$toMillis")))
    }

    fun `test the fields of states depend on the place`() {
        assertEquals(listOf("context", "input", "result"), inOutput("\$states.$caret").sorted())
        assertEquals(listOf("context", "input"), complete(hostText("\$states.$caret", "Arguments")).sorted())

        val catcher = machineText(
            "Type: Task\nResource: arn:aws:states:::lambda:invoke\nArguments:\n  FunctionName: catalog-reel\n" +
                "Catch:\n  - ErrorEquals: [States.ALL]\n    Next: Failed\n    Output: >-\n      {% \$states.$caret %}\nEnd: true",
        )
        assertEquals(listOf("context", "errorOutput", "input"), complete(catcher).sorted())

        val shared = machineText(
            "Type: Task\nResource: arn:aws:states:::lambda:invoke\nArguments:\n  FunctionName: catalog-reel\n" +
                "Output: &shared '{% \$states.$caret %}'\nNext: Copy\n",
        ) + "  Copy:\n    Type: Pass\n    Output: *shared\n    End: true\n"
        assertEquals(listOf("context", "input"), complete(shared).sorted())
    }

    fun `test the context object`() {
        val top = inOutput("\$states.context.$caret")
        assertTrue(top.toString(), top.containsAll(listOf("Execution", "State", "StateMachine", "Task", "Map")))
        val execution = inOutput("\$states.context.Execution.$caret")
        assertTrue(execution.toString(), execution.containsAll(listOf("Id", "Input", "Name", "StartTime")))
    }

    fun `test keywords`() {
        assertTrue(inOutput("\$states.input.a a$caret").contains("and"))
        assertTrue(inOutput("t$caret").contains("true"))
    }

    fun `test choosing a function inserts its brackets`() {
        myFixture.configureByText("test.asl.yaml", hostText("\$uui$caret"))
        myFixture.completeBasic()
        myFixture.checkResult(hostText("\$uuid()$caret"))
        myFixture.configureByText("test.asl.yaml", hostText("\$substringB$caret"))
        myFixture.completeBasic()
        myFixture.checkResult(hostText("\$substringBefore($caret)"))
    }

    fun `test choosing a function by typing an opening bracket inserts one pair`() {
        myFixture.configureByText("test.asl.yaml", hostText("\$substr$caret"))
        myFixture.completeBasic()
        val lookup = myFixture.lookup as com.intellij.codeInsight.lookup.impl.LookupImpl
        lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == "\$substringBefore" }
        // the fixture's finishLookup looks in the host editor; the lookup is in the injected one
        com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, { lookup.finishLookup('(') }, null, null)
        myFixture.checkResult(hostText("\$substringBefore($caret)"))
    }

    fun `test context fields after a path segment written with an escape`() {
        val c = "\\" + "u0063"
        val json = """{"QueryLanguage":"JSONata","StartAt":"A","States":{"A":{"Type":"Pass","Output":"{% ${'$'}states.""" + c + """ontext.$caret %}","End":true}}}"""
        assertTrue(complete(json, "test.asl.json").containsAll(listOf("Execution", "State")))
    }

    fun `test a function hidden by a local variable is not offered`() {
        val items = inOutput("(\$sum := 1; \$su$caret)")
        assertEquals(items.toString(), 1, items.count { it == "\$sum" })
        assertTrue(items.toString(), items.contains("\$substring"))
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == "\$sum" }
        myFixture.finishLookup('\n')
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("(\$sum := 1; \$sum)"))
    }

    fun `test nothing for data fields`() {
        assertEquals(emptyList<String>(), inOutput("\$states.input.$caret"))
    }

    fun `test completion in a half-typed expression`() {
        assertEquals(listOf("context", "input", "result"), inOutput("\$sum(\$states.$caret").sorted())
    }

    fun `test nothing in a string or a comment`() {
        assertEquals(emptyList<String>(), inOutput("'\$st$caret'"))
        assertEquals(emptyList<String>(), inOutput("/* \$st$caret */ 1"))
        // an unterminated comment is a token of its own (BAD_COMMENT): the `%}` lies outside the injected range
        val unterminated = complete(machineText("Type: Task\nResource: arn:aws:states:::lambda:invoke\nOutput: '{% /* \$st$caret %}'\nEnd: true"))
        assertEquals(emptyList<String>(), unterminated)
        assertTrue(myFixture.file.language == io.github.saigyo.aslan.jsonata.JsonataLanguage)
        assertTrue(myFixture.file.findElementAt(myFixture.caretOffset - 1)!!.node.elementType == io.github.saigyo.aslan.jsonata.parser.JsonataTokens.BAD_COMMENT)
    }

    fun `test a local variable named states has no states fields`() {
        assertEquals(emptyList<String>(), inOutput("(\$states := {}; \$states.$caret)"))
    }

    fun `test an expression with a placeholder still completes`() {
        assertTrue(inOutput("\${Bucket} & \$su$caret").contains("\$sum"))
    }

    private fun lookupTypeText(name: String): String? {
        val presentation = com.intellij.codeInsight.lookup.LookupElementPresentation()
        myFixture.lookupElements!!.single { it.lookupString == name }.renderElement(presentation)
        return presentation.typeText
    }

    /** A JSONata machine: Load assigns [assigned], then a Parallel state whose branch Left assigns `inner` and whose branch Right outputs [expression]. */
    private fun forked(assigned: String, expression: String): String = """
        QueryLanguage: JSONata
        StartAt: Load
        States:
          Load:
            Type: Pass
            Assign:
              $assigned
            Next: Fork
          Fork:
            Type: Parallel
            End: true
            Branches:
              - StartAt: Left
                States:
                  Left:
                    Type: Pass
                    Assign:
                      inner: 1
                    End: true
              - StartAt: Right
                States:
                  Right:
                    Type: Pass
                    Output: '{% $expression %}'
                    End: true
    """.trimIndent() + "\n"

    fun `test state variables after a dollar sign`() {
        val items = complete(forked("reel: 1", "\$re$caret"))
        assertEquals(items.toString(), 1, items.count { it == "\$reel" })
        assertFalse(items.toString(), items.contains("\$inner"))
        assertEquals("state variable", lookupTypeText("\$reel"))
    }

    fun `test a local variable hides a state variable`() {
        val items = complete(forked("reel: 1", "(\$reel := 2; \$re$caret)"))
        assertEquals(items.toString(), 1, items.count { it == "\$reel" })
        assertEquals("variable", lookupTypeText("\$reel"))
    }

    fun `test a state variable hides a built-in function`() {
        myFixture.configureByText("test.asl.yaml", forked("sum: 1", "\$su$caret"))
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertEquals(items.toString(), 1, items.count { it == "\$sum" })
        assertEquals("state variable", lookupTypeText("\$sum"))
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == "\$sum" }
        myFixture.finishLookup('\n')
        // the editor holds the injected expression; no brackets are written after the variable
        assertEquals(" \$sum ", myFixture.editor.document.text)
    }

    fun `test an expression used by two states offers what both see`() {
        val text = """
            QueryLanguage: JSONata
            StartAt: Load
            States:
              Load:
                Type: Pass
                Assign:
                  reel: 1
                Next: Fork
              Fork:
                Type: Parallel
                End: true
                Branches:
                  - StartAt: Left
                    States:
                      Left:
                        Type: Pass
                        Assign:
                          onlyLeft: 1
                          both: 1
                        Output: &shared '{% ${'$'}$caret %}'
                        End: true
                  - StartAt: Right
                    States:
                      Right:
                        Type: Pass
                        Assign:
                          both: 2
                        Output: *shared
                        End: true
        """.trimIndent() + "\n"
        val items = complete(text)
        assertTrue(items.toString(), items.containsAll(listOf("\$reel", "\$both")))
        assertFalse(items.toString(), items.contains("\$onlyLeft"))
    }

    fun `test no state variables for an expression in no state`() {
        val items = complete(forked("reel: 1", "1").replace("QueryLanguage: JSONata\n", "QueryLanguage: JSONata\nComment: '{% \$re$caret %}'\n"))
        assertFalse(items.toString(), items.contains("\$reel"))
    }
}
