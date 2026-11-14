package io.github.saigyo.aslan.model

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslEntry
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.Placeholders
import io.github.saigyo.aslan.tree.ScalarType

/** Where an `Assign` stands: in the state itself, in one of its top-level Choice rules or in one of its Catchers. */
enum class AssignPlace { STATE, CHOICE_RULE, CATCHER }

/**
 * A key directly under an `Assign` mapping, as used by [state]: [name] is the key without the `.$` suffix of a JSONPath
 * state. [index] is the position of the Choice rule or Catcher in its list, 0 for the state's own `Assign`.
 */
class AslAssignment(val name: String, val entry: AslEntry, val state: AslState, val place: AssignPlace, val index: Int) {
    /** "ReserveStock", "ReserveStock, second Choice rule", "ReserveStock, third Catcher"; ordinal words up to "tenth", then "11th" */
    val placeText: String
        get() = when (place) {
            AssignPlace.STATE -> state.name
            AssignPlace.CHOICE_RULE -> "${state.name}, ${ordinal(index + 1)} Choice rule"
            AssignPlace.CATCHER -> "${state.name}, ${ordinal(index + 1)} Catcher"
        }
}

private val ORDINAL_WORDS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")

/** "first" to "tenth", then "11th", "12th", "21st", "22nd", "23rd", "111th". */
internal fun ordinal(n: Int): String {
    if (n in 1..ORDINAL_WORDS.size) return ORDINAL_WORDS[n - 1]
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

/** A read of a state machine variable in [state]. [name] is without `$`, decoded from the host's escapes. */
sealed interface AslRead {
    val name: String
    val state: AslState
}

/** A `$name` in the JSONata expression of [site] that is no local variable, no built-in function and not `$states`. */
class JsonataRead(override val name: String, override val state: AslState, val variable: JsonataVariableRef, val site: JsonataSite) : AslRead

/** [range] is relative to `scalar.psi` and covers `$` and the name in the host text. */
class PathRead(override val name: String, override val state: AslState, val scalar: AslScalar, val range: TextRange) : AslRead

/** A state machine variable: its name and its owning `States` object (spec 6.4). */
data class AslVariable(val name: String, val scope: AslStateMachine)

/**
 * The assignments and reads of state machine variables in a definition, and the variable each one belongs to.
 *
 * The *chain* of a machine is the machine, then the machine of its owner state, and so on outward; it ends after a
 * machine owned by a Distributed Map, which cannot read outer variables. The owning scope of an assignment or read of a
 * name in a state is the outermost machine in the chain of the state's machine that assigns the name. A machine is
 * *open* when the file does not say every name assigned in it or which way its chain goes: its `States` is open, an
 * `Assign` in it is not a mapping or has a placeholder or unapplied merge key, a state, a top-level Choice rule or a
 * Catcher in it is not a mapping or leaves its fields open, or it belongs to a Map whose `Mode` is not settled.
 *
 * Everything is computed once, when the instance is made: after the model is built, since the reads need the injected
 * JSONata files.
 */
class AslVariables internal constructor(doc: AslDocument) {
    /** In file order; one per use through an alias or merge. */
    val assignments: List<AslAssignment>

    /** In file order; one per site, so an expression or string used through an alias has one read per use. */
    val reads: List<AslRead>

    private val chains = HashMap<AslStateMachine, List<AslStateMachine>>()
    private val assigned = HashMap<AslStateMachine, MutableSet<String>>()
    private val openMachines = HashSet<AslStateMachine>()
    private val visible = HashMap<AslStateMachine, Set<String>>()
    private val open = HashMap<AslStateMachine, Boolean>()
    private val assignmentsByEntry: Map<PsiElement, List<AslAssignment>>
    private val readsByVariable: Map<JsonataVariableRef, List<JsonataRead>>
    private val readsByHost: Map<PsiElement, List<PathRead>>
    private val assignmentsByIdentity: Map<AslVariable, List<AslAssignment>>
    private val readsByIdentity: Map<AslVariable, List<AslRead>>

    init {
        val machines = doc.allMachines()
        for (machine in machines) {
            if (machine.open || boundary(machine) == Boundary.UNKNOWN) openMachines += machine
        }
        val found = ArrayList<AslAssignment>()
        for (state in doc.allStates()) collectAssignments(state, found)
        assignments = found.sortedBy { it.entry.keyPsi.textRange.startOffset }
        for (assignment in assignments) assigned.getOrPut(assignment.state.machine) { HashSet() } += assignment.name
        for (machine in machines) {
            val chain = chainOf(machine)
            chains[machine] = chain
            visible[machine] = chain.flatMapTo(HashSet()) { assigned[it].orEmpty() }
            open[machine] = chain.any { it in openMachines }
        }
        reads = (jsonataReads(doc) + pathReads(doc)).sortedBy { offsetInFile(it) }

        assignmentsByEntry = assignments.groupBy { it.entry.entryPsi }
        readsByVariable = reads.filterIsInstance<JsonataRead>().groupBy { it.variable }
        readsByHost = reads.filterIsInstance<PathRead>().groupBy { it.scalar.psi }
        assignmentsByIdentity = assignments.groupBy { variableOf(it) }
        readsByIdentity = reads.mapNotNull { read -> variableOf(read)?.let { it to read } }.groupBy({ it.first }, { it.second })
    }

    fun variableOf(assignment: AslAssignment): AslVariable =
        AslVariable(assignment.name, owningScope(assignment.name, assignment.state.machine) ?: assignment.state.machine)

    /** The variable [read] reads; null when no machine in its reach assigns the name. */
    fun variableOf(read: AslRead): AslVariable? = owningScope(read.name, read.state.machine)?.let { AslVariable(read.name, it) }

    fun assignmentsOf(variable: AslVariable): List<AslAssignment> = assignmentsByIdentity[variable].orEmpty()

    fun readsOf(variable: AslVariable): List<AslRead> = readsByIdentity[variable].orEmpty()

    /** The assignments whose entry is [entryPsi] (an [AslEntry.entryPsi]), one per use through an alias or merge. */
    fun assignmentsAt(entryPsi: PsiElement): List<AslAssignment> = assignmentsByEntry[entryPsi].orEmpty()

    /** The reads that [variable] stands for, one per use of its expression. */
    fun readsAt(variable: JsonataVariableRef): List<JsonataRead> = readsByVariable[variable].orEmpty()

    /** The reads in the JSONPath string [host] (an [AslScalar.psi]), one per name and use. */
    fun pathReadsAt(host: PsiElement): List<PathRead> = readsByHost[host].orEmpty()

    /** The names assigned in the state's machine or in one its reads can reach. */
    fun visibleNames(state: AslState): Set<String> = visible[state.machine].orEmpty()

    /** Whether the file does not say every name a read in [state] can reach. */
    fun isOpen(state: AslState): Boolean = open[state.machine] == true

    private fun owningScope(name: String, machine: AslStateMachine): AslStateMachine? =
        chains[machine].orEmpty().lastOrNull { name in assigned[it].orEmpty() }

    private fun chainOf(machine: AslStateMachine): List<AslStateMachine> = buildList {
        var current: AslStateMachine? = machine
        while (current != null) {
            add(current)
            if (boundary(current) == Boundary.DISTRIBUTED) break
            current = current.owner?.machine
        }
    }

    /** The assignments of [state], its top-level Choice rules and its Catchers; marks the machine open where they leave names unsaid. */
    private fun collectAssignments(state: AslState, into: MutableList<AslAssignment>) {
        val obj = state.obj
        if (obj == null || leavesFieldsOpen(obj)) openMachines += state.machine
        if (obj == null) return
        for (entry in obj.entries) {
            when (entry.key) {
                "Assign" -> assign(entry, state, AssignPlace.STATE, 0, into)
                "Choices" -> listed(entry, state, AssignPlace.CHOICE_RULE, into)
                "Catch" -> listed(entry, state, AssignPlace.CATCHER, into)
            }
        }
    }

    private fun listed(list: AslEntry, state: AslState, place: AssignPlace, into: MutableList<AslAssignment>) {
        val items = (list.value as? AslArray)?.items
        if (items == null) {
            openMachines += state.machine
            return
        }
        items.forEachIndexed { index, item ->
            if (item !is AslObject || leavesFieldsOpen(item)) openMachines += state.machine
            (item as? AslObject)?.get("Assign")?.let { assign(it, state, place, index, into) }
        }
    }

    private fun assign(assign: AslEntry, state: AslState, place: AssignPlace, index: Int, into: MutableList<AslAssignment>) {
        val mapping = assign.value as? AslObject
        if (mapping == null) {
            openMachines += state.machine
            return
        }
        val jsonPath = state.queryLanguage == QueryLanguage.JSONPATH
        for (entry in mapping.entries) {
            if (isUnappliedMerge(entry) || Placeholders.contains(entry.key)) {
                openMachines += state.machine
                continue
            }
            val name = if (jsonPath && entry.key.endsWith(".$")) entry.key.dropLast(2) else entry.key
            into += AslAssignment(name, entry, state, place, index)
        }
    }

    /**
     * The `$name`s of each JSONata site that no scope of the expression declares, except `$states` and a built-in
     * function whose name no assignment makes visible in the site's state (decision 2).
     */
    private fun jsonataReads(doc: AslDocument): List<JsonataRead> {
        val out = ArrayList<JsonataRead>()
        for (site in doc.jsonataSites.all) {
            val file = JsonataPsi.injectedFile(site.scalar.psi) ?: continue
            for (variable in JsonataScopes.variables(file)) {
                val name = JsonataScopes.localName(variable) ?: continue
                if (name == "states" || JsonataScopes.declarations(variable).isNotEmpty()) continue
                if (AslSpec.jsonataFunction(name) != null && name !in visibleNames(site.state)) continue
                out += JsonataRead(name, site.state, variable, site)
            }
        }
        return out
    }

    /** The `$name`s of the strings JSONPath evaluates, with their range in the host text. */
    private fun pathReads(doc: AslDocument): List<PathRead> {
        val out = ArrayList<PathRead>()
        for ((scalar, state) in JsonPathReads.sites(doc)) {
            val names = JsonPathReads.names(scalar.text)
            if (names.isEmpty()) continue
            val ranges = hostRanges(scalar, names) ?: continue
            names.forEachIndexed { i, name -> out += PathRead(name.name, state, scalar, ranges[i]) }
        }
        return out
    }

    /**
     * The range of each of [names] in the host text of [scalar], through the host's escaper over the value's range
     * (inside the quotes; a JSON string's escaper reports the whole literal as relevant). The escaper of a YAML scalar
     * that spans lines keeps the line breaks the model folds, so offsets in the model's text are carried over by the
     * position among the characters that are not whitespace, which folding leaves alone. Null when the two texts
     * differ in more than whitespace.
     */
    private fun hostRanges(scalar: AslScalar, names: List<PathName>): List<TextRange>? {
        val host = scalar.psi as? PsiLanguageInjectionHost ?: return null
        val escaper = host.createLiteralTextEscaper()
        val inside = ElementManipulators.getValueTextRange(host)
        val builder = StringBuilder()
        if (!escaper.decode(inside, builder)) return null
        val decoded = builder.toString()
        val toDecoded: (Int) -> Int = if (decoded == scalar.text) { offset -> offset } else {
            val fromModel = solidOffsets(scalar.text)
            val inDecoded = solidOffsets(decoded)
            if (fromModel.map { scalar.text[it] } != inDecoded.map { decoded[it] }) return null
            { offset -> inDecoded[fromModel.binarySearch(offset)] }
        }
        return names.map { name ->
            // a name starts with `$` and ends with a letter, digit or `_`, so both ends are characters that are not whitespace
            val start = escaper.getOffsetInHost(toDecoded(name.start), inside)
            val end = escaper.getOffsetInHost(toDecoded(name.end - 1) + 1, inside)
            if (start < 0 || end < start) return null
            TextRange(start, end)
        }
    }

    private fun solidOffsets(text: String): List<Int> = text.indices.filter { !text[it].isWhitespace() }

    private fun offsetInFile(read: AslRead): Int = when (read) {
        is JsonataRead -> InjectedLanguageManager.getInstance(read.variable.project).injectedToHost(read.variable, read.variable.textRange.startOffset)
        is PathRead -> read.scalar.psi.textRange.startOffset + read.range.startOffset
    }

    private enum class Boundary { NONE, DISTRIBUTED, UNKNOWN }

    /**
     * Whether [machine] is the processor of a Distributed Map (decision 4): `ProcessorConfig.Mode` is the string
     * `DISTRIBUTED`. A missing `ProcessorConfig` or `Mode` means an inline Map, unless a placeholder or unapplied merge
     * key may supply it; any other `Mode` (a placeholder, a tag, a value of the wrong kind) leaves it unknown.
     */
    private fun boundary(machine: AslStateMachine): Boundary {
        if (machine.owner?.type?.structure != StructureId.MAP) return Boundary.NONE
        val configEntry = machine.obj["ProcessorConfig"] ?: return if (leavesFieldsOpen(machine.obj)) Boundary.UNKNOWN else Boundary.NONE
        val config = configEntry.value as? AslObject ?: return Boundary.UNKNOWN
        val mode = config["Mode"] ?: return if (leavesFieldsOpen(config)) Boundary.UNKNOWN else Boundary.NONE
        val scalar = mode.value as? AslScalar
        if (scalar == null || scalar.type != ScalarType.STRING || scalar.ambiguous || scalar.dynamic) return Boundary.UNKNOWN
        return when (scalar.text) {
            "DISTRIBUTED" -> Boundary.DISTRIBUTED
            "INLINE" -> Boundary.NONE
            else -> Boundary.UNKNOWN
        }
    }
}
