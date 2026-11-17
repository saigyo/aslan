package io.github.saigyo.aslan.diagram

import com.intellij.ui.components.ZoomableViewport
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.geom.Point2D
import javax.swing.SwingUtilities

class PinchZoomTest : DiagramTestCase() {
    /** A pane of 900 by 900 with its components laid out, showing the small definition unless [show] is false. */
    private fun pane(show: Boolean = true): DiagramPane {
        val pane = DiagramPane(testRootDisposable)
        pane.toolbar.updateActionsImmediately()
        pane.component.setSize(900, 900)
        layOut(pane.component)
        if (show) {
            val model = build(archive).first
            pane.show(model, DiagramLayouter.layout(model, LayoutMode.LOOSE, emptySet(), pane.measure), LayoutMode.LOOSE, null)
        }
        return pane
    }

    private fun layOut(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach(::layOut)
    }

    /** The point of the diagram under (x, y) of the pinch container. */
    private fun DiagramPane.under(x: Int, y: Int): Point2D.Double {
        val at = SwingUtilities.convertPoint(pinch, x, y, diagram)
        val v = diagram.viewport!!
        return Point2D.Double((at.x - v.tx) / v.scale, (at.y - v.ty) / v.scale)
    }

    private fun assertSamePoint(a: Point2D, b: Point2D) {
        assertEquals(a.x, b.x, 1e-6)
        assertEquals(a.y, b.y, 1e-6)
    }

    fun `test a pinch zooms around the pointer`() {
        val pane = pane()
        val start = pane.diagram.viewport!!.scale
        val before = pane.under(200, 150)
        pane.pinch.magnificationStarted(Point(200, 150))
        pane.pinch.magnify(0.5)
        assertEquals(start * 1.5, pane.diagram.viewport!!.scale, 1e-9)
        assertSamePoint(before, pane.under(200, 150))
        // the IDE sends the sum of the magnification so far, not a step
        pane.pinch.magnify(1.0)
        assertEquals(start * 2.0, pane.diagram.viewport!!.scale, 1e-9)
        assertSamePoint(before, pane.under(200, 150))
    }

    fun `test a pinch inwards zooms out`() {
        val pane = pane()
        val start = pane.diagram.viewport!!.scale
        pane.pinch.magnificationStarted(Point(300, 300))
        pane.pinch.magnify(-1.0)
        assertEquals(start / 2, pane.diagram.viewport!!.scale, 1e-9)
    }

    fun `test a pinch holds to the zoom limits`() {
        val pane = pane()
        pane.pinch.magnificationStarted(Point(300, 300))
        pane.pinch.magnify(10.0)
        assertEquals(Viewport.MAX_SCALE, pane.diagram.viewport!!.scale, 1e-9)
        pane.pinch.magnify(-50.0)
        assertEquals(Viewport.MIN_SCALE, pane.diagram.viewport!!.scale, 1e-9)
    }

    fun `test a pinch after its end changes nothing`() {
        val pane = pane()
        pane.pinch.magnificationStarted(Point(300, 300))
        pane.pinch.magnificationFinished(0.5)
        val view = pane.diagram.viewport
        pane.pinch.magnify(2.0)
        assertSame(view, pane.diagram.viewport)
    }

    fun `test a pinch without a diagram changes nothing`() {
        val empty = pane(show = false)
        assertNull(empty.diagram.viewport)
        empty.pinch.magnificationStarted(Point(300, 300))
        empty.pinch.magnify(0.5)
        empty.pinch.magnificationFinished(0.5)
        assertNull(empty.diagram.viewport)

        val message = pane()
        message.diagram.message = "No state machine to show yet."
        val view = message.diagram.viewport
        message.pinch.magnificationStarted(Point(300, 300))
        message.pinch.magnify(0.5)
        message.pinch.magnificationFinished(0.5)
        assertSame(view, message.diagram.viewport)
    }

    fun `test the diagram lies in the pinch container`() {
        val pane = pane()
        val host = SwingUtilities.getAncestorOfClass(ZoomableViewport::class.java, pane.diagram)
        assertSame(pane.pinch, host)
        assertNotNull((host as ZoomableViewport).magnificator)
    }

    fun `test a magnification turns into a scale as in the platform's viewport`() {
        val pinch = pane().pinch
        assertEquals(1.0, pinch.scale(0.0), 1e-9)
        assertEquals(1.5, pinch.scale(0.5), 1e-9)
        assertEquals(0.5, pinch.scale(-1.0), 1e-9)
    }
}
