package io.github.saigyo.aslan.diagram

import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.TransitionKind
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File

class DiagramSceneTest : DiagramTestCase() {
    private val measure = TextMeasure { text, _, _ -> 6.0 * text.length }

    private fun layout(model: DiagramModel, mode: LayoutMode = LayoutMode.LOOSE, collapsed: Set<String> = emptySet()) =
        DiagramLayouter.layout(model, mode, collapsed, measure)

    private fun sceneOf(
        model: DiagramModel, selected: String? = null, catches: CatchMode = CatchMode.NORMAL,
        mode: LayoutMode = LayoutMode.LOOSE, collapsed: Set<String> = emptySet(),
    ) = DiagramScene.of(layout(model, mode, collapsed), selected, catches, measure)

    private fun DiagramScene.boxes() = items.filterIsInstance<SceneItem.Box>()
    private fun DiagramScene.paths() = items.filterIsInstance<SceneItem.Path>()
    private fun DiagramScene.labels() = items.filterIsInstance<SceneItem.Label>()
    private fun DiagramScene.toggles() = items.filterIsInstance<SceneItem.Toggle>()
    private fun DiagramScene.box(id: String) = boxes().single { it.id == id }
    private fun DiagramScene.path(id: String) = paths().single { it.id == id }
    private fun DiagramScene.label(id: String) = labels().single { it.edgeId == id }

    private fun small() = build(archive).first

    private fun center(b: Rectangle2D) = b.centerX to b.centerY

    /** A state definition with one state named [name], followed by a Succeed state. */
    private fun named(name: String, extra: String = ""): DiagramModel = build(
        "StartAt: \"$name\"\nStates:\n  \"$name\":\n    Type: Pass\n    Next: Done\n$extra  Done:\n    Type: Succeed\n"
    ).first

    private fun definitions(): List<Pair<String, DiagramModel>> {
        val files = File("src/test/testData/clean").listFiles().orEmpty().filter { ".asl." in it.name }.sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        val committed = files.map { f -> f.name to DiagramBuilder.build(AslModel.of(myFixture.configureByText(f.name, f.readText()))!!) }
        return listOf("small" to small()) + committed
    }

    fun `test each node and edge of the layout has its items`() {
        val model = small()
        for (mode in LayoutMode.entries) {
            val lay = layout(model, mode)
            val scene = DiagramScene.of(lay, null, CatchMode.NORMAL, measure)
            assertEquals(lay.nodes.map { it.id }, scene.boxes().map { it.id })
            assertEquals(lay.edges.map { it.edge.id }, scene.paths().map { it.id })
            assertEquals(lay.edges.filter { it.label != null }.map { it.edge.id }.sorted(), scene.labels().map { it.edgeId }.sorted())
            assertEquals(lay.nodes.filter { it.collapsible }.map { it.state!!.name }.sorted(), scene.toggles().map { it.stateName }.sorted())
            assertEquals(listOf("Each", "Both"), scene.toggles().map { it.stateName })
            assertEquals(lay.width, scene.width, 0.0)
            assertEquals(lay.height, scene.height, 0.0)
            // the order: boxes, paths, labels, toggles
            val kinds = scene.items.map { it::class }
            assertEquals(kinds.sortedBy { listOf(SceneItem.Box::class, SceneItem.Path::class, SceneItem.Label::class, SceneItem.Toggle::class).indexOf(it) }, kinds)
            val sort = scene.box("s0")
            assertEquals("Choice", sort.type)
            assertEquals("Sort", sort.name)
            assertEquals("Choice", sort.sub)
            assertTrue(sort.start)
            assertTrue(scene.box("s8").terminal)
            assertFalse(scene.box("s0").terminal)
            assertEquals(LaidNode.Kind.GROUP, scene.box("s2").kind)
            assertEquals(LaidNode.Kind.BRANCH, scene.box(model.state("s5")!!.machines[0].id).kind)
            assertEquals(TransitionKind.CATCH, scene.path("s1.k0").kind)
        }
    }

