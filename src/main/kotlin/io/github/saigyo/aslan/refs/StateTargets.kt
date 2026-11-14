package io.github.saigyo.aslan.refs

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
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
        return AslStateReference(element, valueRange(element))
    }

    /** The range of the name inside [element]: between any quotes, and after any tag or anchor in front of it. */
    private fun valueRange(element: PsiElement): TextRange {
        val range = ElementManipulators.getValueTextRange(element)
        if (element !is YAMLScalar) return range
        val start = YamlScalars.contentStart(element)
        return if (range.startOffset >= start) range else TextRange(start, maxOf(start, range.endOffset))
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

    /**
     * The references that name [state]: the `StartAt` of its machine and the transitions of the states in it. A value
     * written through an alias counts only when its anchored scalar is itself a reference to [state].
     */
    fun referencesTo(document: AslDocument, state: AslState): List<AslStateReference> =
        namedTargets(state.machine).asSequence()
            .filter { it.text == state.name }
            .map { it.psi }
            .distinct()
            .mapNotNull { referenceAt(it) }
            .filter { reference -> reference.multiResolve(false).any { it.element == state.entry.entryPsi } }
            .toList()

    /**
     * The alias values (`Next: *d`) that a rename of [state] would leave wrong: those that name [state] but whose
     * anchored scalar is not a reference to it, so they keep the old name, and those whose anchored scalar is a
     * reference to it but that name a state of another machine, which the rename would change with it.
     */
    fun aliasesBrokenByRename(document: AslDocument, state: AslState): List<PsiElement> {
        val renamed = referencesTo(document, state).map { it.element }.toSet()
        return document.allMachines().flatMap { machine ->
            namedTargets(machine).filter { scalar ->
                scalar.usePsi !== scalar.psi && scalar.text == state.name && isStateNameField(document, scalar.usePsi) &&
                    (machine === state.machine) != (scalar.psi in renamed)
            }.map { it.usePsi }
        }.distinct()
    }

    /** The string scalars written as the `StartAt` of [machine] and as the targets of its states' transitions. */
    private fun namedTargets(machine: AslStateMachine): List<AslScalar> = buildList {
        startScalar(machine)?.let { add(it) }
        for (s in machine.states) for (t in s.transitions) t.target?.let { add(it) }
    }.filter { it.type == ScalarType.STRING }

    private fun isStateNameField(document: AslDocument, value: PsiElement): Boolean {
        val keyPsi = keyOf(value) ?: return false
        return document.schema.field(keyPsi)?.spec?.kind == ValueKind.StateName
    }

    /** The machine and the name for the state name written as [element]; null when [element] is not one. */
    private fun target(document: AslDocument, element: PsiElement): Pair<AslStateMachine, String>? {
        if (!isStateNameField(document, element)) return null
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
