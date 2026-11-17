package io.github.saigyo.aslan.diagram

import com.intellij.testFramework.PlatformTestUtil
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.TransitionKind
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

class DiagramLayoutTest : DiagramTestCase() {
    private val measure = TextMeasure { text, _, _ -> 6.0 * text.length }

    private fun lay(model: DiagramModel, mode: LayoutMode, collapsed: Set<String> = emptySet()): DiagramLayout =
        DiagramLayouter.layout(model, mode, collapsed, measure)

    private fun large(): DiagramModel =
        DiagramBuilder.build(AslModel.of(myFixture.configureByFile("clean/film-archive-intake.asl.yaml"))!!)

    private fun DiagramModel.machines(): List<DiagramMachine> {
        val all = ArrayList<DiagramMachine>()
        fun visit(m: DiagramMachine) {
            all += m
            for (s in m.states) s.machines.forEach(::visit)
        }
        root?.let(::visit)
        return all
    }

    private fun DiagramModel.allStates(): List<DiagramState> = machines().flatMap { it.states }

    private fun DiagramModel.allEdges(): List<DiagramEdge> = machines().flatMap { it.edges }

    private fun DiagramLayout.node(id: String): LaidNode = nodes.single { it.id == id }

    private fun DiagramLayout.edge(id: String): LaidEdge = edges.single { it.edge.id == id }

    private fun Rectangle2D.holds(other: Rectangle2D) =
        other.minX >= minX - 0.01 && other.maxX <= maxX + 0.01 && other.minY >= minY - 0.01 && other.maxY <= maxY + 0.01

    private fun DiagramLayout.below(target: String, source: String) = node(target).bounds.minY >= node(source).bounds.maxY

    /** Whether [edge] lies on a cycle of its machine: its source is reachable from its target. */
    private fun leadsBack(machine: DiagramMachine, edge: DiagramEdge): Boolean {
        val seen = HashSet<String>()
        val todo = ArrayDeque(listOf(edge.to))
        while (todo.isNotEmpty()) {
            val id = todo.removeFirst()
            if (id == edge.from) return true
            if (!seen.add(id)) continue
            machine.edges.filter { it.from == id }.forEach { todo += it.to }
        }
        return false
    }

    private fun assertRightAngles(name: String, layout: DiagramLayout) {
        for (e in layout.edges) for ((a, b) in e.points.zipWithNext()) {
            assertTrue("$name ${e.edge.id} $a $b", abs(a.x - b.x) < 0.01 || abs(a.y - b.y) < 0.01)
        }
    }

    private fun assertNoOverlap(name: String, model: DiagramModel, layout: DiagramLayout) {
        val laid = layout.nodes.associateBy { it.id }
        val groups = model.machines().map { m -> m.states.mapNotNull { laid[it.id] } } +
            model.allStates().map { s -> s.machines.mapNotNull { laid[it.id] } }
        for (group in groups) for (i in group.indices) for (j in i + 1 until group.size) {
            assertFalse("$name ${group[i].id} ${group[j].id}", group[i].bounds.intersects(group[j].bounds))
        }
    }

    fun `test every state and every path has a place`() {
        for ((name, model) in definitions()) for (mode in LayoutMode.entries) {
            val layout = lay(model, mode)
            val states = layout.nodes.filter { it.kind != LaidNode.Kind.BRANCH }
            assertEquals("$name $mode", model.allStates().map { it.id }.sorted(), states.map { it.id }.sorted())
            for (node in states) assertSame(model.state(node.id), node.state)
            assertEquals("$name $mode", model.allEdges().map { it.id }.sorted(), layout.edges.map { it.edge.id }.sorted())
            for (e in layout.edges) {
                assertSame(model.edge(e.edge.id), e.edge)
                assertTrue("$name $mode ${e.edge.id}", e.points.size >= 2)
            }
            assertTrue("$name $mode", layout.width > 0 && layout.height > 0)
            for (node in layout.nodes) {
                assertTrue("$name $mode ${node.id}", Rectangle2D.Double(0.0, 0.0, layout.width, layout.height).holds(node.bounds))
            }
        }
    }

