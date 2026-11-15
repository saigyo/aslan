package io.github.saigyo.aslan.diagram

import io.github.saigyo.aslan.model.TransitionKind
import java.awt.geom.Line2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D

/** What the switch for catch paths does: draw them as they are, faint, or not at all. */
enum class CatchMode { NORMAL, MUTED, HIDDEN }

/**
 * One thing to paint, in diagram coordinates. The texts are exactly the ones of the file (cut to fit, never escaped or
 * stripped): nothing here, and nothing in [DiagramPainter], reads them as markup.
 */
sealed interface SceneItem {
    /** A state, or a branch of a Parallel state; [type] is null for a branch and for a state of no type; [name] and [sub] are cut to the box. */
    class Box(
        val id: String, val kind: LaidNode.Kind, val bounds: Rectangle2D.Double, val type: String?, val name: String, val sub: String,
        val tooltip: String?, val selected: Boolean, val linked: Boolean, val start: Boolean, val terminal: Boolean, val collapsed: Boolean,
    ) : SceneItem

    class Path(val id: String, val kind: TransitionKind, val points: List<Point2D.Double>, val selected: Boolean, val muted: Boolean) : SceneItem

    class Label(
        val edgeId: String, val text: String, val bounds: Rectangle2D.Double, val tooltip: String?, val selected: Boolean, val muted: Boolean,
    ) : SceneItem

    /** The control of a Map or Parallel state, by the state's name; [collapsed] says what a click on it undoes. */
    class Toggle(val stateName: String, val bounds: Rectangle2D.Double, val collapsed: Boolean) : SceneItem
}

/** What lies at a point of the diagram. */
sealed interface Hit {
    data class State(val id: String) : Hit
    data class Edge(val id: String) : Hit
    data class Toggle(val stateName: String) : Hit
}

/**
 * A laid-out diagram with a selection, as items to paint in painting order. Plain data: no PSI and no Swing component,
 * usable on any thread. [containers] maps the ID of each state and path that a collapsed box holds to the box's ID.
 */
