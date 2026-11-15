package io.github.saigyo.aslan.diagram

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import io.github.saigyo.aslan.model.TransitionKind
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D

/**
 * The colours of a diagram. The type colours and the colour of catch paths are fixed (they come from the icons of the
 * structure view); the rest is derived from the pane's [background] and [foreground] and an [accent].
 */
class DiagramColors(val background: Color, val foreground: Color, val accent: Color, val isDark: Boolean) {
    val boxFill: Color = mix(background, foreground, if (isDark) 0.10 else 0.05)
    val groupFill: Color = mix(background, foreground, if (isDark) 0.05 else 0.025)
    val border: Color = mix(background, foreground, 0.35)
    val line: Color = mix(background, foreground, 0.6)
    val secondary: Color = mix(background, foreground, 0.65)
    val selectedFill: Color = mix(boxFill, accent, 0.3)
    val catchColor: Color = CATCH

    fun typeColor(type: String?): Color = typeColor(type, isDark)

    companion object {
        private val CATCH = Color(0xDB5C5C)
        private val types = mapOf(
            "Task" to Color(0x56A8F5), "Choice" to Color(0xE0A84E), "Pass" to Color(0x8A8D93), "Wait" to Color(0x7E8CE0),
            "Succeed" to Color(0x3FB8AF), "Fail" to Color(0xDB5C5C), "Parallel" to Color(0xC77DBB), "Map" to Color(0x6AAB73),
        )
        private val darkPass = Color(0xA5A8AE)

        /** The colour of a state of [type]; an unknown or missing type has the colour of Pass (decision 15). */
        fun typeColor(type: String?, dark: Boolean): Color {
            val key = if (type in types) type else "Pass"
            return if (dark && key == "Pass") darkPass else types.getValue(key!!)
        }

        /** From the editor's colour scheme and the IDE's theme. */
        fun current(): DiagramColors {
            val scheme = EditorColorsManager.getInstance().globalScheme
            val background = scheme.defaultBackground
            return DiagramColors(background, scheme.defaultForeground, JBUI.CurrentTheme.Focus.focusColor(), ColorUtil.isDark(background))
        }

        fun light() = DiagramColors(Color.WHITE, Color(0x1E1F22), Color(0x3574F0), false)

        fun dark() = DiagramColors(Color(0x1E1F22), Color(0xDFE1E5), Color(0x548AF7), true)

        internal fun mix(a: Color, b: Color, share: Double): Color = Color(
            (a.red + (b.red - a.red) * share).toInt(), (a.green + (b.green - a.green) * share).toInt(),
            (a.blue + (b.blue - a.blue) * share).toInt(),
        )
    }
}

/**
 * Paints a [DiagramScene] with Java2D at scale 1 in diagram coordinates; the pane sets the transform. Text goes through
 * `drawString`, never through a component that reads markup.
 */
object DiagramPainter {
    private const val BAR = 4.0
    private const val ARC = 10.0
    private const val MUTED = 0.3f

    /** The font of the diagram at [size] points, unscaled: the pane measures text with the same font. */
    fun font(size: Int, bold: Boolean): Font = Font(JBUI.Fonts.label().family, if (bold) Font.BOLD else Font.PLAIN, size)

    fun paint(g: Graphics2D, scene: DiagramScene, colors: DiagramColors) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            for (item in scene.items) when (item) {
                is SceneItem.Box -> box(g2, item, colors)
                is SceneItem.Path -> faint(g2, item.muted) { path(it, item, colors) }
                is SceneItem.Label -> faint(g2, item.muted) { label(it, item, colors) }
                is SceneItem.Toggle -> toggle(g2, item, colors)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun faint(g: Graphics2D, muted: Boolean, paint: (Graphics2D) -> Unit) {
        if (!muted) return paint(g)
        val old = g.composite
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, MUTED)
        try {
            paint(g)
        } finally {
            g.composite = old
        }
    }

