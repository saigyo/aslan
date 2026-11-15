package io.github.saigyo.aslan.check

import com.intellij.psi.util.PsiTreeUtil
import io.github.saigyo.aslan.jsonata.psi.JsonataCall
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import io.github.saigyo.aslan.model.AslAssignment
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslVariable
import io.github.saigyo.aslan.model.JsonataRead
import io.github.saigyo.aslan.model.PathRead
import io.github.saigyo.aslan.refs.variableNameProblem

/**
 * The checks of the state machine variables, on the model of [AslDocument.variables], held to the answers of AWS
 * rounds 11 to 14. Where the file does not say every name a read can reach or which way a chain goes (`isOpen`), nothing is
 * concluded about the reads there, nor about whether an assignment is read; a name and an assignment of an outer name
 * are judged by what the file says. A read is reported on its host with the range of `$name`, an assignment on its key.
 */
internal object VariableChecks {
    fun check(doc: AslDocument, out: MutableList<AslFinding>) = Checks(doc, out).run()

    /** The longest name AWS accepts, in code points (`r11-a-len-80`, `r11-a-len-81`). */
    private const val MAX_NAME_LENGTH = 80

    /**
     * Whether AWS rejects [name], in a state of any query language, for the classes probed in rounds 11 and 12 only: a
     * first character that is an ASCII digit or `_`, a `.`, `-` or space, more than 80 code points
     * (`INVALID_VARIABLE_NAME`), and `states` (`RESERVED_VARIABLE_NAME`).
     */
    private fun rejectedName(name: String): Boolean {
        val first = name.firstOrNull() ?: return false
        return first in '0'..'9' || first == '_' || name.any { it == '.' || it == '-' || it == ' ' } ||
            name.codePointCount(0, name.length) > MAX_NAME_LENGTH || name == "states"
    }

    private class Checks(doc: AslDocument, val out: MutableList<AslFinding>) {
        private val model = doc.variables

        /** Whether a machine of the file does not say every name assigned in it or which way its chain goes. */
        private val fileOpen = doc.allMachines().any { model.isOpen(it) }

        private val outerAssignments = OuterAssignments(model.assignments)
        private val mentioned = HashMap<String, Boolean>()

        fun run() {
            for (read in model.reads) {
                if (model.variableOf(read) != null) continue
                when (read) {
                    is PathRead -> unassignedPathRead(read)
                    is JsonataRead -> unassignedJsonataRead(read)
                }
            }
            for (assignment in model.assignments) {
                val name = assignment.name
                val nameProblem = variableNameProblem(name)
                if (nameProblem != null) {
                    if (rejectedName(name)) {
                        report(AslProblem.INVALID_VARIABLE_NAME, assignment, "'$name' is no valid variable name: $nameProblem")
                    } else {
                        report(AslProblem.UNUSUAL_VARIABLE_NAME, assignment, "'$name' is no plain variable name: $nameProblem")
                    }
                    continue
                }
                if (outerAssignments.before(assignment.state.machine) { it.name == name }) {
                    report(AslProblem.OUTER_VARIABLE_ASSIGNED, assignment,
                        "\$$name is already assigned in an outer state machine before this one runs")
                }
                val variable = model.variableOf(assignment)
                if (neverRead(variable)) report(AslProblem.VARIABLE_NEVER_READ, assignment, "\$$name is assigned but never read")
            }
        }

        /**
         * A JSONPath read without a variable is an error: AWS rejects the definition (`UNDEFINED_VARIABLE`) in every
         * relation probed where the model finds no variable (assigned nowhere, only inside a Map or a Parallel branch,
         * only in another branch, only outside a Distributed Map).
         */
        private fun unassignedPathRead(read: PathRead) {
            if (model.isOpen(read.state)) return
            out += AslFinding(AslProblem.PATH_VARIABLE_NOT_ASSIGNED, read.scalar.psi,
                "\$${read.name} is not defined where this state can read it", read.range)
        }

        /**
         * Whether [read] stands inside the processor of a certain Distributed Map and a machine around that Map assigns
         * its name where that applies before the flow enters the Map ([OuterAssignments]; `r11-j-distributed-outer`).
         * The chain of a machine ends after such a processor and nowhere else but at the top level.
         */
        private fun outsideDistributedMap(read: JsonataRead): Boolean {
            val processor = model.chain(read.state.machine).lastOrNull()?.takeIf { it.owner != null } ?: return false
            // an assignment in a state that holds the processor does not count here, not even through a loop (round 14
            // asked only about the duplicate name): the read stays the warning of an unassigned read
            return outerAssignments.before(processor, holderCounts = false) { it.name == read.name }
        }

        /**
         * A JSONata read without a variable, unless the expression is not judged or has a syntax error (what it reads once
         * complete is not known), or the read is the callee of a call: that stays a function call, which the check of
         * unknown functions covers (decision 2). AWS only warns about it (`POSSIBLY_UNDEFINED_VARIABLE`), apart from a
         * read of an outer name inside a Distributed Map, which it rejects.
         */
        private fun unassignedJsonataRead(read: JsonataRead) {
            if (model.isOpen(read.state)) return
            val file = read.variable.containingFile as? JsonataFile ?: return
            if (!JsonataPsi.judged(file) || PsiTreeUtil.hasErrorElements(file)) return
            val call = read.variable.parent as? JsonataCall
            if (call != null && call.callee === read.variable) return
            val host = read.site.scalar.psi
            val range = JsonataPsi.rangeInHost(file, read.variable.textRange)
                ?.takeIf { it.first == host && !it.second.isEmpty && it.second.endOffset <= host.textLength }?.second
            out += if (outsideDistributedMap(read)) {
                AslFinding(AslProblem.DISTRIBUTED_MAP_OUTER_READ, host,
                    "\$${read.name} is assigned only outside this Distributed Map, whose states can read only the variables assigned inside it", range)
            } else {
                AslFinding(AslProblem.VARIABLE_NOT_ASSIGNED, host, notAssigned(read.name), range)
            }
        }

        /**
         * Whether [variable] has no read, and no doubt about it (decision 9): no machine of the file is open, and no
         * string of the file mentions `$name` beyond the reads the model knows.
         */
        private fun neverRead(variable: AslVariable): Boolean {
            if (model.readsOf(variable).isNotEmpty() || fileOpen) return false
            return !mentioned.getOrPut(variable.name) { model.unknownMentions(variable.name).isNotEmpty() }
        }

        private fun notAssigned(name: String) = "\$$name is not assigned in this state machine or in one whose variables it can read"

        private fun report(problem: AslProblem, assignment: AslAssignment, message: String) {
            out += AslFinding(problem, assignment.entry.keyPsi, message)
        }
    }
}
