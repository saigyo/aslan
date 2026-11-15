package io.github.saigyo.aslan.diagram

import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil

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

/** The placeholder of the diagram pane: two nodes and an edge, laid out with the layered algorithm. */
internal fun layOutTwoNodes(): ElkNode {
    registerLayeredAlgorithm()
    val root = ElkGraphUtil.createGraph()
    root.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
    root.setProperty(CoreOptions.DIRECTION, Direction.DOWN)
    root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
    val first = ElkGraphUtil.createNode(root).apply { setDimensions(190.0, 40.0) }
    val second = ElkGraphUtil.createNode(root).apply { setDimensions(190.0, 40.0) }
    ElkGraphUtil.createSimpleEdge(first, second)
    RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())
    return root
}
