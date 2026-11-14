package io.github.saigyo.aslan.docs

import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.StructureId

class AslDocumentationTest : AslTestCase() {
    private fun docAtCaret(text: String, name: String = "test.asl.yaml"): String? {
        myFixture.configureByText(name, text.trimIndent())
        return AslDocumentation.html(myFixture.file.findElementAt(myFixture.caretOffset)!!)
    }
    private val head = "QueryLanguage: JSONata\nStartAt: A\nStates:\n  A:\n    Type: Task\n"

    fun `test field name`() {
        val html = docAtCaret(head + "    Argu<caret>ments: {}")!!
        assertTrue("Arguments" in html)
        assertTrue("JSONata" in html)                                            // says which query language it belongs to
        assertTrue(AslSpec.structure(StructureId.TASK).field("Arguments")!!.description in html)
        assertTrue("https://docs.aws.amazon.com/step-functions/" in html)
    }
    fun `test required field says so`() =
        assertTrue("required" in docAtCaret(head + "    Reso<caret>urce: x")!!.lowercase())
    fun `test state type value`() =
        assertTrue(AslSpec.stateType("Task")!!.description in docAtCaret("StartAt: A\nStates:\n  A:\n    Type: Ta<caret>sk")!!)
    fun `test fixed value`() =
        assertTrue("FULL" in docAtCaret(head + "    Retry:\n      - JitterStrategy: FU<caret>LL")!!)
    fun `test same field name documents the structure it is in`() =
        assertTrue(AslSpec.structure(StructureId.CATCHER).field("Next")!!.description in docAtCaret(head + "    Catch:\n      - ErrorEquals: [States.ALL]\n        Ne<caret>xt: A")!!)
    fun `test nothing for free-form keys state names and other files`() {
        assertNull(docAtCaret(head + "    Arguments:\n      Ne<caret>xt: 1"))
        assertNull(docAtCaret("StartAt: A\nStates:\n  <caret>A:\n    Type: Succeed"))
        assertNull(docAtCaret("Ty<caret>pe: Task", "plain.yaml"))
    }
    fun `test json`() =
        assertTrue("Resource" in docAtCaret("""{"StartAt": "A", "States": {"A": {"Type": "Task", "Reso<caret>urce": "x"}}}""", "test.asl.json")!!)
    fun `test the provider answers for the element at the caret`() {
        myFixture.configureByText("test.asl.yaml", head + "    Reso<caret>urce: x")
        val context = myFixture.file.findElementAt(myFixture.caretOffset)
        val provider = AslDocumentationProvider()
        val element = provider.getCustomDocumentationElement(myFixture.editor, myFixture.file, context, myFixture.caretOffset)
        assertNotNull(element)
        assertTrue("Resource" in provider.generateDoc(element, context)!!)
    }

    fun `test predefined error name`() {
        val html = docAtCaret(head + "    Retry:\n      - ErrorEquals: [States.Time<caret>out]")!!
        assertTrue(AslSpec.errorNames.first { it.name == "States.Timeout" }.description in html)
    }
    fun `test custom error name and unknown arn give nothing`() {
        assertNull(docAtCaret(head + "    Retry:\n      - ErrorEquals: [Reel<caret>Jam]"))
        assertNull(docAtCaret("StartAt: A\nStates:\n  A:\n    Type: Task\n    Resource: arn:aws:states:::film:Sc<caret>an"))
    }
    fun `test service integration arn`() {
        val arn = AslSpec.resourceArns.first()
        val html = docAtCaret("StartAt: A\nStates:\n  A:\n    Type: Task\n    Resource: ${arn.name.dropLast(2)}<caret>${arn.name.takeLast(2)}")!!
        assertTrue(arn.description in html)
    }
    fun `test quoted key and value in yaml and json values`() {
        assertTrue("Resource" in docAtCaret(head + "    \"Reso<caret>urce\": x")!!)
        assertTrue("FULL" in docAtCaret(head + "    Retry:\n      - JitterStrategy: \"FU<caret>LL\"")!!)
        assertTrue(AslSpec.stateType("Succeed")!!.description in
            docAtCaret("""{"StartAt": "A", "States": {"A": {"Type": "Suc<caret>ceed"}}}""", "test.asl.json")!!)
    }
    fun `test unknown type value gives nothing`() =
        assertNull(docAtCaret("StartAt: A\nStates:\n  A:\n    Type: Ts<caret>ak"))
    fun `test text from the file is escaped`() {
        val html = docAtCaret("StartAt: A\nStates:\n  A:\n    Type: Task\n    Resource: x\n    Retry:\n      - ErrorEquals: [\"<b>\"]\n        Jitter<caret>Strategy: FULL")!!
        assertFalse("<b>" in html)
    }
}
