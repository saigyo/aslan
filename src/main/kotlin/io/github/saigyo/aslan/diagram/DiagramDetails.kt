package io.github.saigyo.aslan.diagram

import com.intellij.lang.injection.InjectedLanguageManager
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslRead
import io.github.saigyo.aslan.model.AslState
import io.github.saigyo.aslan.model.JsonataRead
import io.github.saigyo.aslan.model.PathRead
import io.github.saigyo.aslan.model.TransitionKind
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslNode
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.ScalarType

/** The rows of the details panel for one state, as plain data. */
object DiagramDetails {
    private const val ARROW = " → "
    private const val END = "End"

    /** Called in a read action. Never throws on a half-typed state; a row without items is left out. */
    fun rows(doc: AslDocument, state: AslState): List<DetailRow> = listOf(
        row("Type", listOfNotNull(state.typeName?.let { DetailItem(it) })),
        row("Resource", listOfNotNull(resource(state))),
        row("Reads", reads(doc, state)),
        row("Assigns", assigns(doc, state)),
        row("Retry", retry(state)),
        row("Catch", catches(state)),
        row("Next", next(state)),
    ).filterNotNull()

    private fun row(label: String, items: List<DetailItem>): DetailRow? = if (items.isEmpty()) null else DetailRow(label, items)

    private fun resource(state: AslState): DetailItem? {
        val scalar = state.obj?.get("Resource")?.value as? AslScalar ?: return null
        if (scalar.type != ScalarType.STRING || scalar.dynamic) return null
        return DetailItem(scalar.text)
    }

    private fun reads(doc: AslDocument, state: AslState): List<DetailItem> {
        val first = LinkedHashMap<String, Int>()
        for (read in doc.variables.reads) {
            if (read.state !== state) continue
            first.putIfAbsent(read.name, offset(read))
        }
        return first.map { (name, offset) -> DetailItem("$" + name, kind = DetailItem.Kind.VARIABLE, offset = offset) }
    }

    private fun offset(read: AslRead): Int = when (read) {
        is JsonataRead -> InjectedLanguageManager.getInstance(read.variable.project)
            .injectedToHost(read.variable, read.variable.textRange.startOffset)
        is PathRead -> read.scalar.psi.textRange.startOffset + read.range.startOffset
    }

    private fun assigns(doc: AslDocument, state: AslState): List<DetailItem> {
        val first = LinkedHashMap<String, Int>()
        for (assignment in doc.variables.assignments) {
            if (assignment.state !== state) continue
            first.putIfAbsent(assignment.name, assignment.entry.keyPsi.textRange.startOffset)
        }
        return first.map { (name, offset) -> DetailItem("$" + name, kind = DetailItem.Kind.VARIABLE, offset = offset) }
    }

    private fun retry(state: AslState): List<DetailItem> {
        val retriers = (state.obj?.get("Retry")?.value as? AslArray)?.items.orEmpty()
        return retriers.mapIndexed { i, retrier ->
            val name = aliasName(retrier) ?: errorNames(retrier).takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Retrier ${i + 1}"
            DetailItem(name, offset = retrier.usePsi.textRange.startOffset)
        }
    }

    /** The name of a YAML alias that is the value of [node]: the text of where it is used, without the `*`. Also for an alias that does not resolve. */
    private fun aliasName(node: AslNode): String? {
        val text = node.usePsi.text
        return if (text.startsWith("*") && text.length > 1) text.substring(1) else null
    }

    private fun errorNames(node: AslNode?): List<String> {
        val names = ((node as? AslObject)?.get("ErrorEquals")?.value as? AslArray)?.items.orEmpty()
        return names.filterIsInstance<AslScalar>().filter { it.type == ScalarType.STRING }.map { it.text }
    }

    private fun catches(state: AslState): List<DetailItem> =
        state.transitions.filter { it.kind == TransitionKind.CATCH }.mapNotNull { t ->
            val name = t.targetName ?: return@mapNotNull null
            val names = errorNames(t.owner)
            target(state, name, if (names.isEmpty()) "" else names.joinToString(", ") + ARROW)
        }

    private fun next(state: AslState): List<DetailItem> {
        val kinds = if (state.typeName == "Choice") setOf(TransitionKind.CHOICE, TransitionKind.DEFAULT) else setOf(TransitionKind.NEXT)
        val names = state.transitions.filter { it.kind in kinds }.mapNotNull { it.targetName }.distinct()
        if (names.isNotEmpty()) return names.map { target(state, it, "") }
        return if (DiagramBuilder.isTerminal(state)) listOf(DetailItem(END)) else emptyList()
    }

    private fun target(state: AslState, name: String, prefix: String): DetailItem {
        val target = state.machine.state(name) ?: return DetailItem(name, prefix)
        return DetailItem(name, prefix, DetailItem.Kind.STATE, target.entry.keyPsi.textRange.startOffset)
    }
}
