package io.github.saigyo.aslan.check

import io.github.saigyo.aslan.model.AslAssignment
import io.github.saigyo.aslan.model.AslStateMachine
import io.github.saigyo.aslan.model.AssignPlace
import io.github.saigyo.aslan.model.TransitionKind

/**
 * Which assignments in the machines around a nested state machine apply before the flow enters it, as AWS judges
 * "already defined in a parent scope" (rounds 12 to 14). For a machine E around the nested one, S is the state of E
 * that holds it (directly or through further nesting). An assignment Y in E applies before S when the flow leads from
 * it to S, along any transitions of E (`Catch` included, so a loop counts), starting from:
 * - for the `Assign` of a state: its `Next` and its `Default` only. The `Assign` does not apply when the state fails,
 *   and AWS documents that a Choice state's own `Assign` applies only on the way to its `Default`;
 * - for the `Assign` of a Catcher or a Choice rule: its own `Next`.
 *
 * An assignment in S itself (its own `Assign`, its Catchers) applies after S, and so before S only when the flow leads
 * from it back to S, by the same rule, S itself as a target included (round 14: `r14-o-owner-loop`,
 * `r14-o-owner-self-loop`, `r14-o-catcher-loop`, `r14-o-parallel-owner-loop`). A machine E whose flow is not known
 * ([hasUnknownFlow]) cannot tell the order: an assignment there that matches makes the answer [Order.UNKNOWN], which
 * the checks treat as no assignment before and the rename as a possible one. Used by the outer-assignment check, the
 * check of outer reads inside a Distributed Map, and the rename.
 */
internal class OuterAssignments(assignments: List<AslAssignment>) {
    private val byMachine: Map<AslStateMachine, List<AslAssignment>> = assignments.groupBy { it.state.machine }
    private val flowUnknown = HashMap<AslStateMachine, Boolean>()

    /** Whether an assignment applies before the flow enters a nested machine, as far as the file tells. */
    enum class Order { BEFORE, NOT_BEFORE, UNKNOWN }

    /** Whether the answer of [order] is [Order.BEFORE]: what the checks report. */
    fun before(machine: AslStateMachine, holderCounts: Boolean = true, matches: (AslAssignment) -> Boolean): Boolean =
        order(machine, holderCounts, matches) == Order.BEFORE

    /**
     * Whether a machine around [machine], at any level, has an assignment that [matches] and that applies before the
     * flow enters the state of that machine that holds [machine]: [Order.BEFORE] at the first level where one does,
     * else [Order.UNKNOWN] when a level whose flow is not known has a matching assignment, else [Order.NOT_BEFORE].
     * Without [holderCounts], an assignment in that state itself never counts, loop or not: no probe asks whether a
     * read inside a Distributed Map sees it, so the check of such reads keeps to what rounds 11 to 13 answered.
     */
    fun order(machine: AslStateMachine, holderCounts: Boolean = true, matches: (AslAssignment) -> Boolean): Order {
        var unknown = false
        var inner = machine
        while (true) {
            val holder = inner.owner ?: return if (unknown) Order.UNKNOWN else Order.NOT_BEFORE
            val outer = holder.machine
            val candidates = byMachine[outer].orEmpty().filter { (holderCounts || it.state !== holder) && matches(it) }
            if (candidates.isNotEmpty()) {
                if (flowUnknown.getOrPut(outer) { outer.hasUnknownFlow() }) unknown = true
                else if (candidates.any { reaches(outer, entered(it), holder.name) }) return Order.BEFORE
            }
            inner = outer
        }
    }

    /** The states the flow enters once [assignment] has applied. */
    private fun entered(assignment: AslAssignment): List<String> = assignment.state.transitions.filter { t ->
        when (assignment.place) {
            AssignPlace.STATE -> t.kind == TransitionKind.NEXT || t.kind == TransitionKind.DEFAULT
            AssignPlace.CATCHER -> t.kind == TransitionKind.CATCH && t.index == assignment.index
            AssignPlace.CHOICE_RULE -> t.kind == TransitionKind.CHOICE && t.index == assignment.index
        }
    }.mapNotNull { it.targetName }

    /** Whether the flow of [machine] reaches the state [target] from [from], along any transitions, `Catch` included. */
    private fun reaches(machine: AslStateMachine, from: List<String>, target: String): Boolean {
        val reached = HashSet<String>()
        val queue = ArrayDeque(from)
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name == target) return true
            if (!reached.add(name)) continue
            val state = machine.state(name) ?: continue
            for (t in state.transitions) t.targetName?.let(queue::addLast)
        }
        return false
    }
}