class DiagramScene(
    val items: List<SceneItem>, val width: Double, val height: Double, private val containers: Map<String, String> = emptyMap(),
) {
    private val boxes = items.filterIsInstance<SceneItem.Box>()
    private val paths = items.filterIsInstance<SceneItem.Path>()
    private val labels = items.filterIsInstance<SceneItem.Label>()
    private val toggles = items.filterIsInstance<SceneItem.Toggle>()

    /** The paths to test in this order: the selected one, then the others. */
    private val pathsByPriority = paths.filter { it.selected } + paths.filterNot { it.selected }

    /** Where in diagram coordinates the point is; [tolerance] is how far from a path's line it still counts. */
    fun hit(x: Double, y: Double, tolerance: Double): Hit? {
        toggles.lastOrNull { it.bounds.contains(x, y) }?.let { return Hit.Toggle(it.stateName) }
        // a state is never taken from under a path that ends at it, but a path inside a group is found before the group
        boxes.lastOrNull { it.kind == LaidNode.Kind.STATE && it.bounds.contains(x, y) }?.let { return Hit.State(it.id) }
        for (path in pathsByPriority) {
            if (near(path, x, y, tolerance) || labels.any { it.edgeId == path.id && it.bounds.contains(x, y) }) return Hit.Edge(path.id)
        }
        boxes.lastOrNull { it.kind == LaidNode.Kind.GROUP && it.bounds.contains(x, y) }?.let { return Hit.State(it.id) }
        return null
    }

    private fun near(path: SceneItem.Path, x: Double, y: Double, tolerance: Double): Boolean =
        path.points.zipWithNext().any { (a, b) -> Line2D.ptSegDist(a.x, a.y, b.x, b.y, x, y) <= tolerance }

    /** The hover text at the point, plain with lines separated by "\n", or null. */
    fun tooltip(x: Double, y: Double, tolerance: Double): String? = when (val hit = hit(x, y, tolerance)) {
        is Hit.State -> boxes.firstOrNull { it.id == hit.id }?.tooltip
        is Hit.Edge -> labels.firstOrNull { it.edgeId == hit.id }?.tooltip
        else -> null
    }

    /** The place of a state or a path; for one a collapsed box holds, the box's; null for an ID the scene does not show. */
    fun bounds(id: String): Rectangle2D? {
        boxes.firstOrNull { it.id == id }?.let { return it.bounds }
        paths.firstOrNull { it.id == id }?.let { path ->
            val first = path.points.firstOrNull() ?: return null
            val rect = Rectangle2D.Double(first.x, first.y, 0.0, 0.0)
            for (p in path.points) rect.add(p)
            return rect
        }
        val container = containers[id] ?: return null
        return boxes.firstOrNull { it.id == container }?.bounds
    }

    companion object {
        const val NAME_SIZE = 12
        const val SUB_SIZE = 10
        /** The room in a box left of the text (the bar and a gap) and right of it, and the extra room for a toggle. */
        const val PAD_LEFT = 14.0
        const val PAD_RIGHT = 10.0
        const val TOGGLE_SIZE = 16.0
        const val TOGGLE_ROOM = 24.0
        private const val ELLIPSIS = "…"

        fun of(layout: DiagramLayout, selected: String?, catchMode: CatchMode, measure: TextMeasure): DiagramScene {
            val inside = HashMap<String, String>()
            for (node in layout.nodes) {
                if (node.collapsed) node.state?.machines?.forEach { collect(it, node.id, inside) }
            }
            val edge = layout.edges.firstOrNull { it.edge.id == selected }?.edge
            val boxes = layout.nodes.map { box(it, selected, edge, inside, measure) }
            val shown = layout.edges.filter { catchMode != CatchMode.HIDDEN || it.edge.kind != TransitionKind.CATCH }
            val (top, rest) = shown.partition { it.edge.id == selected }
            fun path(e: LaidEdge) = SceneItem.Path(
                e.edge.id, e.edge.kind, e.points, e.edge.id == selected, catchMode == CatchMode.MUTED && e.edge.kind == TransitionKind.CATCH,
            )
            fun label(e: LaidEdge): SceneItem.Label? = e.label?.let { l ->
                val whole = e.edge.label
                val tooltip = e.edge.title ?: whole?.takeIf { it != l.text }
                SceneItem.Label(
                    e.edge.id, l.text, l.bounds, tooltip, e.edge.id == selected,
                    catchMode == CatchMode.MUTED && e.edge.kind == TransitionKind.CATCH,
                )
            }
            val toggles = layout.nodes.filter { it.collapsible }.map { node ->
                val b = node.bounds
                SceneItem.Toggle(
                    node.state!!.name,
                    Rectangle2D.Double(b.maxX - TOGGLE_ROOM + (TOGGLE_ROOM - TOGGLE_SIZE) / 2 - 2, b.y + 10, TOGGLE_SIZE, TOGGLE_SIZE),
                    node.collapsed,
                )
            }
            val items = ArrayList<SceneItem>(boxes)
            rest.forEach { items += path(it) }
            rest.forEach { e -> label(e)?.let { items += it } }
            items += toggles
            top.forEach { items += path(it) }
            top.forEach { e -> label(e)?.let { items += it } }
            return DiagramScene(items, layout.width, layout.height, inside)
        }

        /** Every state and path of [machine], and of the machines below it, is held by the box [box]. */
        private fun collect(machine: DiagramMachine, box: String, into: MutableMap<String, String>) {
            for (e in machine.edges) into[e.id] = box
            for (s in machine.states) {
                into[s.id] = box
                s.machines.forEach { collect(it, box, into) }
            }
        }

        private fun box(node: LaidNode, selected: String?, edge: DiagramEdge?, inside: Map<String, String>, measure: TextMeasure): SceneItem.Box {
            val state = node.state
            val name = state?.name ?: node.title.orEmpty()
            val sub = state?.sub.orEmpty()
            val room = node.bounds.width - PAD_LEFT - PAD_RIGHT - if (node.collapsible) TOGGLE_ROOM else 0.0
            val shownName = fit(name, NAME_SIZE, true, room, measure)
            val shownSub = fit(sub, SUB_SIZE, false, room, measure)
            val tooltip = when {
                shownSub != sub -> "$name\n$sub"
                shownName != name -> name
                else -> null
            }
            val isSelected = selected != null && node.kind != LaidNode.Kind.BRANCH &&
                (node.id == selected || (node.collapsed && inside[selected] == node.id))
            val linked = edge != null && node.kind != LaidNode.Kind.BRANCH && (node.id == edge.from || node.id == edge.to)
            return SceneItem.Box(
                node.id, node.kind, node.bounds, state?.type, shownName, shownSub, tooltip, isSelected, linked,
                node.start, state?.terminal ?: false, node.collapsed,
            )
        }

        /** [text] if it is at most [room] wide, else its longest start that with an ellipsis still is. */
        private fun fit(text: String, size: Int, bold: Boolean, room: Double, measure: TextMeasure): String {
            if (measure.width(text, size, bold) <= room) return text
            var end = text.codePointCount(0, text.length)
            while (end > 0) {
                end--
                val cut = text.substring(0, text.offsetByCodePoints(0, end)) + ELLIPSIS
                if (measure.width(cut, size, bold) <= room) return cut
            }
            return ELLIPSIS
        }
    }
}
