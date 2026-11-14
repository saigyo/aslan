package io.github.saigyo.aslan.jsonata

import com.google.gson.JsonParser
import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.jsonata.parser.JsonataScanner
import java.io.File

/**
 * Holds the scanner to the test suite of jsonata-js 2.0.6 (`src/test/testData/jsonata/suite.json`): an expression the
 * suite expects to parse scans without an error, and one with a syntax error code (`S…`) scans with an error. Only
 * "an error or none" is compared, never the code or the position.
 */
class JsonataConformanceTest : AslTestCase() {
    class Case(val id: String, val expr: String, val code: String?)

    /** Syntax error codes the scanner does not find, each with the reason. */
    private val gaps = mapOf(
        "S0217" to "the parent operator is not resolved", "S0401" to "function signatures are not validated",
        "S0209" to "Task 3", "S0210" to "Task 3", "S0213" to "Task 3", "S0215" to "Task 3", "S0216" to "Task 3",
    )
    // S0402 ("function signatures are not validated") is not a gap: the only suite case of it,
    // `λ($arr)<(sa<n>)>>{$arr}([[1]])`, also fails to parse after the signature (S0202 at the third `>`), so it is found.

    private fun failed(case: Case): Boolean = JsonataScanner.scan(case.expr).error != null

    private fun syntaxError(case: Case): Boolean = case.code?.startsWith("S") == true

    fun `test every suite expression that is expected to parse scans without an error`() {
        assertEquals(emptyList<String>(), suite.filter { !syntaxError(it) && failed(it) }.map { it.id })
    }

    fun `test every suite syntax error is found, apart from the named gaps`() {
        assertEquals(emptyList<String>(), suite.filter { syntaxError(it) && it.code !in gaps && !failed(it) }.map { it.id })
    }

    fun `test every named gap is still a gap`() {
        assertEquals(emptyList<String>(), suite.filter { it.code in gaps && failed(it) }.map { it.id })
    }

    companion object {
        val suite: List<Case> by lazy {
            JsonParser.parseString(File("src/test/testData/jsonata/suite.json").readText()).asJsonArray.map {
                val o = it.asJsonObject
                Case(o["id"].asString, o["expr"].asString, o["code"].takeUnless { c -> c.isJsonNull }?.asString)
            }
        }
    }
}
