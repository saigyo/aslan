package io.github.saigyo.aslan.diagram

import java.awt.Dimension
import java.awt.geom.Dimension2D
import java.awt.geom.Rectangle2D

/**
 * Which part of the diagram the pane shows: a point of the diagram at (x, y) is at (x * [scale] + [tx], y * [scale] + [ty])
 * in the pane. Plain arithmetic, no Swing component.
 */
class Viewport(val scale: Double, val tx: Double, val ty: Double) {
    /** The viewport with [factor] applied to the scale, within [MIN_SCALE] and [MAX_SCALE], that keeps the diagram point under (x, y) of the pane in place. */
    fun zoomAt(factor: Double, x: Double, y: Double): Viewport {
        // a scale outside the limits (a fitted, very large diagram) is never moved against the factor
        val next = (scale * factor).coerceIn(minOf(MIN_SCALE, scale), maxOf(MAX_SCALE, scale))
        val k = next / scale
        return Viewport(next, x - (x - tx) * k, y - (y - ty) * k)
    }

    /**
     * This viewport when [rect] (in diagram coordinates) lies in the pane at least [margin] pixels from every edge, else
     * moved on each axis by the least that brings it there; a rectangle larger than the pane is put at the top left.
     */
    fun reveal(rect: Rectangle2D, pane: Dimension, margin: Int): Viewport {
        val dx = shift(rect.minX * scale + tx, rect.maxX * scale + tx, pane.width, margin)
        val dy = shift(rect.minY * scale + ty, rect.maxY * scale + ty, pane.height, margin)
        return if (dx == 0.0 && dy == 0.0) this else Viewport(scale, tx + dx, ty + dy)
    }

    private fun shift(min: Double, max: Double, extent: Int, margin: Int): Double = when {
        min < margin -> margin - min
        max > extent - margin -> if (max - min > extent - 2 * margin) margin - min else extent - margin - max
        else -> 0.0
    }

    companion object {
        const val MIN_SCALE = 0.2
        const val MAX_SCALE = 3.0

        /**
         * Scale 1 with [rect] in the middle of [pane]. Without a rectangle the top of the diagram: in the middle of the
         * pane when the diagram is narrower, else with its left edge at the pane's.
         */
        fun around(rect: Rectangle2D?, size: Dimension2D, pane: Dimension): Viewport =
            if (rect == null) Viewport(1.0, maxOf(0.0, (pane.width - size.width) / 2), 0.0)
            else Viewport(1.0, pane.width / 2.0 - rect.centerX, pane.height / 2.0 - rect.centerY)

        /**
         * The whole diagram in the middle of [pane], [padding] pixels from its edges, at scale 1 or less. Below
         * [MIN_SCALE] it still shows the whole diagram; only [zoomAt] holds to the limits.
         */
        fun fit(size: Dimension2D, pane: Dimension, padding: Int): Viewport {
            if (size.width <= 0 || size.height <= 0 || pane.width <= 0 || pane.height <= 0) return Viewport(1.0, 0.0, 0.0)
            val room = 1.0
            val scale = minOf(1.0, maxOf(pane.width - 2.0 * padding, room) / size.width, maxOf(pane.height - 2.0 * padding, room) / size.height)
            return Viewport(scale, (pane.width - size.width * scale) / 2, (pane.height - size.height * scale) / 2)
        }
    }
}
