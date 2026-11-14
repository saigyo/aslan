package io.github.saigyo.aslan.jsonata

import com.google.gson.JsonParser
import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.jsonata.parser.JsonataScanner
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens
import java.io.File

/**
 * Holds the scanner to the answers of the real jsonata-js 2.0.6 parser, recorded in `src/test/testData/jsonata/reference.json`:
 * each entry is an expression with the code the parser threw (`S…`), `EXCEPTION` for another exception, or null when it parsed.
 * Only "an error or none" is compared.
 */
class JsonataReferenceTest : AslTestCase() {
    private class Entry(val expr: String, val code: String?)

    /** Reference error codes the scanner does not report, each with the reason. */
    private val gaps = mapOf(
        "S0217" to "the parent operator is not resolved", "S0401" to "function signatures are not validated",
        // S0402 is missed only where nothing but the signature is wrong; the suite's single S0402 case also fails after
        // its signature, so JsonataConformanceTest finds it and does not list S0402 as a gap
        "S0402" to "function signatures are not validated",
        "EXCEPTION" to "the syntax of a regular expression is JavaScript's and is not checked",
    )

    private val entries: List<Entry> by lazy {
        JsonParser.parseString(File("src/test/testData/jsonata/reference.json").readText()).asJsonArray.map {
            val o = it.asJsonObject
            Entry(o["expr"].asString, o["code"].takeUnless { c -> c.isJsonNull }?.asString)
        }
    }

    /** An expression the plugin reads differently on purpose: a placeholder, or an operator of JSONata 2.1. */
    private fun deliberate(entry: Entry): Boolean = JsonataScanner.scan(entry.expr).tokens.any {
        it.type == JsonataTokens.PLACEHOLDER || it.type == JsonataTokens.ELVIS || it.type == JsonataTokens.COALESCE
    }

    private fun failed(entry: Entry): Boolean = JsonataScanner.scan(entry.expr).error != null

    private fun listed(entries: List<Entry>): List<String> = entries.map { "${it.code}: ${it.expr}" }

    fun `test no error where the reference parses`() {
        assertEquals(emptyList<String>(), listed(entries.filter { it.code == null && !deliberate(it) && failed(it) }))
    }

    fun `test an error where the reference fails, apart from the gaps`() {
        assertEquals(emptyList<String>(), listed(entries.filter { it.code != null && it.code !in gaps && !deliberate(it) && !failed(it) }))
    }

    fun `test every exception of the reference is about a regular expression`() {
        val exceptions = entries.filter { it.code == "EXCEPTION" }
        assertFalse(exceptions.isEmpty())
        assertEquals(emptyList<String>(), listed(exceptions.filter { e -> JsonataScanner.scan(e.expr).tokens.none { it.type == JsonataTokens.REGEX } }))
    }
}
