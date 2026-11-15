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
 * The checks of the state machine variables, on the model of [AslDocument.variables]. Where the file does not say
 * every name a read can reach or which way a chain goes (`isOpen`), nothing is concluded about the reads and
 * assignments there. A read is reported on its host with the range of `$name`, an assignment on its key.
 */
internal object VariableChecks {
    fun check(doc: AslDocument, out: MutableList<AslFinding>) = Checks(doc, out).run()

    private class Checks(doc: AslDocument, val out: MutableList<AslFinding>) {
        private val model = doc.variables

        /** Whether a machine of the file does not say every name assigned in it or which way its chain goes. */
        private val fileOpen = doc.allMachines().any { model.isOpen(it) }

        private val assignedNames = model.assignments.mapTo(HashSet()) { it.name }
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
                if (model.isOpen(assignment.state)) continue
                val nameProblem = variableNameProblem(assignment.name)
                if (nameProblem != null) {
                    report(AslProblem.INVALID_VARIABLE_NAME, assignment, "'${assignment.name}' is no variable name: $nameProblem")
                    continue
                }
                val variable = model.variableOf(assignment)
                if (variable.scope != assignment.state.machine) {
                    report(AslProblem.OUTER_VARIABLE_ASSIGNED, assignment,
                        "\$${assignment.name} is a variable of an outer state machine, which a state of a nested one cannot assign")
                }
                if (neverRead(variable)) report(AslProblem.VARIABLE_NEVER_READ, assignment, "\$${assignment.name} is assigned but never read")
            }
        }

        /**
         * A JSONPath read of a name that no machine assigns, in a file with no open machine, is an error: AWS rejects the
         * definition (`UNDEFINED_VARIABLE`). A name assigned only where the read cannot reach is a warning.
         */
        private fun unassignedPathRead(read: PathRead) {
            when {
                !fileOpen && read.name !in assignedNames ->
                    out += AslFinding(AslProblem.PATH_VARIABLE_NOT_ASSIGNED, read.scalar.psi,
                        "\$${read.name} is not defined: no Assign in the definition assigns it", read.range)
                !model.isOpen(read.state) ->
                    out += AslFinding(AslProblem.VARIABLE_NOT_ASSIGNED, read.scalar.psi, notAssigned(read.name), read.range)
            }
        }

        /**
         * A JSONata read without a variable, unless the expression is not judged or has a syntax error (what it reads once
         * complete is not known), or the read is the callee of a call: that stays a function call, which the check of
         * unknown functions covers (decision 2).
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
            out += AslFinding(AslProblem.VARIABLE_NOT_ASSIGNED, host, notAssigned(read.name), range)
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
