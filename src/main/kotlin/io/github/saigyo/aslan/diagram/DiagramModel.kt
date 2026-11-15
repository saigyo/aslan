package io.github.saigyo.aslan.diagram

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslState
import io.github.saigyo.aslan.model.AslStateMachine
import io.github.saigyo.aslan.model.AslTransition
import io.github.saigyo.aslan.model.TransitionKind
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.Placeholders
import io.github.saigyo.aslan.tree.ScalarType
import java.util.IdentityHashMap

/**
 * What the diagram shows of one definition: plain data, made by [DiagramBuilder] and then read on any thread. It holds
 * no PSI and no object of the document model. [root] is null when the file has no top-level machine.
 */
class DiagramModel(val root: DiagramMachine?, val details: Map<String, List<DetailRow>>, val stateCount: Int) {
    private val states: Map<String, DiagramState>
    private val edges: Map<String, DiagramEdge>

    /** Every state and edge ID to its offset in the file. */
    val offsets: Map<String, Int>

    init {
        val allStates = LinkedHashMap<String, DiagramState>()
        val allEdges = LinkedHashMap<String, DiagramEdge>()
        fun visit(machine: DiagramMachine) {
            for (edge in machine.edges) allEdges[edge.id] = edge
            for (state in machine.states) {
                allStates[state.id] = state
                state.machines.forEach(::visit)
            }
        }
        root?.let(::visit)
        states = allStates
        edges = allEdges
        offsets = buildMap {
            for (state in allStates.values) put(state.id, state.offset)
            for (edge in allEdges.values) put(edge.id, edge.offset)
        }
    }

    fun state(id: String): DiagramState? = states[id]

    fun edge(id: String): DiagramEdge? = edges[id]

    /** The ID of the innermost state whose range holds [offset], or null. */
    fun stateAt(offset: Int): String? {
        var best: DiagramState? = null
        for (state in states.values) {
            if (!state.range.containsOffset(offset)) continue
            if (best == null || state.range.length < best.range.length) best = state
        }
        return best?.id
    }
}

/** A state machine of the diagram; [title] is "Branch n" for a Parallel branch, else null; [start] is the ID of the state `StartAt` names. */
class DiagramMachine(val id: String, val title: String?, val start: String?, val states: List<DiagramState>, val edges: List<DiagramEdge>)

/**
 * A state; [sub] is the second line of its node; [range] the text of its entry; [offset] the start of its key. [path]
 * identifies the state by its place and survives a rebuild of the model, which the [id] does not: the name for a state of
 * the top machine, else the path of the enclosing state, the index of the machine in that state and the name, joined by
 * [PATH_SEPARATOR].
 */
class DiagramState(
    val id: String,
    val path: String,
    val name: String,
    val type: String?,
    val sub: String,
    val terminal: Boolean,
    val offset: Int,
    val range: TextRange,
    val machines: List<DiagramMachine>,
)

/** A transition that leads to a state of the same machine; [from] and [to] are state IDs. */
class DiagramEdge(
    val id: String,
    val kind: TransitionKind,
    val from: String,
    val to: String,
    val label: String?,
    val title: String?,
    val offset: Int,
)

/** One row of the details panel. */
class DetailRow(val label: String, val items: List<DetailItem>)

class DetailItem(val text: String, val prefix: String = "", val kind: Kind = Kind.PLAIN, val offset: Int? = null) {
    enum class Kind { PLAIN, STATE, VARIABLE }
}

/** The character between the parts of a [DiagramState.path]; a state name cannot hold it in a definition that is read. */
const val PATH_SEPARATOR = "\u0000"

object DiagramBuilder {
    private const val BRANCH = "Branch "
    private const val SEPARATOR = " · "

    /**
     * Whether the diagram can follow the file: false for no model, a top level that is not a mapping, a value of `States`
     * that is not a mapping, and a syntax error in the file itself (an error inside a JSONata expression does not count).
     * Called in a read action.
     */
    fun readable(doc: AslDocument?): Boolean {
        val root = doc?.root ?: return false
        if (root.statesEntry?.value !is AslObject) return false
        return !PsiTreeUtil.hasErrorElements(doc.file)
    }

    /** Called in a read action; the result holds no PSI. Never throws on a half-typed file. */
    fun build(doc: AslDocument): DiagramModel {
        val root = doc.root ?: return DiagramModel(null, emptyMap(), 0)
        val stateIds = IdentityHashMap<AslState, String>()
        doc.allStates().forEachIndexed { i, s -> stateIds[s] = "s$i" }
        val machineIds = IdentityHashMap<AslStateMachine, String>()
        doc.allMachines().forEachIndexed { i, m -> machineIds[m] = "m$i" }
        val builder = Builder(stateIds, machineIds)
        val variables = DiagramDetails.Variables.of(doc)
        val details = doc.allStates().associate { stateIds.getValue(it) to DiagramDetails.rows(variables, it) }
        return DiagramModel(builder.machine(root, null, null), details, doc.allStates().size)
    }

