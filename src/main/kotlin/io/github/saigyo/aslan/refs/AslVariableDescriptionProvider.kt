package io.github.saigyo.aslan.refs

import com.intellij.json.psi.JsonProperty
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewNodeTextLocation
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Names an assignment of a state machine variable, as an entry or as a go-to target, in the usage view and the rename
 * preview: the type is "state machine variable" and the name is the variable's, instead of the YAML or JSON key-value
 * and its value.
 */
class AslVariableDescriptionProvider : ElementDescriptionProvider {
    override fun getElementDescription(element: PsiElement, location: ElementDescriptionLocation): String? {
        val described = location is UsageViewTypeLocation || location is UsageViewShortNameLocation ||
            location is UsageViewLongNameLocation || location is UsageViewNodeTextLocation
        if (!described) return null
        val name = nameOf(element) ?: return null
        return if (location is UsageViewTypeLocation) TYPE else name
    }

    private fun nameOf(element: PsiElement): String? = when (element) {
        is AslVariableTarget -> element.variable.name
        is YAMLKeyValue, is JsonProperty ->
            if (AslVariableTargets.assignmentEntryAt(element) === element) AslVariableTargets.variablesAt(element).firstOrNull()?.name else null
        else -> null
    }

    companion object {
        const val TYPE = "state machine variable"
    }
}
