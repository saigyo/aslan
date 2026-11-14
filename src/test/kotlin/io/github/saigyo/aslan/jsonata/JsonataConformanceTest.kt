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
        "S0217" to "the parent operator is not resolved", "S0401" to SIGNATURES,
    )
    // S0402 is not a gap here: the only suite case of it,
    // `λ($arr)<(sa<n>)>>{$arr}([[1]])`, also fails to parse after the signature (S0202 at the third `>`), so it is found.
    // JsonataReferenceTest lists S0402 as a gap, since there the reference also fails expressions whose only fault is the signature.

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

    fun `test errors found after parsing`() {
        for ((expr, code) in listOf(
            "[1,2,3]{\"num\": \$}[true]" to "S0209", "[1,2,3]{\"num\": \$}{\"num\": \$}" to "S0210", "\$.7" to "S0213",
            "Account.Order[1]@\$o.Product" to "S0215", "Account.Order^(>OrderID)@\$o.Product" to "S0216",
        )) assertEquals(expr, code, JsonataScanner.scan(expr).error?.code)
        for (expr in listOf("Account.Order{\"a\": 1}[0]", "\$.\"7\"", "Account.Order@\$o[1].Product", "(7).a"))
            assertNull(expr, JsonataScanner.scan(expr).error)
    }

    companion object {
        /** The reason of the gaps about function signatures. */
        const val SIGNATURES = "function signatures are not parsed at all: S0401, S0402, and the plain JavaScript exceptions " +
            "of parseSignature, such as for <n++>"

        val suite: List<Case> by lazy {
            JsonParser.parseString(File("src/test/testData/jsonata/suite.json").readText()).asJsonArray.map {
                val o = it.asJsonObject
                Case(o["id"].asString, o["expr"].asString, o["code"].takeUnless { c -> c.isJsonNull }?.asString)
            }
        }
    }
}
