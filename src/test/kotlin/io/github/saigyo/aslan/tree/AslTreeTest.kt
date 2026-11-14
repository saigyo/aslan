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

    fun `test quoted keys are decoded`() {
        val r = root("""
            'Director''s cut': 1
            "say \"hi\"": 2
            "back\\slash": 3
            "tab\there": 4
            "\u0041b": 5
            "\x41c": 6
            plain: 7
            two words here: 8
        """)
        assertEquals(
            listOf("Director's cut", "say \"hi\"", "back\\slash", "tab\there", "Ab", "Ac", "plain", "two words here"),
            r.entries.map { it.key },
        )
        assertEquals("'Director''s cut'", r.entries[0].keyPsi.text)
    }

    fun `test quoted values are decoded`() {
        val r = root("""
            A: 'Director''s cut'
            B: "Director's cut"
            C: "say \"hi\""
        """)
        assertEquals("Director's cut", r["A"]!!.value.scalar().text)
        assertEquals("Director's cut", r["B"]!!.value.scalar().text)
        assertEquals("say \"hi\"", r["C"]!!.value.scalar().text)
    }

    private fun values(text: String): List<String> = root(text).entries.map { it.value.scalar().text }

    fun `test quoted values with a tag or an anchor in front`() {
        assertEquals(
            listOf("it's", "it's", "it's", "x\"y", "x\"y", "x\"y", "plain", "plain", "plain"),
            values("""
                A: 'it''s'
                B: !!str 'it''s'
                C: &a 'it''s'
                D: "x\"y"
                E: !!str "x\"y"
                F: &b "x\"y"
                G: plain
                H: &c plain
                I: !!str plain
            """),
        )
    }

    fun `test multi-line quoted values are folded`() {
        assertEquals(
            listOf("it's x", "it's x", "it's x", "a b", "a\nb", "a b", "ab c", "x y"),
            values("""
                A: 'it''s
                  x'
                B: !!str 'it''s
                  x'
                C: &a 'it''s
                    x'
                D: "a
                  b"
                E: "a

                  b"
                F: &b "a  
                  b"
                G: "a\
                  b c"
                H: !!str 'x
                  y'
            """),
        )
    }

    fun `test astral escape is the same on one line and across lines`() {
        val one = values("A: \"\\U0001F600\"").single()
        assertEquals("\uD83D\uDE00", one)
        assertEquals("\uD83D\uDE00 a", values("A: \"\\U0001F600\n  a\"").single())
        assertEquals("\uD83D\uDE00a", values("A: \"\\U0001F600\\\n  a\"").single())
    }

    fun `test multi-line double-quoted value with a tag in front`() =
        assertEquals("a b", values("A: !!str \"a\n  b\"").single())

    fun `test empty lines after an escaped line break become line feeds`() {
        assertEquals("a\nb", values("A: \"a\\\n\n  b\"").single())
        assertEquals("a\n\nb", values("A: \"a\\\n\n\n  b\"").single())
    }

    fun `test an unknown escape is kept`() = assertEquals("\\q", values("A: \"\\q\"").single())

    fun `test multi-line quoted value is decoded again after an edit in the same file`() {
        assertEquals("a b", AslTree.root(yaml("A: \"a\n  b\"")).let { (it as AslObject)["A"]!!.value.scalar().text })
        assertEquals("it's x", AslTree.root(yaml("A: 'it''s\n  x'")).let { (it as AslObject)["A"]!!.value.scalar().text })
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

    fun `test exponent floats signed hex and timestamps are ambiguous`() {
        val r = root(
            "K: 1e3\nL: 1E5\nM: 1.0e3\nN: 2e-3\nO: -0x1F\nP: +0x1F\nQ: 2026-11-14\nR: 2026-11-14T10:00:00Z\n" +
                "S: 1.0e+3\nT: 0x1F\nU: 1.5\nV: -7",
        )
        assertEquals(listOf("K", "L", "M", "N", "O", "P", "Q", "R"), r.entries.filter { it.value.scalar().ambiguous }.map { it.key })
    }

    fun `test tagged plain scalars are ambiguous`() {
        val r = root("A: !!str 12\nB: !Ref Timeout\nC: !x text\nD: 12\nE: text")
        assertEquals(listOf("A", "B", "C"), r.entries.filter { it.value.scalar().ambiguous }.map { it.key })
        assertEquals("Timeout", r["B"]!!.value.scalar().text)
    }

    fun `test block scalar indicator ignores anchor tag and comment`() {
        fun text(source: String) =
            (AslTree.root(myFixture.configureByText("test.asl.yaml", source)) as AslObject)["A"]!!.value.scalar().text
        assertEquals("{% 1 %}", text("A: &no-retry >\n  {% 1 %}"))
        assertEquals("{% 1 %}", text("A: !to-text >\n  {% 1 %}"))
        assertEquals("{% 1 %}", text("A: > # see a-b\n  {% 1 %}"))
        assertEquals("{% 1 %}", text("A: >- # trimmed\n  {% 1 %}\n"))
        assertEquals("{% 1 %}\n", text("A: &no-retry >\n  {% 1 %}\n"))
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
