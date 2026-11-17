package io.github.saigyo.aslan.diagram

import com.intellij.lang.injection.InjectedLanguageManager
import io.github.saigyo.aslan.model.AslAssignment
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
import java.util.IdentityHashMap

/** The rows of the details panel for one state, as plain data. */
object DiagramDetails {
    private const val ARROW = " → "
    private const val END = "End"
    private const val DEFAULT = "Default"

    /** The reads and assignments of a document, grouped by their state in document order. */
    class Variables private constructor(
        private val reads: IdentityHashMap<AslState, MutableList<AslRead>>,
        private val assignments: IdentityHashMap<AslState, MutableList<AslAssignment>>,
    ) {
        fun readsOf(state: AslState): List<AslRead> = reads[state].orEmpty()
        fun assignmentsOf(state: AslState): List<AslAssignment> = assignments[state].orEmpty()

        companion object {
            fun of(doc: AslDocument): Variables {
                val reads = IdentityHashMap<AslState, MutableList<AslRead>>()
                val assignments = IdentityHashMap<AslState, MutableList<AslAssignment>>()
                for (read in doc.variables.reads) reads.getOrPut(read.state) { ArrayList() } += read
                for (assignment in doc.variables.assignments) assignments.getOrPut(assignment.state) { ArrayList() } += assignment
                return Variables(reads, assignments)
            }
        }
    }

    /** One state's rows; groups the document's variables first, so a caller with many states uses [rows] with [Variables]. */
    fun rows(doc: AslDocument, state: AslState): List<DetailRow> = rows(Variables.of(doc), state)

    /** Called in a read action. Never throws on a half-typed state; a row without items is left out. */
    fun rows(variables: Variables, state: AslState): List<DetailRow> = listOf(
        row("Type", listOfNotNull(state.typeName?.let { DetailItem(it) })),
        row("Resource", listOfNotNull(resource(state))),
        row("Reads", reads(variables, state)),
        row("Assigns", assigns(variables, state)),
        row("Retry", retry(state)),
        row("Catch", catches(state)),
        row("Next", next(state)),
    ).filterNotNull()

    /**
     * The rows of the path [edge] of [model]: its two states as links, a Choice rule's whole condition, "Default" for a
     * `Default` path, and a catch path's error names. Made from the model alone, so callable on any thread.
     */
    fun pathRows(model: DiagramModel, edge: DiagramEdge): List<DetailRow> = listOf(
        row("From", listOfNotNull(model.state(edge.from)?.let(::link))),
        row("To", listOfNotNull(model.state(edge.to)?.let(::link))),
        row("Condition", listOfNotNull(condition(edge))),
        row("Errors", listOfNotNull(edge.title?.takeIf { edge.kind == TransitionKind.CATCH }?.let { DetailItem(it, kind = DetailItem.Kind.WRAPPED) })),
    ).filterNotNull()

    private fun link(state: DiagramState) = DetailItem(state.name, kind = DetailItem.Kind.STATE, offset = state.offset)

    private fun condition(edge: DiagramEdge): DetailItem? = when (edge.kind) {
        TransitionKind.CHOICE -> edge.condition?.let { DetailItem(it, kind = DetailItem.Kind.WRAPPED) }
        TransitionKind.DEFAULT -> DetailItem(DEFAULT)
        else -> null
    }

    private fun row(label: String, items: List<DetailItem>): DetailRow? = if (items.isEmpty()) null else DetailRow(label, items)

    private fun resource(state: AslState): DetailItem? {
        val scalar = state.obj?.get("Resource")?.value as? AslScalar ?: return null
        if (scalar.type != ScalarType.STRING || scalar.dynamic) return null
        return DetailItem(scalar.text)
    }

    private fun reads(variables: Variables, state: AslState): List<DetailItem> {
        val first = LinkedHashMap<String, Int>()
        for (read in variables.readsOf(state)) {
            first.putIfAbsent(read.name, offset(read))
        }
        return first.map { (name, offset) -> DetailItem("$" + name, kind = DetailItem.Kind.VARIABLE, offset = offset) }
    }

    private fun offset(read: AslRead): Int = when (read) {
        is JsonataRead -> InjectedLanguageManager.getInstance(read.variable.project)
            .injectedToHost(read.variable, read.variable.textRange.startOffset)
        is PathRead -> read.scalar.psi.textRange.startOffset + read.range.startOffset
    }

    private fun assigns(variables: Variables, state: AslState): List<DetailItem> {
        val first = LinkedHashMap<String, Int>()
        for (assignment in variables.assignmentsOf(state)) {
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
            val name = DiagramBuilder.settledName(t.target) ?: return@mapNotNull null
            val names = errorNames(t.owner)
            target(state, name, if (names.isEmpty()) "" else names.joinToString(", ") + ARROW)
        }

    private fun next(state: AslState): List<DetailItem> {
        val kinds = if (state.type?.name == "Choice") setOf(TransitionKind.CHOICE, TransitionKind.DEFAULT) else setOf(TransitionKind.NEXT)
        val names = state.transitions.filter { it.kind in kinds }.mapNotNull { DiagramBuilder.settledName(it.target) }.distinct()
        if (names.isNotEmpty()) return names.map { target(state, it, "") }
        return if (DiagramBuilder.isTerminal(state)) listOf(DetailItem(END)) else emptyList()
    }

    private fun target(state: AslState, name: String, prefix: String): DetailItem {
        val target = state.machine.state(name) ?: return DetailItem(name, prefix)
        return DetailItem(name, prefix, DetailItem.Kind.STATE, target.entry.keyPsi.textRange.startOffset)
    }
}
