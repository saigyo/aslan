package io.github.saigyo.aslan.diagram

import kotlin.math.roundToInt
import java.awt.event.ComponentEvent
import java.awt.event.ComponentAdapter
import java.awt.Dimension
import com.intellij.ui.scale.JBUIScale
import com.intellij.openapi.ui.ThreeComponentsSplitter
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * What the controller sees of the diagram pane. The pane shows what it is given and reports what the user does; it
 * holds no selection of its own beyond the one it was last given or the user last clicked. All calls on the UI thread.
 */
interface DiagramView {
    val component: JComponent

    /** A click on a state or a path; null for a click on the background. */
    var onSelect: (id: String?) -> Unit

    /** A link in the details panel. */
    var onNavigate: (offset: Int) -> Unit

    /** A link to code in the details panel. */
    var onOpenCode: (CodeLink) -> Unit

    /** A click on a box's toggle. */
    var onToggleBox: (statePath: String) -> Unit

    /** The layout switch. */
    var onLayoutMode: (LayoutMode) -> Unit

    /** Measures with the fonts the panel paints with; callable from any thread. */
    val measure: TextMeasure

    /** [selected]: a state or edge ID of this model, or null. Also clears the out-of-date mark. */
    fun show(model: DiagramModel, layout: DiagramLayout, mode: LayoutMode, selected: String?)

    /** A state or an edge of the model shown, or null. */
    fun select(id: String?)

    fun setStale()
}

/** Text for Swing components that read HTML: a text that starts with `<html>` would be read as markup (decision 10). */
object DiagramText {
    /**
     * [text] as HTML that shows it as it is: `&`, `<` and `>` escaped, a line break as `<br>`. HTML shows a run of spaces
     * as one and drops a space at the start of a line, so such a space becomes a non-breaking one; a single space between
     * words stays ordinary, and the line can still be wrapped there.
     */
    fun literal(text: String): String {
        val out = StringBuilder(text.length + 16).append("<html>")
        var previous = '\n'
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '\n' -> out.append("<br>")
                ' ' -> out.append(if (previous == ' ' || previous == '\n') "&nbsp;" else " ")
                else -> out.append(c)
            }
            previous = c
        }
        return out.append("</html>").toString()
    }
}

/**
 * The diagram pane, from top to bottom: a toolbar with the catch switch, the layout switch and the zoom controls; the
 * diagram; the legend; the details panel. Changes of the editor's colour scheme and the IDE's theme are followed
 * through the application's message bus, connected with [parent].
 */
class DiagramPane(parent: Disposable) : DiagramView {
    override var onSelect: (id: String?) -> Unit = {}
    override var onNavigate: (offset: Int) -> Unit = {}
    override var onToggleBox: (statePath: String) -> Unit = {}
    override var onLayoutMode: (LayoutMode) -> Unit = {}
    override var onOpenCode: (CodeLink) -> Unit = {}

    private val fonts = FontMeasure()
    override val measure: TextMeasure get() = fonts

    internal val diagram = DiagramPanel()
    internal val pinch = PinchZoom(diagram)
    internal val details = DiagramDetailsPanel({ onNavigate(it) }, { onOpenCode(it) })
    private val legend = Legend { diagram.colors }

    private var model: DiagramModel? = null
    internal var layout: DiagramLayout? = null
        private set
    private var mode: LayoutMode? = null
    private var selected: String? = null
    private var catchMode: CatchMode = DiagramPrefs.catchMode

    /** The path of the box whose toggle was clicked last; the next [show] brings that box into view. */
    private var toggled: String? = null

    private val catchSwitches = CatchMode.entries.associateWith { CatchSwitch(it) }
    private val layoutSwitches = LayoutMode.entries.associateWith { LayoutSwitch(it) }
    internal val catchMenu = SwitchMenu({ "Catch: " + catchMode.title() }, { true }, CatchMode.entries.map { catchSwitches.getValue(it) })
    internal val layoutMenu = SwitchMenu({ mode?.let { "Layout: " + it.title() } ?: "Layout" }, { mode != null }, LayoutMode.entries.map { layoutSwitches.getValue(it) })
    internal val zoomOut: AnAction = Zoom("Zoom Out", AllIcons.General.ZoomOut) { zoomAtCenter(1 / ZOOM_STEP) }
    internal val zoomIn: AnAction = Zoom("Zoom In", AllIcons.General.ZoomIn) { zoomAtCenter(ZOOM_STEP) }
    internal val fit: AnAction = Zoom("Fit to Window", AllIcons.General.FitContent) { fitToWindow() }

    override val component: JComponent = JPanel(BorderLayout())

