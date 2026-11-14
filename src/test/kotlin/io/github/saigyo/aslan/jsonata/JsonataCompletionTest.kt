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
}
