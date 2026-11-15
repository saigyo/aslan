package io.github.saigyo.aslan.diagram

import org.eclipse.elk.alg.layered.options.CycleBreakingStrategy
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy
import org.eclipse.elk.alg.layered.options.OrderingStrategy
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.options.PortSide
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkLabel
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.ElkPort
import org.eclipse.elk.graph.util.ElkGraphUtil
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.util.concurrent.CancellationException

/** The two layouts of the diagram; [maxLabel] is the number of characters at which a label on a path is cut. */
enum class LayoutMode(val maxLabel: Int) {
    LOOSE(30), COMPACT(20);

    companion object {
        fun defaultFor(states: Int): LayoutMode = if (states <= 25) LOOSE else COMPACT
    }
}

private val layeredAlgorithm: Unit by lazy {
    LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
}

/** Registers the layered algorithm with ELK; only the first call does it. ELK finds no algorithm by a service lookup. */
internal fun registerLayeredAlgorithm() = layeredAlgorithm

/** The width of [text] in the font of the diagram at [size] points; the layouter measures the labels on paths with it. */
fun interface TextMeasure { fun width(text: String, size: Int, bold: Boolean): Double }

/** A laid-out diagram in absolute coordinates; [nodes] lists a parent before its children. */
class DiagramLayout(val width: Double, val height: Double, val nodes: List<LaidNode>, val edges: List<LaidEdge>)

/**
 * A box of the diagram. [id] is the state's ID for a [Kind.STATE] and a [Kind.GROUP], and the machine's ID for a
 * [Kind.BRANCH], whose [state] is null and whose [title] is the branch's title; [title] is null for a state.
 */
class LaidNode(
    val id: String, val kind: Kind, val bounds: Rectangle2D.Double, val state: DiagramState?, val title: String?,
    val start: Boolean, val collapsed: Boolean, val collapsible: Boolean,
) {
    enum class Kind { STATE, GROUP, BRANCH }
}

/** A path: [points] run from the source to the target in right angles. */
class LaidEdge(val edge: DiagramEdge, val points: List<Point2D.Double>, val label: LaidLabel?)

/** The label of a path, cut to the layout's limit, at its place on the path. */
class LaidLabel(val text: String, val bounds: Rectangle2D.Double)

/**
 * A progress monitor that answers [isCanceled] with whether the thread that made it has been interrupted. ELK asks it
 * before each phase of the layered algorithm and before each graph of the recursion, and returns early when it is true.
 * Its sub-monitors are of the same kind: ELK hands them to the phases.
 */
private class InterruptMonitor(private val thread: Thread = Thread.currentThread()) : BasicProgressMonitor() {
    override fun isCanceled(): Boolean = thread.isInterrupted

    override fun doSubTask(work: Float, maxHierarchyLevels: Int): BasicProgressMonitor =
        InterruptMonitor(thread).withMaxHierarchyLevels(if (maxHierarchyLevels > 0) maxHierarchyLevels - 1 else maxHierarchyLevels)
}

/**
 * Lays out a [DiagramModel] with ELK's layered algorithm, from top to bottom. Called from any thread; each call builds
 * its own graph, and a call left behind over the time limit may still run beside the next one. It holds no PSI and
 * takes no read action. An interrupt of the calling thread cancels it: ELK stops at its next
 * phase, and [layout] throws a [CancellationException] and leaves the thread interrupted.
 */
object DiagramLayouter {
    private const val STATE_WIDTH = 190.0
    private const val STATE_HEIGHT = 40.0
    private const val LABEL_SIZE = 10
    private const val LABEL_PADDING = 8.0
    private const val LABEL_HEIGHT = 14.0
    private const val LAYER_SPACING = 54.0
    private const val SIDE_PADDING = 14.0
    /** The painter draws the start mark in the 12 pixels above a state, and the end mark in the 14 below it. */
    private const val START_MARK = 12.0
    private const val END_MARK = 14.0
    private const val BOTTOM_PADDING = END_MARK + 4
    /** The text at the top of a group (the state's name and type line, baseline at 31) and of a branch (its title, baseline at 17). */
    private const val GROUP_TEXT = 34.0
    private const val BRANCH_TEXT = 26.0
    /** Room above the first state of a machine inside a group or a branch: its texts, then the start mark. */
    private const val GROUP_TOP = GROUP_TEXT + START_MARK
    private const val BRANCH_TOP = BRANCH_TEXT + START_MARK

    fun layout(model: DiagramModel, mode: LayoutMode, collapsed: Set<String>, measure: TextMeasure): DiagramLayout {
        val root = model.root ?: return DiagramLayout(0.0, 0.0, emptyList(), emptyList())
        registerLayeredAlgorithm()
        val graph = ElkGraphUtil.createGraph()
        val run = Run(mode, collapsed, measure)
        run.machine(root, graph, SIDE_PADDING)
        val monitor = InterruptMonitor()
        RecursiveGraphLayoutEngine().layout(graph, monitor)
        // a cancelled run leaves the graph partly laid out
        if (monitor.isCanceled()) throw CancellationException("The layout was interrupted")
        return DiagramLayout(graph.width, graph.height, run.nodes(), run.edges())
    }

    private class Routed(val elk: ElkEdge, val edge: DiagramEdge, val label: ElkLabel?)

    private class Run(val mode: LayoutMode, val collapsed: Set<String>, val measure: TextMeasure) {
        private val drafts = ArrayList<Pair<ElkNode, (Rectangle2D.Double) -> LaidNode>>()
        private val routed = ArrayList<Routed>()
        private val ports = HashMap<Pair<ElkNode, PortSide>, ElkPort>()

