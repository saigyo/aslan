package io.github.saigyo.aslan.jsonata

import com.intellij.psi.tree.IElementType
import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.jsonata.parser.JsonataElements.CALL
import io.github.saigyo.aslan.jsonata.parser.JsonataElements.FIELD
import io.github.saigyo.aslan.jsonata.parser.JsonataElements.PATH
import io.github.saigyo.aslan.jsonata.parser.JsonataScan
import io.github.saigyo.aslan.jsonata.parser.JsonataScanner
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens.AND
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens.COALESCE
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens.DOT
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens.ELVIS
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens.LBRACE
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens.NAME
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens.PLACEHOLDER
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens.REGEX
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens.SLASH
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens.VARIABLE
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens.WHITE_SPACE
import io.github.saigyo.aslan.jsonata.parser.ScanNode

class JsonataScannerTest : AslTestCase() {
    private fun kinds(text: String): List<IElementType> = JsonataScanner.scan(text).tokens.map { it.type }.filter { it != WHITE_SPACE }

    private fun nodes(text: String): List<IElementType> {
        val result = mutableListOf<IElementType>()
        fun visit(node: ScanNode) {
            result += node.type
            node.children.forEach(::visit)
        }
        JsonataScanner.scan(text).root?.let(::visit)
        return result
    }

    private fun assertCovers(text: String, scan: JsonataScan) {
        var offset = 0
        for (token in scan.tokens) {
            assertEquals(text, offset, token.start)
            assertTrue(text, token.end > token.start)
            offset = token.end
        }
        assertEquals(text, text.length, offset)
    }

    private fun assertError(code: String, start: Int, end: Int, text: String) {
        val error = JsonataScanner.scan(text).error
        assertNotNull(text, error)
        assertEquals(text, listOf(code, start, end), listOf(error!!.code, error.start, error.end))
    }

    fun `test the tokens cover the text without a gap`() {
        for (case in JsonataConformanceTest.suite) assertCovers(case.expr, JsonataScanner.scan(case.expr))
    }

    fun `test a slash is a regular expression only where the reference reads one`() {
        assertEquals(listOf(REGEX), kinds("/ab+/i").filter { it == REGEX || it == SLASH })
        assertEquals(listOf(SLASH), kinds("\$x / 2").filter { it == REGEX || it == SLASH })
        assertEquals(listOf(REGEX), kinds("\$match(\$x, /a\\/b/)").filter { it == REGEX || it == SLASH })
        assertEquals("S0211", JsonataScanner.scan("[/a/]").error?.code)      // a quirk of the reference: no regex directly after a prefix bracket
        assertEquals("S0211", JsonataScanner.scan("(/a/)").error?.code)
        assertEquals("S0211", JsonataScanner.scan("-/a/").error?.code)
        for (text in listOf("{\"a\": /a/}", "\$f(?) / 2", "(1) / 2", "[1] / 2", "{\"a\":1} / 2", "\$x.a / 2"))
            assertNull(text, JsonataScanner.scan(text).error)
        // after these closing tokens the reference reads a regular expression, which is not closed
        for (text in listOf("\$x[] / 2", "\$x^(a) / 2", "function(\$x){\$x} / 2", "\$x ~> |a|{}| / 2"))
            assertEquals(text, "S0302", JsonataScanner.scan(text).error?.code)
    }

    fun `test keywords are names where a value is expected`() {
        assertEquals(listOf(NAME, DOT, NAME), kinds("and.or"))
        assertEquals(listOf(PATH, FIELD, FIELD), nodes("and.or"))
        assertTrue(AND in kinds("a and b"))
        assertEquals(listOf(NAME, AND, NAME), kinds("a and and"))
        for (text in listOf("and", "or.and.in", "a and and", "\$", "\$\$", "a\"b", "a\$b")) assertNull(text, JsonataScanner.scan(text).error)
    }

    fun `test a placeholder is one token`() {
        assertEquals(listOf(PLACEHOLDER), kinds("\${Bucket}"))
        assertNull(JsonataScanner.scan("\${Bucket}").error)
        assertEquals(listOf(VARIABLE, LBRACE), kinds("\${\"a\": 1}").take(2))
    }

    fun `test the operators of JSONata 2_1 are tokens and no syntax error`() {
        assertTrue(ELVIS in kinds("\$a ?: \$b"))
        assertNull(JsonataScanner.scan("\$a ?: \$b").error)
        assertTrue(COALESCE in kinds("\$a ?? \$b"))
        assertNull(JsonataScanner.scan("\$a ?? \$b").error)
    }

    fun `test the tree built before an error is kept`() {
        val scan = JsonataScanner.scan("\$sum(\$states.input.")
        assertNotNull(scan.error)
        val root = scan.root!!
        assertEquals(CALL, root.type)
        assertTrue(root.children.any { it.type == PATH })
    }