    /**
     * The state name a transition target or `StartAt` [scalar] stands for, or null: only a string that is not dynamic
     * (`!Ref B`) and holds no placeholder (`${Target}`) names a state, as the rest is settled at deployment.
     */
    internal fun settledName(scalar: AslScalar?): String? =
        scalar?.takeIf { it.type == ScalarType.STRING && !it.dynamic && !Placeholders.contains(it.text) }?.text

    /** Whether the state ends the machine: a Succeed or Fail state, or `End: true` written as a certain boolean. */
    internal fun isTerminal(state: AslState): Boolean {
        // a type with a deployment tag has no resolved type and decides nothing
        if (state.type?.terminal == true) return true
        val end = state.obj?.get("End")?.value as? AslScalar ?: return false
        return end.type == ScalarType.BOOLEAN && !end.ambiguous && !end.dynamic && end.text.equals("true", ignoreCase = true)
    }

    private class Builder(val stateIds: IdentityHashMap<AslState, String>, val machineIds: IdentityHashMap<AslStateMachine, String>) {
        /** [owner] is the path of the state that holds [machine], index [index] of its machines, or null for the top machine. */
        fun machine(machine: AslStateMachine, title: String?, owner: String?, index: Int = 0): DiagramMachine {
            val prefix = owner?.let { it + PATH_SEPARATOR + index + PATH_SEPARATOR }.orEmpty()
            val states = machine.states.map { state(it, prefix) }
            val edges = machine.states.flatMap { edges(it) }
            val startName = settledName(machine.startAt?.value as? AslScalar)
            val start = startName?.let { machine.state(it) }?.let { stateIds[it] }
            return DiagramMachine(machineIds.getValue(machine), title, start, states, edges)
        }

        private fun state(state: AslState, prefix: String): DiagramState {
            val parallel = state.type?.structure == StructureId.PARALLEL
            val path = prefix + state.name
            val machines = state.children.mapIndexed { i, child -> machine(child, if (parallel) "$BRANCH${i + 1}" else null, path, i) }
            val range = state.entry.entryPsi.textRange
            return DiagramState(
                stateIds.getValue(state), path, state.name, state.type?.name, sub(state), isTerminal(state),
                state.entry.keyPsi.textRange.startOffset, range, machines,
            )
        }

        private fun sub(state: AslState): String {
            val type = state.typeName ?: return ""
            if (type != "Task") return type
            val resource = (state.obj?.get("Resource")?.value as? AslScalar)
                ?.takeIf { it.type == ScalarType.STRING && !it.ambiguous && !it.dynamic } ?: return type
            return type + SEPARATOR + DiagramLabels.resource(resource.text)
        }

        private fun edges(state: AslState): List<DiagramEdge> {
            val from = stateIds.getValue(state)
            val range = state.entry.entryPsi.textRange
            return state.transitions.mapNotNull { t ->
                val target = settledName(t.target)?.let { state.machine.state(it) } ?: return@mapNotNull null
                val label: DiagramLabels.Label? = when (t.kind) {
                    TransitionKind.NEXT -> null
                    TransitionKind.DEFAULT -> DiagramLabels.Label("Default", null)
                    TransitionKind.CHOICE -> DiagramLabels.rule(t.owner, t.index, state.queryLanguage)
                    TransitionKind.CATCH -> DiagramLabels.catcher(t.owner)
                }
                DiagramEdge(from + suffix(t), t.kind, from, stateIds.getValue(target), label?.text, label?.title, offset(state, t, range))
            }
        }

        private fun suffix(t: AslTransition) = when (t.kind) {
            TransitionKind.NEXT -> ".n"
            TransitionKind.DEFAULT -> ".d"
            TransitionKind.CHOICE -> ".c${t.index}"
            TransitionKind.CATCH -> ".k${t.index}"
        }

        /** The first place that lies inside the state's own entry: see the task's rules; a shared list or alias lies elsewhere. */
        private fun offset(state: AslState, t: AslTransition, range: TextRange): Int {
            val candidates: List<PsiElement?> = when (t.kind) {
                TransitionKind.NEXT -> listOf(state.obj?.get("Next")?.keyPsi)
                TransitionKind.DEFAULT -> listOf(state.obj?.get("Default")?.keyPsi)
                TransitionKind.CHOICE -> listOf(t.owner.usePsi, state.obj?.get("Choices")?.keyPsi)
                TransitionKind.CATCH -> listOf(t.owner.usePsi, state.obj?.get("Catch")?.keyPsi)
            }
            for (c in candidates) {
                val start = c?.textRange?.startOffset ?: continue
                if (range.containsOffset(start)) return start
            }
            return state.entry.keyPsi.textRange.startOffset
        }
    }
}