    fun `test the selected state is marked, and so are a selected path and its two ends`() {
        val model = small()
        val state = sceneOf(model, "s1")
        assertEquals(listOf("s1"), state.boxes().filter { it.selected }.map { it.id })
        assertTrue(state.boxes().none { it.linked })
        assertTrue(state.paths().none { it.selected })

        val edge = sceneOf(model, "s0.c0")
        assertEquals(listOf("s0.c0"), edge.paths().filter { it.selected }.map { it.id })
        assertEquals(listOf("s0.c0"), edge.labels().filter { it.selected }.map { it.edgeId })
        assertEquals(listOf("s0", "s1"), edge.boxes().filter { it.linked }.map { it.id })
        assertTrue(edge.boxes().none { it.selected })

        val unknown = sceneOf(model, "nothing")
        assertTrue(unknown.items.none { (it as? SceneItem.Box)?.let { b -> b.selected || b.linked } ?: false })
        assertTrue(unknown.paths().none { it.selected })
    }

    fun `test the selected path lies on top`() {
        val scene = sceneOf(small(), "s0.c0")
        assertEquals("s0.c0", (scene.items[scene.items.size - 2] as SceneItem.Path).id)
        assertEquals("s0.c0", (scene.items.last() as SceneItem.Label).edgeId)
        // a path without a label is the last item alone
        val plain = sceneOf(small(), "s1.n")
        assertEquals("s1.n", (plain.items.last() as SceneItem.Path).id)
    }

    fun `test catch paths are muted on demand and absent when hidden`() {
        val model = small()
        val normal = sceneOf(model)
        val muted = sceneOf(model, catches = CatchMode.MUTED)
        val hidden = sceneOf(model, catches = CatchMode.HIDDEN)
        assertTrue(normal.paths().none { it.muted } && normal.labels().none { it.muted })
        assertEquals(listOf("s1.k0"), muted.paths().filter { it.muted }.map { it.id })
        assertEquals(listOf("s1.k0"), muted.labels().filter { it.muted }.map { it.edgeId })
        assertTrue(hidden.paths().none { it.kind == TransitionKind.CATCH })
        assertTrue(hidden.labels().none { it.edgeId == "s1.k0" })
        assertEquals(normal.paths().size - 1, hidden.paths().size)
        fun placed(s: DiagramScene) = s.items.filterNot { (it as? SceneItem.Path)?.kind == TransitionKind.CATCH || (it as? SceneItem.Label)?.edgeId == "s1.k0" }
        for (other in listOf(muted, hidden)) {
            val a = placed(normal)
            val b = placed(other)
            assertEquals(a.size, b.size)
            for ((x, y) in a.zip(b)) assertEquals(boundsOf(x), boundsOf(y))
        }
    }

    private fun boundsOf(item: SceneItem): Any = when (item) {
        is SceneItem.Box -> item.bounds
        is SceneItem.Path -> item.points
        is SceneItem.Label -> item.bounds
        is SceneItem.Toggle -> item.bounds
    }.let { if (it is List<*>) it.map { p -> p.toString() } else it }

    fun `test a selection inside a collapsed box marks the box`() {
        val model = small()
        val shut = setOf("Each")
        val box = sceneOf(model, "s2", collapsed = shut).box("s2")
        assertTrue(box.collapsed)
        assertEquals(LaidNode.Kind.STATE, box.kind)
        assertTrue(box.selected)
        assertTrue(sceneOf(model, "s3", collapsed = shut).box("s2").selected)
        assertTrue(sceneOf(model, "s3.n", collapsed = shut).box("s2").selected)
        assertFalse(sceneOf(model, "s3", collapsed = shut).box("s1").selected)
        // open, the box is not selected by what it holds
        assertFalse(sceneOf(model, "s3").box("s2").selected)
        // a state that is outside the box does not mark it
        assertFalse(sceneOf(model, "s5", collapsed = shut).box("s2").selected)
    }

    fun `test a long name is cut and whole on hover`() {
        val long = "N" + "a".repeat(79)
        val scene = sceneOf(named(long))
        val box = scene.boxes().single { it.type == "Pass" }
        assertTrue(box.name, box.name.endsWith("…"))
        assertTrue(box.name.length < long.length)
        assertTrue(box.name.length * 6.0 <= box.bounds.width)
        assertEquals(long, box.tooltip)
        assertEquals("Pass", box.sub)
        assertNull(scene.boxes().single { it.type == "Succeed" }.tooltip)
        assertNull(sceneOf(small()).box("s0").tooltip)
    }

    fun `test a long sub line is cut and follows the whole name on hover`() {
        val resource = "arn:aws:s3:::" + "b".repeat(80)
        val model = build("StartAt: A\nStates:\n  A:\n    Type: Task\n    Resource: $resource\n    End: true\n").first
        val box = sceneOf(model).boxes().single { it.type == "Task" }
        assertTrue(box.sub, box.sub.endsWith("…"))
        // the resource is one word longer than a line of the hover text, so it is broken
        assertEquals("A\n" + "Task ·\n" + resource.take(80) + "\n" + resource.drop(80), box.tooltip)
    }