    fun `test a Map is a group and each Parallel branch a box inside its state`() {
        val model = build(archive).first
        for (mode in LayoutMode.entries) {
            val layout = lay(model, mode)
            val map = layout.node("s2")
            assertEquals(LaidNode.Kind.GROUP, map.kind)
            assertTrue(map.collapsible)
            assertFalse(map.collapsed)
            assertNull(map.title)
            for (inner in listOf("s3", "s4")) assertTrue("$mode $inner", map.bounds.holds(layout.node(inner).bounds))
            val parallel = layout.node("s5")
            assertEquals(LaidNode.Kind.GROUP, parallel.kind)
            val branches = model.state("s5")!!.machines
            assertEquals(listOf("Branch 1", "Branch 2"), branches.map { layout.node(it.id).title })
            for ((branch, inner) in branches.map { it.id }.zip(listOf("s6", "s7"))) {
                val box = layout.node(branch)
                assertEquals(LaidNode.Kind.BRANCH, box.kind)
                assertNull(box.state)
                assertFalse(box.collapsible)
                assertTrue("$mode $branch", parallel.bounds.holds(box.bounds))
                assertTrue("$mode $inner", box.bounds.holds(layout.node(inner).bounds))
            }
            // the branches stand side by side in file order
            val (first, second) = branches.map { layout.node(it.id).bounds }
            assertTrue("$mode", first.maxX <= second.minX && first.y == second.y)
            val plain = layout.node("s1")
            assertEquals(LaidNode.Kind.STATE, plain.kind)
            assertEquals(190.0, plain.bounds.width)
            assertEquals(40.0, plain.bounds.height)
            assertEquals(listOf(true, false), listOf("s0", "s1").map { layout.node(it).start })
            assertEquals(listOf(true, false), listOf("s3", "s4").map { layout.node(it).start })
        }
        // in every definition each child lies inside its parent, and a parent comes before its children
        for ((name, m) in definitions()) for (mode in LayoutMode.entries) {
            val layout = lay(m, mode)
            val index = layout.nodes.withIndex().associate { it.value.id to it.index }
            for (state in m.allStates()) for (machine in state.machines) {
                val children = if (machine.title != null) listOf(machine.id) else machine.states.map { it.id }
                for (child in children) {
                    assertTrue("$name $mode $child", layout.node(state.id).bounds.holds(layout.node(child).bounds))
                    assertTrue("$name $mode $child", index.getValue(state.id) < index.getValue(child))
                }
                if (machine.title != null) for (inner in machine.states) {
                    assertTrue("$name $mode ${inner.id}", layout.node(machine.id).bounds.holds(layout.node(inner.id).bounds))
                    assertTrue("$name $mode ${inner.id}", index.getValue(machine.id) < index.getValue(inner.id))
                }
            }
        }
    }

    fun `test collapsing one of two boxes of the same name shuts only that box`() {
        val model = build(twins).first
        for (mode in LayoutMode.entries) {
            val layout = lay(model, mode, setOf(secondEach))
            val boxes = layout.nodes.filter { it.collapsible && it.state!!.name == "Each" }
            assertEquals(listOf(firstEach, secondEach), boxes.map { it.state!!.path })
            assertEquals(listOf(false, true), boxes.map { it.collapsed })
            // the open Map still shows its inner state, the shut one does not
            assertEquals(1, layout.nodes.count { it.state?.name == "One" })
        }
    }

