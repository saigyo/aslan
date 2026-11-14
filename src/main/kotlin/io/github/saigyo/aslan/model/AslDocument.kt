package io.github.saigyo.aslan.model

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.spec.StateTypeSpec
import io.github.saigyo.aslan.tree.AslEntry
import io.github.saigyo.aslan.tree.AslNode
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.AslUnresolved
import io.github.saigyo.aslan.tree.Placeholders
import io.github.saigyo.aslan.tree.ScalarType

/**
 * The typed view of one ASL file: its state machines, states and transitions. [rootNode] is the value tree as read;
 * [root] is null when that is not a mapping. [queryLanguage] is the language the top-level `QueryLanguage` sets
 * (JSONPath when absent); [queryLanguageKnown] is false when that field is present but names neither language exactly,
 * and [queryLanguage] then holds the fallback.
 */
class AslDocument(
    val file: PsiFile,
    val rootNode: AslNode?,
    val root: AslStateMachine?,
    val schema: SchemaIndex,
    val queryLanguage: QueryLanguage,
    val queryLanguageKnown: Boolean,
) {
    private val machines: List<AslStateMachine> by lazy { root?.let { collectMachines(it, ArrayList()) } ?: emptyList() }
    private val states: List<AslState> by lazy { root?.let { collectStates(it, ArrayList()) } ?: emptyList() }
    private val statesByEntry: Map<PsiElement, AslState> by lazy {
        HashMap<PsiElement, AslState>().also { map -> for (s in states) map.putIfAbsent(s.entry.entryPsi, s) }
    }

    /** The root machine first, then the nested ones, depth first in file order. */
    fun allMachines(): List<AslStateMachine> = machines

    /** Every state of every machine, each followed by the states of its nested machines. */
    fun allStates(): List<AslState> = states

    /** The innermost state whose entry contains [element], or null. */
    fun stateAt(element: PsiElement): AslState? {
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            statesByEntry[current]?.let { return it }
            current = current.parent
        }
        return null
    }

    private fun collectMachines(machine: AslStateMachine, into: MutableList<AslStateMachine>): List<AslStateMachine> {
        into += machine
        for (state in machine.states) for (child in state.children) collectMachines(child, into)
        return into
    }

    private fun collectStates(machine: AslStateMachine, into: MutableList<AslState>): List<AslState> {
        for (state in machine.states) {
            into += state
            for (child in state.children) collectStates(child, into)
        }
        return into
    }
}

/** A state machine: the top-level one, a Map state's item processor or a Parallel state's branch. */
class AslStateMachine(
    val obj: AslObject,
    /** The Map or Parallel state this machine belongs to; null for the top-level machine. */
    val owner: AslState?,
    val startAt: AslEntry?,
    val statesEntry: AslEntry?,
    val states: List<AslState>,
) {
    /** The first state named [name], or null. */
    fun state(name: String): AslState? = states.firstOrNull { it.name == name }

    /**
     * True when the file does not say which states the machine has: `States` is a mapping with a placeholder key or a
     * merge key that could not be applied, `States` is anything else (a placeholder, a tagged value, an alias that does
     * not resolve, a value of the wrong kind), or there is no `States` and [obj] leaves its own fields open.
     */
    val open: Boolean = when (val value = statesEntry?.value) {
        null -> leavesFieldsOpen(obj)
        is AslObject -> leavesFieldsOpen(value)
        else -> true
    }
}

/**
 * One state. [obj] is null when the state's value is not a mapping. [type] is null when [typeName] is missing or not
 * an ASL state type. [queryLanguage] is the effective query language; [queryLanguageKnown] is false when the
 * `QueryLanguage` that decides it names neither language exactly (a placeholder, a typo), and [queryLanguage] then
 * holds the fallback.
 */
class AslState(
    val name: String,
    val entry: AslEntry,
    val obj: AslObject?,
    val machine: AslStateMachine,
    val typeName: String?,
    val type: StateTypeSpec?,
    val queryLanguage: QueryLanguage,
    val queryLanguageKnown: Boolean,
    val children: List<AslStateMachine>,
    val transitions: List<AslTransition>,
)

enum class TransitionKind { NEXT, CHOICE, DEFAULT, CATCH }

/**
 * A transition from [from] to the state named by [target]; [target] is null when the rule or catcher has no `Next`
 * or its value is not a scalar. [owner] is the object holding the `Next` (the state, Choice rule or Catcher), and
 * [index] the position of that rule or catcher in its list (0 for the state's own `Next` and `Default`).
 */
class AslTransition(val kind: TransitionKind, val from: AslState, val target: AslScalar?, val owner: AslObject, val index: Int) {
    val targetName: String? get() = target?.takeIf { it.type == ScalarType.STRING }?.text
}

/** An entry with key `<<` that is not the result of an applied YAML merge; it is never a field or a state. */
internal fun isUnappliedMerge(entry: AslEntry): Boolean = entry.key == "<<" && !entry.viaMerge

/**
 * Whether the file leaves open which entries [o] has: a merge key that could not be applied or a key with a placeholder
 * may stand for any field or state, so nothing may be concluded from one being absent.
 */
internal fun leavesFieldsOpen(o: AslObject): Boolean = o.entries.any { isUnappliedMerge(it) || Placeholders.contains(it.key) }