    fun `test a long hover text is wrapped at white space and cut`() {
        val condition = (1..60).joinToString(" and ") { "§reel$it = 'nitrate'" }.replace('§', '$')
        assertTrue(condition.length > 1000)
        val model = build(
            "QueryLanguage: JSONata\nStartAt: S\nStates:\n  S:\n    Type: Choice\n    Choices:\n" +
                "      - Condition: \"{% $condition %}\"\n        Next: Done\n    Default: Done\n  Done:\n    Type: Succeed\n"
        ).first
        val tooltip = sceneOf(model).label("s0.c0").tooltip!!
        val lines = tooltip.split('\n')
        assertTrue(tooltip, lines.size > 1)
        for (line in lines) assertTrue(line, line.length <= 80)
        // a line ends at the last white space that lets it fit: the next word would not have fitted
        for ((line, next) in lines.zipWithNext()) assertTrue("$line|$next", line.length + 1 + next.substringBefore(' ').length > 80)
        // the breaks take the place of spaces, and the whole is cut at 600 characters
        assertEquals(DiagramLabels.shorten(condition, 600), tooltip.replace('\n', ' '))
        assertTrue(tooltip.endsWith("…"))

        // a word longer than a line is broken
        val name = "N" + "a".repeat(199)
        assertEquals(listOf(name.take(80), name.substring(80, 160), name.drop(160)), sceneOf(named(name)).boxes().single { it.type == "Pass" }.tooltip!!.split('\n'))
    }

    fun `test texts that look like markup are kept as they are`() {
        val scene = sceneOf(named("<html><b>Scan"))
        assertEquals("<html><b>Scan", scene.boxes().single { it.type == "Pass" }.name)
        assertNull(scene.boxes().single { it.type == "Pass" }.tooltip)

        val long = "<html>" + "x".repeat(74)
        assertEquals(80, long.length)
        val cut = sceneOf(named(long)).boxes().single { it.type == "Pass" }
        assertTrue(cut.name.startsWith("<html>"))
        assertEquals(long, cut.tooltip)

        val condition = "<html>x = 1 and y = ".padEnd(40, '2')
        assertEquals(40, condition.length)
        val model = build(
            "QueryLanguage: JSONata\nStartAt: S\nStates:\n  S:\n    Type: Choice\n    Choices:\n" +
                "      - Condition: \"{% $condition %}\"\n        Next: Done\n    Default: Done\n  Done:\n    Type: Succeed\n"
        ).first
        val s = sceneOf(model)
        val label = s.labels().single { it.edgeId == "s0.c0" }
        assertTrue(label.text, label.text.startsWith("<html>"))
        assertTrue(label.text.endsWith("…"))
        assertEquals(condition, label.tooltip)
        assertNull(s.labels().single { it.edgeId == "s0.d" }.tooltip)
    }

    fun `test the tooltip of a label is the title of its edge`() {
        val scene = sceneOf(small())
        val label = scene.label("s0.c0")
        assertEquals(small().edge("s0.c0")!!.title, label.tooltip)
        assertEquals("States.ALL", scene.label("s1.k0").tooltip)
        val bounds = label.bounds
        assertEquals(label.tooltip, scene.tooltip(bounds.centerX, bounds.centerY, 4.0))
    }

    private fun Rectangle2D.inner() = Rectangle2D.Double(x + 4, y + 4, width - 8, height - 8)

    fun `test hit finds the innermost state, a path near its line, and a toggle before its box`() {
        val scene = sceneOf(small())
        val frame = scene.box("s3").bounds
        assertEquals(Hit.State("s3"), scene.hit(frame.centerX, frame.centerY, 4.0))
        val map = scene.box("s2").bounds
        assertEquals(Hit.State("s2"), scene.hit(map.x + 3, map.y + 3, 1.0))
        val toggle = scene.toggles().single { it.stateName == "Each" }.bounds
        assertTrue(map.contains(toggle))
        assertEquals(Hit.Toggle("Each"), scene.hit(toggle.centerX, toggle.centerY, 4.0))
        val points = scene.path("s0.c0").points
        // a point on the first segment, away from the label
        val a = points[0]
        val b = points[1]
        val mid = if (a.distance(b) > 0) java.awt.geom.Point2D.Double((a.x + b.x) / 2, (a.y + b.y) / 2) else a
        val hit = scene.hit(mid.x, mid.y, 4.0)
        assertTrue(hit.toString(), hit is Hit.Edge)
        // a path inside a group is found, not the group
        val inner = scene.path("s3.n").points
        val p = inner[0].let { java.awt.geom.Point2D.Double((it.x + inner[1].x) / 2, (it.y + inner[1].y) / 2) }
        assertEquals(Hit.Edge("s3.n"), scene.hit(p.x, p.y, 4.0))
    }

