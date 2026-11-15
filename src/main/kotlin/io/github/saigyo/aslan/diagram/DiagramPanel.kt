package io.github.saigyo.aslan.diagram

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.font.FontRenderContext
import java.awt.geom.Dimension2D
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import kotlin.math.pow

/**
 * The diagram itself: paints a [DiagramScene] through a [Viewport], pans with a drag and the wheel, zooms with the wheel
 * and Ctrl or Cmd, shows the hover texts and reports what a click hits. What a click means is the pane's business. It
 * takes the keyboard focus when clicked, so a click in the diagram does not leave the focus in the text editor.
 */
class DiagramPanel internal constructor() : JComponent() {
    internal var scene: DiagramScene? = null
    internal var viewport: Viewport? = null
    internal var colors: DiagramColors = DiagramColors.current()

    /** Whether the diagram no longer follows the file; marked over the diagram. */
    internal var stale = false

    /** A text shown instead of a diagram, or null. */
    internal var message: String? = null

    /** What a click hit: a toggle, a state, a path, or null for the background. */
    internal var onClick: (Hit?) -> Unit = {}

    /** Called once, the next time the panel gets a size that is not empty. */
    internal var whenSized: (() -> Unit)? = null

    private var pressed: Point? = null
    private var pressedView: Viewport? = null
    private var dragging = false

    init {
        isFocusable = true
        isOpaque = true
        val mouse = Mouse()
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
        ToolTipManager.sharedInstance().registerComponent(this)
    }

    internal val hasSize: Boolean get() = width > 0 && height > 0

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        if (width > 0 && height > 0) {
            val then = whenSized ?: return
            whenSized = null
            then()
        }
    }

    /** The point of the diagram under (x, y) of the panel, and the reach of a path there; null without a diagram. */
    private fun <T> atPoint(x: Int, y: Int, use: (DiagramScene, Double, Double, Double) -> T): T? {
        val s = scene ?: return null
        val v = viewport ?: return null
        if (message != null) return null
        return use(s, (x - v.tx) / v.scale, (y - v.ty) / v.scale, TOLERANCE / v.scale)
    }

    override fun getToolTipText(event: MouseEvent): String? =
        atPoint(event.x, event.y) { s, x, y, tolerance -> s.tooltip(x, y, tolerance) }?.let(DiagramText::literal)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = colors.background
            g2.fillRect(0, 0, width, height)
            val text = message
            val s = scene
            val v = viewport
            if (text != null) {
                paintMessage(g2, text)
            } else if (s != null && v != null) {
                val diagram = g2.create() as Graphics2D
                try {
                    diagram.translate(v.tx, v.ty)
                    diagram.scale(v.scale, v.scale)
                    DiagramPainter.paint(diagram, s, colors)
                } finally {
                    diagram.dispose()
                }
            }
            if (stale) paintStale(g2)
        } finally {
            g2.dispose()
        }
    }

    private fun paintMessage(g: Graphics2D, text: String) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = JBFont.label()
        g.color = colors.secondary
        val metrics = g.fontMetrics
        g.drawString(text, (width - metrics.stringWidth(text)) / 2, height / 3)
    }

    /** A badge at the top of the panel, over the diagram. */
    private fun paintStale(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = JBFont.label().asBold()
        val metrics = g.fontMetrics
        val padX = JBUI.scale(10)
        val padY = JBUI.scale(4)
        val w = metrics.stringWidth(STALE) + 2 * padX
        val h = metrics.height + 2 * padY
        val x = (width - w) / 2.0
        val y = JBUI.scale(8).toDouble()
        val badge = RoundRectangle2D.Double(x, y, w.toDouble(), h.toDouble(), h.toDouble(), h.toDouble())
        g.color = DiagramColors.mix(colors.background, WARNING, 0.25)
        g.fill(badge)
        g.color = WARNING
        g.stroke = BasicStroke(1f)
        g.draw(badge)
        g.color = colors.foreground
        g.drawString(STALE, (x + padX).toFloat(), (y + padY + metrics.ascent).toFloat())
    }

    private inner class Mouse : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            requestFocusInWindow()
            if (!SwingUtilities.isLeftMouseButton(e) && !SwingUtilities.isMiddleMouseButton(e)) return
            pressed = e.point
            pressedView = viewport
            dragging = false
        }

        override fun mouseDragged(e: MouseEvent) {
            val from = pressed ?: return
            val view = pressedView ?: return
            if (!dragging && from.distance(e.point) < DRAG_START) return
            dragging = true
            viewport = Viewport(view.scale, view.tx + e.x - from.x, view.ty + e.y - from.y)
            repaint()
        }

        override fun mouseReleased(e: MouseEvent) {
            val from = pressed
            val click = from != null && !dragging && SwingUtilities.isLeftMouseButton(e)
            pressed = null
            pressedView = null
            dragging = false
            if (!click) return
            if (message != null || scene == null || viewport == null) return
            onClick(atPoint(e.x, e.y) { s, x, y, tolerance -> s.hit(x, y, tolerance) })
        }

        override fun mouseWheelMoved(e: MouseWheelEvent) {
            val v = viewport ?: return
            val rotation = e.preciseWheelRotation
            viewport = when {
                e.isControlDown || e.isMetaDown -> v.zoomAt(WHEEL_ZOOM.pow(-rotation), e.x.toDouble(), e.y.toDouble())
                e.isShiftDown -> Viewport(v.scale, v.tx - rotation * WHEEL_STEP, v.ty)
                else -> Viewport(v.scale, v.tx, v.ty - rotation * WHEEL_STEP)
            }
            repaint()
        }
    }

    internal companion object {
        const val STALE = "Out of date"
        /** How far from a path's line, in pixels of the panel, a click or the pointer still counts as on it. */
        const val TOLERANCE = 4.0
        /** How far the pointer has to move with a button down before it pans instead of clicking. */
        const val DRAG_START = 4.0
        const val WHEEL_STEP = 30.0
        const val WHEEL_ZOOM = 1.1
        private val WARNING = java.awt.Color(0xE0A84E)
    }
}

/**
 * Measures text in the fonts [DiagramPainter] draws with. Callable from any thread: the fonts are kept until [reset],
 * which the pane calls when the IDE's look and feel changes.
 */
internal class FontMeasure : TextMeasure {
    private val fonts = ConcurrentHashMap<Pair<Int, Boolean>, Font>()

    override fun width(text: String, size: Int, bold: Boolean): Double {
        val font = fonts.computeIfAbsent(size to bold) { DiagramPainter.font(size, bold) }
        return font.getStringBounds(text, CONTEXT).width
    }

    fun reset() = fonts.clear()

    private companion object {
        /** Antialiased and without fractional metrics, as the painter draws. */
        val CONTEXT = FontRenderContext(null, true, false)
    }
}

/** A size in diagram units. */
internal class DiagramSize(private var w: Double, private var h: Double) : Dimension2D() {
    override fun getWidth(): Double = w

    override fun getHeight(): Double = h

    override fun setSize(width: Double, height: Double) {
        w = width
        h = height
    }
}