    fun `test a missing operand is an error at the end`() {
        for (text in listOf("1 +", "1 =", "\$states.", "(", "", " ")) assertError("S0207", text.length, text.length, text)
    }

    fun `test text after a complete expression is an error`() {
        assertError("S0201", 24, 25, "Account.Order[0].Product;")
        assertError("S0201", 2, 3, "1 2")
        assertError("S0201", 3, 4, "/a/x")
    }

    fun `test the syntax of a regular expression is not checked`() {
        for (text in listOf("/[^]/", "/a{2,1}/")) assertNull(text, JsonataScanner.scan(text).error)
        // the tokenizer counts open brackets, and finds no closing slash outside them
        for (text in listOf("/a{/", "/(/")) assertEquals(text, "S0302", JsonataScanner.scan(text).error?.code)
    }

    fun `test lexical errors`() {
        assertError("S0101", 0, 4, "\"abc")
        assertError("S0102", 0, 7, "10e1000")
        assertError("S0103", 0, 4, "\"\\y\"")
        assertError("S0104", 0, 6, "\"\\u12\"")
        assertError("S0105", 0, 4, "`abc")
        assertError("S0106", 0, 6, "/* abc")
        assertError("S0301", 0, 2, "//")
        assertError("S0302", 0, 4, "/abc")
        assertError("S0204", 2, 3, "[1!2]")
    }

    fun `test an error found after parsing has the range the reference points at, and the tree is whole`() {
        assertError("S0209", 19, 20, "[1] /* c */ {\"a\":1}[0]")
        assertError("S0210", 11, 12, "[1]{\"a\":1} {\"b\":2}")
        assertError("S0210", 10, 11, "a{\"x\":1}.b{\"y\":1}")   // the group stays on the path
        assertError("S0213", 2, 3, "\$.7")
        assertError("S0213", 3, 4, "a.-1")
        assertError("S0213", 2, 6, "a.true")
        assertError("S0213", 6, 10, "\$.7[\$.true]")          // the predicate is processed before the steps are checked
        assertError("S0215", 16, 17, "Account.Order[1]@\$o.Product")
        assertError("S0216", 24, 25, "Account.Order^(>OrderID)@\$o.Product")
        for (text in listOf("a[1]{\"x\":1}[0]", "7#\$i", "-1#\$i", "7[0]", "\$x.\"7\"")) assertNull(text, JsonataScanner.scan(text).error)
        assertEquals(listOf("PATH", "PATH", "CONTEXT_REF", "LITERAL", "FIELD"), nodes("\$.7.a").map { it.toString() })
    }

    fun `test the shape of a tree`() {
        val text = "(\$t := 1; \$f(\$t, ?).a[0])"
        val dump = StringBuilder()
        fun visit(node: ScanNode, depth: Int) {
            dump.append("  ".repeat(depth)).append(node.type).append(' ').append(node.start).append('-').append(node.end).append('\n')
            node.children.forEach { visit(it, depth + 1) }
        }
        visit(JsonataScanner.scan(text).root!!, 0)
        assertEquals(
            """
            BLOCK 0-25
              ASSIGNMENT 1-8
                VARIABLE_REF 1-3
                LITERAL 7-8
              PATH 10-24
                CALL 10-19
                  VARIABLE_REF 10-12
                  VARIABLE_REF 13-15
                  PARTIAL_ARGUMENT 17-18
                FILTER 20-24
                  FIELD 20-21
                  LITERAL 22-23

            """.trimIndent(),
            dump.toString(),
        )
    }

    fun `test the shape of a lambda, an object and an order`() {
        assertEquals(
            listOf("LAMBDA", "PARAMETER", "SIGNATURE", "VARIABLE_REF"),
            nodes("function(\$a)<n:n>{\$a}").map { it.toString() },
        )
        assertEquals(
            listOf("GROUP_BY", "FIELD", "PAIR", "LITERAL", "FIELD", "ORDER_BY", "FIELD", "SORT_TERM", "FIELD", "SORT_TERM", "FIELD"),
            nodes("a{\"k\": b}").map { it.toString() } + nodes("a^(>b, c)").map { it.toString() },
        )
        assertEquals(listOf("ARRAY", "RANGE", "LITERAL", "LITERAL", "FILTER", "FIELD"), nodes("[1..2]").map { it.toString() } + nodes("a[]").map { it.toString() })
    }

    fun `test half typed text never throws and is covered`() {
        val long = JsonataConformanceTest.suite.map { it.expr }.sortedByDescending { it.length }.take(8)
        for (text in long) for (end in 0..text.length) assertCovers(text.substring(0, end), JsonataScanner.scan(text.substring(0, end)))
        for (case in JsonataConformanceTest.suite) {
            val text = case.expr.dropLast(1)
            assertCovers(text, JsonataScanner.scan(text))
        }
        val deep = "(".repeat(20_000) + "\"" + "a".repeat(100_000)
        assertCovers(deep, JsonataScanner.scan(deep))
    }
}