    fun `test a click on the empty part of a branch hits the Parallel state`() {
        val model = small()
        val scene = sceneOf(model)
        val branch = scene.box(model.state("s5")!!.machines[0].id).bounds
        val left = scene.box("s6").bounds
        // just inside the branch, below its title, left of the state in it
        val x = branch.x + 2
        val y = left.centerY
        assertTrue(branch.contains(x, y) && !left.contains(x, y))
        assertEquals(Hit.State("s5"), scene.hit(x, y, 1.0))
        assertEquals(Hit.State("s6"), scene.hit(left.centerX, left.centerY, 1.0))
        // the title of the branch too
        assertEquals(Hit.State("s5"), scene.hit(branch.centerX, branch.y + 4, 1.0))
    }

    fun `test the tolerance of a path is the one passed`() {
        val scene = sceneOf(small())
        val points = scene.path("s0.d").points
        // find a segment and a point 10 away from it, in the open
        for ((a, b) in points.zipWithNext()) {
            if (a.distance(b) < 2) continue
            val horizontal = a.y == b.y
            val x = if (horizontal) (a.x + b.x) / 2 else a.x + 10
            val y = if (horizontal) a.y + 10 else (a.y + b.y) / 2
            if (scene.boxes().any { it.kind != LaidNode.Kind.BRANCH && it.bounds.contains(x, y) } ||
                scene.paths().any { it.id != "s0.d" && near(it, x, y, 20.0) }) continue
            assertNull(scene.hit(x, y, 4.0))
            assertEquals(Hit.Edge("s0.d"), scene.hit(x, y, 20.0))
            return
        }
        fail("no segment with open space beside it")
    }

    private fun near(p: SceneItem.Path, x: Double, y: Double, tolerance: Double) =
        p.points.zipWithNext().any { (a, b) -> java.awt.geom.Line2D.ptSegDist(a.x, a.y, b.x, b.y, x, y) <= tolerance }

    fun `test bounds of a state inside a collapsed box are the box's`() {
        val model = small()
        val shut = sceneOf(model, collapsed = setOf("Each"))
        val box = shut.box("s2").bounds
        assertEquals(box, shut.bounds("s2"))
        assertEquals(box, shut.bounds("s3"))
        assertEquals(box, shut.bounds("s3.n"))
        assertNull(shut.bounds("nothing"))
        val open = sceneOf(model)
        assertEquals(open.box("s3").bounds, open.bounds("s3"))
        val line = open.bounds("s0.c0")!!
        val points = open.path("s0.c0").points
        assertEquals(points.minOf { it.x }, line.minX, 0.001)
        assertEquals(points.maxY(), line.maxY, 0.001)
    }

    private fun List<java.awt.geom.Point2D.Double>.maxY() = maxOf { it.y }

    fun `test a label counts as its path and a hidden catch path is not hit`() {
        val model = small()
        val scene = sceneOf(model)
        val label = scene.label("s0.c0").bounds
        assertEquals(Hit.Edge("s0.c0"), scene.hit(label.centerX, label.centerY, 4.0))
        val catchLabel = scene.label("s1.k0").bounds
        assertEquals(Hit.Edge("s1.k0"), scene.hit(catchLabel.centerX, catchLabel.centerY, 4.0))
        val hidden = sceneOf(model, catches = CatchMode.HIDDEN)
        val a = hidden.hit(catchLabel.centerX, catchLabel.centerY, 4.0)
        assertTrue(a.toString(), a != Hit.Edge("s1.k0"))
        for ((p, q) in scene.path("s1.k0").points.zipWithNext()) {
            assertTrue(hidden.hit((p.x + q.x) / 2, (p.y + q.y) / 2, 4.0) != Hit.Edge("s1.k0"))
        }
    }

