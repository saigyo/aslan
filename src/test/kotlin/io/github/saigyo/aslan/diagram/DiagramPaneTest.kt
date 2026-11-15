package io.github.saigyo.aslan.diagram

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.ActionLink
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.TransitionKind
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.SwingUtilities

class DiagramPaneTest : DiagramTestCase() {
    private lateinit var oldCatchMode: CatchMode
    private var oldCollapsed = false

    override fun setUp() {
        super.setUp()
        // the application's properties are shared by all test classes
        oldCatchMode = DiagramPrefs.catchMode
        oldCollapsed = DiagramPrefs.detailsCollapsed
        DiagramPrefs.catchMode = CatchMode.NORMAL
        DiagramPrefs.detailsCollapsed = false
    }

    override fun tearDown() {
        try {
            DiagramPrefs.catchMode = oldCatchMode
            DiagramPrefs.detailsCollapsed = oldCollapsed
        } finally {
            super.tearDown()
        }
    }

    /** A pane of [width] by [height] with its components laid out; without a size when [width] is 0. */
    private fun pane(width: Int = 900, height: Int = 900): DiagramPane {
        val pane = DiagramPane(testRootDisposable)
        if (width > 0) resize(pane, width, height)
        return pane
    }

    private fun resize(pane: DiagramPane, width: Int, height: Int) {
        pane.component.setSize(width, height)
        layOut(pane.component)
    }

    private fun layOut(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach(::layOut)
    }

    private fun lay(model: DiagramModel, mode: LayoutMode, pane: DiagramPane, collapsed: Set<String> = emptySet()) =
        DiagramLayouter.layout(model, mode, collapsed, pane.measure)

    private fun small() = build(archive).first

    private fun large(): DiagramModel =
        DiagramBuilder.build(AslModel.of(myFixture.configureByFile("clean/film-archive-intake.asl.yaml"))!!)

    private fun DiagramPane.scene() = diagram.scene!!
    private fun DiagramPane.box(id: String) = scene().items.filterIsInstance<SceneItem.Box>().single { it.id == id }
    private fun DiagramPane.boxes() = scene().items.filterIsInstance<SceneItem.Box>()
    private fun DiagramPane.paths() = scene().items.filterIsInstance<SceneItem.Path>()

    /** Where the point (x, y) of the diagram lies in the diagram panel. */
    private fun DiagramPane.screen(x: Double, y: Double): Pair<Int, Int> {
        val v = diagram.viewport!!
        return Math.round(x * v.scale + v.tx).toInt() to Math.round(y * v.scale + v.ty).toInt()
    }

    private fun DiagramPane.onScreen(rect: Rectangle2D): Rectangle2D {
        val v = diagram.viewport!!
        return Rectangle2D.Double(rect.x * v.scale + v.tx, rect.y * v.scale + v.ty, rect.width * v.scale, rect.height * v.scale)
    }

    /** Whether [rect] lies in the diagram panel, at least [inset] pixels from its edges. */
    private fun DiagramPane.visible(rect: Rectangle2D, inset: Double = 0.0): Boolean =
        Rectangle2D.Double(inset, inset, diagram.width - 2 * inset, diagram.height - 2 * inset).contains(onScreen(rect))

    private fun mouse(pane: DiagramPane, id: Int, x: Int, y: Int, modifiers: Int = 0, button: Int = MouseEvent.BUTTON1, clicks: Int = 1) =
        pane.diagram.dispatchEvent(MouseEvent(pane.diagram, id, System.currentTimeMillis(), modifiers, x, y, clicks, false, button))

    private fun click(pane: DiagramPane, at: Pair<Int, Int>) {
        val (x, y) = at
        mouse(pane, MouseEvent.MOUSE_PRESSED, x, y, InputEvent.BUTTON1_DOWN_MASK)
        mouse(pane, MouseEvent.MOUSE_RELEASED, x, y)
        mouse(pane, MouseEvent.MOUSE_CLICKED, x, y)
    }

    private fun drag(pane: DiagramPane, from: Pair<Int, Int>, by: Pair<Int, Int>) {
        val (x, y) = from
        mouse(pane, MouseEvent.MOUSE_PRESSED, x, y, InputEvent.BUTTON1_DOWN_MASK)
        mouse(pane, MouseEvent.MOUSE_DRAGGED, x + by.first / 2, y + by.second / 2, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON, 0)
        mouse(pane, MouseEvent.MOUSE_DRAGGED, x + by.first, y + by.second, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON, 0)
        mouse(pane, MouseEvent.MOUSE_RELEASED, x + by.first, y + by.second, clicks = 0)
    }

