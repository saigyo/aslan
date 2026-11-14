package io.github.saigyo.aslan.spec

import junit.framework.TestCase

class JsonataSpecTest : TestCase() {
    fun `test the functions are those of JSONata 2_0_6 and of Step Functions`() {
        val jsonata = ("sum count max min average string substring substringBefore substringAfter lowercase uppercase length trim pad match " +
            "contains replace split join formatNumber formatBase formatInteger parseInteger number floor ceil round abs sqrt power random boolean " +
            "not map zip filter single reduce sift keys lookup append exists spread merge reverse each error assert type sort shuffle distinct " +
            "base64encode base64decode encodeUrlComponent encodeUrl decodeUrlComponent decodeUrl eval toMillis fromMillis clone now millis").split(' ')
        val added = listOf("partition", "range", "hash", "uuid", "parse")
        assertEquals((jsonata + added).sorted(), AslSpec.jsonataFunctions.map { it.name }.sorted())
        assertEquals(added.sorted(), AslSpec.jsonataFunctions.filter { it.stepFunctionsOnly }.map { it.name }.sorted())
        assertEquals(listOf("eval"), AslSpec.jsonataFunctions.filter { !it.supported }.map { it.name })
    }

    fun `test every function has a category, a description and a link`() {
        for (f in AslSpec.jsonataFunctions) {
            assertTrue(f.name, f.category.isNotBlank())
            assertTrue(f.name, f.description.isNotBlank())
            assertTrue(f.name, f.url.startsWith("https://"))
        }
        assertSame(AslSpec.jsonataFunction("substring"), AslSpec.jsonataFunctions.single { it.name == "substring" })
        assertEquals(listOf("str", "start", "length"), AslSpec.jsonataFunction("substring")!!.parameters.map { it.name })
        assertEquals(listOf(false, false, true), AslSpec.jsonataFunction("substring")!!.parameters.map { it.optional })
        assertNull(AslSpec.jsonataFunction("nothing"))
        for (field in StatesField.entries) assertTrue(field.text, AslSpec.statesFieldDoc(field).description.isNotBlank())
    }

    fun `test only the functions that take no argument have no parameters`() {
        // Bound without arguments in jsonata.js 2.0.6: millis. The Step Functions function $uuid takes none either.
        // $now and $random take optional arguments; $random's seed is a Step Functions addition.
        assertEquals(listOf("millis", "uuid"), AslSpec.jsonataFunctions.filter { it.parameters.isEmpty() }.map { it.name }.sorted())
        assertTrue(AslSpec.jsonataFunction("random")!!.parameters.single().optional)
        assertEquals(listOf("str"), AslSpec.jsonataFunction("base64encode")!!.parameters.map { it.name })
    }

    fun `test the context object`() {
        val names = AslSpec.contextFields.map { it.name }
        assertTrue(names.toString(), names.containsAll(listOf("Execution", "State", "StateMachine", "Task", "Map")))
        val execution = AslSpec.contextFields.single { it.name == "Execution" }
        assertTrue(execution.children.map { it.name }.containsAll(listOf("Id", "Input", "Name", "RoleArn", "StartTime")))
    }
}
