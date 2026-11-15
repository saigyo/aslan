package io.github.saigyo.aslan.diagram

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The details of the selected state below the diagram: a header with the state's whole name and a control that
 * collapses the panel to its header, and a two-column table of the state's rows. A row's item with a place in the file
 * is a link that reports its offset. Every text goes through [DiagramText.literal], so nothing of the file is read as
 * markup.
 */
class DiagramDetailsPanel internal constructor(private val onNavigate: (Int) -> Unit) : JPanel(BorderLayout()) {
    internal val header = JBLabel(DiagramText.literal(TITLE)).apply { font = JBFont.label().asBold() }
    internal val toggle = ActionLink("") { collapse(!collapsed) }

    /** The table of rows; it says so when it has none. */
    internal val content: JComponent = JBPanelWithEmptyText(GridBagLayout()).withEmptyText(NOTHING).apply {
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

    private companion object {
        const val TITLE = "Details"
        const val NOTHING = "Select a state to see its details"
        const val COLLAPSE = "Collapse"
        const val EXPAND = "Expand"
        const val SEPARATOR = ","
        const val BODY_HEIGHT = 150
    }
}
