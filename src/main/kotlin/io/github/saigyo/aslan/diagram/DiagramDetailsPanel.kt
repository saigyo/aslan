package io.github.saigyo.aslan.diagram

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * The details of the selected state or path below the diagram: a header with the state's whole name, or the names of
 * the path's two states, and a control that collapses the panel to its header, and a two-column table of the rows. A
 * row's item with a place in the file is a link that reports its offset. Every text of a label goes through
 * [DiagramText.literal]; a [DetailItem.Kind.WRAPPED] item is a text area, which reads no markup, and the
 * [DetailItem.Kind.TEXT] items of a row are text panes stacked one below the other, which hold plain text and report a
 * click on one of their links to code. The table takes the
 * width of the panel, so its rows wrap instead of scrolling sideways, as long as its widest item that cannot wrap fits;
 * else it is as wide as that item needs, and the panel scrolls sideways.
 */
class DiagramDetailsPanel internal constructor(
    private val onNavigate: (Int) -> Unit,
    private val onOpenCode: (CodeLink) -> Unit = {},
) : JPanel(BorderLayout()) {
    internal val header = JBLabel(DiagramText.literal(TITLE)).apply { font = JBFont.label().asBold() }
    internal val toggle = ActionLink("") { collapse(!collapsed) }

    /** The table of rows; it says so when it has none. */
    private val table = Rows()

    internal val content: JComponent = table.withEmptyText(NOTHING).apply {
        border = JBUI.Borders.empty(0, 10, 6, 10)
    }

    internal val body: JComponent = object : JBScrollPane(content) {
        override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, JBUI.scale(BODY_HEIGHT))
    }.apply { border = JBUI.Borders.empty() }

    internal var rows: List<DetailRow> = emptyList()
        private set

    internal var collapsed: Boolean = DiagramPrefs.detailsCollapsed
        private set

    /** Told when the panel is collapsed or expanded, with the new state. */
    internal var onCollapse: (Boolean) -> Unit = {}

    /** Expanded, the panel keeps its header and about one row in view; collapsed, it is as high as its header. */
    override fun getMinimumSize(): Dimension =
        if (collapsed) super.getMinimumSize() else Dimension(super.getMinimumSize().width, JBUI.scale(MIN_HEIGHT))

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
        table.labels.clear()
        table.pieces.clear()
        for ((i, row) in rows.withIndex()) {
            val label = JBLabel(DiagramText.literal(row.label)).apply { foreground = UIUtil.getContextHelpForeground() }
            table.labels += label
            content.add(label, GridBagConstraints(0, i, 1, 1, 0.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE, JBUI.insets(2, 0, 2, LABEL_GAP), 0, 0))
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
        if (items.isNotEmpty() && items.all { it.kind == DetailItem.Kind.TEXT }) {
            return JPanel(VerticalLayout(JBUI.scale(2))).apply {
                isOpaque = false
                for (item in items) add(LinkedText(item, onOpenCode))
            }
        }
        val panel = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(ITEM_GAP), 0)).apply { isOpaque = false }
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
            table.pieces += one
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
        onCollapse(value)
    }

    /**
     * The table: as wide as the scroll pane's view while its widest item that cannot wrap fits there, else as wide as it
     * wants; as high as its rows or the view, whichever is higher. [labels] are the rows' labels and [pieces] the items
     * that cannot wrap, each a label or a link with its prefix; a text area wraps and is in neither.
     */
    private class Rows : JBPanelWithEmptyText(GridBagLayout()), Scrollable {
        val labels = ArrayList<JComponent>()
        val pieces = ArrayList<JComponent>()

        /** The width the table needs to show its widest item that cannot wrap whole. */
        fun least(): Int {
            val label = labels.maxOfOrNull { it.preferredSize.width }?.plus(JBUI.scale(LABEL_GAP)) ?: 0
            val piece = pieces.maxOfOrNull { it.preferredSize.width } ?: 0
            return insets.left + insets.right + label + piece
        }

        /** A row of items that wrap asks for no more width than it has; the widest item still needs its whole width. */
        override fun getPreferredSize(): Dimension = super.getPreferredSize().let { Dimension(maxOf(it.width, least()), it.height) }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = visible.height
        override fun getScrollableTracksViewportWidth(): Boolean = (parent as? JViewport)?.let { it.width >= least() } ?: true
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
            // a row of items starts after one gap of its layout; the text starts there too
            border = JBUI.Borders.emptyLeft(ITEM_GAP)
            font = JBFont.label()
        }

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(WRAPPED_WIDTH), super.getPreferredSize().height)

        override fun getMinimumSize(): Dimension = preferredSize
    }

    /**
     * A text from a comment, wrapped at spaces to the width of its column like [WrappedText], with its links to code
     * underlined in the colour of links. The text goes into the document as it is, so it is never read as markup; a
     * click with the primary button on a link reports it. A text with links is a stop of the keyboard: it selects its
     * first link (its last when reached backwards), Tab and Shift+Tab move to the next and the previous link and then
     * on, and Enter or Space report the selected one.
     */
    private class LinkedText(item: DetailItem, onOpen: (CodeLink) -> Unit) : JTextPane() {
        private val links = item.links.sortedBy { it.start }

        /** The link the keyboard has selected. */
        private var current = 0

        init {
            isEditable = false
            isFocusable = links.isNotEmpty()
            focusTraversalKeysEnabled = false
            isOpaque = false
            border = JBUI.Borders.emptyLeft(ITEM_GAP)
            // the look and feel of Linux gives a text pane a margin; the text starts where the other values start
            margin = JBUI.emptyInsets()
            font = JBFont.label()
            // a text pane scrolls its caret into view as the text goes in, which moved the panel to the end of a long
            // line when the rows are wider than the pane
            (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
            val plain = SimpleAttributeSet()
            val link = SimpleAttributeSet().apply {
                StyleConstants.setForeground(this, JBUI.CurrentTheme.Link.Foreground.ENABLED)
                StyleConstants.setUnderline(this, true)
            }
            var at = 0
            for (one in links) {
                if (one.start < at || one.end > item.text.length) continue
                styledDocument.insertString(styledDocument.length, item.text.substring(at, one.start), plain)
                styledDocument.insertString(styledDocument.length, item.text.substring(one.start, one.end), link)
                at = one.end
            }
            styledDocument.insertString(styledDocument.length, item.text.substring(at), plain)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e) || e.isPopupTrigger) return
                    linkAt(e)?.let(onOpen)
                }
            })
            addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) {
                    current = if (e.cause == FocusEvent.Cause.TRAVERSAL_BACKWARD) links.lastIndex else 0
                    mark()
                }

                override fun focusLost(e: FocusEvent) = select(0, 0)
            })
            key(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openLink") { links.getOrNull(current)?.let(onOpen) }
            key(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "openLink") { links.getOrNull(current)?.let(onOpen) }
            key(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "nextLink") {
                if (current < links.lastIndex) {
                    current++
                    mark()
                } else {
                    transferFocus()
                }
            }
            key(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "previousLink") {
                if (current > 0) {
                    current--
                    mark()
                } else {
                    transferFocusBackward()
                }
            }
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    cursor = if (linkAt(e) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                }
            })
        }

        private fun key(stroke: KeyStroke, name: String, run: () -> Unit) {
            getInputMap(WHEN_FOCUSED).put(stroke, name)
            actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = run()
            })
        }

        /** Selects the link the keyboard is on, so it can be seen. */
        private fun mark() {
            links.getOrNull(current)?.let { select(it.start, it.end) }
        }

        /** The link under the pointer of [e], or null; a point beside the text, which the nearest offset is found for, has none. */
        private fun linkAt(e: MouseEvent): CodeLink? {
            val offset = viewToModel2D(e.point)
            if (offset < 0) return null
            val link = links.firstOrNull { offset >= it.start && offset < it.end } ?: return null
            val box = modelToView2D(offset) ?: return null
            return link.takeIf { e.y >= box.y && e.y <= box.y + box.height }
        }

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(WRAPPED_WIDTH), super.getPreferredSize().height)

        override fun getMinimumSize(): Dimension = preferredSize
    }

    private companion object {
        const val TITLE = "Details"
        const val NOTHING = "Select a state or a path to see its details"
        const val WRAPPED_WIDTH = 60
        /** The space between the items of a row, and before its first one. */
        const val ITEM_GAP = 6
        /** The space between a row's label and its items. */
        const val LABEL_GAP = 16
        const val COLLAPSE = "Collapse"
        const val EXPAND = "Expand"
        const val SEPARATOR = ","
        /** The height of the rows before the divider is first dragged. */
        const val BODY_HEIGHT = 150
        /** The least height of the expanded panel: its header and about one row. */
        const val MIN_HEIGHT = 60
    }
}
