package io.github.saigyo.aslan.check

import io.github.saigyo.aslan.check.AslProblem.DUPLICATE_STATE_NAME
import io.github.saigyo.aslan.check.AslProblem.MISSING_TRANSITION_TARGET
import io.github.saigyo.aslan.check.AslProblem.NO_TERMINAL_STATE
import io.github.saigyo.aslan.check.AslProblem.STATE_NAME_TOO_LONG
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslState
import io.github.saigyo.aslan.model.AslStateMachine
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.Placeholders
import io.github.saigyo.aslan.tree.ScalarType

/** AWS limit on the length of a state name, in Unicode code points. */
private const val MAX_STATE_NAME_CODE_POINTS = 80

/** A string without placeholder that is certainly meant as a state name; null for anything the file leaves uncertain. */
private fun certainName(scalar: AslScalar?): String? =
    scalar?.takeIf { it.type == ScalarType.STRING && !it.ambiguous && !Placeholders.contains(it.text) }?.text

/** The checks of transitions and state names whose failure AWS documents as a rejection. */
internal object TransitionChecks {
    fun check(doc: AslDocument, out: MutableList<AslFinding>) {
        for (machine in doc.allMachines()) {
            if (machine.open) continue
            startAt(machine, out)
            targets(machine, out)
            noTerminal(machine, out)
        }
        names(doc, out)
    }

    private fun startAt(machine: AslStateMachine, out: MutableList<AslFinding>) {
        val value = machine.startAt?.value as? AslScalar ?: return
        val name = certainName(value) ?: return
        if (machine.state(name) == null) out += AslFinding(MISSING_TRANSITION_TARGET, value.psi, "No state named '$name' in this state machine")
    }

    private fun targets(machine: AslStateMachine, out: MutableList<AslFinding>) {
        for (state in machine.states) {
            if (state.entry.viaMerge) continue
            for (transition in state.transitions) {
                val target = transition.target ?: continue
                val name = certainName(target) ?: continue
                if (machine.state(name) == null) out += AslFinding(MISSING_TRANSITION_TARGET, target.psi, "No state named '$name' in this state machine")
            }
        }
    }

    private fun noTerminal(machine: AslStateMachine, out: MutableList<AslFinding>) {
        val key = machine.statesEntry?.keyPsi ?: return
        if (machine.states.isEmpty()) return
        for (state in machine.states) {
            val type = state.type ?: return
            if (type.terminal) return
            val o = state.obj ?: return
            if (StructureChecks.uncertain(o) || StructureChecks.mayEnd(o["End"])) return
        }
        out += AslFinding(NO_TERMINAL_STATE, key, "No state ends the state machine")
    }

    private fun names(doc: AslDocument, out: MutableList<AslFinding>) {
        val states = doc.allStates().filter { !it.entry.viaMerge && !Placeholders.contains(it.name) }.distinctBy { it.entry.entryPsi }
        val counts = states.groupingBy { it.name }.eachCount()
        for (state in states) {
            if (counts.getValue(state.name) > 1) out += AslFinding(DUPLICATE_STATE_NAME, state.entry.keyPsi, "State name '${state.name}' is used more than once")
            if (state.name.codePointCount(0, state.name.length) > MAX_STATE_NAME_CODE_POINTS) {
                out += AslFinding(STATE_NAME_TOO_LONG, state.entry.keyPsi, "A state name has at most $MAX_STATE_NAME_CODE_POINTS characters")
            }
        }
    }
}

/**
 * True when the file does not say enough to judge which states of this machine can be reached: the machine is open,
 * `StartAt` or any transition target is missing, a placeholder, uncertain or names no state, or a state's way on is
 * not fully known (no `Next` or `End: true`, an uncertain `End`, a state that is not a mapping, an unapplied merge
 * or a placeholder key, or `Choices` or `Catch` that cannot be read as lists of fully known mappings).
 */
internal fun AslStateMachine.hasUnknownFlow(): Boolean {
    if (open) return true
    val start = startAt?.value as? AslScalar ?: return true
    val startName = certainName(start)
    if (startName == null || state(startName) == null) return true
    return states.any { stateFlowUnknown(it) }
}

private fun AslStateMachine.stateFlowUnknown(state: AslState): Boolean {
    for (t in state.transitions) {
        val target = t.target ?: return true
        val name = certainName(target) ?: return true
        if (state(name) == null) return true
    }
    val o = state.obj ?: return true
    if (StructureChecks.uncertain(o)) return true
    for (key in listOf("Choices", "Catch")) {
        val value = o[key]?.value ?: continue
        if (value !is AslArray || value.items.any { it !is AslObject || StructureChecks.uncertain(it) }) return true
    }
    val type = state.type
    if (type == null || !type.needsTransition || o["Next"] != null) return false
    val end = o["End"]?.value as? AslScalar ?: return true
    return !(end.type == ScalarType.BOOLEAN && !end.ambiguous && end.text.equals("true", ignoreCase = true))
}
