package io.github.saigyo.aslan.jsonata

import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import io.github.saigyo.aslan.jsonata.docs.JsonataDocumentationProvider
import io.github.saigyo.aslan.jsonata.docs.JsonataParameterInfoHandler
import io.github.saigyo.aslan.spec.AslSpec

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
        assertTrue(doc("\$uu<caret>id()")!!.contains("Step Functions"))
        assertTrue(doc("\$ev<caret>al(\"1\")")!!.contains("does not support"))
    }

    fun `test states and its fields`() {
        assertNotNull(doc("\$sta<caret>tes.input"))
        assertTrue(doc("\$states.res<caret>ult")!!.contains(AslSpec.statesFieldDoc(io.github.saigyo.aslan.spec.StatesField.RESULT).description))
        val execution = AslSpec.contextFields.first { it.name == "Execution" }
        assertTrue(doc("\$states.context.Exec<caret>ution")!!.contains(execution.description))
        assertTrue(doc("\$states.context.Execution.I<caret>d")!!.contains("ARN of the execution"))
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
}
