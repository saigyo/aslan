package io.github.saigyo.aslan.diagram

import com.intellij.ui.components.Magnificator
import com.intellij.ui.components.ZoomableViewport
import java.awt.BorderLayout
import java.awt.Point
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Holds the [diagram] and zooms it with the trackpad's pinch on macOS. The IDE delivers a pinch to the nearest ancestor
 * of the component under the pointer that implements [ZoomableViewport] and has a [Magnificator]: first
 * [magnificationStarted], then [magnify] with the sum of the magnification since the start, then
 * [magnificationFinished]. The pinch is applied to the view the diagram had at its start, around the point under the
 * pointer, so it does not drift, and within the limits of [Viewport.zoomAt]. All calls on the UI thread.
 */
internal class PinchZoom(val diagram: DiagramPanel) : JPanel(BorderLayout()), ZoomableViewport {
    private var start: Viewport? = null
    private var at: Point? = null

    /** The platform's image-scaling delegate would call it; this class zooms the diagram itself and leaves the point. */
    private val magnificator = Magnificator { _, point -> point }

    init {
        add(diagram, BorderLayout.CENTER)
    }

    override fun getMagnificator(): Magnificator = magnificator

    override fun magnificationStarted(at: Point) {
        val view = diagram.viewport
        if (view == null || diagram.message != null) return
        start = view
        this.at = SwingUtilities.convertPoint(this, at, diagram)
    }

    override fun magnify(magnification: Double) {
        val view = start ?: return
        val point = at ?: return
        diagram.viewport = view.zoomAt(scale(magnification), point.x.toDouble(), point.y.toDouble())
        diagram.repaint()
    }

    override fun magnificationFinished(magnification: Double) {
        magnify(magnification)
        start = null
        at = null
    }

    /** The factor of a magnification, as the platform's own viewport reads it: 1 + m when growing, 1 / (1 - m) when shrinking. */
    internal fun scale(magnification: Double): Double = if (magnification < 0) 1 / (1 - magnification) else 1 + magnification
}
