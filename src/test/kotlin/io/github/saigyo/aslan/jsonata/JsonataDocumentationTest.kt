package io.github.saigyo.aslan.jsonata

import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import io.github.saigyo.aslan.jsonata.docs.JsonataDocumentationProvider
import io.github.saigyo.aslan.jsonata.docs.JsonataParameterInfoHandler
import io.github.saigyo.aslan.docs.AslDocumentation
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.StatesField
import io.github.saigyo.aslan.spec.StructureId

class JsonataDocumentationTest : JsonataTestCase() {
    private fun doc(text: String): String? {
        val file = jsonata(text)
        val leaf = file.findElementAt(myFixture.caretOffset)!!
        val provider = JsonataDocumentationProvider()
        val element = provider.getCustomDocumentationElement(myFixture.editor, file, leaf, myFixture.caretOffset) ?: return null
        return provider.generateDoc(element, leaf)
    }

    fun `test a function`() {
        val html = doc("\$subs<caret>tring(\"abc\", 1)")!!
        assertTrue(html, "\$substring" in html)
        for (parameter in AslSpec.jsonataFunction("substring")!!.parameters) assertTrue(html, parameter.name in html)
        assertTrue(html, "docs.jsonata.org" in html)
    }

    fun `test a Step Functions function and eval`() {
        // A note in italics stands before the description of an addition and of an unsupported function, not of a plain one.
        assertTrue(AslSpec.jsonataFunction("uuid")!!.stepFunctionsOnly)
        assertFalse(AslSpec.jsonataFunction("eval")!!.supported)
        assertTrue(doc("\$uu<caret>id()")!!.contains("<i>"))
        assertTrue(doc("\$ev<caret>al(\"1\")")!!.contains("<i>"))
        assertFalse(doc("\$subs<caret>tring(\"a\", 1)")!!.contains("<i>"))
    }

    fun `test states and its fields`() {
        assertNotNull(doc("\$sta<caret>tes.input"))
        assertTrue(doc("\$states.res<caret>ult")!!.contains(AslDocumentation.esc(AslSpec.statesFieldDoc(StatesField.RESULT).description)))
        val execution = AslSpec.contextFields.first { it.name == "Execution" }
        assertTrue(doc("\$states.context.Exec<caret>ution")!!.contains(AslDocumentation.esc(execution.description)))
        val id = execution.children.first { it.name == "Id" }
        assertTrue(doc("\$states.context.Execution.I<caret>d")!!.contains(AslDocumentation.esc(id.description)))
        assertNull(doc("\$states.input.na<caret>me"))
    }

    fun `test nothing for a local variable named like a function`() {
        assertNull(doc("(\$sum := 1; \$su<caret>m)"))
        assertNull(doc("(\$states := {}; \$sta<caret>tes.result)"))
        assertNull(doc("\$noSuchFunc<caret>tion()"))
    }

    private fun parameterIndex(text: String): Pair<List<String>, Int>? {
        val file = jsonata(text)
        val handler = JsonataParameterInfoHandler()
        val create = MockCreateParameterInfoContext(myFixture.editor, file)
        val call = handler.findElementForParameterInfo(create) ?: return null
        val shown = create.itemsToShow.orEmpty().map { (it as io.github.saigyo.aslan.spec.JsonataFunction).name }
        val update = MockUpdateParameterInfoContext(myFixture.editor, file)
        update.parameterOwner = call
        val owner = handler.findElementForUpdatingParameterInfo(update)!!
        handler.updateParameterInfo(owner, update)
        return shown to update.currentParameter
    }

    fun `test parameter info`() {
        assertEquals(listOf("substring") to 1, parameterIndex("\$substring(\"abc\", <caret>)"))
        assertEquals(listOf("substring") to 1, parameterIndex("\$substring(\$join([\"a\", \"b\"], \",\"), <caret>)"))
        assertEquals(listOf("substring") to 0, parameterIndex("\$substring(<caret>"))
        assertEquals(listOf("substring") to 2, parameterIndex("\$substring({\"a\": 1, \"b\": 2}, 1, <caret>"))
    }

    fun `test no parameter info for a function that is not built in`() {
        assertNull(parameterIndex("\$mine(1, <caret>)"))
        assertNull(parameterIndex("(\$sum := function(\$a){ \$a }; \$sum(1, <caret>))"))
    }

    /**
     * The documentation the way the IDE asks for it: the targets of the editor the fixture hands out (the injected editor
     * when the caret is in an expression, as the data context of the IDE does), then the document of the first target.
     */
    private fun ideDoc(name: String, text: String): String? {
        myFixture.configureByText(name, text)
        val target = IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, myFixture.file, myFixture.editor.caretModel.offset).firstOrNull() ?: return null
        return computeDocumentationBlocking(target.createPointer())?.html
    }

    private fun jsonHost(string: String): String =
        """{"QueryLanguage": "JSONata", "StartAt": "Work", "States": {"Work": {"Type": "Task", "Resource": "arn:aws:states:::lambda:invoke", "Arguments": {"FunctionName": "catalog-reel"}, "Output": $string, "End": true}}}"""

    fun `test the IDE asks for documentation in a YAML host`() {
        val substring = AslSpec.jsonataFunction("substring")!!
        val function = ideDoc("a.asl.yaml", hostText("\$subs<caret>tring('a', 1)"))!!
        assertTrue(function, substring.url in function)
        for (parameter in substring.parameters) assertTrue(function, parameter.name in function)
        val result = ideDoc("b.asl.yaml", hostText("\$states.res<caret>ult"))!!
        assertTrue(result, AslDocumentation.esc(AslSpec.statesFieldDoc(StatesField.RESULT).description) in result)
    }

    fun `test the IDE asks for documentation in a JSON host`() {
        val substring = AslSpec.jsonataFunction("substring")!!
        val function = ideDoc("a.asl.json", jsonHost("\"{% \$subs<caret>tring('a', 1) %}\""))!!
        assertTrue(function, substring.url in function)
        val result = ideDoc("b.asl.json", jsonHost("\"{% \$states.res<caret>ult %}\""))!!
        assertTrue(result, AslDocumentation.esc(AslSpec.statesFieldDoc(StatesField.RESULT).description) in result)
    }

    fun `test the documentation of a key outside an expression is the stage 1 one`() {
        val yaml = ideDoc("c.asl.yaml", hostText("1").replace("Output: >-", "Out<caret>put: >-"))!!
        val output = AslSpec.structure(StructureId.TASK).field("Output")!!
        assertTrue(yaml, AslDocumentation.esc(output.description) in yaml)
        val json = ideDoc("c.asl.json", jsonHost("\"{% 1 %}\"").replace("\"Output\"", "\"Out<caret>put\""))!!
        assertTrue(json, AslDocumentation.esc(output.description) in json)
    }
}
