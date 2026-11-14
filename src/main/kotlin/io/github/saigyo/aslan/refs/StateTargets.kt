package io.github.saigyo.aslan.refs

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.AslState
import io.github.saigyo.aslan.model.AslStateMachine
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.Placeholders
import io.github.saigyo.aslan.tree.ScalarType
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * The state-specific lookup behind [AslStateReference] and [AslReferencesSearcher]: which values name a state, which
 * states they name, and which values name a given state. Everything is read from the model.
 */
internal object StateTargets {
    /**
     * The reference on [element] when the model reads it as the `StartAt` of a state machine or as the target of a
     * transition (`Next`, `Default`, a Choice rule's or a catcher's `Next`), whatever the type of its state; else null.
     */
    fun referenceAt(element: PsiElement): AslStateReference? {
        if (element !is YAMLScalar && element !is JsonStringLiteral) return null
        val file = element.containingFile ?: return null
        if (!AslFiles.isAslFile(file)) return null
        val document = AslModel.of(file) ?: return null
        if (target(document, element) == null) return null
        return AslStateReference(element, valueRange(element))
    }

    /** The range of the name inside [element]: between any quotes, and after any tag or anchor in front of it. */
    internal fun valueRange(element: PsiElement): TextRange {
        val range = ElementManipulators.getValueTextRange(element)
        if (element !is YAMLScalar) return range
        val content = YamlScalarContent.start(element)
        if (range.startOffset >= content) return range
        // The platform measures the quotes from the start of the element, so with a tag or anchor in front the
        // range starts too early and still holds the opening quote.
        val quote = element is YAMLQuotedText && content < element.textLength && element.text[content] in "'\""
        val start = if (quote) content + 1 else content
        return TextRange(start, maxOf(start, range.endOffset))
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
     * The values that a rename of [state] would leave wrong, each where it is written in the machine that uses it: an
     * alias (`Next: *d`) or a value inside an aliased list or mapping (`Catch: *handlers`). These are the values that
     * name [state] but whose own scalar is not a reference to it, so they keep the old name, and those whose scalar is
     * a reference to it but that name a state of another machine, which the rename would change with it.
     */
    fun aliasesBrokenByRename(document: AslDocument, state: AslState): List<PsiElement> {
        val renamed = referencesTo(document, state).map { it.element }.toSet()
        return document.allMachines().flatMap { machine ->
            namedTargets(machine).filter { scalar ->
                scalar.text == state.name && (machine === state.machine) != (scalar.psi in renamed)
            }.map { it.usePsi }
        }.distinct()
    }

    /**
     * The string scalars written as the `StartAt` of [machine] and as the targets of its states' transitions. A value
     * with a deployment tag (`!Ref Done`) names whatever the tag resolves to, not a state, and is left out.
     */
    private fun namedTargets(machine: AslStateMachine): List<AslScalar> = buildList {
        startScalar(machine)?.let { add(it) }
        for (s in machine.states) for (t in s.transitions) t.target?.let { add(it) }
    }.filter { it.type == ScalarType.STRING && !it.dynamic }

    /**
     * Where a value written directly as [element] names a state: [machine] is the machine whose states it names, and
     * [from] the state whose transition it is (null for a `StartAt`).
     */
    internal class Site(val scalar: AslScalar, val machine: AslStateMachine, val from: AslState?)

    /**
     * The [Site] of [element] when the model reads it, written directly and not through an alias, as the `StartAt` of a
     * machine or the target of a transition; null otherwise. The model alone decides, so this holds in states whose
     * type is a placeholder, unknown or missing too.
     */
    internal fun site(document: AslDocument, element: PsiElement): Site? {
        fun AslScalar.isWrittenAs(element: PsiElement) = psi === element && usePsi === element
        val owner = document.stateAt(element)
        if (owner == null) {
            val root = document.root ?: return null
            return startScalar(root)?.takeIf { it.isWrittenAs(element) }?.let { Site(it, root, null) }
        }
        owner.transitions.firstNotNullOfOrNull { t -> t.target?.takeIf { it.isWrittenAs(element) } }?.let { return Site(it, owner.machine, owner) }
        return owner.children.firstNotNullOfOrNull { m -> startScalar(m)?.takeIf { it.isWrittenAs(element) }?.let { Site(it, m, null) } }
    }

    /** The machine and the name for the state name written as [element]; null when [element] is not one. */
    private fun target(document: AslDocument, element: PsiElement): Pair<AslStateMachine, String>? {
        val site = site(document, element) ?: return null
        val scalar = site.scalar
        if (scalar.type != ScalarType.STRING || scalar.dynamic || Placeholders.contains(scalar.text)) return null
        return site.machine to scalar.text
    }

    private fun startScalar(machine: AslStateMachine): AslScalar? = machine.startAt?.value as? AslScalar
}
