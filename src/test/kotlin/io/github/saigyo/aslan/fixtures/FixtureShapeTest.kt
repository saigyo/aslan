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
        assertTrue(text.lines().size > 1000)
        assertTrue(doc.allStates().size >= 40)
        assertEquals(setOf("Task", "Pass", "Choice", "Wait", "Succeed", "Fail", "Parallel", "Map"), doc.allStates().mapNotNull { it.typeName }.toSet())
        assertTrue(doc.allStates().any { it.typeName == "Map" && it.children.single().states.size >= 2 })
        assertTrue(doc.allStates().any { it.typeName == "Parallel" && it.children.size == 2 })
        assertTrue(Regex("""\*\w+""").findAll(text).count() >= 20)
        assertTrue(Regex("""^\s+\$\{\w+}:""", RegexOption.MULTILINE).containsMatchIn(text))        // placeholder as key
        assertTrue(Regex(""""[^"\n]*\$\{\w+}[^"\n]*"""").containsMatchIn(text))                     // inside a JSONata string
        assertTrue(listOf(": >-", ": |-", ": '{%", ": \"{%").all { it in text })
        val assignments = objects(doc.rootNode).flatMap { it.entries }.filter { it.key == "Assign" }
            .flatMap { (it.value as? AslObject)?.entries.orEmpty() }.groupingBy { it.key }.eachCount()
        assertTrue(assignments.values.max() >= 15)                                                  // one variable assigned in many places
        assertTrue(doc.root!!.states.maxOf { it.transitions.size } >= 6)                            // the dispatching Choice
        assertTrue(text.lines().count { it.trimStart().startsWith("#") } >= 30)
    }

    fun `test json and jsonpath definitions load`() {
        assertTrue(AslModel.of(load("reel-check.asl.json"))!!.allStates().size >= 6)
        val legacy = AslModel.of(load("legacy-loan.asl.yaml"))!!
        assertTrue(legacy.allStates().all { it.queryLanguage == QueryLanguage.JSONPATH })
    }

    fun `test no name from outside the invented domain`() {
        for (name in listOf("film-archive-intake.asl.yaml", "reel-check.asl.json", "legacy-loan.asl.yaml"))
            assertFalse(Regex("[äöüßÄÖÜ]").containsMatchIn(load(name).text))
    }
}
