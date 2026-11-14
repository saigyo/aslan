package io.github.saigyo.aslan.refs

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.AslState
import io.github.saigyo.aslan.model.AslStateMachine
import io.github.saigyo.aslan.spec.ValueKind
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.Placeholders
import io.github.saigyo.aslan.tree.ScalarType
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * The state-specific lookup behind [AslStateReference] and [AslReferencesSearcher]: which values name a state, which
 * states they name, and which values name a given state. Everything is read from the model.
 */
internal object StateTargets {
    /** The reference on [element] when it is the value of a `StartAt`, `Next` or `Default` field, else null. */
    fun referenceAt(element: PsiElement): AslStateReference? {
        if (element !is YAMLScalar && element !is JsonStringLiteral) return null
        val file = element.containingFile ?: return null
        if (!AslFiles.isAslFile(file)) return null
        val document = AslModel.of(file) ?: return null
        if (target(document, element) == null) return null
        return AslStateReference(element, ElementManipulators.getValueTextRange(element))
    }

    /** The entries of the states that the state name written as [element] names. */
    fun declarations(element: PsiElement): List<PsiElement> {
        val document = element.containingFile?.let(AslModel::of) ?: return emptyList()
        val (machine, name) = target(document, element) ?: return emptyList()
        return machine.states.filter { it.name == name }.map { it.entry.entryPsi }.distinct()
    }

    /** The states whose entry is [element] (several when a YAML merge brings one entry into more machines). */
    fun statesDeclaredBy(document: AslDocument, element: PsiElement): List<AslState> =
        document.allStates().filter { it.entry.entryPsi == element }

    /** The references that name [state]: the `StartAt` of its machine and the transitions of the states in it. */
    fun referencesTo(document: AslDocument, state: AslState): List<AslStateReference> {
        val machine = state.machine
        val candidates = buildList {
            machine.startAt?.value?.let { add(it) }
            for (s in machine.states) for (t in s.transitions) t.target?.let { add(it) }
        }
        return candidates.asSequence()
            .filter { it is AslScalar && it.text == state.name }
            .map { it.psi }
            .distinct()
            .mapNotNull { referenceAt(it) }
            .toList()
    }

    /** The machine and the name for the state name written as [element]; null when [element] is not one. */
    private fun target(document: AslDocument, element: PsiElement): Pair<AslStateMachine, String>? {
        val keyPsi = keyOf(element) ?: return null
        if (document.schema.field(keyPsi)?.spec?.kind != ValueKind.StateName) return null
        val owner = document.stateAt(element)
        val (scalar, machine) = if (owner == null) {
            val root = document.root ?: return null
            (startScalar(root) ?: return null) to root
        } else {
            owner.transitions.firstNotNullOfOrNull { t -> t.target?.takeIf { it.psi === element } }?.let { it to owner.machine }
                ?: owner.children.firstNotNullOfOrNull { m -> startScalar(m)?.takeIf { it.psi === element }?.let { it to m } }
                ?: return null
        }
        if (scalar.psi !== element || scalar.usePsi !== element) return null
        if (scalar.type != ScalarType.STRING || Placeholders.contains(scalar.text)) return null
        return machine to scalar.text
    }

    private fun startScalar(machine: AslStateMachine): AslScalar? = machine.startAt?.value as? AslScalar

    /** The key of the entry whose value is [element]. */
    private fun keyOf(element: PsiElement): PsiElement? = when (val parent = element.parent) {
        is YAMLKeyValue -> parent.key?.takeIf { parent.value === element }
        is JsonProperty -> parent.nameElement.takeIf { parent.value === element }
        else -> null
    }
}
