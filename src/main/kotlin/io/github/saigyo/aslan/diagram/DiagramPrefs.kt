package io.github.saigyo.aslan.diagram

import com.intellij.ide.util.PropertiesComponent

/** What the diagram pane remembers for the whole IDE, in the application's properties (decision 7). */
object DiagramPrefs {
    private const val CATCH_PATHS = "aslan.diagram.catchPaths"
    private const val DETAILS_COLLAPSED = "aslan.diagram.detailsCollapsed"

    private val properties: PropertiesComponent get() = PropertiesComponent.getInstance()

    /** The setting of the switch for catch paths; a value it does not know reads as [CatchMode.NORMAL]. */
    var catchMode: CatchMode
        get() = properties.getValue(CATCH_PATHS)?.let { name -> CatchMode.entries.firstOrNull { it.name == name } } ?: CatchMode.NORMAL
        set(value) = properties.setValue(CATCH_PATHS, value.name, CatchMode.NORMAL.name)

    /** Whether the details panel shows only its header. */
    var detailsCollapsed: Boolean
        get() = properties.getBoolean(DETAILS_COLLAPSED, false)
        set(value) = properties.setValue(DETAILS_COLLAPSED, value, false)
}
