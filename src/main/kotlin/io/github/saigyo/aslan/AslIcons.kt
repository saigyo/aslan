package io.github.saigyo.aslan

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Icons of the structure view. Each state type has its own colour, which the diagram of a later stage reuses. */
object AslIcons {
    private fun load(name: String): Icon = IconLoader.getIcon("/icons/$name.svg", AslIcons::class.java)

    private val stateIcons: Map<String, Icon> = listOf("Task", "Pass", "Choice", "Wait", "Succeed", "Fail", "Parallel", "Map")
        .associateWith { load("state-${it.lowercase()}") }

    /** The icon of a state of type [typeName]; a neutral one when the type is null or unknown. */
    fun forStateType(typeName: String?): Icon = stateIcons[typeName] ?: Neutral

    val Neutral: Icon = load("state-pass")
    val Assign: Icon = load("assign")
    val Rule: Icon = load("rule")
    val Catch: Icon = load("catch")
    val Branch: Icon = load("branch")
}