        /** Adds the states and edges of [machine] to [parent], which shows it with [top] as its upper padding. */
        fun machine(machine: DiagramMachine, parent: ElkNode, top: Double) {
            configure(parent, top)
            val boxes = HashMap<String, ElkNode>()
            for (state in machine.states) {
                val box = box(parent)
                boxes[state.id] = box
                val collapsible = state.machines.isNotEmpty()
                val shut = collapsible && state.name in collapsed
                val kind = if (collapsible && !shut) LaidNode.Kind.GROUP else LaidNode.Kind.STATE
                val start = state.id == machine.start
                drafts += box to { bounds -> LaidNode(state.id, kind, bounds, state, null, start, shut, collapsible) }
                if (kind != LaidNode.Kind.GROUP) continue
                for (inner in state.machines) {
                    val title = inner.title
                    if (title == null) {
                        machine(inner, box, GROUP_TOP)
                        continue
                    }
                    configure(box, GROUP_TOP, branches = true)
                    val branch = box(box)
                    drafts += branch to { bounds -> LaidNode(inner.id, LaidNode.Kind.BRANCH, bounds, null, title, false, false, false) }
                    machine(inner, branch, BRANCH_TOP)
                }
            }
            for (edge in machine.edges) {
                val from = boxes[edge.from] ?: continue
                val to = boxes[edge.to] ?: continue
                val elk = if (mode == LayoutMode.COMPACT) {
                    ElkGraphUtil.createSimpleEdge(port(from, PortSide.SOUTH), port(to, PortSide.NORTH))
                } else {
                    ElkGraphUtil.createSimpleEdge(from, to)
                }
                val label = edge.label?.let { DiagramLabels.shorten(it, mode.maxLabel) }?.let { text ->
                    ElkGraphUtil.createLabel(text, elk).apply {
                        setDimensions(measure.width(text, LABEL_SIZE, false) + LABEL_PADDING, LABEL_HEIGHT)
                        setProperty(CoreOptions.EDGE_LABELS_INLINE, true)   // an option of the label, not of the graph
                    }
                }
                routed += Routed(elk, edge, label)
            }
        }

        fun nodes(): List<LaidNode> = drafts.map { (elk, make) ->
            val origin = origin(elk)
            make(Rectangle2D.Double(origin.x, origin.y, elk.width, elk.height))
        }

        fun edges(): List<LaidEdge> = routed.map { r ->
            val origin = origin(r.elk.containingNode)
            fun at(x: Double, y: Double) = Point2D.Double(origin.x + x, origin.y + y)
            val points = r.elk.sections.flatMap { s ->
                listOf(at(s.startX, s.startY)) + s.bendPoints.map { at(it.x, it.y) } + at(s.endX, s.endY)
            }
            val label = r.label?.let { LaidLabel(it.text, Rectangle2D.Double(origin.x + it.x, origin.y + it.y, it.width, it.height)) }
            LaidEdge(r.edge, points, label)
        }

        private fun box(parent: ElkNode): ElkNode = ElkGraphUtil.createNode(parent).apply { setDimensions(STATE_WIDTH, STATE_HEIGHT) }

        /**
         * The one port on [side] of [node]. In the compact layout every path leaves a state at the bottom and enters at the
         * top, so the paths into a state share a line, and a path that leads back enters at the top as in a flow chart.
         * ELK's merged edges did the sharing per side after cycle breaking had turned such a path, so it shared the point
         * where the paths leave; with ports, edge merging has no effect and is not set.
         */
        private fun port(node: ElkNode, side: PortSide): ElkPort = ports.getOrPut(node to side) {
            node.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)
            ElkGraphUtil.createPort(node).apply { setProperty(CoreOptions.PORT_SIDE, side) }
        }

        /** The absolute place of [node]'s top left corner; ELK places a node relative to its parent. */
        private fun origin(node: ElkNode): Point2D.Double {
            var x = 0.0
            var y = 0.0
            var current: ElkNode? = node
            while (current?.parent != null) {
                x += current.x
                y += current.y
                current = current.parent
            }
            return Point2D.Double(x, y)
        }

        /**
         * The options of a graph that holds states or [branches]: the root, a group or a branch. They start from decision 9
         * of the plan.
         */
        private fun configure(node: ElkNode, top: Double, branches: Boolean = false) {
            node.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
            node.setProperty(CoreOptions.DIRECTION, Direction.DOWN)
            node.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
            node.setProperty(CoreOptions.PADDING, ElkPadding(top, SIDE_PADDING, BOTTOM_PADDING, SIDE_PADDING))
            node.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, LAYER_SPACING)
            // the default, greedy cycle breaking may turn a path that closes no loop upwards; depth first turns only loops
            node.setProperty(LayeredOptions.CYCLE_BREAKING_STRATEGY, CycleBreakingStrategy.DEPTH_FIRST)
            if (branches) {
                // no path joins two branches; as one component in one layer they stand side by side in file order
                node.setProperty(LayeredOptions.SEPARATE_CONNECTED_COMPONENTS, false)
                node.setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY, OrderingStrategy.NODES_AND_EDGES)
            }
            if (mode == LayoutMode.COMPACT) {
                node.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.NETWORK_SIMPLEX)
                node.setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY, OrderingStrategy.NODES_AND_EDGES)
            }
        }
    }
}