    fun `test the background is not hit`() {
        val scene = sceneOf(small())
        assertNull(scene.hit(-50.0, -50.0, 4.0))
        assertNull(scene.hit(scene.width + 50, scene.height + 50, 4.0))
        assertNull(scene.tooltip(-50.0, -50.0, 4.0))
    }

    fun `test a diagram paints without a window`() {
        for ((name, model) in definitions()) for (mode in LayoutMode.entries) {
            val scene = sceneOf(model, mode = mode)
            for (colors in listOf(DiagramColors.current(), DiagramColors.light(), DiagramColors.dark())) {
                val image = BufferedImage(scene.width.toInt() + 40, scene.height.toInt() + 40, BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                g.color = colors.background
                g.fillRect(0, 0, image.width, image.height)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                DiagramPainter.paint(g, scene, colors)
                g.dispose()
                val first = scene.boxes().first { it.type != null }
                val x = first.bounds.x.toInt() + 2
                val y = first.bounds.centerY.toInt()
                val expected = DiagramColors.typeColor(first.type, colors.isDark)
                assertEquals("$name $mode ${first.type}", expected.rgb, image.getRGB(x, y))
            }
        }
    }

    fun `test the colours of the types are the fixed ones`() {
        val expected = mapOf(
            "Task" to 0x56A8F5, "Choice" to 0xE0A84E, "Pass" to 0x8A8D93, "Wait" to 0x7E8CE0,
            "Succeed" to 0x3FB8AF, "Fail" to 0xDB5C5C, "Parallel" to 0xC77DBB, "Map" to 0x6AAB73,
        )
        for ((type, rgb) in expected) assertEquals(type, Color(rgb).rgb, DiagramColors.typeColor(type, false).rgb)
        for ((type, rgb) in expected) {
            val dark = if (type == "Pass") 0xA5A8AE else rgb
            assertEquals(type, Color(dark).rgb, DiagramColors.typeColor(type, true).rgb)
        }
        assertEquals(DiagramColors.typeColor("Pass", false), DiagramColors.typeColor(null, false))
        assertEquals(DiagramColors.typeColor("Pass", true), DiagramColors.typeColor("Nonsense", true))
        assertEquals(Color(0xDB5C5C).rgb, DiagramColors.light().catchColor.rgb)
    }

    fun `test the result of bounds is a copy`() {
        val scene = sceneOf(small())
        val box = scene.bounds("s0")!!
        val before = Rectangle2D.Double().also { it.setRect(box) }
        (box as Rectangle2D.Double).add(5000.0, 5000.0)
        assertEquals(before, scene.bounds("s0"))
        assertEquals(before, scene.box("s0").bounds)
        val path = scene.bounds("s0.c0")!!
        val pathBefore = Rectangle2D.Double().also { it.setRect(path) }
        (path as Rectangle2D.Double).add(5000.0, 5000.0)
        assertEquals(pathBefore, scene.bounds("s0.c0"))
    }

    fun `test a branch title is cut by the size it is painted at`() {
        val model = small()
        val branchId = model.state("s5")!!.machines[0].id
        val room = layout(model).nodes.single { it.id == branchId }.bounds.width - DiagramScene.PAD_LEFT - DiagramScene.PAD_RIGHT
        val k = room / (8 * 11.0)   // "Branch 1" is wider than the room at 12 points, narrower at 10
        val wide = TextMeasure { text, size, _ -> k * size * text.length }
        val scene = DiagramScene.of(layout(model), null, CatchMode.NORMAL, wide)
        assertEquals("Branch 1", scene.box(branchId).name)
    }

    fun `test a muted label hides the line behind it`() {
        val path = SceneItem.Path("p", TransitionKind.NEXT, listOf(java.awt.geom.Point2D.Double(0.0, 50.0), java.awt.geom.Point2D.Double(100.0, 50.0)), false, true)
        val label = SceneItem.Label("p", "", Rectangle2D.Double(40.0, 43.0, 20.0, 14.0), null, false, true)
        val scene = DiagramScene(listOf(path, label), 100.0, 100.0)
        val colors = DiagramColors.light()
        val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = colors.background
        g.fillRect(0, 0, 100, 100)
        DiagramPainter.paint(g, scene, colors)
        g.dispose()
        assertEquals(colors.background.rgb, image.getRGB(50, 50))
        assertTrue(colors.background.rgb != image.getRGB(20, 50))   // the line itself is there, faint
    }
}
