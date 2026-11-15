package io.github.saigyo.aslan.diagram

import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.TransitionKind
import java.io.File

class DiagramBuilderTest : DiagramTestCase() {
    private fun DiagramModel.at(file: PsiFile, id: String): String = file.text.substring(offsets.getValue(id)).lines().first()

    fun `test states and nested machines keep file order`() {
        val (model, _) = build(archive)
        val root = model.root!!
        assertEquals(listOf("Sort", "Scan", "Each", "Both", "Done", "Failed"), root.states.map { it.name })
        assertEquals(listOf("Choice", "Task", "Map", "Parallel", "Succeed", "Fail"), root.states.map { it.type })
        assertEquals(listOf("Choice", "Task · Lambda invoke", "Map", "Parallel", "Succeed", "Fail"), root.states.map { it.sub })
        val map = root.states[2]
        assertEquals(listOf(null), map.machines.map { it.title })
        assertEquals(listOf("Frame", "Last"), map.machines[0].states.map { it.name })
        assertEquals(listOf("Branch 1", "Branch 2"), root.states[3].machines.map { it.title })
        assertEquals(10, model.stateCount)
        assertEquals("s3", map.machines[0].states[0].id)
        assertEquals("m0", root.id)
        assertEquals("m1", map.machines[0].id)
    }

    fun `test start and terminal states are marked`() {
        val (model, _) = build(archive)
        val root = model.root!!
        assertEquals(root.states[0].id, root.start)
        val map = root.states[2]
        assertEquals(map.machines[0].states[0].id, map.machines[0].start)
        val parallel = root.states[3]
        assertEquals(parallel.machines[1].states[0].id, parallel.machines[1].start)
        assertEquals(listOf(false, false, false, false, true, true), root.states.map { it.terminal })
        assertEquals(listOf(false, true), map.machines[0].states.map { it.terminal })
        assertEquals(listOf(true), parallel.machines[0].states.map { it.terminal })
    }

    fun `test each kind of transition is an edge`() {
        val (model, _) = build(archive)
        val root = model.root!!
        assertEquals(setOf("s0.c0", "s0.d", "s1.k0", "s1.n", "s2.n", "s5.n"), root.edges.map { it.id }.toSet())
        val rule = root.edges.first { it.id == "s0.c0" }
        assertEquals(TransitionKind.CHOICE, rule.kind)
        assertEquals("s0", rule.from)
        assertEquals("s1", rule.to)
        assertEquals("format = \"nitrate\"", rule.label)
        assertEquals("\$.format = \"nitrate\"", rule.title)
        val default = root.edges.first { it.id == "s0.d" }
        assertEquals("Default", default.label)
        assertEquals("s8", default.to)
        val catch = root.edges.first { it.id == "s1.k0" }
        assertEquals(TransitionKind.CATCH, catch.kind)
        assertEquals("Catch", catch.label)
        assertEquals("States.ALL", catch.title)
        assertEquals("s9", catch.to)
        assertNull(root.edges.first { it.id == "s1.n" }.label)
        assertEquals(listOf("s3.n"), root.states[2].machines[0].edges.map { it.id })
        assertEquals("s4", model.edge("s3.n")?.to)
        assertSame(rule, model.edge("s0.c0"))
        assertEquals("Frame", model.state("s3")?.name)
        assertNull(model.edge("nope"))
    }

    fun `test a target that does not resolve gives no edge`() {
        val (model, _) = build(
            """
            StartAt: A
            States:
              A:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                Next: Missing
              B:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                Next: §{Placeholder}
              C:
                Type: Parallel
                Branches:
                  - StartAt: Inner
                    States:
                      Inner:
                        Type: Pass
                        Next: A
              D:
                Type: Pass
                Next:
                  - x
                End: true
            """,
        )
        assertEquals(emptyList<DiagramEdge>(), model.root!!.edges)
        assertEquals(emptyList<DiagramEdge>(), model.root.states[2].machines[0].edges)
    }

    fun `test a state may lead to itself and twice to the same state`() {
        val (model, _) = build(
            """
            StartAt: A
            States:
              A:
                Type: Choice
                Choices:
                  - Variable: §.x
                    NumericEquals: 1
                    Next: A
                  - Variable: §.x
                    NumericEquals: 2
                    Next: B
                Default: B
              B:
                Type: Pass
                Next: B
            """,
        )
        val edges = model.root!!.edges
        assertEquals(listOf("s0.c0", "s0.c1", "s0.d", "s1.n"), edges.map { it.id })
        assertEquals(listOf("s0", "s1", "s1", "s1"), edges.map { it.to })
        assertEquals(4, edges.map { it.id }.toSet().size)
    }

    fun `test two states of the same name get their own IDs`() {
        val (model, _) = build(
            """
            StartAt: A
            States:
              A:
                Type: Pass
                Next: A
              A:
                Type: Pass
                End: true
            """,
        )
        assertEquals(listOf("s0", "s1"), model.root!!.states.map { it.id })
        assertEquals(2, model.offsets.keys.filter { it.startsWith("s") && '.' !in it }.size)
        assertEquals(listOf("s0.n"), model.root.edges.map { it.id })
        assertEquals("s0", model.root.start)
    }

    fun `test offsets are the lines that create each item`() {
        val (model, file) = build(archive)
        for (state in model.root!!.states) assertTrue(model.at(file, state.id).trim().startsWith(state.name))
        assertTrue(model.at(file, "s0.c0").startsWith("Variable"))
        assertTrue(model.at(file, "s0.d").startsWith("Default"))
        assertTrue(model.at(file, "s1.k0").startsWith("ErrorEquals"))
        assertTrue(model.at(file, "s1.n").startsWith("Next"))
        assertTrue(model.at(file, "s3.n").startsWith("Next"))
    }

