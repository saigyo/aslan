package io.github.saigyo.aslan.refs

import com.intellij.ide.projectView.PresentationData
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.ItemPresentationProvider
import io.github.saigyo.aslan.AslIcons
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Presents a YAML key-value that is an assignment of a state machine variable by the variable's name, with the place
 * of the assignment as its location, as Go to Declaration does. Without it a key-value presents itself by its value,
 * which is what the rename preview and the usage view showed as the renamed element (`null` for `rejection: null`).
 * A JSON property presents itself by its name already. The YAML key-value asks the item presentation providers first;
 * for any other key-value this one gives nothing, so YAML's own presentation applies.
 */
class AslAssignmentPresentationProvider : ItemPresentationProvider<YAMLKeyValue> {
    override fun getPresentation(item: YAMLKeyValue): ItemPresentation? {
        if (!item.isValid || AslVariableTargets.assignmentEntryAt(item) !== item) return null
        val targets = AslVariableTargets.assignmentTargets(AslVariableTargets.variablesAt(item)).filter { it.entry == item }
        val name = targets.firstOrNull()?.variable?.name ?: return null
        // an entry used through a YAML alias by several states has a place for each
        return PresentationData(name, targets.map { it.locationString }.distinct().joinToString("; "), AslIcons.Assign, null)
    }
}