    fun `test a collapsed Map is a plain node and its inner states are not laid out`() {
        val model = build(archive).first
        for (mode in LayoutMode.entries) {
            val layout = lay(model, mode, setOf("Each", "Both"))
            for (id in listOf("s2", "s5")) {
                val node = layout.node(id)
                assertEquals(LaidNode.Kind.STATE, node.kind)
                assertTrue(node.collapsed)
                assertTrue(node.collapsible)
                assertEquals(190.0, node.bounds.width)
                assertEquals(40.0, node.bounds.height)
            }
            val branches = model.state("s5")!!.machines.map { it.id }
            assertEquals(listOf("s0", "s1", "s2", "s5", "s8", "s9"), layout.nodes.map { it.id })
            assertTrue(layout.nodes.none { it.id in branches })
            assertEquals(model.root!!.edges.map { it.id }.sorted(), layout.edges.map { it.edge.id }.sorted())
            assertTrue(layout.below("s5", "s2"))
        }
    }

    fun `test the layout flows downwards`() {
        for ((name, model) in definitions()) for (mode in LayoutMode.entries) {
            val layout = lay(model, mode)
            var down = 0
            for (machine in model.machines()) for (edge in machine.edges) {
                val below = layout.below(edge.to, edge.from)
                if (below) down++
                if (edge.kind == TransitionKind.NEXT && !leadsBack(machine, edge)) assertTrue("$name $mode ${edge.id}", below)
            }
            val all = model.allEdges().size
            assertTrue("$name $mode $down of $all", 3 * down >= 2 * all)
        }
    }

    fun `test every path runs in right angles`() {
        for ((name, model) in definitions()) for (mode in LayoutMode.entries) assertRightAngles("$name $mode", lay(model, mode))
    }

    fun `test no two nodes of one machine overlap`() {
        for ((name, model) in definitions()) for (mode in LayoutMode.entries) assertNoOverlap("$name $mode", model, lay(model, mode))
    }

    fun `test the start and end marks of a state stay clear of its group's texts and border`() {
        // the painter draws the start mark in the 12 pixels above a state and the end mark in the 14 below it; a group's
        // type line has its baseline 31 below the group's top, a branch's title 17; text reaches about 3 below its baseline
        val startMark = 12.0
        val endMark = 14.0
        val text = mapOf(LaidNode.Kind.GROUP to 34.0, LaidNode.Kind.BRANCH to 20.0)
        for ((name, model) in definitions()) for (mode in LayoutMode.entries) {
            val layout = lay(model, mode)
            for (machine in model.machines()) {
                val holder = layout.nodes.firstOrNull { it.id == machine.id }
                    ?: layout.nodes.firstOrNull { n -> n.state?.machines?.any { it.id == machine.id } == true }
                val area = holder?.bounds ?: Rectangle2D.Double(0.0, 0.0, layout.width, layout.height)
                val top = area.minY + (holder?.let { text.getValue(it.kind) } ?: 0.0)
                for (state in machine.states) {
                    val box = layout.node(state.id).bounds
                    if (state.id == machine.start) assertTrue("$name $mode ${state.id}", box.minY - startMark >= top)
                    assertTrue("$name $mode ${state.id}", box.maxY + endMark <= area.maxY)
                }
            }
        }
    }

    fun `test a path that leads back does not end where paths leave`() {
        for ((name, model) in definitions()) for (mode in LayoutMode.entries) {
            val layout = lay(model, mode)
            val ends = layout.edges.filter { it.edge.from != it.edge.to }.groupBy { it.edge.to }
            for (e in layout.edges) {
                if (e.edge.from == e.edge.to) continue
                val start = e.points.first()
                for (incoming in ends[e.edge.from].orEmpty()) {
                    val end = incoming.points.last()
                    assertFalse(
                        "$name $mode ${incoming.edge.id} ends where ${e.edge.id} leaves, at $end",
                        abs(end.x - start.x) < 0.01 && abs(end.y - start.y) < 0.01,
                    )
                }
            }
        }
    }

    fun `test no path leaves its state at the top or enters it at the bottom`() {
        for ((name, model) in definitions()) for (mode in LayoutMode.entries) {
            val layout = lay(model, mode)
            for (e in layout.edges) {
                val from = layout.node(e.edge.from).bounds
                val to = layout.node(e.edge.to).bounds
                assertTrue("$name $mode ${e.edge.id} leaves at the top", abs(e.points.first().y - from.minY) >= 0.5)
                assertTrue("$name $mode ${e.edge.id} enters at the bottom", abs(e.points.last().y - to.maxY) >= 0.5)
            }
        }
    }