    fun `test a path of a shared Catch list starts at the state's own line`() {
        val (model, file) = build(
            """
            StartAt: A
            States:
              A:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                Catch: &onFault
                  - ErrorEquals: [States.ALL]
                    Next: Failed
                Next: Failed
              B:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                Catch: *onFault
                Next: Failed
              C:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                Catch:
                  - &oneCatcher
                    ErrorEquals: [States.Timeout]
                    Next: Failed
                Next: Failed
              D:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                Catch:
                  - *oneCatcher
                Next: Failed
              Failed:
                Type: Fail
            """,
        )
        val states = model.root!!.states
        for ((id, name) in listOf("s0" to "A", "s1" to "B", "s2" to "C", "s3" to "D")) {
            val state = model.state(id)!!
            assertEquals(name, state.name)
            val edge = model.edge("$id.k0")!!
            assertTrue("$id: ${model.offsets[edge.id]}", state.range.containsOffset(edge.offset))
        }
        assertEquals("B", states[1].name)
        assertTrue(model.at(file, "s1.k0").startsWith("Catch"))
        assertTrue(model.at(file, "s3.k0").startsWith("*oneCatcher"))
        assertEquals("s4", model.edge("s3.k0")?.to)
    }

    fun `test stateAt finds the innermost state`() {
        val (model, file) = build(archive)
        val text = file.text
        assertEquals("s3", model.stateAt(text.indexOf("Type: Pass")))
        assertEquals("s2", model.stateAt(text.indexOf("ItemProcessor")))
        assertEquals("s0", model.stateAt(text.indexOf("StringEquals")))
        assertEquals("s6", model.stateAt(text.indexOf("Left:") + 2))
        assertNull(model.stateAt(text.indexOf("StartAt: Sort")))
        assertNull(model.stateAt(text.indexOf("Film archive")))
        assertNull(model.stateAt(text.length + 10))
    }

    fun `test a half-typed file builds without an exception`() {
        val texts = listOf(
            "StartAt: A\nStates:\n  A:\n    Next: B\n  B:\n    Next: A",
            "StartAt: A\nStates:\n  A: scalar\n  B:\n    Type: Pass\n    End: true",
            "StartAt: A\nStates: {}",
            "StartAt: A\nStates:\n  A:\n    Type: Map\n    Next: A\n  B:\n    Type: Parallel\n    Branches: x",
            "StartAt: A\nStates: §{States}",
            "StartAt: A\nStates:\n  A:\n    Type: Task\n    Resource:\n    Next:\n    Catch: 5\n    End: yes",
        )
        for (text in texts) {
            val file = file(text)
            val doc = AslModel.of(file)
            if (doc != null) DiagramBuilder.build(doc)
        }
        val map = build(texts[3]).first
        assertEquals(emptyList<DiagramMachine>(), map.root!!.states[0].machines)
        assertEquals(emptyList<DiagramMachine>(), map.root.states[1].machines)
        assertEquals("s0", map.root.edges.single().to)
        assertEquals("", build(texts[0]).first.root!!.states[0].sub)
        assertTrue(DiagramBuilder.readable(AslModel.of(file(texts[1]))))
        assertTrue(DiagramBuilder.readable(AslModel.of(file(texts[2]))))
        assertFalse(DiagramBuilder.readable(AslModel.of(file(texts[4]))))
        assertFalse(DiagramBuilder.readable(null))
        assertFalse(DiagramBuilder.readable(AslModel.of(file("- a\n- b"))))
        val jsonata = "QueryLanguage: JSONata\nStartAt: A\nStates:\n  A:\n    Type: Pass\n    Output: \"{% §states.input.( %}\"\n    End: true"
        assertTrue(DiagramBuilder.readable(AslModel.of(file(jsonata))))
        val json = file("{\"StartAt\": \"A\", \"States\": {\"A\": {\"Type\": \"Pass\", \"End\": true,}}", "broken.asl.json")
        assertFalse(DiagramBuilder.readable(AslModel.of(json)))
    }

    fun `test the committed definitions build completely`() {
        val files = File("src/test/testData/clean").listFiles().orEmpty().filter { ".asl." in it.name }.sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val psi = myFixture.configureByText(f.name, f.readText())
            val doc = AslModel.of(psi)!!
            assertTrue(f.name, DiagramBuilder.readable(doc))
            val model = DiagramBuilder.build(doc)
            val all = doc.allStates()
            assertEquals(f.name, all.size, model.stateCount)
            val nodes = ArrayList<DiagramState>()
            val edges = ArrayList<DiagramEdge>()
            fun walk(m: DiagramMachine) {
                edges += m.edges
                for (s in m.states) {
                    nodes += s
                    s.machines.forEach(::walk)
                }
            }
            walk(model.root!!)
            assertEquals(f.name, all.size, nodes.size)
            assertEquals(f.name, all.size, nodes.map { it.id }.toSet().size)
            val expected = all.sumOf { s -> s.transitions.count { t -> t.targetName?.let { s.machine.state(it) } != null } }
            assertEquals(f.name, expected, edges.size)
            assertEquals(f.name, edges.size, edges.map { it.id }.toSet().size)
            for ((id, offset) in model.offsets) assertTrue("${f.name} $id $offset", offset in 0 until psi.textLength)
            for (s in nodes) assertTrue(f.name, s.range.startOffset <= s.offset && s.offset < s.range.endOffset)
            for (e in edges) assertTrue("${f.name} ${e.id}", model.state(e.from)!!.range.containsOffset(e.offset))
        }
    }
}
