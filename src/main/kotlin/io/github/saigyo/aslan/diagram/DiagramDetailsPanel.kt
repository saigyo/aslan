package io.github.saigyo.aslan.diagram

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable

/**
 * The details of the selected state or path below the diagram: a header with the state's whole name, or the names of
 * the path's two states, and a control that collapses the panel to its header, and a two-column table of the rows. A
 * row's item with a place in the file is a link that reports its offset. Every text of a label goes through
 * [DiagramText.literal]; a [DetailItem.Kind.WRAPPED] item is a text area, which reads no markup. The table takes the
 * width of the panel, so its rows wrap instead of scrolling sideways.
 */
class DiagramDetailsPanel internal constructor(private val onNavigate: (Int) -> Unit) : JPanel(BorderLayout()) {
    internal val header = JBLabel(DiagramText.literal(TITLE)).apply { font = JBFont.label().asBold() }
    internal val toggle = ActionLink("") { collapse(!collapsed) }

    /** The table of rows; it says so when it has none. */
    internal val content: JComponent = Rows().withEmptyText(NOTHING).apply {
        border = JBUI.Borders.empty(0, 10, 6, 10)
    }

    internal val body: JComponent = object : JBScrollPane(content) {
        override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, JBUI.scale(BODY_HEIGHT))
    }.apply { border = JBUI.Borders.empty() }

    internal var rows: List<DetailRow> = emptyList()
        private set

    internal var collapsed: Boolean = DiagramPrefs.detailsCollapsed
        private set

    init {
        border = JBUI.Borders.customLineTop(JBColor.border())
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 10, 4, 10)
            add(header, BorderLayout.CENTER)
            add(toggle, BorderLayout.EAST)
        }
        add(top, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        collapse(collapsed)
    }

    /** Shows the rows of the state named [name]; with a null name the header says "Details" and there are no rows. */
    internal fun show(name: String?, rows: List<DetailRow>) {
        this.rows = rows
        header.text = DiagramText.literal(name ?: TITLE)
        // a narrow pane clips a long name; the tool tip still holds it whole
        header.toolTipText = name?.let(DiagramText::literal)
        content.removeAll()
        for ((i, row) in rows.withIndex()) {
            val label = JBLabel(DiagramText.literal(row.label)).apply { foreground = UIUtil.getContextHelpForeground() }
            content.add(label, GridBagConstraints(0, i, 1, 1, 0.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE, JBUI.insets(2, 0, 2, 16), 0, 0))
            content.add(values(row.items), GridBagConstraints(1, i, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.HORIZONTAL, JBUI.insets(2, 0), 0, 0))
        }
        if (rows.isNotEmpty()) {
            val filler = JPanel().apply { isOpaque = false }
            content.add(filler, GridBagConstraints(0, rows.size, 2, 1, 1.0, 1.0, GridBagConstraints.NORTHWEST, GridBagConstraints.BOTH, JBUI.emptyInsets(), 0, 0))
        }
        content.revalidate()
        content.repaint()
    }

    private fun values(items: List<DetailItem>): JComponent {
        items.singleOrNull()?.takeIf { it.kind == DetailItem.Kind.WRAPPED }?.let { return WrappedText(it.text) }
        val panel = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        for ((i, item) in items.withIndex()) {
            val one = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
            }
            val prefix = item.prefix.trimEnd()
            if (prefix.isNotEmpty()) {
                one.add(JBLabel(DiagramText.literal(prefix)))
                one.add(Box.createHorizontalStrut(JBUI.scale(4)))
            }
            val offset = item.offset
            one.add(if (offset != null) ActionLink(DiagramText.literal(item.text)) { onNavigate(offset) } else JBLabel(DiagramText.literal(item.text)))
            if (i < items.size - 1) one.add(JBLabel(DiagramText.literal(SEPARATOR)))
            panel.add(one)
        }
        return panel
    }

    private fun collapse(value: Boolean) {
        collapsed = value
        DiagramPrefs.detailsCollapsed = value
        body.isVisible = !value
        toggle.text = if (value) EXPAND else COLLAPSE
        toggle.icon = if (value) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
        revalidate()
        repaint()
    }

    /** The table: as wide as the scroll pane's view, and as high as its rows or the view, whichever is higher. */
    private class Rows : JBPanelWithEmptyText(GridBagLayout()), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = visible.height
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = (parent as? JViewport)?.let { it.height > preferredSize.height } ?: false
    }

    /**
     * A long text, such as a condition, wrapped at spaces to the width of its column. It asks for little width, so the
     * column's width decides, and for the height of its text at the width it was given last.
     */
    private class WrappedText(text: String) : JBTextArea(text) {
        init {
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
            isFocusable = false
            isOpaque = false
            border = JBUI.Borders.empty()
            font = JBFont.label()
        }

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(WRAPPED_WIDTH), super.getPreferredSize().height)

        override fun getMinimumSize(): Dimension = preferredSize
    }

    private companion object {
        const val TITLE = "Details"
        const val NOTHING = "Select a state or a path to see its details"
        const val WRAPPED_WIDTH = 60
        const val COLLAPSE = "Collapse"
        const val EXPAND = "Expand"
        const val SEPARATOR = ","
        const val BODY_HEIGHT = 150
    }
}