    private fun path(id: String, from: String, vararg points: Pair<Double, Double>) = LaidEdge(
        DiagramEdge(id, TransitionKind.NEXT, from, "x", null, null, 0),
        points.map { (x, y) -> java.awt.geom.Point2D.Double(x, y) },
        null,
    )

    /** The places of a box's start mark (above it) and end mark (below it), as the painter draws them, with a pixel to spare. */
    private fun marks(node: LaidNode): List<Rectangle2D> {
        val b = node.bounds
        return listOfNotNull(
            if (node.start) Rectangle2D.Double(b.x + 10, b.y - 13, 12.0, 13.0) else null,
            if (node.state?.terminal == true && node.kind == LaidNode.Kind.STATE) Rectangle2D.Double(b.x + 10, b.maxY, 12.0, 15.0) else null,
        )
    }

    fun `test no path runs through a start or an end mark`() {
        for ((name, model) in definitions()) for (mode in LayoutMode.entries) {
            val layout = lay(model, mode)
            val hits = ArrayList<String>()
            for (node in layout.nodes) for (mark in marks(node)) for (e in layout.edges) {
                if (e.points.zipWithNext().any { (a, b) -> mark.intersectsLine(Line2D.Double(a, b)) }) hits += "${node.state?.name}/${e.edge.id}"
            }
            assertEquals("$name $mode $hits", 0, hits.size)
        }
    }

    fun `test two paths cross where a line of one passes through a line of the other`() {
        val down = path("a", "s", 50.0 to 0.0, 50.0 to 100.0)
        val across = path("b", "s", 0.0 to 50.0, 100.0 to 50.0)
        assertTrue(PathCrossings.cross(down, across))
        // touching at an end, meeting at a bend, and running side by side are no crossings
        assertFalse(PathCrossings.cross(down, path("c", "s", 50.0 to 100.0, 90.0 to 100.0)))
        assertFalse(PathCrossings.cross(down, path("d", "s", 0.0 to 0.0, 50.0 to 0.0)))
        assertFalse(PathCrossings.cross(down, path("e", "s", 60.0 to 0.0, 60.0 to 100.0)))
        assertFalse(PathCrossings.cross(down, path("f", "s", 0.0 to 150.0, 100.0 to 150.0)))
    }

    fun `test only paths of one state count as a crossing pair`() {
        val down = path("a", "s", 50.0 to 0.0, 50.0 to 100.0)
        val across = path("b", "s", 0.0 to 50.0, 100.0 to 50.0)
        val other = path("c", "t", 0.0 to 60.0, 100.0 to 60.0)
        val pairs = PathCrossings.pairs(DiagramLayout(100.0, 100.0, emptyList(), listOf(down, across, other)))
        assertEquals(listOf("a" to "b"), pairs.map { (p, q) -> p.edge.id to q.edge.id })
    }

    fun `test no two paths of one state cross in the compact layout`() {
        for ((name, model) in definitions()) {
            val pairs = PathCrossings.pairs(lay(model, LayoutMode.COMPACT))
            assertEquals("$name ${pairs.map { (a, b) -> a.edge.id + "/" + b.edge.id }}", 0, pairs.size)
        }
    }

    fun `test the loose layout has fewer crossing paths of one state than a single pass`() {
        for ((name, model) in definitions()) {
            val once = PathCrossings.pairs(DiagramLayouter.layoutOnce(model, LayoutMode.LOOSE, emptySet(), measure)).size
            val laid = PathCrossings.pairs(lay(model, LayoutMode.LOOSE)).size
            assertTrue("$name $laid > $once", laid <= once)
            if (once > 0) assertTrue("$name $laid >= $once", laid < once)
        }
    }

