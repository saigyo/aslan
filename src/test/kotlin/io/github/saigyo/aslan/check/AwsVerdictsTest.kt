package io.github.saigyo.aslan.check

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeHighlighting.HighlightDisplayLevel
import java.io.File

/**
 * Compares the checks with what AWS answered. `src/test/testData/aws/verdicts.json` holds small definitions, each with
 * the result of `aws stepfunctions validate-state-machine-definition` recorded on 2026-11-14: `OK` means the definition
 * can be deployed, `FAIL` that it cannot.
 */
class AwsVerdictsTest : CheckTestCase() {
    private class Probe(val name: String, val accepted: Boolean, val diagnostics: List<String>, val definition: String)

    private val probes: List<Probe> by lazy {
        val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        JsonParser.parseString(File("src/test/testData/aws/verdicts.json").readText()).asJsonObject.entrySet().map { (name, value) ->
            val o = value as JsonObject
            Probe(name, o["aws"].asString == "OK", o["diagnostics"].asJsonArray.map { it.asString }, gson.toJson(o["definition"]))
        }
    }

    private companion object {
        const val PARENT = "the parent operator % is not resolved"
        const val ORDER = "the order of states is not modelled, so a read before the assignment in the flow is not found"

        val VARIABLE_PROBLEMS = setOf(
            AslProblem.PATH_VARIABLE_NOT_ASSIGNED, AslProblem.DISTRIBUTED_MAP_OUTER_READ, AslProblem.VARIABLE_NOT_ASSIGNED,
            AslProblem.VARIABLE_NEVER_READ, AslProblem.INVALID_VARIABLE_NAME, AslProblem.UNUSUAL_VARIABLE_NAME,
            AslProblem.OUTER_VARIABLE_ASSIGNED,
        )
    }

    private fun errors(probe: Probe): List<AslProblem> =
        AslChecker.findings(myFixture.configureByText("probe.asl.json", probe.definition)).map { it.problem }.filter { it.level == HighlightDisplayLevel.ERROR }

    /** Rejected definitions that the checks do not mark as an error, by name or by the start of the name, each with the reason. */
    private val gaps: Map<String, String> = mapOf(
        "r3-AH-" to "the parameters of a service call are not known to the plugin",
        "r2-F-empty-parameters-" to "the parameters of a service call are not known to the plugin",
        "r2-F-unknown-parameter-" to "the parameters of a service call are not known to the plugin",
        "r2-J-parameters" to "the parameters of a service call are not known to the plugin",
        "r3-AC-jsonata-arguments-key" to "the parameters of a service call are not known to the plugin",
        "r2-G-sdk-unknown-" to "the plugin has no list of the AWS SDK services and actions",
        "r1-W-integration-unknown" to "an unknown service integration is a warning, since the plugin's list can be behind AWS",
        "r2-G-lambda-sync" to "an unknown service integration is a warning, since the plugin's list can be behind AWS",
        "r3-W-state-machine" to "an unknown service integration is a warning, since the plugin's list can be behind AWS",
        "r3-W-states-other-kind" to "an unknown service integration is a warning, since the plugin's list can be behind AWS",
        "r8-t-region-account-unknown-integration" to "an unknown service integration is a warning, since the plugin's list can be behind AWS",
        "r3-AC-parameters-unknown-intrinsic" to "the name of an intrinsic function is not checked, so that a new one is never a false error",
        "r9-s-S0217-1" to PARENT, "r9-s-S0217-2" to PARENT, "r9-s-S0217-3" to PARENT, "r9-p-parent" to PARENT,
        "r9-s-S0401-1" to "function signatures are not parsed",
        "r9-p-context-unknown" to "the fields below \$states.context are not checked, since the plugin's list can be behind AWS",
        "r11-p-same-state" to ORDER, "r11-p-later" to ORDER,
    )

