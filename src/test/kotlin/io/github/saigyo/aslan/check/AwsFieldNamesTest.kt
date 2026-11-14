package io.github.saigyo.aslan.check

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeHighlighting.HighlightDisplayLevel
import java.io.File

/**
 * Compares the field names the plugin knows with what AWS answered. Each entry of
 * `src/test/testData/aws/field-names.json` is a definition that holds many candidate field names in one position, with
 * the names `aws stepfunctions validate-state-machine-definition` reported as not supported there on 2026-11-14. The
 * candidates are the names the plugin knows, the names in the schemas of asl-validator, and a made-up one, `Zzz`.
 */
class AwsFieldNamesTest : CheckTestCase() {
    private class Probe(val name: String, val candidates: Set<String>, val unsupported: Set<String>, val definition: String)

    private val probes: List<Probe> by lazy {
        val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        JsonParser.parseString(File("src/test/testData/aws/field-names.json").readText()).asJsonObject.entrySet().map { (name, value) ->
            val o = value as JsonObject
            Probe(name, o["candidates"].asJsonArray.map { it.asString }.toSet(), o["unsupported"].asJsonArray.map { it.asString }.toSet(), gson.toJson(o["definition"]))
        }
    }

    /** The problems reported on each key of the definition of [probe]. */
    private fun byKey(probe: Probe): Map<String, List<AslProblem>> =
        AslChecker.findings(myFixture.configureByText("probe.asl.json", probe.definition)).groupBy({ it.element.text.trim('"') }, { it.problem })

    private fun isError(found: Map<String, List<AslProblem>>, key: String) = found[key].orEmpty().any { it.level == HighlightDisplayLevel.ERROR }

    fun `test no field that AWS has in a position is called unknown or misplaced there`() {
        val wrong = probes.flatMap { p ->
            val found = byKey(p)
            (p.candidates - p.unsupported).filter { name -> found[name].orEmpty().any { it == AslProblem.UNKNOWN_FIELD || it == AslProblem.MISPLACED_FIELD } }.map { "${p.name}: $it" }
        }
        assertEquals("false errors", "", wrong.joinToString("\n"))
    }

    /** A `ManifestType` beside an `InputType` is reported on the `InputType`, the other half of the same conflict. */
    fun `test every field that AWS does not have in a position is an error there`() {
        val missed = probes.flatMap { p ->
            val found = byKey(p)
            (p.candidates intersect p.unsupported).filter { name -> !isError(found, name) && !(name == "ManifestType" && isError(found, "InputType")) }
                .map { "${p.name}: $it" }
        }
        assertEquals("not supported by AWS, no error in the plugin", "", missed.joinToString("\n"))
    }
}