    private fun box(g: Graphics2D, box: SceneItem.Box, colors: DiagramColors) {
        val b = box.bounds
        val shape = RoundRectangle2D.Double(b.x, b.y, b.width, b.height, ARC, ARC)
        if (box.kind == LaidNode.Kind.BRANCH) {
            g.color = colors.groupFill
            g.fill(shape)
            g.color = colors.border
            g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 3f), 0f)
            g.draw(shape)
            g.font = font(DiagramScene.SUB_SIZE, false)
            g.color = colors.secondary
            g.drawString(box.name, (b.x + 10).toFloat(), (b.y + 17).toFloat())
            return
        }
        g.color = when {
            box.selected -> colors.selectedFill
            box.kind == LaidNode.Kind.GROUP -> colors.groupFill
            else -> colors.boxFill
        }
        g.fill(shape)
        val clip = g.clip
        g.clip(shape)
        g.color = colors.typeColor(box.type)
        g.fill(java.awt.geom.Rectangle2D.Double(b.x, b.y, BAR, b.height))
        g.clip = clip
        g.color = if (box.selected || box.linked) colors.accent else colors.border
        g.stroke = BasicStroke(if (box.selected) 2f else if (box.linked) 1.5f else 1f)
        g.draw(shape)
        g.font = font(DiagramScene.NAME_SIZE, true)
        g.color = colors.foreground
        g.drawString(box.name, (b.x + DiagramScene.PAD_LEFT).toFloat(), (b.y + 18).toFloat())
        g.font = font(DiagramScene.SUB_SIZE, false)
        g.color = colors.secondary
        g.drawString(box.sub, (b.x + DiagramScene.PAD_LEFT).toFloat(), (b.y + 31).toFloat())
        g.color = colors.foreground
        g.stroke = BasicStroke(1.5f)
        if (box.start) {
            g.fill(Ellipse2D.Double(b.x + 12, b.y - 12, 8.0, 8.0))
            g.draw(Line2D.Double(b.x + 16, b.y - 4, b.x + 16, b.y))
        }
        if (box.terminal) {
            g.draw(Line2D.Double(b.x + 16, b.maxY, b.x + 16, b.maxY + 4))
            g.draw(Ellipse2D.Double(b.x + 11, b.maxY + 4, 10.0, 10.0))
            g.fill(Ellipse2D.Double(b.x + 13.5, b.maxY + 6.5, 5.0, 5.0))
        }
    }

    private fun path(g: Graphics2D, path: SceneItem.Path, colors: DiagramColors) {
        if (path.points.size < 2) return
        val catches = path.kind == TransitionKind.CATCH
        g.color = when {
            path.selected -> colors.accent
            catches -> colors.catchColor
            else -> colors.line
        }
        val width = if (path.selected) 2.5f else 1.5f
        g.stroke = if (catches) BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(6f, 4f), 0f)
        else BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        val line = Path2D.Double()
        line.moveTo(path.points[0].x, path.points[0].y)
        for (p in path.points.drop(1)) line.lineTo(p.x, p.y)
        g.draw(line)
        arrowhead(g, path.points, width)
    }

    private fun arrowhead(g: Graphics2D, points: List<Point2D.Double>, width: Float) {
        val tip = points.last()
        val from = points.dropLast(1).lastOrNull { it.distance(tip) > 0.5 } ?: return
        val length = tip.distance(from)
        val dx = (tip.x - from.x) / length
        val dy = (tip.y - from.y) / length
        val size = 6.0 + width
        val baseX = tip.x - dx * size
        val baseY = tip.y - dy * size
        val half = size * 0.5
        val head = Path2D.Double()
        head.moveTo(tip.x, tip.y)
        head.lineTo(baseX - dy * half, baseY + dx * half)
        head.lineTo(baseX + dy * half, baseY - dx * half)
        head.closePath()
        g.stroke = BasicStroke(1f)
        g.fill(head)
    }

    private fun label(g: Graphics2D, label: SceneItem.Label, colors: DiagramColors) {
        val b = label.bounds
        val shape = RoundRectangle2D.Double(b.x, b.y, b.width, b.height, 6.0, 6.0)
        g.color = if (label.selected) colors.selectedFill else colors.background
        g.fill(shape)
        if (label.selected) {
            g.color = colors.accent
            g.stroke = BasicStroke(1f)
            g.draw(shape)
        }
        g.font = font(DiagramScene.SUB_SIZE, false)
        g.color = if (label.selected) colors.foreground else colors.secondary
        val metrics = g.fontMetrics
        val x = b.centerX - metrics.stringWidth(label.text) / 2.0
        val y = b.centerY + (metrics.ascent - metrics.descent) / 2.0
        g.drawString(label.text, x.toFloat(), y.toFloat())
    }

    private fun toggle(g: Graphics2D, toggle: SceneItem.Toggle, colors: DiagramColors) {
        val b = toggle.bounds
        g.color = colors.background
        g.fill(RoundRectangle2D.Double(b.x, b.y, b.width, b.height, 4.0, 4.0))
        g.color = colors.border
        g.stroke = BasicStroke(1f)
        g.draw(RoundRectangle2D.Double(b.x, b.y, b.width, b.height, 4.0, 4.0))
        g.color = colors.foreground
        g.draw(Line2D.Double(b.x + 4, b.centerY, b.maxX - 4, b.centerY))
        if (toggle.collapsed) g.draw(Line2D.Double(b.centerX, b.y + 4, b.centerX, b.maxY - 4))
    }
}
