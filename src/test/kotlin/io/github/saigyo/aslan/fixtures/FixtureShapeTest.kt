package io.github.saigyo.aslan.fixtures

import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslNode
import io.github.saigyo.aslan.tree.AslObject

class FixtureShapeTest : AslTestCase() {
    private fun load(name: String) = myFixture.configureByFile("clean/$name")
    private fun objects(n: AslNode?): Sequence<AslObject> = when (n) {
        is AslObject -> sequenceOf(n) + n.entries.asSequence().flatMap { objects(it.value) }
        is AslArray -> n.items.asSequence().flatMap { objects(it) }
        else -> emptySequence()
    }

    fun `test intake definition is as large and varied as required`() {
        val file = load("film-archive-intake.asl.yaml")
        val text = file.text
        val doc = AslModel.of(file)!!
        assertTrue("intake has more than 1,000 lines", text.lines().size > 1000)
        assertTrue("intake has at least 40 states", doc.allStates().size >= 40)
        assertEquals("intake uses all eight state types", setOf("Task", "Pass", "Choice", "Wait", "Succeed", "Fail", "Parallel", "Map"), doc.allStates().mapNotNull { it.typeName }.toSet())
        assertTrue("intake has a Map with an inline processor of at least two states", doc.allStates().any { it.typeName == "Map" && it.children.single().states.size >= 2 })
        assertTrue("intake has a Parallel state with two branches", doc.allStates().any { it.typeName == "Parallel" && it.children.size == 2 })
        assertTrue("intake reuses retry rules through at least 20 aliases", Regex("""\*\w+""").findAll(text).count() >= 20)
        assertTrue("intake has a placeholder as a mapping key", Regex("""^\s+\$\{\w+}:""", RegexOption.MULTILINE).containsMatchIn(text))
        assertTrue("intake has a placeholder inside a JSONata string literal", Regex(""""[^"\n]*\$\{\w+}[^"\n]*"""").containsMatchIn(text))
        assertTrue("intake has JSONata in folded, literal, single- and double-quoted scalars", listOf(": >-", ": |-", ": '{%", ": \"{%").all { it in text })
        val assignments = objects(doc.rootNode).flatMap { it.entries }.filter { it.key == "Assign" }
            .flatMap { (it.value as? AslObject)?.entries.orEmpty() }.groupingBy { it.key }.eachCount()
        assertTrue("intake assigns one variable in at least 15 places", assignments.values.max() >= 15)
        assertTrue("intake has a Choice with at least six transitions", doc.root!!.states.maxOf { it.transitions.size } >= 6)
        assertTrue("intake has at least 30 comment lines", text.lines().count { it.trimStart().startsWith("#") } >= 30)
    }

    fun `test json and jsonpath definitions load`() {
        assertTrue("reel-check loads with at least 6 states", AslModel.of(load("reel-check.asl.json"))!!.allStates().size >= 6)
        val legacy = AslModel.of(load("legacy-loan.asl.yaml"))!!
        assertTrue("every legacy-loan state uses JSONPath", legacy.allStates().all { it.queryLanguage == QueryLanguage.JSONPATH })
    }

    fun `test no name from outside the invented domain`() {
        for (name in listOf("film-archive-intake.asl.yaml", "reel-check.asl.json", "legacy-loan.asl.yaml"))
            assertFalse("$name contains no German umlauts or sharp s", Regex("[äöüßÄÖÜ]").containsMatchIn(load(name).text))
    }
}
