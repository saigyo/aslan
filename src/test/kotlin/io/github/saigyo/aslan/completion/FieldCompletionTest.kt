package io.github.saigyo.aslan.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import io.github.saigyo.aslan.AslTestCase

class FieldCompletionTest : AslTestCase() {
    private val head = "QueryLanguage: JSONata\nStartAt: A\nStates:\n  A:\n    Type: Task\n"

    fun `test keys of a JSONata task without those already present`() {
        val items = complete(head + "    Resource: x\n    <caret>")
        assertTrue(items.containsAll(listOf("Arguments", "Assign", "Output", "Retry", "Catch", "Next", "End", "TimeoutSeconds", "Comment")))
        assertFalse(items.any { it in setOf("Type", "Resource", "Parameters", "ResultPath", "InputPath", "Choices") })
    }
    fun `test required fields come first`() =
        assertEquals("Resource", complete(head + "    <caret>").first())
    fun `test prefix filters`() =
        assertEquals(listOf("Arguments", "Assign"), complete(head + "    A<caret>").sorted())
    fun `test JSONPath task offers path fields`() {
        val items = complete("StartAt: A\nStates:\n  A:\n    Type: Task\n    <caret>")
        assertTrue(items.containsAll(listOf("Parameters", "ResultPath", "InputPath")))
        assertFalse("Arguments" in items)
    }
    fun `test top level keys`() =
        assertEquals(listOf("Comment", "States", "TimeoutSeconds", "Version"), complete("QueryLanguage: JSONata\nStartAt: A\n<caret>").sorted())
    fun `test keys inside a retrier and a catcher`() {
        assertTrue(complete(head + "    Retry:\n      - ErrorEquals: [States.ALL]\n        <caret>").containsAll(listOf("MaxAttempts", "BackoffRate", "JitterStrategy")))
        assertTrue("Next" in complete(head + "    Catch:\n      - ErrorEquals: [States.ALL]\n        <caret>"))
    }
    fun `test first key of a new state offers Type`() =
        assertEquals("Type", complete("StartAt: A\nStates:\n  A:\n    <caret>").first())
    fun `test nothing inside free-form data`() =
        assertEquals(emptyList<String>(), complete(head + "    Arguments:\n      <caret>").filter { it in setOf("Next", "Type", "Retry") })
    fun `test fixed values`() {
        assertEquals(listOf("JSONPath", "JSONata"), complete("QueryLanguage: <caret>").sorted())
        assertEquals(listOf("FULL", "NONE"), complete(head + "    Retry:\n      - JitterStrategy: <caret>").sorted())
        assertEquals(listOf("false", "true"), complete(head + "    End: <caret>").sorted())
    }
    fun `test inserting a scalar field adds colon and space`() {
        complete(head + "    Resource: x\n    Comm<caret>")
        myFixture.checkResult(head + "    Resource: x\n    Comment: <caret>")
    }
    fun `test inserting a list field starts the first item`() {
        complete(head + "    Resource: x\n    Retr<caret>")
        myFixture.checkResult(head + "    Resource: x\n    Retry:\n      - <caret>")
    }
    fun `test json keys`() {
        val items = complete("""{"StartAt": "A", "States": {"A": {"Type": "Pass", "<caret>"}}}""", "test.asl.json")
        assertTrue(items.containsAll(listOf("Next", "End", "Result", "Comment")))
        assertFalse("Resource" in items)
    }
    fun `test nothing in other files`() =
        assertFalse("StartAt" in complete("<caret>", "plain.yaml"))
    fun `test deprecated fields come last and are struck out`() {
        val items = complete("StartAt: A\nStates:\n  A:\n    Type: Map\n    <caret>")
        assertEquals(setOf("Iterator", "Parameters"), items.takeLast(2).toSet())
        val struck = myFixture.lookupElements.orEmpty().filter {
            LookupElementPresentation.renderElement(it).isStrikeout
        }.map { it.lookupString }
        assertEquals(setOf("Iterator", "Parameters"), struck.toSet())
    }
    fun `test first key of an empty list item or object`() {
        assertTrue("MaxAttempts" in complete(head + "    Retry:\n      - <caret>"))
        assertTrue("ProcessorConfig" in complete("StartAt: A\nStates:\n  A:\n    Type: Map\n    ItemProcessor:\n      <caret>"))
    }
    fun `test json boolean value`() {
        val text = """{"StartAt": "A", "States": {"A": {"Type": "Pass", "End": <caret>}}}"""
        assertEquals(listOf("false", "true"), complete(text, "test.asl.json").sorted())
        myFixture.type("tr")
        myFixture.completeBasic()
        myFixture.checkResult("""{"StartAt": "A", "States": {"A": {"Type": "Pass", "End": true<caret>}}}""")
    }
    fun `test json fixed value inside quotes`() =
        assertEquals(listOf("JSONPath", "JSONata"), complete("""{"QueryLanguage": "<caret>"}""", "test.asl.json").sorted())
    fun `test json fixed value without quotes is inserted quoted`() {
        assertEquals(listOf("JSONPath", "JSONata"), complete("""{"QueryLanguage": <caret>}""", "test.asl.json").sorted())
        myFixture.type("JSONa")
        myFixture.completeBasic()
        myFixture.checkResult("""{"QueryLanguage": "JSONata"<caret>}""")
    }
    fun `test no items of its own in a text value`() {
        val items = complete("StartAt: A\nStates:\n  A:\n    Type: Pass\n    End: true\n    Comment: <caret>")
        val own = setOf("Next", "Result", "Parameters", "InputPath", "QueryLanguage", "JSONPath", "JSONata", "Choice", "Wait", "false")
        assertEquals(emptyList<String>(), items.filter { it in own })
    }
    fun `test json key without quotes is inserted quoted`() {
        complete("""{"StartAt": "A", "States": {"A": {"Type": "Pass", Nex<caret>}}}""", "test.asl.json")
        myFixture.checkResult("""{"StartAt": "A", "States": {"A": {"Type": "Pass", "Next"<caret>}}}""")
    }
    fun `test json empty objects and trailing comma`() {
        assertEquals("Type", complete("""{"StartAt": "A", "States": {"A": {<caret>}}}""", "test.asl.json").first())
        assertTrue(complete("{<caret>}", "test.asl.json").containsAll(listOf("StartAt", "States", "Comment")))
        val items = complete("""{"StartAt": "A", "States": {"A": {"Type": "Pass", <caret>}}}""", "test.asl.json")
        assertTrue(items.containsAll(listOf("Next", "End", "Result")))
        assertFalse("Type" in items)
    }
    fun `test new key after a key with an empty value`() {
        val items = complete(head + "    Resource:\n    <caret>")
        assertTrue(items.containsAll(listOf("Comment", "Next", "Retry")))
        assertFalse("Resource" in items)
        complete(head + "    Resource:\n    Co<caret>")
        myFixture.checkResult(head + "    Resource:\n    Comment: <caret>")
    }
    fun `test deeper line under an empty key is its value`() {
        val items = complete(head + "    Resource: x\n    Comment:\n      <caret>")
        assertFalse(items.any { it in setOf("Next", "Retry", "End", "Arguments") })
    }
    fun `test new line between existing keys`() {
        val items = complete(head + "    <caret>\n    Resource: x\n    End: true")
        assertTrue("Comment" in items)
        assertFalse(items.any { it in setOf("Resource", "End", "Type") })
    }
    fun `test first key of catcher, choice rule and branch items`() {
        assertTrue("Next" in complete(head + "    Catch:\n      - <caret>"))
        val rule = complete("QueryLanguage: JSONata\nStartAt: A\nStates:\n  A:\n    Type: Choice\n    Choices:\n      - <caret>")
        assertTrue("Condition" in rule)
        assertFalse("Variable" in rule)
        assertTrue(complete("StartAt: A\nStates:\n  A:\n    Type: Parallel\n    Branches:\n      - <caret>").containsAll(listOf("StartAt", "States")))
    }
    fun `test new state inside an item processor offers Type`() =
        assertEquals("Type", complete("StartAt: A\nStates:\n  A:\n    Type: Map\n    ItemProcessor:\n      StartAt: B\n      States:\n        B:\n          <caret>").first())
    fun `test state with an invalid type offers the generic fields`() =
        assertEquals(listOf("Comment", "QueryLanguage"), complete("StartAt: A\nStates:\n  A:\n    Type: Tsak\n    <caret>").sorted())
    fun `test no field names where a state name goes`() {
        val items = complete("StartAt: A\nStates:\n  <caret>")
        assertFalse(items.any { it in setOf("Type", "Comment", "Next", "TimeoutSeconds", "Version") })
    }
    fun `test no booleans inside quotes`() =
        assertFalse(complete(head + "    End: \"<caret>\"").any { it == "true" || it == "false" })
    fun `test odd input does not fail`() {
        complete("<caret>")
        complete("<caret>", "test.asl.json")
        complete(head + "    # a <caret> comment")
        complete(head + "    End: true\n<caret>")
        complete(head + "    Retry:\n      - <caret>")
        complete(head + "    Retry:\n      - ErrorEquals: [States.ALL, <caret>]")
        complete("""{"StartAt": "A", "States": {"A": {"Type": "Pass", <caret>""", "test.asl.json")
        complete("""{"StartAt": "A", "States": {"A": {"Type": "Pass", "<caret>""", "test.asl.json")
    }
}