    /**
     * Each variable check is reported exactly on the probes of rounds 11 to 13 that AWS answers for that reason: an
     * error where AWS rejects the definition, a warning where it warns. A read that stands before its assignment in the
     * flow is not found (decision 3): rejected for JSONPath (named gaps), only warned about for JSONata (missed warnings).
     */
    fun `test the variable checks agree with AWS probe by probe`() {
        val round = probes.filter { p -> listOf("r11-", "r12-", "r13-").any { p.name.startsWith(it) } }
        assertEquals(45 + 11 + 6, round.size)
        fun answered(prefix: String, diagnostic: String) =
            round.filter { p -> p.name.startsWith(prefix) && p.diagnostics.any { it.startsWith(diagnostic) } }.map { it.name }.toSet()
        val missedWarnings = setOf("r11-j-same-state", "r11-j-later")
        val expected = round.associate { it.name to HashSet<AslProblem>() }
        fun expect(problem: AslProblem, names: Set<String>) = names.forEach { expected.getValue(it) += problem }

        expect(AslProblem.PATH_VARIABLE_NOT_ASSIGNED, answered("r11-p-", "ERROR UNDEFINED_VARIABLE") - gaps.keys)
        expect(AslProblem.DISTRIBUTED_MAP_OUTER_READ, answered("r11-j-", "ERROR UNDEFINED_VARIABLE"))
        expect(AslProblem.VARIABLE_NOT_ASSIGNED, answered("r11-j-", "WARNING POSSIBLY_UNDEFINED_VARIABLE") - missedWarnings)
        expect(AslProblem.INVALID_VARIABLE_NAME,
            answered("", "ERROR INVALID_VARIABLE_NAME") + answered("", "ERROR RESERVED_VARIABLE_NAME"))
        expect(AslProblem.OUTER_VARIABLE_ASSIGNED, answered("", "ERROR DUPLICATE_VARIABLE_NAME"))
        // A name assigned and read nowhere in the scope of the assignment: the inner assignment of the distributed
        // shadow; both branch assignments of the siblings; the assignments whose read AWS rejects or warns about
        // because it stands outside their reach (inner, after, sibling, outer of a Distributed Map). The invalid
        // names get only the name finding, and a read before the assignment in the flow is still a read. Rounds 12 and
        // 13 read no variable at all, so every probe of theirs with a valid name assigns one that is never read.
        expect(AslProblem.VARIABLE_NEVER_READ, setOf("r11-a-distributed-shadow", "r11-a-siblings", "r12-p-shadow-before") +
            listOf("p", "j").flatMap { q ->
                listOf("inline-map-inner", "distributed-inner", "distributed-outer", "parallel-after", "parallel-sibling").map { "r11-$q-$it" }
            } + round.map { it.name }.filter { it.startsWith("r12-o-") || it.startsWith("r13-o-") })

        assertEquals(setOf("r11-j-distributed-outer"), answered("r11-j-", "ERROR UNDEFINED_VARIABLE"))
        assertEquals(7 + 4, round.count { AslProblem.INVALID_VARIABLE_NAME in expected.getValue(it.name) })
        assertEquals(3 + 1 + 4, round.count { AslProblem.OUTER_VARIABLE_ASSIGNED in expected.getValue(it.name) })
        // an outer assignment after the Map or Parallel, in its own Assign, in its Catcher, on a path only its Catcher
        // takes, or on another Choice rule's path is no outer assignment
        assertEquals(6 + 2, round.count { it.name.matches(Regex("r1[23]-o-.*")) && it.accepted && it.diagnostics.isEmpty() })
        val reported = round.associate { p -> p.name to problems(p.name).filterTo(HashSet()) { it in VARIABLE_PROBLEMS } }
        fun shown(m: Map<String, Set<AslProblem>>) = m.mapValues { it.value.toSortedSet() }.toSortedMap().toString().replace("], ", "]\n")
        assertEquals(shown(expected), shown(reported))

        // stage 1's check of fields that only a top-level Choice rule takes covers an Assign in a nested rule
        assertTrue(AslProblem.MISPLACED_FIELD in problems("r11-a-nested-choice"))
    }