    fun `test a state that leads to itself and two edges between the same states lay out`() {
        val loops = build(
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
        ).first
        val twins = build(
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
        ).first
        for (mode in LayoutMode.entries) {
            val layout = lay(loops, mode)
            assertEquals(listOf("s0", "s1"), layout.nodes.map { it.id })
            assertEquals(listOf("s0.c0", "s0.c1", "s0.d", "s1.n"), layout.edges.map { it.edge.id }.sorted())
            for (e in layout.edges) assertTrue("$mode ${e.edge.id}", e.points.size >= 2)
            assertNotNull(layout.edge("s0.c0").label)
            assertRightAngles("loops $mode", layout)
            assertNoOverlap("loops $mode", loops, layout)
            val same = lay(twins, mode, setOf("A"))
            assertEquals(listOf("s0", "s1"), same.nodes.map { it.id })
            assertEquals(listOf("s0.n"), same.edges.map { it.edge.id })
            assertRightAngles("twins $mode", same)
            assertNoOverlap("twins $mode", twins, same)
        }
    }

    fun `test a label is cut to the layout's limit and lies on its path`() {
        val model = build(
            """
            StartAt: Sort
            States:
              Sort:
                Type: Choice
                Choices:
                  - Variable: §.reel.condition.assessment
                    StringEquals: needs-careful-restoration
                    Next: Restore
                Default: Shelve
              Restore:
                Type: Pass
                Next: Shelve
              Shelve:
                Type: Succeed
            """,
        ).first
        val full = model.edge("s0.c0")!!.label!!
        assertTrue(full.length > 30)
        for ((mode, limit) in listOf(LayoutMode.LOOSE to 30, LayoutMode.COMPACT to 20)) {
            val layout = lay(model, mode)
            val label = layout.edge("s0.c0").label!!
            assertEquals(full.take(limit - 1) + "…", label.text)
            assertEquals(6.0 * limit + 8, label.bounds.width)
            assertEquals(14.0, label.bounds.height)
            assertEquals("Default", layout.edge("s0.d").label!!.text)
            assertEquals(6.0 * 7 + 8, layout.edge("s0.d").label!!.bounds.width)
            assertNull(layout.edge("s1.n").label)
        }
        for ((name, m) in definitions() + ("labels" to model)) for (mode in LayoutMode.entries) {
            for (e in lay(m, mode).edges) {
                val label = e.label
                if (e.edge.label == null) {
                    assertNull("$name $mode ${e.edge.id}", label)
                    continue
                }
                assertNotNull("$name $mode ${e.edge.id}", label)
                assertEquals(DiagramLabels.shorten(e.edge.label!!, mode.maxLabel), label!!.text)
                val area = Rectangle2D.Double(label.bounds.x - 0.5, label.bounds.y - 0.5, label.bounds.width + 1, label.bounds.height + 1)
                val crossed = e.points.zipWithNext().any { (a, b) -> Line2D.Double(a, b).intersects(area) }
                assertTrue("$name $mode ${e.edge.id} ${label.bounds} ${e.points}", crossed)
            }
        }
    }

    fun `test the compact layout of the large definition is at most 2200 wide`() {
        val model = large()
        val compact = lay(model, LayoutMode.COMPACT)
        val loose = lay(model, LayoutMode.LOOSE)
        assertTrue("${compact.width}", compact.width <= 2200)
        assertTrue("${loose.width} ${compact.width}", loose.width > compact.width)
    }

    fun `test a state reached only through a Catcher keeps its place in the flow`() {
        val small = build(archive).first
        for (mode in LayoutMode.entries) assertTrue("$mode", lay(small, mode).below("s9", "s1"))
        for ((name, model) in definitions()) for (mode in LayoutMode.entries) {
            val layout = lay(model, mode)
            for (machine in model.machines()) for (state in machine.states) {
                val incoming = machine.edges.filter { it.to == state.id && it.from != state.id }
                if (incoming.isEmpty() || incoming.any { it.kind != TransitionKind.CATCH }) continue
                for (edge in incoming) assertTrue("$name $mode ${edge.id}", layout.below(state.id, edge.from))
            }
        }
    }

