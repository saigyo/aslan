package io.github.saigyo.aslan.completion

import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.spec.QueryLanguage

class ValueCompletionTest : AslTestCase() {
    private val two = "QueryLanguage: JSONata\nStartAt: First\nStates:\n  First:\n    Type: Pass\n    Next: Second\n  Second:\n    Type: Succeed\n"

    fun `test state types`() =
        assertEquals(listOf("Task", "Pass", "Choice", "Wait", "Succeed", "Fail", "Parallel", "Map"), complete(two + "  Third:\n    Type: <caret>"))

    fun `test choosing a type on a new state inserts its required fields`() {
        myFixture.configureByText("test.asl.yaml", two + "  Third:\n    Type: Tas<caret>")
        myFixture.completeBasic()
        myFixture.checkResult(two + "  Third:\n    Type: Task\n    Resource: <caret>\n    Next: ")
    }

    fun `test choosing a type on a state with other fields inserts only the type`() {
        myFixture.configureByText("test.asl.yaml", two + "  Third:\n    Type: Tas<caret>\n    Comment: x")
        myFixture.completeBasic()
        myFixture.checkResult(two + "  Third:\n    Type: Task<caret>\n    Comment: x")
    }

    fun `test the template follows the indentation of a nested state`() {
        val text = two + "  Each:\n    Type: Map\n    ItemProcessor:\n      StartAt: In\n      States:\n        In:\n          Type: Pas<caret>\n    End: true"
        myFixture.configureByText("test.asl.yaml", text)
        myFixture.completeBasic()
        myFixture.checkResult(text.replace("Pas<caret>", "Pass\n          Next: <caret>"))
    }

    fun `test choice template follows the query language`() {
        assertTrue(StateTemplates.yamlBody("Choice", QueryLanguage.JSONATA).any { "Condition" in it })
        assertTrue(StateTemplates.yamlBody("Choice", QueryLanguage.JSONPATH).any { "Variable" in it })
        assertEquals(emptyList<String>(), StateTemplates.yamlBody("Succeed", QueryLanguage.JSONATA))
    }

    fun `test json type is quoted and gets no template`() {
        myFixture.configureByText("test.asl.json", """{"StartAt": "A", "States": {"A": {"Type": Tas<caret>}}}""")
        myFixture.completeBasic()
        myFixture.checkResult("""{"StartAt": "A", "States": {"A": {"Type": "Task"<caret>}}}""")
    }

    fun `test state names of the same machine for Next Default and StartAt`() {
        assertEquals(listOf("First", "Second"), complete(two.replace("Next: Second", "Next: <caret>")).sorted())
        assertEquals(listOf("First", "Second"), complete(two.replace("StartAt: First", "StartAt: <caret>")).sorted())
    }

    fun `test the current state is offered last for Next`() =
        assertEquals(listOf("Second", "First"), complete(two.replace("Next: Second", "Next: <caret>")))

    fun `test state names are matched from the start ignoring case`() {
        myFixture.configureByText("test.asl.yaml", two.replace("StartAt: First", "StartAt: sec<caret>"))
        myFixture.completeBasic()
        myFixture.checkResult(two.replace("StartAt: First", "StartAt: Second<caret>"))
    }

    fun `test state names do not leak across machines`() {
        val text = two + "  Each:\n    Type: Map\n    ItemProcessor:\n      StartAt: <caret>\n      States:\n        Inner:\n          Type: Succeed\n    End: true"
        assertEquals(listOf("Inner"), complete(text))
    }

    fun `test predefined error names`() {
        val items = complete(two.replace("    Next: Second", "    Next: Second\n    Catch:\n      - ErrorEquals:\n          - <caret>").replace("Type: Pass", "Type: Task\n    Resource: x"))
        assertTrue(items.containsAll(listOf("States.ALL", "States.Timeout", "States.TaskFailed")))
    }

    fun `test resource arns`() {
        val items = complete(two + "  Third:\n    Type: Task\n    Resource: <caret>")
        assertTrue(items.containsAll(listOf("arn:aws:states:::lambda:invoke", "arn:aws:states:::dynamodb:putItem")))
    }

    fun `test resource arns match on any part`() {
        val items = complete(two + "  Third:\n    Type: Task\n    Resource: dynamo<caret>")
        assertTrue(items.isNotEmpty() && items.all { "dynamodb" in it })
    }

    fun `test json state names`() =
        assertEquals(listOf("A", "B"), complete("""{"StartAt": "<caret>", "States": {"A": {"Type": "Succeed"}, "B": {"Type": "Succeed"}}}""", "test.asl.json").sorted())

    fun `test json state name typed without quotes is quoted`() {
        myFixture.configureByText("test.asl.json", """{"StartAt": Al<caret>, "States": {"Alpha": {"Type": "Succeed"}, "Beta": {"Type": "Succeed"}}}""")
        myFixture.completeBasic()
        myFixture.checkResult("""{"StartAt": "Alpha"<caret>, "States": {"Alpha": {"Type": "Succeed"}, "Beta": {"Type": "Succeed"}}}""")
    }
}