    /**
     * What AWS says about the JSONata expressions of round 9 beyond rejecting or accepting: it warns about `$eval`, an
     * unknown function and an undeclared variable ("possibly not defined") and accepts them, so the plugin warns at most.
     */
    fun `test the warnings of AWS about JSONata are no errors in the plugin`() {
        val warned = probes.filter { p -> p.name.startsWith("r9-") && p.diagnostics.any { it.startsWith("WARNING POSSIBLY_UNDEFINED_VARIABLE") } }
        assertEquals(listOf("r9-p-eval", "r9-p-unknown-function", "r9-p-variable"), warned.map { it.name }.sorted())
        for (p in warned) assertTrue(p.name, p.accepted && errors(p).isEmpty())
        fun reportedOn(problem: AslProblem) = probes.filter { it.name.startsWith("r9-") && problem in problems(it.name) }.map { it.name }
        assertEquals(listOf("r9-p-eval"), reportedOn(AslProblem.UNSUPPORTED_JSONATA_FUNCTION))
        assertEquals(listOf("r9-p-unknown-function"), reportedOn(AslProblem.UNKNOWN_JSONATA_FUNCTION))
    }

    /** Each expression check is an error exactly on the probes of rounds 9 and 10 that AWS rejects for that reason. */
    fun `test the JSONata checks agree with AWS probe by probe`() {
        fun reportedOn(problem: AslProblem, among: (String) -> Boolean) = probes.filter { among(it.name) && problem in problems(it.name) }.map { it.name }.toSet()
        fun rejectedFor(text: String, among: (String) -> Boolean) = probes.filter { p -> among(p.name) && p.diagnostics.any { text in it } }.map { it.name }.toSet()
        val fields = { name: String -> name.startsWith("r9-") || name.startsWith("r10-") }
        // r10-d- are rejected for their delimiters, a check of stage 1
        val inputs = { name: String -> name.startsWith("r9-p-") || (name.startsWith("r10-") && !name.startsWith("r10-d-")) }

        assertEquals(rejectedFor("does not exist", fields) - "r9-p-context-unknown", reportedOn(AslProblem.STATES_FIELD_UNAVAILABLE, fields))
        assertEquals(rejectedFor("is not supported", inputs), reportedOn(AslProblem.JSONATA_WITHOUT_INPUT, inputs))
        assertEquals(setOf("r9-p-elvis", "r9-p-coalesce"), reportedOn(AslProblem.NEWER_JSONATA_OPERATOR) { it.startsWith("r9-") })
        assertTrue(probes.first { it.name == "r9-p-elvis" }.accepted.not() && probes.first { it.name == "r9-p-coalesce" }.accepted.not())
    }

    private fun problems(name: String): List<AslProblem> {
        val probe = probes.first { it.name == name }
        return AslChecker.findings(myFixture.configureByText("probe.asl.json", probe.definition)).map { it.problem }.distinct()
    }

    private fun gap(name: String): String? = gaps.entries.firstOrNull { name.startsWith(it.key) }?.value

    fun `test no error on a definition that AWS accepts`() {
        val wrong = probes.filter { it.accepted }.mapNotNull { p -> errors(p).takeIf { it.isNotEmpty() }?.let { "${p.name}: $it" } }
        assertEquals("false errors", "", wrong.joinToString("\n"))
    }

    fun `test an error on every definition that AWS rejects, apart from the known gaps`() {
        val missed = probes.filter { !it.accepted && gap(it.name) == null }.filter { errors(it).isEmpty() }
            .map { "${it.name}: ${it.diagnostics.joinToString(" || ").take(200)}" }
        assertEquals("rejected by AWS, no error in the plugin", "", missed.joinToString("\n"))
    }

    fun `test every known gap is still a gap`() {
        val closed = probes.filter { !it.accepted && gap(it.name) != null && errors(it).isNotEmpty() }.map { it.name }
        assertEquals("these are reported as errors now, so they are no gaps any more", emptyList<String>(), closed)
        for (key in gaps.keys) assertTrue("no probe for $key", probes.any { it.name.startsWith(key) })
    }
}