    fun `test the large definition lays out in under two seconds`() {
        val model = large()
        for (mode in LayoutMode.entries) {
            lay(model, mode) // warm-up: the layout library's start-up is not measured
            val millis = (1..3).minOf {
                val start = System.nanoTime()
                lay(model, mode)
                (System.nanoTime() - start) / 1_000_000
            }
            assertTrue("$mode $millis ms", millis < 2000)
        }
    }

    fun `test an empty model and a Map without a machine lay out`() {
        for (mode in LayoutMode.entries) {
            val empty = lay(DiagramModel(null, emptyMap(), 0), mode)
            assertEquals(0.0, empty.width)
            assertEquals(0.0, empty.height)
            assertEquals(emptyList<LaidNode>(), empty.nodes)
            assertEquals(emptyList<LaidEdge>(), empty.edges)
        }
        val model = build("StartAt: A\nStates:\n  A:\n    Type: Map\n    Next: A\n  B:\n    Type: Parallel\n    Branches: x").first
        for (mode in LayoutMode.entries) {
            val layout = lay(model, mode, setOf("A"))
            for (id in listOf("s0", "s1")) {
                val node = layout.node(id)
                assertEquals(LaidNode.Kind.STATE, node.kind)
                assertFalse(node.collapsible)
                assertFalse(node.collapsed)
            }
            assertEquals(listOf("s0.n"), layout.edges.map { it.edge.id })
            assertRightAngles("$mode", layout)
        }
        // the half-typed files of the builder test lay out without an exception
        val texts = listOf(
            "StartAt: A\nStates:\n  A:\n    Next: B\n  B:\n    Next: A",
            "StartAt: A\nStates: {}",
            "StartAt: A\nStates:\n  A:\n    Type: Map\n    ItemProcessor:\n      StartAt: X\n      States: {}\n    End: true",
            "StartAt: A\nStates:\n  A:\n    Type: Parallel\n    Branches:\n      - StartAt: X\n        States: {}\n    End: true",
        )
        for (text in texts) for (mode in LayoutMode.entries) lay(build(text).first, mode)
    }

    fun `test a layout whose flag is set ends with a cancellation`() {
        val model = build(archive).first
        // the flag is set after ELK has asked it once: so ELK asks it, and the layouter does not read the unfinished graph
        var asked = 0
        val thrown = try {
            DiagramLayouter.layout(model, LayoutMode.LOOSE, emptySet(), measure) { ++asked > 1 }
            null
        } catch (e: CancellationException) {
            e
        }
        assertNotNull("the layout was not cancelled after $asked questions", thrown)
        assertTrue("$asked", asked >= 2)
        // a flag that is never set lets the layout end
        val whole = DiagramLayouter.layout(model, LayoutMode.LOOSE, emptySet(), measure) { false }
        assertEquals(lay(model, LayoutMode.LOOSE).nodes.map { it.id }, whole.nodes.map { it.id })
    }

    fun `test an interrupt does not cancel a layout and is kept`() {
        val model = build(archive).first
        // the outcome of the layout, and whether its thread was still interrupted after it
        val outcome = CompletableFuture<Pair<Any, Boolean>>()
        val thread = Thread({
            Thread.currentThread().interrupt()
            val result: Any = try {
                lay(model, LayoutMode.LOOSE)
            } catch (e: Throwable) {
                e
            }
            outcome.complete(result to Thread.interrupted())
        }, "interrupted layout")
        thread.start()
        val (result, interrupted) = PlatformTestUtil.waitForFuture(outcome)
        assertInstanceOf(result, DiagramLayout::class.java)
        assertEquals(model.state("s0")!!.id, (result as DiagramLayout).nodes.first().id)
        assertTrue("the thread is still interrupted", interrupted)
    }
}