    /**
     * The diagram with its legend above a divider, and the details panel below it; dragging the divider sets the
     * panel's height, which stays when the pane grows or shrinks and is remembered for the whole IDE.
     */
    internal val split: ThreeComponentsSplitter = Split()

    /** The height the details panel should have: the one dragged to last, else its default. */
    private var wantedHeight = 0

    /**
     * Fits the details panel into the splitter whenever the splitter's own height changes, which a drag of the divider
     * never does: the panel takes [wantedHeight] as far as the diagram keeps its least height.
     */
    private inner class Split : ThreeComponentsSplitter(true, true) {
        private var laidHeight = -1

        override fun doLayout() {
            if (height != laidHeight) {
                laidHeight = height
                fitDetails()
            }
            super.doLayout()
        }
    }

    /** Holds the collapsed details panel, which shows only its header and cannot be dragged. */
    private val collapsedDetails = JPanel(BorderLayout())
    internal val toolbar: ActionToolbar

    init {
        // two drop-downs instead of five buttons and two labels: the toolbar fits the diagram's side of the split
        val group = DefaultActionGroup().apply {
            add(catchMenu)
            add(layoutMenu)
            addSeparator()
            add(zoomOut)
            add(zoomIn)
            add(fit)
        }
        toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        toolbar.setTargetComponent(component)
        toolbar.component.border = JBUI.Borders.customLineBottom(JBColor.border())
        // No update here: the platform ignores one before the toolbar is in a window and logs a warning; it fills a new
        // toolbar itself when the toolbar is added to one.
        val area = object : JPanel(BorderLayout()) {
            override fun getMinimumSize(): Dimension = Dimension(super.getMinimumSize().width, JBUI.scale(MIN_DIAGRAM_HEIGHT))
        }.apply {
            add(pinch, BorderLayout.CENTER)
            add(legend, BorderLayout.SOUTH)
        }
        split.innerComponent = area
        split.setHonorComponentsMinimumSize(true)
        split.addDividerResizeListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = dividerMoved()
            override fun componentMoved(e: ComponentEvent) = dividerMoved()
        })
        component.add(toolbar.component, BorderLayout.NORTH)
        component.add(split, BorderLayout.CENTER)
        component.add(collapsedDetails, BorderLayout.SOUTH)
        placeDetails(details.collapsed)
        details.onCollapse = ::placeDetails
        diagram.onClick = ::clicked
        // The IDE's image viewer zooms on the keys of Expand All and Collapse All (Cmd+Plus and Cmd+Minus on macOS).
        // Registered on the diagram, they zoom only while it has the focus, so in the text editor they still fold.
        zoomIn.registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_EXPAND_ALL), diagram)
        zoomOut.registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_COLLAPSE_ALL), diagram)

        val bus = ApplicationManager.getApplication().messageBus.connect(parent)
        bus.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { recolour() })
        bus.subscribe(LafManagerListener.TOPIC, LafManagerListener {
            fonts.reset()
            recolour()
            rebuild()
        })
    }

    internal fun catchSwitch(mode: CatchMode): ToggleAction = catchSwitches.getValue(mode)

    internal fun layoutSwitch(mode: LayoutMode): ToggleAction = layoutSwitches.getValue(mode)

    override fun show(model: DiagramModel, layout: DiagramLayout, mode: LayoutMode, selected: String?) {
        val modeChanged = this.mode != null && this.mode != mode
        this.model = model
        this.layout = layout
        this.mode = mode
        this.selected = selected
        diagram.stale = false
        diagram.message = if (model.root == null) EMPTY else null
        rebuild()
        showDetails()
        val box = toggled
        toggled = null
        when {
            diagram.viewport == null -> if (diagram.hasSize) place() else diagram.whenSized = ::place
            // the new layout moves everything; the box just clicked stays where the user is looking
            box != null -> reveal(layout.nodes.firstOrNull { it.collapsible && it.state?.path == box }?.id)
            // with nothing to reveal, the old zoom and position belong to another layout: start again
            modeChanged -> if (selected?.let { diagram.scene?.bounds(it) } != null) reveal(selected) else place()
        }
        if (diagram.viewport != null && diagram.hasSize && !anyBoxVisible()) place()
        toolbar.updateActionsAsync()
    }

    override fun select(id: String?) {
        mark(id)
        reveal(id)
    }

    override fun setStale() {
        diagram.stale = true
        diagram.repaint()
    }

    /** Puts the details panel below the divider at its remembered height, or, [collapsed], under the diagram without one. */
    private fun placeDetails(collapsed: Boolean) {
        if (collapsed) {
            split.lastComponent = null
            collapsedDetails.add(details, BorderLayout.CENTER)
        } else {
            collapsedDetails.remove(details)
            split.lastComponent = details
            wantedHeight = DiagramPrefs.detailsHeight?.let(JBUI::scale) ?: details.preferredSize.height
            split.lastSize = wantedHeight
            fitDetails()
        }
        component.revalidate()
        component.repaint()
    }

    /** Remembers the height the divider was dragged to, in pixels at 100% scale. */
    internal fun dividerMoved() {
        if (details.collapsed || split.lastComponent !== details) return
        wantedHeight = split.lastSize
        DiagramPrefs.detailsHeight = (split.lastSize / JBUIScale.scale(1f)).roundToInt()
    }

    /** Gives the details panel [wantedHeight], or less where the diagram would get less than its least height. */
    private fun fitDetails() {
        if (split.lastComponent !== details || split.height <= 0) return
        val room = split.height - (split.innerComponent?.minimumSize?.height ?: 0) - split.dividerWidth
        val fitted = minOf(wantedHeight, room).coerceAtLeast(details.minimumSize.height)
        if (split.lastSize != fitted) split.lastSize = fitted
    }

    private fun clicked(hit: Hit?) {
        when (hit) {
            is Hit.Toggle -> {
                toggled = hit.statePath
                onToggleBox(hit.statePath)
            }
            is Hit.State -> {
                mark(hit.id)
                onSelect(hit.id)
            }
            is Hit.Edge -> {
                mark(hit.id)
                onSelect(hit.id)
            }
            null -> {
                mark(null)
                onSelect(null)
            }
        }
    }

    private fun mark(id: String?) {
        selected = id
        rebuild()
        showDetails()
    }

    private fun rebuild() {
        val l = layout ?: return
        diagram.scene = DiagramScene.of(l, selected, catchMode, fonts)
        diagram.repaint()
    }

    /** The details of the selected state or path; a path is named by its two states. */
    private fun showDetails() {
        val m = model
        val id = selected
        val edge = id?.let { m?.edge(it) }
        if (m != null && edge != null) {
            val from = m.state(edge.from)?.name ?: edge.from
            val to = m.state(edge.to)?.name ?: edge.to
            details.show("$from → $to", DiagramDetails.pathRows(m, edge))
            return
        }
        val state = id?.let { m?.state(it) }
        details.show(state?.name, state?.let { m?.details?.get(it.id) }.orEmpty())
    }

    /**
     * Sets the first view: full size around what is selected; with nothing selected the top of the diagram, moved the
     * least that brings the start state of the root machine into view (a file opens with the caret outside every state,
     * and the start of a wide diagram need not lie at its top left). A model without a machine sets none, so that the
     * first diagram with one still gets its first view.
     */
    private fun place() {
        val s = diagram.scene ?: return
        val root = model?.root ?: return
        val size = DiagramSize(s.width, s.height)
        val chosen = selected?.let(s::bounds)
        val top = Viewport.around(chosen, size, diagram.size)
        val start = if (chosen == null) root.start?.let(s::bounds) else null
        diagram.viewport = if (start != null) top.reveal(start, diagram.size, REVEAL_MARGIN) else top
        diagram.repaint()
    }

    /** Whether some box of the scene lies at least partly in the pane under the current view. */
    private fun anyBoxVisible(): Boolean {
        val s = diagram.scene ?: return true
        val v = diagram.viewport ?: return true
        val pane = Rectangle2D.Double(0.0, 0.0, diagram.width.toDouble(), diagram.height.toDouble())
        val boxes = s.items.filterIsInstance<SceneItem.Box>()
        return boxes.isEmpty() || boxes.any {
            pane.intersects(it.bounds.x * v.scale + v.tx, it.bounds.y * v.scale + v.ty, it.bounds.width * v.scale, it.bounds.height * v.scale)
        }
    }

    private fun reveal(id: String?) {
        val v = diagram.viewport ?: return
        val rect = id?.let { diagram.scene?.bounds(it) } ?: return
        diagram.viewport = v.reveal(rect, diagram.size, REVEAL_MARGIN)
        diagram.repaint()
    }

    private fun zoomAtCenter(factor: Double) {
        val v = diagram.viewport ?: return
        diagram.viewport = v.zoomAt(factor, diagram.width / 2.0, diagram.height / 2.0)
        diagram.repaint()
    }

    private fun fitToWindow() {
        val s = diagram.scene ?: return
        if (diagram.viewport == null) return
        diagram.viewport = Viewport.fit(DiagramSize(s.width, s.height), diagram.size, FIT_PADDING)
        diagram.repaint()
    }

    private fun setCatchMode(mode: CatchMode) {
        catchMode = mode
        DiagramPrefs.catchMode = mode
        rebuild()
        toolbar.updateActionsAsync()   // the drop-down's caption names the new mode
    }

    private fun recolour() {
        diagram.colors = DiagramColors.current()
        legend.recolour()
        diagram.repaint()
    }

    /** An option of the catch drop-down. */
    private inner class CatchSwitch(private val value: CatchMode) : DumbAwareToggleAction(value.title(), "Catch paths: ${value.title().lowercase()}", null) {
        init {
            closesMenu()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = catchMode == value

        /** A click on the setting that is on leaves it on. */
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) setCatchMode(value)
        }
    }

    /** An option of the layout drop-down. */
    private inner class LayoutSwitch(private val value: LayoutMode) : DumbAwareToggleAction(value.title(), "${value.title()} layout", null) {
        init {
            closesMenu()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        /** The mode of the last [show]: the switch paints nothing itself, the controller lays out again and shows. */
        override fun isSelected(e: AnActionEvent): Boolean = mode == value

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state && mode != value) onLayoutMode(value)
        }
    }

    private inner class Zoom(text: String, icon: Icon, private val perform: () -> Unit) : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = diagram.viewport != null && diagram.message == null
        }

        override fun actionPerformed(e: AnActionEvent) = perform()
    }

    /**
     * A drop-down of the toolbar: a popup group shown as its text with the platform's drop-down arrow. It shows
     * [caption] (the switch and its current value) and offers [options], one per setting; it does nothing while
     * [enabled] is false. A popup group rather than a `ComboBoxAction`: the combo button takes its padding from the look
     * and feel's button border, which made the toolbar too wide for the diagram's side of the split.
     */
    internal class SwitchMenu(
        private val caption: () -> String,
        private val enabled: () -> Boolean,
        internal val options: List<ToggleAction>,
    ) : DefaultActionGroup(options), DumbAware {
        init {
            templatePresentation.isPopupGroup = true
            templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
            templatePresentation.text = caption()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.text = caption()
            e.presentation.isEnabled = enabled()
        }
    }

    private companion object {
        /**
         * An option of a drop-down closes it when chosen, as in a combo box. A toggle keeps its menu open by default, and
         * an open menu is not updated when the new layout arrives a moment later, so it showed the old check mark.
         */
        fun ToggleAction.closesMenu() {
            templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never
        }

        const val TOOLBAR_PLACE = "AslanDiagram"
        /** The least height of the diagram with its legend above the divider. */
        const val MIN_DIAGRAM_HEIGHT = 100
        const val EMPTY = "No state machine to show yet."
        const val ZOOM_STEP = 1.25
        const val FIT_PADDING = 16
        /** How far from the edges of the panel a revealed state or path is placed, in pixels. */
        const val REVEAL_MARGIN = 24

        fun CatchMode.title() = name.lowercase().replaceFirstChar { it.uppercase() }

        fun LayoutMode.title() = name.lowercase().replaceFirstChar { it.uppercase() }
    }
}

