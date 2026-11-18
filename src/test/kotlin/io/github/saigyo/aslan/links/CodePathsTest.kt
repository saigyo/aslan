package io.github.saigyo.aslan.links

import junit.framework.TestCase

class CodePathsTest : TestCase() {
    private fun paths(text: String) = CodePaths.find(text).map { it.path to it.type }

    fun `test a relative path is found`() =
        assertEquals(listOf("lambdas/src/intake/handler.ts" to null), paths("Function: lambdas/src/intake/handler.ts"))

    fun `test a type follows a hash`() =
        assertEquals(listOf("lambdas/src/shared/contracts.ts" to "IntakeInput"), paths("Input: lambdas/src/shared/contracts.ts#IntakeInput"))

    fun `test dots and parents at the start belong to the path`() =
        assertEquals(listOf("./a/b.json" to null, "../schemas/order.json" to null), paths("./a/b.json and ../schemas/order.json"))

    fun `test wrapping and trailing punctuation are not part of it`() =
        assertEquals(
            listOf("a/b.ts" to null, "c/d.yaml" to null, "e/f.ts" to "G", "h/i.ts" to null),
            paths("`a/b.ts`, see (c/d.yaml). Also \"e/f.ts#G\"; h/i.ts:"),
        )

    fun `test a URL, a version and a word with a slash are no paths`() =
        assertEquals(emptyList<Pair<String, String?>>(), paths("https://example.com/a.json v1.2 and/or x/y"))

    fun `test a type must be an identifier`() =
        assertEquals(listOf("a/b.ts" to null), paths("a/b.ts#1x"))

    fun `test a type may hold dollar and underscore`() =
        assertEquals(listOf("a/b.ts" to "\$Intake_Input2"), paths("a/b.ts#\$Intake_Input2"))

    fun `test the offsets cover the path and its type`() {
        val text = "see a/b.ts#T."
        val p = CodePaths.find(text).single()
        assertEquals("a/b.ts#T", text.substring(p.start, p.end))
        assertEquals("T", text.substring(p.typeStart, p.end))
    }

    fun `test the offsets of a path without a type`() {
        val text = "(a/b.ts)"
        val p = CodePaths.find(text).single()
        assertEquals("a/b.ts", text.substring(p.start, p.end))
        assertEquals(-1, p.typeStart)
    }

    fun `test an absolute path is found`() =
        assertEquals(listOf("/lambdas/a.ts" to null), paths("at /lambdas/a.ts"))

    fun `test an empty text has no paths`() =
        assertEquals(emptyList<Pair<String, String?>>(), paths(""))

    fun `test versions, numbers, globs and placeholders are no paths`() =
        assertEquals(emptyList<Pair<String, String?>>(), paths("upgrade v1.2/v1.3, ratio 1/2.5, uploads/*.json {bucket}/key.json [a](lambdas/a.ts)"))
}
