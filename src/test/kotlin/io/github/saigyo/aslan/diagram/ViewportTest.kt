package io.github.saigyo.aslan.diagram

import junit.framework.TestCase
import java.awt.Dimension
import java.awt.geom.Dimension2D
import java.awt.geom.Rectangle2D

class ViewportTest : TestCase() {
    private fun size(w: Double, h: Double) = object : Dimension2D() {
        override fun getWidth() = w
        override fun getHeight() = h
        override fun setSize(width: Double, height: Double) = Unit
    }

    private fun Viewport.screen(r: Rectangle2D) =
        Rectangle2D.Double(r.x * scale + tx, r.y * scale + ty, r.width * scale, r.height * scale)

    fun `test around centres a state at full size`() {
        val state = Rectangle2D.Double(1000.0, 500.0, 190.0, 40.0)
        val pane = Dimension(600, 400)
        val v = Viewport.around(state, size(3000.0, 2000.0), pane)
        assertEquals(1.0, v.scale, 0.0)
        val on = v.screen(state)
        assertEquals(300.0, on.centerX, 0.001)
        assertEquals(200.0, on.centerY, 0.001)
    }

    fun `test around without a state shows the top of the diagram`() {
        val v = Viewport.around(null, size(400.0, 2000.0), Dimension(600, 400))
        assertEquals(1.0, v.scale, 0.0)
        assertEquals(0.0, v.ty, 0.0)
        assertEquals(100.0, v.tx, 0.001)   // the diagram's middle is the pane's middle
    }

    fun `test fit shows the whole diagram and never enlarges it`() {
        val pane = Dimension(600, 400)
        val big = Viewport.fit(size(3000.0, 1000.0), pane, 20)
        assertEquals(560.0 / 3000.0, big.scale, 1e-9)
        val on = big.screen(Rectangle2D.Double(0.0, 0.0, 3000.0, 1000.0))
        assertTrue(on.minX >= 20 - 1e-9 && on.maxX <= 580 + 1e-9 && on.minY >= 20 - 1e-9 && on.maxY <= 380 + 1e-9)
        assertEquals(300.0, on.centerX, 0.001)
        assertEquals(200.0, on.centerY, 0.001)
        val small = Viewport.fit(size(100.0, 50.0), pane, 20)
        assertEquals(1.0, small.scale, 0.0)
        assertEquals(300.0, small.screen(Rectangle2D.Double(0.0, 0.0, 100.0, 50.0)).centerX, 0.001)
        assertEquals(1.0, Viewport.fit(size(0.0, 0.0), pane, 20).scale, 0.0)
    }

    fun `test zoom keeps the point under the pointer and stays within its limits`() {
        val v = Viewport(1.0, 30.0, 40.0)
        val z = v.zoomAt(2.0, 200.0, 100.0)
        assertEquals(2.0, z.scale, 1e-9)
        // the diagram point under (200, 100) is the same before and after
        assertEquals((200.0 - v.tx) / v.scale, (200.0 - z.tx) / z.scale, 1e-9)
        assertEquals((100.0 - v.ty) / v.scale, (100.0 - z.ty) / z.scale, 1e-9)
        assertEquals(3.0, v.zoomAt(100.0, 0.0, 0.0).scale, 0.0)
        assertEquals(0.2, v.zoomAt(0.001, 0.0, 0.0).scale, 0.0)
        val atLimit = Viewport(3.0, 5.0, 6.0).zoomAt(2.0, 77.0, 88.0)
        assertEquals(3.0, atLimit.scale, 0.0)
        assertEquals(5.0, atLimit.tx, 1e-9)
        assertEquals(6.0, atLimit.ty, 1e-9)
    }

    fun `test reveal leaves a visible state alone and moves the least for a hidden one`() {
        val pane = Dimension(600, 400)
        val v = Viewport(1.0, 0.0, 0.0)
        val visible = Rectangle2D.Double(100.0, 100.0, 190.0, 40.0)
        val same = v.reveal(visible, pane, 20)
        assertSame(v, same)
        // below the pane: moved up just enough to lie margin above the bottom
        val below = Rectangle2D.Double(100.0, 700.0, 190.0, 40.0)
        val a = v.reveal(below, pane, 20)
        assertEquals(1.0, a.scale, 0.0)
        assertEquals(0.0, a.tx, 0.0)
        assertEquals(380.0, a.screen(below).maxY, 1e-9)
        // right of the pane
        val right = Rectangle2D.Double(900.0, 100.0, 190.0, 40.0)
        val b = v.reveal(right, pane, 20)
        assertEquals(580.0, b.screen(right).maxX, 1e-9)
        assertEquals(0.0, b.ty, 0.0)
        // left of and above the pane
        val before = Rectangle2D.Double(-300.0, -200.0, 190.0, 40.0)
        val c = v.reveal(before, pane, 20)
        assertEquals(20.0, c.screen(before).minX, 1e-9)
        assertEquals(20.0, c.screen(before).minY, 1e-9)
        // inside the pane but within the margin of its edge
        val edge = Rectangle2D.Double(5.0, 100.0, 190.0, 40.0)
        assertEquals(20.0, v.reveal(edge, pane, 20).screen(edge).minX, 1e-9)
    }

    fun `test zooming never moves the scale against the factor`() {
        val small = Viewport(0.1, 0.0, 0.0)
        assertTrue(small.zoomAt(0.5, 10.0, 10.0).scale <= 0.1)
        assertTrue(small.zoomAt(1.1, 10.0, 10.0).scale > 0.1)
        val big = Viewport(4.0, 0.0, 0.0)
        assertTrue(big.zoomAt(2.0, 10.0, 10.0).scale >= 4.0)
        assertTrue(big.zoomAt(0.5, 10.0, 10.0).scale < 4.0)
    }

    fun `test fit leaves the viewport alone for a pane that is not laid out`() {
        val v = Viewport.fit(size(3000.0, 1000.0), Dimension(0, 0), 20)
        assertEquals(1.0, v.scale, 0.0)
        assertEquals(0.0, v.tx, 0.0)
        assertEquals(0.0, v.ty, 0.0)
    }
}
