package io.github.saigyo.aslan.tree

import io.github.saigyo.aslan.AslTestCase

class AslTreeTest : AslTestCase() {
    private fun root(text: String) = AslTree.root(yaml(text)) as AslObject
    private fun AslNode?.scalar() = this as AslScalar

    fun `test yaml mapping sequence and scalar types`() {
        val r = root("""
            Comment: intake
            TimeoutSeconds: 900
            Enabled: true
            Nothing:
            Quoted: '900'
            Yes: yes
            List: [a, 2]
        """)
        assertEquals(listOf("Comment", "TimeoutSeconds", "Enabled", "Nothing", "Quoted", "Yes", "List"), r.entries.map { it.key })
        assertEquals(ScalarType.STRING, r["Comment"]!!.value.scalar().type)
        assertEquals(ScalarType.NUMBER, r["TimeoutSeconds"]!!.value.scalar().type)
        assertEquals(ScalarType.BOOLEAN, r["Enabled"]!!.value.scalar().type)
        assertEquals(ScalarType.NULL, r["Nothing"]!!.value.scalar().type)
        assertEquals(ScalarType.STRING, r["Quoted"]!!.value.scalar().type)
        assertEquals(ScalarType.STRING, r["Yes"]!!.value.scalar().type)
        assertEquals(2, (r["List"]!!.value as AslArray).items.size)
        assertEquals("Comment", r["Comment"]!!.keyPsi.text)
    }

    fun `test folded scalar text is the decoded value`() {
        val r = root("""
            A: >-
              {% ${'$'}x
              + 1 %}
            B: >
              {% 1 %}
            C: 1
        """)
        assertEquals("{% \$x + 1 %}", r["A"]!!.value.scalar().text)
        assertEquals("{% 1 %}\n", r["B"]!!.value.scalar().text)
    }

    fun `test alias resolves to the anchored value and keeps the use site`() {
        val r = root("""
            First:
              - &noRetry
                MaxAttempts: 0
            Second: [*noRetry]
        """)
        val defined = (r["First"]!!.value as AslArray).items.single() as AslObject
        val used = (r["Second"]!!.value as AslArray).items.single() as AslObject
        assertEquals("0", used["MaxAttempts"]!!.value.scalar().text)
        assertSame(defined.psi, used.psi)
        assertEquals("*noRetry", used.usePsi.text)
    }

    fun `test merge key contributes entries marked viaMerge`() {
        val r = root("""
            Base: &base
              IntervalSeconds: 2
            Derived:
              <<: *base
              MaxAttempts: 3
        """)
        val d = r["Derived"]!!.value as AslObject
        assertEquals(setOf("IntervalSeconds", "MaxAttempts"), d.entries.map { it.key }.toSet())
        assertTrue(d["IntervalSeconds"]!!.viaMerge)
        assertFalse(d["MaxAttempts"]!!.viaMerge)
        assertNull(d["<<"])
    }

    fun `test self referencing alias does not recurse forever`() {
        val r = root("""
            Loop: &loop
              Inner: *loop
        """)
        assertNotNull(r["Loop"])   // reaching this line is the assertion
        assertTrue((r["Loop"]!!.value as AslObject)["Inner"]!!.value is AslUnresolved)
    }

    fun `test alias without an anchor is unresolved not null`() =
        assertTrue(root("Retry: *missing")["Retry"]!!.value is AslUnresolved)

    fun `test empty value is a null scalar at the key`() {
        val e = root("Next:\nEnd: true")["Next"]!!
        assertEquals(ScalarType.NULL, e.value.scalar().type)
        assertSame(e.keyPsi, e.value.psi)
    }

    fun `test scalars typed differently by yaml 1_1 and 1_2 are ambiguous`() {
        val r = root("A: yes\nB: Off\nC: 1_000\nD: 010\nE: 0o14\nF: 1:30\nG: true\nH: 12\nI: 'yes'\nJ: text")
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), r.entries.filter { it.value.scalar().ambiguous }.map { it.key })
    }

    fun `test clipped block scalar at end of file without line break`() {
        val r = AslTree.root(myFixture.configureByText("test.asl.yaml", "A: >\n  {% 1 %}")) as AslObject
        assertEquals("{% 1 %}", r["A"]!!.value.scalar().text)
    }

    fun `test incomplete and odd files do not throw`() {
        assertNull(AslTree.root(yaml("")))
        assertTrue(AslTree.root(yaml("- a\n- b")) is AslArray)
        assertTrue(AslTree.root(yaml("just text")) is AslScalar)
        AslTree.root(yaml("StartAt:\nStates:\n  A:\n    Type"))          // must not throw
        AslTree.root(yaml("StartAt: A\n   States: oops\n Type: x"))        // must not throw
    }

    fun `test only the first yaml document is read`() {
        val r = root("StartAt: A\n---\nStartAt: B")
        assertEquals("A", r["StartAt"]!!.value.scalar().text)
    }

    fun `test json objects arrays and literals`() {
        val r = AslTree.root(json("""{"StartAt": "A", "TimeoutSeconds": 900, "Flag": true, "None": null, "L": [1]}""")) as AslObject
        assertEquals("A", r["StartAt"]!!.value.scalar().text)
        assertEquals(ScalarType.STRING, r["StartAt"]!!.value.scalar().type)
        assertEquals(ScalarType.NUMBER, r["TimeoutSeconds"]!!.value.scalar().type)
        assertEquals(ScalarType.BOOLEAN, r["Flag"]!!.value.scalar().type)
        assertEquals(ScalarType.NULL, r["None"]!!.value.scalar().type)
        assertEquals("StartAt", r["StartAt"]!!.key)
    }
}