/** The legend under the diagram: the eight type colours and the catch line, in the diagram's colours. */
private class Legend(private val colors: () -> DiagramColors) : JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(12), JBUI.scale(2))) {
    private val labels: List<JBLabel>

    init {
        border = JBUI.Borders.empty(2, 6, 4, 6)
        labels = TYPES.map { JBLabel(it, Swatch(it), JBLabel.LEFT) } + JBLabel("Catch", CatchLine(), JBLabel.LEFT)
        labels.forEach { it.iconTextGap = JBUI.scale(4); add(it) }
        recolour()
    }

    fun recolour() {
        val c = colors()
        background = c.background
        labels.forEach { it.foreground = c.secondary }
        repaint()
    }

    private inner class Swatch(private val type: String) : Icon {
        override fun getIconWidth() = JBUI.scale(10)

        override fun getIconHeight() = JBUI.scale(10)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = colors().typeColor(type)
                g2.fillRoundRect(x, y, iconWidth, iconHeight, JBUI.scale(3), JBUI.scale(3))
            } finally {
                g2.dispose()
            }
        }
    }

    private inner class CatchLine : Icon {
        override fun getIconWidth() = JBUI.scale(22)

        override fun getIconHeight() = JBUI.scale(10)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.color = colors().catchColor
                val unit = JBUI.scale(1).toFloat()
                g2.stroke = BasicStroke(1.5f * unit, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(4f * unit, 3f * unit), 0f)
                val middle = y + iconHeight / 2
                g2.drawLine(x, middle, x + iconWidth, middle)
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        val TYPES = listOf("Task", "Choice", "Pass", "Wait", "Succeed", "Fail", "Parallel", "Map")
    }
}
