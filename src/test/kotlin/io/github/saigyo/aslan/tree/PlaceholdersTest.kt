package io.github.saigyo.aslan.tree

import junit.framework.TestCase

class PlaceholdersTest : TestCase() {
    fun `test ranges`() {
        assertEquals(listOf(0..13), Placeholders.ranges("\${OrdersTable}"))
        assertEquals(listOf(4..9, 11..16), Placeholders.ranges("arn:\${A.b}/\${c-d}"))
        assertEquals(emptyList<IntRange>(), Placeholders.ranges("\${} \${a b} \$x {y} \${\"k\": 1}"))
        assertTrue(Placeholders.contains("x \${Stage} y"))
    }
}
