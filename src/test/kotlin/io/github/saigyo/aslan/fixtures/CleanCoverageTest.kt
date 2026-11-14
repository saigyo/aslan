package io.github.saigyo.aslan.fixtures

import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslNode
import io.github.saigyo.aslan.tree.AslObject
import java.io.File

/** The clean definitions between them use every field of the reference data, so a wrong mode or kind shows as a finding. */
class CleanCoverageTest : AslTestCase() {
    fun `test every field name occurs as a known field in a clean definition`() {
        val used = HashSet<String>()
        for (name in cleanDefinitions()) {
            val file = myFixture.configureByFile("clean/$name")
            val doc = AslModel.of(file)!!
            for (entry in entries(doc.rootNode)) if (doc.schema.field(entry.keyPsi) != null) used += entry.key
        }
        val all = StructureId.entries.flatMap { id -> AslSpec.structure(id).fields.map { it.name } }.toSortedSet()
        assertEquals("field names no clean definition uses", emptyList<String>(), (all - used).toList())
    }

    private fun entries(node: AslNode?): Sequence<io.github.saigyo.aslan.tree.AslEntry> = when (node) {
        is AslObject -> node.entries.asSequence().flatMap { sequenceOf(it) + entries(it.value) }
        is AslArray -> node.items.asSequence().flatMap { entries(it) }
        else -> emptySequence()
    }

    companion object {
        /** Every file under `src/test/testData/clean`, by name. */
        fun cleanDefinitions(): List<String> =
            File("src/test/testData/clean").listFiles().orEmpty().map { it.name }.filter { ".asl." in it }.sorted()
    }
}