    private fun wheel(pane: DiagramPane, x: Int, y: Int, rotation: Double, modifiers: Int = 0) =
        pane.diagram.dispatchEvent(
            MouseWheelEvent(
                pane.diagram, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), modifiers, x, y, x, y, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation.toInt(), rotation,
            ),
        )

    private fun center(rect: Rectangle2D) = rect.centerX to rect.centerY

    private fun DiagramPane.at(rect: Rectangle2D) = center(rect).let { (x, y) -> screen(x, y) }

    private fun perform(action: ToggleAction) = action.actionPerformed(TestActionEvent.createTestEvent(action))

    private fun selected(action: ToggleAction) = action.isSelected(TestActionEvent.createTestEvent(action))

    /** The text and whether the action is enabled, after an update. */
    private fun caption(action: AnAction): Pair<String?, Boolean> {
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        return event.presentation.text to event.presentation.isEnabled
    }

    private fun components(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components.orEmpty().flatMap(::components))

    private fun DiagramPane.links(): List<ActionLink> = components(details.content).filterIsInstance<ActionLink>()

    private fun DiagramPane.texts(): List<String> = components(details.content).mapNotNull {
        when (it) {
            is JLabel -> it.text
            is AbstractButton -> it.text
            else -> null
        }
    }

    /** The labels of the rows the details panel shows. */
    private fun DiagramPane.rowLabels(): List<String> = details.rows.map { it.label }

    fun `test a click on a state selects it and reports it`() {
        val model = small()
        val pane = pane()
        val selects = ArrayList<String?>()
        pane.onSelect = { selects += it }
        pane.show(model, lay(model, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, null)
        val frame = pane.box("s3").bounds
        assertTrue(pane.visible(frame))
        click(pane, pane.at(frame))
        assertEquals(listOf<String?>("s3"), selects)
        assertEquals(listOf("s3"), pane.boxes().filter { it.selected }.map { it.id })
        assertEquals(model.details.getValue("s3").map { it.label }, pane.rowLabels())
        assertTrue(pane.rowLabels().isNotEmpty())
        assertEquals(DiagramText.literal("Frame"), pane.details.header.text)
        // the diagram can hold the keyboard focus, so a click in it does not leave the focus in the editor
        assertTrue(pane.diagram.isFocusable)
    }

    fun `test a click on a path selects the path and shows no details`() {
        val model = small()
        val pane = pane()
        val selects = ArrayList<String?>()
        pane.onSelect = { selects += it }
        pane.show(model, lay(model, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, "s1")
        assertEquals(model.details.getValue("s1").map { it.label }, pane.rowLabels())
        val label = pane.scene().items.filterIsInstance<SceneItem.Label>().single { it.edgeId == "s0.c0" }.bounds
        click(pane, pane.at(label))
        assertEquals(listOf<String?>("s0.c0"), selects)
        assertEquals(listOf("s0.c0"), pane.paths().filter { it.selected }.map { it.id })
        assertEquals(listOf("s0", "s1"), pane.boxes().filter { it.linked }.map { it.id })
        assertTrue(pane.boxes().none { it.selected })
        assertEquals(emptyList<String>(), pane.rowLabels())
    }

    fun `test a click on the background clears the selection`() {
        val model = small()
        val pane = pane()
        val selects = ArrayList<String?>()
        pane.onSelect = { selects += it }
        pane.show(model, lay(model, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, null)
        click(pane, pane.at(pane.box("s1").bounds))
        assertEquals(listOf<String?>("s1"), selects)
        val x = pane.diagram.width - 3
        val y = pane.diagram.height - 3
        val v = pane.diagram.viewport!!
        assertNull(pane.scene().hit((x - v.tx) / v.scale, (y - v.ty) / v.scale, 4.0))
        click(pane, x to y)
        assertEquals(listOf("s1", null), selects)
        assertTrue(pane.boxes().none { it.selected || it.linked })
        assertEquals(emptyList<String>(), pane.rowLabels())
    }

    fun `test a click on a toggle reports the box`() {
        val model = small()
        val pane = pane()
        val toggles = ArrayList<String>()
        val selects = ArrayList<String?>()
        pane.onToggleBox = { toggles += it }
        pane.onSelect = { selects += it }
        pane.show(model, lay(model, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, null)
        val toggle = pane.scene().items.filterIsInstance<SceneItem.Toggle>().single { it.stateName == "Each" }.bounds
        click(pane, pane.at(toggle))
        assertEquals(listOf("Each"), toggles)
        assertEquals(emptyList<String?>(), selects)
    }

    fun `test the first diagram opens at full size around the selected state`() {
        val model = large()
        val last = "s${model.stateCount - 1}"
        val pane = pane(800, 700)
        val layout = lay(model, LayoutMode.COMPACT, pane)
        pane.show(model, layout, LayoutMode.COMPACT, last)
        val v = pane.diagram.viewport!!
        assertEquals(1.0, v.scale)
        val box = pane.box(last).bounds
        assertTrue("${pane.onScreen(box)}", pane.visible(box))
        val around = Viewport.around(box, java.awt.Dimension(layout.width.toInt(), layout.height.toInt()), pane.diagram.size)
        assertEquals(listOf(around.tx, around.ty), listOf(v.tx, v.ty))
        assertTrue(pane.box(last).selected)

        // a diagram narrower than the pane opens at its top, in the middle
        val small = small()
        val narrow = pane(1200, 700)
        val smallLayout = lay(small, LayoutMode.COMPACT, narrow)
        assertTrue(smallLayout.width < narrow.diagram.width)
        narrow.show(small, smallLayout, LayoutMode.COMPACT, null)
        val t = narrow.diagram.viewport!!
        assertEquals(1.0, t.scale)
        assertEquals((narrow.diagram.width - smallLayout.width) / 2, t.tx, 0.001)
        // the top, moved down only as far as the margin around the start state asks
        val top = Viewport.around(null, java.awt.Dimension(1, 1), narrow.diagram.size)
        val expected = Viewport(1.0, t.tx, top.ty).reveal(narrow.box("s0").bounds, narrow.diagram.size, 24)
        assertEquals(expected.ty, t.ty, 0.001)
        assertTrue(t.ty in 0.0..24.0)
    }

    fun `test the first diagram without a selection shows the start state`() {
        val model = large()
        val start = model.root!!.start!!
        for (mode in LayoutMode.entries) {
            val pane = pane(600, 800)
            val layout = lay(model, mode, pane)
            assertTrue("$mode ${layout.width}", layout.width > pane.diagram.width)
            pane.show(model, layout, mode, null)
            val v = pane.diagram.viewport!!
            assertEquals(1.0, v.scale)
            val box = pane.box(start).bounds
            assertTrue("$mode ${pane.onScreen(box)}", pane.visible(box))
        }
    }

    fun `test a pane without a size sets its view when it gets one`() {
        val model = large()
        val last = "s${model.stateCount - 1}"
        val sized = pane(800, 700)
        val layout = lay(model, LayoutMode.COMPACT, sized)
        sized.show(model, layout, LayoutMode.COMPACT, last)
        val unsized = pane(0)
        unsized.show(model, layout, LayoutMode.COMPACT, last)
        assertNull(unsized.diagram.viewport)
        resize(unsized, 800, 700)
        val a = sized.diagram.viewport!!
        val b = unsized.diagram.viewport!!
        assertEquals(listOf(a.scale, a.tx, a.ty), listOf(b.scale, b.tx, b.ty))
        // a later size leaves the view alone
        resize(unsized, 900, 800)
        assertSame(b, unsized.diagram.viewport)
    }

    fun `test a later diagram keeps zoom and position`() {
        val model = small()
        val pane = pane()
        val layout = lay(model, LayoutMode.LOOSE, pane)
        pane.show(model, layout, LayoutMode.LOOSE, null)
        val start = pane.diagram.viewport!!
        pane.zoomIn.actionPerformed(TestActionEvent.createTestEvent(pane.zoomIn))
        assertTrue(pane.diagram.viewport!!.scale > start.scale)
        drag(pane, 300 to 300, 40 to -30)
        val dragged = pane.diagram.viewport!!
        // the wheel pans; with Ctrl it zooms at the pointer
        wheel(pane, 200, 200, 2.0)
        val panned = pane.diagram.viewport!!
        assertEquals(dragged.scale, panned.scale)
        assertEquals(dragged.tx, panned.tx)
        assertTrue(panned.ty < dragged.ty)
        wheel(pane, 200, 150, -1.0, InputEvent.CTRL_DOWN_MASK)
        val zoomed = pane.diagram.viewport!!
        assertTrue(zoomed.scale > panned.scale)
        assertEquals((200 - panned.tx) / panned.scale, (200 - zoomed.tx) / zoomed.scale, 0.001)
        assertEquals((150 - panned.ty) / panned.scale, (150 - zoomed.ty) / zoomed.scale, 0.001)
        // a drag is not a click
        val selects = ArrayList<String?>()
        pane.onSelect = { selects += it }
        drag(pane, pane.at(pane.box("s1").bounds), 30 to 30)
        assertEquals(emptyList<String?>(), selects)
        val before = pane.diagram.viewport!!
        val edited = build(archive.replace("Frame", "Picture")).first
        pane.show(edited, lay(edited, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, "s3")
        val after = pane.diagram.viewport!!
        assertEquals(listOf(before.scale, before.tx, before.ty), listOf(after.scale, after.tx, after.ty))
        assertEquals("Picture", pane.box("s3").name)
        // the toolbar's fit shows the whole diagram
        pane.fit.actionPerformed(TestActionEvent.createTestEvent(pane.fit))
        assertTrue(pane.visible(Rectangle2D.Double(0.0, 0.0, pane.scene().width, pane.scene().height)))
    }

    fun `test a selected path is kept by show`() {
        val model = small()
        val pane = pane()
        pane.show(model, lay(model, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, "s0.c0")
        assertEquals(listOf("s0.c0"), pane.paths().filter { it.selected }.map { it.id })
        assertEquals(listOf("s0", "s1"), pane.boxes().filter { it.linked }.map { it.id })
        assertEquals(emptyList<String>(), pane.rowLabels())
        pane.show(model, lay(model, LayoutMode.LOOSE, pane, setOf("Each")), LayoutMode.LOOSE, "s0.c0")
        assertEquals(listOf("s0.c0"), pane.paths().filter { it.selected }.map { it.id })
    }

    fun `test the compact layout of the large definition is at most 2200 wide with the pane's fonts`() {
        val pane = pane()
        val width = lay(large(), LayoutMode.COMPACT, pane).width
        assertTrue("$width", width <= 2200)
        // the pane measures with the painter's fonts, as the painter draws them: antialiased, without fractional metrics
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val painted = try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.fontRenderContext
        } finally {
            g.dispose()
        }
        for ((size, bold) in listOf(DiagramScene.NAME_SIZE to true, DiagramScene.SUB_SIZE to false)) {
            val expected = DiagramPainter.font(size, bold).getStringBounds("ReelCheckpoint", painted).width
            assertEquals("$size $bold", expected, pane.measure.width("ReelCheckpoint", size, bold), 0.001)
        }
        assertTrue(pane.measure.width("Wide", 12, true) > pane.measure.width("Wide", 10, false))
    }

    fun `test select reveals a state outside the view and leaves a visible one alone`() {
        val model = large()
        val pane = pane(800, 700)
        pane.show(model, lay(model, LayoutMode.COMPACT, pane), LayoutMode.COMPACT, null)
        val states = pane.boxes().filter { it.kind == LaidNode.Kind.STATE }
        val far = states.maxBy { it.bounds.maxY }
        assertFalse(pane.visible(far.bounds))
        // well inside the view: the margin that select keeps around what it reveals is 24 pixels
        val near = states.first { it.id != far.id && pane.visible(it.bounds, 30.0) }
        val view = pane.diagram.viewport
        pane.select(near.id)
        assertSame(view, pane.diagram.viewport)
        assertEquals(listOf(near.id), pane.boxes().filter { it.selected }.map { it.id })
        pane.select(far.id)
        assertTrue(pane.visible(far.bounds))
        assertTrue(pane.box(far.id).selected)
        assertEquals(model.details.getValue(far.id).map { it.label }, pane.rowLabels())
        // a path, and nothing
        val edge = pane.paths().first { it.kind == TransitionKind.NEXT }.id
        pane.select(edge)
        assertEquals(listOf(edge), pane.paths().filter { it.selected }.map { it.id })
        assertEquals(emptyList<String>(), pane.rowLabels())
        pane.select(null)
        assertTrue(pane.boxes().none { it.selected || it.linked })
        assertEquals(emptyList<String>(), pane.rowLabels())
    }

    fun `test select inside a collapsed box marks and reveals the box`() {
        val model = small()
        val pane = pane(500, 400)
        pane.show(model, lay(model, LayoutMode.LOOSE, pane, setOf("Each")), LayoutMode.LOOSE, null)
        drag(pane, 100 to 100, 0 to -2000)
        assertFalse(pane.visible(pane.box("s2").bounds))
        pane.select("s3")
        assertTrue(pane.box("s2").selected)
        assertTrue(pane.visible(pane.box("s2").bounds))
        assertEquals(model.details.getValue("s3").map { it.label }, pane.rowLabels())
    }

    fun `test the catch switch moves nothing and is remembered`() {
        val model = small()
        val pane = pane()
        val modes = ArrayList<LayoutMode>()
        pane.onLayoutMode = { modes += it }
        val layout = lay(model, LayoutMode.LOOSE, pane)
        pane.show(model, layout, LayoutMode.LOOSE, "s1")
        // a drop-down with the caption and the current value, whose options are the three settings
        assertEquals(CatchMode.entries.map { pane.catchSwitch(it) }, pane.catchMenu.options)
        assertEquals("Catch: Normal" to true, caption(pane.catchMenu))
        assertTrue(selected(pane.catchSwitch(CatchMode.NORMAL)))
        val view = pane.diagram.viewport
        val boxes = pane.boxes().map { it.id to it.bounds }
        assertTrue(pane.paths().any { it.kind == TransitionKind.CATCH && !it.muted })
        perform(pane.catchSwitch(CatchMode.MUTED))
        assertEquals(CatchMode.MUTED, DiagramPrefs.catchMode)
        assertEquals("Catch: Muted" to true, caption(pane.catchMenu))
        assertTrue(pane.paths().filter { it.kind == TransitionKind.CATCH }.all { it.muted })
        perform(pane.catchSwitch(CatchMode.HIDDEN))
        assertEquals(CatchMode.HIDDEN, DiagramPrefs.catchMode)
        assertTrue(pane.paths().none { it.kind == TransitionKind.CATCH })
        assertSame(layout, pane.layout)
        assertSame(view, pane.diagram.viewport)
        assertEquals(boxes, pane.boxes().map { it.id to it.bounds })
        assertEquals(listOf("s1"), pane.boxes().filter { it.selected }.map { it.id })
        assertEquals(emptyList<LayoutMode>(), modes)
        assertTrue(selected(pane.catchSwitch(CatchMode.HIDDEN)))
        assertFalse(selected(pane.catchSwitch(CatchMode.NORMAL)))
        // a click on the switch that is on leaves it on
        perform(pane.catchSwitch(CatchMode.HIDDEN))
        assertEquals(CatchMode.HIDDEN, DiagramPrefs.catchMode)
        // a new pane starts with the remembered mode
        val next = pane()
        assertTrue(selected(next.catchSwitch(CatchMode.HIDDEN)))
        assertEquals("Catch: Hidden" to true, caption(next.catchMenu))
        next.show(model, layout, LayoutMode.LOOSE, null)
        assertTrue(next.paths().none { it.kind == TransitionKind.CATCH })
    }

    fun `test the catch mode reads anything unknown as normal`() {
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue("aslan.diagram.catchPaths", "Sometimes")
        assertEquals(CatchMode.NORMAL, DiagramPrefs.catchMode)
        DiagramPrefs.catchMode = CatchMode.MUTED
        assertEquals("MUTED", com.intellij.ide.util.PropertiesComponent.getInstance().getValue("aslan.diagram.catchPaths"))
    }

    fun `test the layout switch reports the mode and shows the current one`() {
        val model = small()
        val pane = pane(500, 400)
        val modes = ArrayList<LayoutMode>()
        pane.onLayoutMode = { modes += it }
        // before the first diagram: the caption without a value, and the drop-down does nothing
        assertEquals("Layout" to false, caption(pane.layoutMenu))
        assertEquals(LayoutMode.entries.map { pane.layoutSwitch(it) }, pane.layoutMenu.options)
        pane.show(model, lay(model, LayoutMode.COMPACT, pane), LayoutMode.COMPACT, "s8")
        assertEquals("Layout: Compact" to true, caption(pane.layoutMenu))
        assertTrue(selected(pane.layoutSwitch(LayoutMode.COMPACT)))
        assertFalse(selected(pane.layoutSwitch(LayoutMode.LOOSE)))
        val scene = pane.diagram.scene
        perform(pane.layoutSwitch(LayoutMode.LOOSE))
        assertEquals(listOf(LayoutMode.LOOSE), modes)
        // it paints nothing itself: the mode of the last show stays until the next one
        assertSame(scene, pane.diagram.scene)
        assertEquals("Layout: Compact" to true, caption(pane.layoutMenu))
        assertTrue(selected(pane.layoutSwitch(LayoutMode.COMPACT)))
        perform(pane.layoutSwitch(LayoutMode.COMPACT))
        assertEquals(listOf(LayoutMode.LOOSE), modes)
        // a show in the other mode brings the selection back into view
        drag(pane, 100 to 100, 3000 to 3000)
        assertFalse(pane.visible(pane.box("s8").bounds))
        pane.show(model, lay(model, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, "s8")
        assertEquals("Layout: Loose" to true, caption(pane.layoutMenu))
        assertTrue(selected(pane.layoutSwitch(LayoutMode.LOOSE)))
        assertFalse(selected(pane.layoutSwitch(LayoutMode.COMPACT)))
        assertTrue(pane.visible(pane.box("s8").bounds))
        // in the same mode it does not
        drag(pane, 100 to 100, 3000 to 3000)
        pane.show(model, lay(model, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, "s8")
        assertFalse(pane.visible(pane.box("s8").bounds))
    }

    fun `test every control of the toolbar is visible in a narrow pane`() {
        // 380 pixels was fixed on macOS against the IDE's own right pane; elsewhere the label font may be wider
        val width = if (SystemInfo.isMac) 380 else 460
        val model = large()
        val pane = pane(width, 700)
        pane.show(model, lay(model, LayoutMode.COMPACT, pane), LayoutMode.COMPACT, null)
        resize(pane, width, 700)
        val bar = pane.toolbar.component
        assertTrue("${bar.preferredSize.width} " + bar.components.map { "${it.javaClass.simpleName} ${it.width}" }, bar.preferredSize.width <= bar.width)
        // the two drop-downs and the three buttons, each whole inside the toolbar
        val controls = components(bar).filterIsInstance<ActionButton>()
        assertEquals(controls.map { it.javaClass.simpleName }.toString(), 5, controls.size)
        for (control in controls) {
            var visible = true
            var c: Component? = control
            while (c != null && c !== bar) {
                visible = visible && c.isVisible
                c = c.parent
            }
            val at = SwingUtilities.convertRectangle(control.parent, control.bounds, bar)
            assertTrue("$control $at", visible && at.width > 0 && at.x >= 0 && at.maxX <= bar.width)
        }
    }

    fun `test a toggled box stays in view`() {
        val model = large()
        val pane = pane(600, 800)
        val toggles = ArrayList<String>()
        pane.onToggleBox = { toggles += it }
        val open = lay(model, LayoutMode.LOOSE, pane)
        val id = open.nodes.single { it.state?.name == "PublishEvents" }.id
        val edge = model.root!!.edges.first().id
        pane.show(model, open, LayoutMode.LOOSE, edge)
        // the view moved to the box, which is far from the selected path
        pane.diagram.viewport = Viewport.around(pane.box(id).bounds, DiagramSize(1.0, 1.0), pane.diagram.size)
        fun toggle() = pane.scene().items.filterIsInstance<SceneItem.Toggle>().single { it.stateName == "PublishEvents" }.bounds
        click(pane, pane.at(toggle()))
        assertEquals(listOf("PublishEvents"), toggles)
        val shut = lay(model, LayoutMode.LOOSE, pane, setOf("PublishEvents"))
        // kept where it was, the view would show another place
        assertFalse(pane.visible(shut.nodes.single { it.id == id }.bounds))
        pane.show(model, shut, LayoutMode.LOOSE, edge)
        assertTrue(pane.box(id).collapsed)
        assertTrue(pane.onScreen(pane.box(id).bounds).toString(), pane.visible(pane.box(id).bounds))
        assertEquals(listOf(edge), pane.paths().filter { it.selected }.map { it.id })
        // a show that follows no toggle keeps zoom and position
        drag(pane, 300 to 300, 0 to -500)
        val moved = pane.diagram.viewport!!
        pane.show(model, shut, LayoutMode.LOOSE, edge)
        assertEquals(listOf(moved.scale, moved.tx, moved.ty), pane.diagram.viewport!!.let { listOf(it.scale, it.tx, it.ty) })
        // and expanding it again
        pane.diagram.viewport = Viewport.around(pane.box(id).bounds, DiagramSize(1.0, 1.0), pane.diagram.size)
        click(pane, pane.at(toggle()))
        assertEquals(listOf("PublishEvents", "PublishEvents"), toggles)
        val reopened = lay(model, LayoutMode.LOOSE, pane)
        pane.show(model, reopened, LayoutMode.LOOSE, edge)
        assertFalse(pane.box(id).collapsed)
        val on = pane.onScreen(pane.box(id).bounds)
        if (on.width <= pane.diagram.width - 48) {
            assertTrue(on.toString(), pane.visible(pane.box(id).bounds))
        } else {
            // open, the box is wider than the pane: its left edge at the margin, its whole height inside
            assertEquals(on.toString(), 24.0, on.x, 0.001)
            assertTrue(on.toString(), on.y >= 0 && on.maxY <= pane.diagram.height)
        }
    }

    fun `test collapsing the details panel is remembered`() {
        val model = small()
        val pane = pane(800, 900)
        pane.show(model, lay(model, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, "s1")
        val open = pane.diagram.height
        assertFalse(pane.details.collapsed)
        assertTrue(pane.details.body.isVisible)
        pane.details.toggle.doClick(0)
        layOut(pane.component)
        assertTrue(DiagramPrefs.detailsCollapsed)
        assertTrue(pane.details.collapsed)
        assertFalse(pane.details.body.isVisible)
        assertTrue("${pane.diagram.height} $open", pane.diagram.height > open)
        // the header still names the selected state
        assertEquals(DiagramText.literal("Scan"), pane.details.header.text)
        val next = pane(800, 900)
        assertTrue(next.details.collapsed)
        assertEquals(pane.diagram.height, next.diagram.height)
        next.details.toggle.doClick(0)
        assertFalse(DiagramPrefs.detailsCollapsed)
    }

    fun `test a link in the details reports its offset`() {
        val (model, file) = build(archive)
        val pane = pane()
        val offsets = ArrayList<Int>()
        pane.onNavigate = { offsets += it }
        pane.show(model, lay(model, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, "s1")
        val each = pane.links().single { it.text == DiagramText.literal("Each") }
        each.doClick(0)
        val target = file.text.indexOf("Each:")
        assertEquals(listOf(target), offsets)
        assertEquals(model.details.getValue("s1").single { it.label == "Next" }.items.single().offset, target)
        // an item without a place is plain text
        assertTrue(pane.links().none { it.text == DiagramText.literal("Task") })
        assertTrue(DiagramText.literal("Task") in pane.texts())
    }

    fun `test literal escapes markup`() {
        assertEquals("<html>&lt;html&gt;&lt;b&gt;Scan<br>&amp; more</html>", DiagramText.literal("<html><b>Scan\n& more"))
        assertEquals("<html>Scan</html>", DiagramText.literal("Scan"))
    }

    fun `test texts from the file are shown literally`() {
        val name = "<html><b>Scan" + "n".repeat(67)
        assertEquals(80, name.length)
        val condition = "<html>x = 1 and y = ".padEnd(40, '2')
        val text = """
            QueryLanguage: JSONata
            StartAt: A
            States:
              A:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                Catch:
                  - ErrorEquals: [States.ALL]
                    Next: "$name"
                Next: B
              B:
                Type: Choice
                Choices:
                  - Condition: "{% $condition %}"
                    Next: "$name"
                Default: "$name"
              "$name":
                Type: Pass
                End: true
        """
        val model = build(text).first
        val pane = pane()
        pane.show(model, lay(model, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, null)
        val box = pane.box("s2")
        assertTrue(box.name, box.name.startsWith("<html>") && box.name.endsWith("…"))
        val (x, y) = pane.at(box.bounds)
        assertTrue(pane.visible(box.bounds))
        val hover = MouseEvent(pane.diagram, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false)
        assertEquals(DiagramText.literal(name), pane.diagram.getToolTipText(hover))
        val label = pane.scene().items.filterIsInstance<SceneItem.Label>().single { it.edgeId == "s1.c0" }
        val (lx, ly) = pane.at(label.bounds)
        assertEquals(DiagramText.literal(condition), pane.diagram.getToolTipText(MouseEvent(pane.diagram, MouseEvent.MOUSE_MOVED, 0, 0, lx, ly, 0, false)))
        // nothing there, no tool tip
        assertNull(pane.diagram.getToolTipText(MouseEvent(pane.diagram, MouseEvent.MOUSE_MOVED, 0, 0, 2, pane.diagram.height - 2, 0, false)))

        pane.select("s2")
        assertEquals(DiagramText.literal(name), pane.details.header.text)
        assertEquals(DiagramText.literal(name), pane.details.header.toolTipText)
        pane.select("s0")
        assertEquals(listOf("Catch", "Next"), pane.rowLabels().filter { it == "Catch" || it == "Next" })
        assertEquals(1, pane.links().count { it.text == DiagramText.literal(name) })
        // every text of the details panel is read literally
        val texts = pane.texts()
        assertTrue(texts.isNotEmpty())
        for (t in texts) assertTrue(t, t.startsWith("<html>") && t.endsWith("</html>"))
        assertTrue(pane.details.header.text.startsWith("<html>"))
    }

    fun `test out of date keeps the diagram and marks it`() {
        val model = small()
        val pane = pane()
        pane.show(model, lay(model, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, "s1")
        assertFalse(pane.diagram.stale)
        val scene = pane.diagram.scene
        val view = pane.diagram.viewport
        pane.setStale()
        assertTrue(pane.diagram.stale)
        assertSame(scene, pane.diagram.scene)
        assertSame(view, pane.diagram.viewport)
        assertEquals(model.details.getValue("s1").map { it.label }, pane.rowLabels())
        // it stays through a selection and a click
        pane.select("s0")
        assertTrue(pane.diagram.stale)
        val stale = paint(pane)
        pane.show(model, lay(model, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, "s0")
        assertFalse(pane.diagram.stale)
        // the mark is painted over the diagram
        val fresh = paint(pane)
        assertFalse(same(stale, fresh, panelArea(pane)))
    }

    fun `test an empty model says so`() {
        val pane = pane()
        val empty = DiagramModel(null, emptyMap(), 0)
        pane.show(empty, lay(empty, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, null)
        assertEquals("No state machine to show yet.", pane.diagram.message)
        assertEquals(emptyList<String>(), pane.rowLabels())
        paint(pane)
        val model = small()
        pane.show(model, lay(model, LayoutMode.LOOSE, pane), LayoutMode.LOOSE, null)
        assertNull(pane.diagram.message)
    }

    private fun panelArea(pane: DiagramPane): Rectangle =
        SwingUtilities.convertRectangle(pane.diagram.parent, pane.diagram.bounds, pane.component)

    private fun paint(pane: DiagramPane): BufferedImage {
        val c = pane.component
        val image = BufferedImage(c.width, c.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            c.paint(g)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun same(a: BufferedImage, b: BufferedImage, area: Rectangle): Boolean {
        for (x in area.x until area.maxX.toInt()) for (y in area.y until area.maxY.toInt()) if (a.getRGB(x, y) != b.getRGB(x, y)) return false
        return true
    }

    private fun uniform(image: BufferedImage, area: Rectangle): Boolean {
        val first = image.getRGB(area.x, area.y)
        for (x in area.x until area.maxX.toInt()) for (y in area.y until area.maxY.toInt()) if (image.getRGB(x, y) != first) return false
        return true
    }

    fun `test the pane paints every committed definition`() {
        val manager = EditorColorsManager.getInstance()
        val old = manager.globalScheme
        val light = manager.allSchemes.firstOrNull { !ColorUtil.isDark(it.defaultBackground) }
        val dark = manager.allSchemes.firstOrNull { ColorUtil.isDark(it.defaultBackground) }
        assertNotNull(manager.allSchemes.map { it.name }.toString(), light)
        assertNotNull(manager.allSchemes.map { it.name }.toString(), dark)
        try {
            val pane = pane(600, 800)
            for ((name, model) in definitions()) for (mode in LayoutMode.entries) {
                pane.show(model, lay(model, mode, pane), mode, null)
                for (scheme in listOf(light!!, dark!!)) {
                    manager.setGlobalScheme(scheme)
                    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                    assertEquals(scheme.defaultBackground.rgb, pane.diagram.colors.background.rgb)
                    assertEquals(ColorUtil.isDark(scheme.defaultBackground), pane.diagram.colors.isDark)
                    val image = paint(pane)
                    assertEquals(600, image.width)
                    assertFalse("$name $mode ${scheme.name}", uniform(image, panelArea(pane)))
                }
            }
        } finally {
            manager.setGlobalScheme(old)
        }
    }
}
