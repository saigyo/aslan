package io.github.saigyo.aslan.refs

import com.intellij.json.psi.JsonProperty
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.AslVariable
import io.github.saigyo.aslan.model.AslVariables
import io.github.saigyo.aslan.model.JsonataRead
import io.github.saigyo.aslan.model.PathRead
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * A read of a state machine variable: a `$name` in JSONata, its range the whole element, or one in a string JSONPath
 * evaluates, its range `$` and the name. [resolve] is the entry of the variable's first assignment in file order;
 * [multiResolve] gives the entry of every assignment, and the reference refers to each of them. A read through a YAML
 * alias that stands for more than one variable resolves to the assignments of all of them.
 */
class AslVariableReadReference(element: PsiElement, range: TextRange) : PsiPolyVariantReferenceBase<PsiElement>(element, range, true) {
    private fun declarations(): List<PsiElement> =
        AslVariableTargets.assignmentEntries(AslVariableTargets.variablesAt(element, rangeInElement.startOffset))

    override fun resolve(): PsiElement? = declarations().firstOrNull()

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        declarations().map { PsiElementResolveResult(it) }.toTypedArray()

    override fun isReferenceTo(element: PsiElement): Boolean {
        if (element is AslVariableTarget) return element.variable in AslVariableTargets.variablesAt(this.element, rangeInElement.startOffset)
        return declarations().any { element.manager.areElementsEquivalent(it, element) }
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

/**
 * The lookup behind the references, navigation and find usages of state machine variables: which variables an element
 * stands for and where they are assigned and read. Everything is read from [AslVariables], so every feature finds the
 * same variable.
 */
internal object AslVariableTargets {
    /**
     * The variables [element] stands for: those assigned by an assignment entry (or by the key of one, or the entry
     * itself given as a go-to target), those read by a `$name` in JSONata (or a part of it), or those read by the
     * JSONPath string [element] at [offsetInElement], the end of a name included. Several when the element is used
     * through a YAML alias by states of different scopes.
     */
    fun variablesAt(element: PsiElement, offsetInElement: Int? = null): List<AslVariable> {
        if (element is AslVariableTarget) return listOf(element.variable)
        PsiTreeUtil.getNonStrictParentOfType(element, JsonataVariableRef::class.java)?.let { return readVariables(it) }
        assignmentEntryAt(element)?.let { entry ->
            val variables = variablesIn(entry) ?: return emptyList()
            return variables.assignmentsAt(entry).map { variables.variableOf(it) }.distinct()
        }
        if (offsetInElement == null) return emptyList()
        val variables = variablesIn(element) ?: return emptyList()
        return variables.pathReadsAt(element).filter { it.range.containsOffset(offsetInElement) }.mapNotNull { variables.variableOf(it) }.distinct()
    }

    /** The variables an assignment entry or a go-to target assigns; empty for anything else, reads included. */
    fun variablesAssignedAt(element: PsiElement): List<AslVariable> =
        if (element is AslVariableTarget || element is YAMLKeyValue || element is JsonProperty) variablesAt(element) else emptyList()

    /** The entry ([YAMLKeyValue] or [JsonProperty]) of an assignment that is [element] or whose key holds it; else null. */
    fun assignmentEntryAt(element: PsiElement): PsiElement? {
        val entry = PsiTreeUtil.getNonStrictParentOfType(element, YAMLKeyValue::class.java, JsonProperty::class.java) ?: return null
        if (entry !== element) {
            val key = (entry as? YAMLKeyValue)?.key ?: (entry as? JsonProperty)?.nameElement ?: return null
            if (!PsiTreeUtil.isAncestor(key, element, false)) return null
        }
        return entry.takeIf { variablesIn(it)?.assignmentsAt(it)?.isNotEmpty() == true }
    }

    /** The distinct entries of the assignments of [variable], in file order. */
    fun assignmentEntries(variable: AslVariable): List<PsiElement> = assignmentEntries(listOf(variable))

    /** The distinct entries of the assignments of all of [variables], in file order. */
    fun assignmentEntries(variables: List<AslVariable>): List<PsiElement> =
        variables.flatMap { variable -> modelOf(variable)?.assignmentsOf(variable).orEmpty().map { it.entry.entryPsi } }
            .distinct()
            .sortedBy { it.textRange.startOffset }

    /** A reference for each distinct place that reads [variable], in file order. */
    fun readReferences(variable: AslVariable): List<PsiReference> =
        modelOf(variable)?.readsOf(variable).orEmpty().map { read ->
            when (read) {
                is JsonataRead -> read.variable to TextRange(0, read.variable.textLength)
                is PathRead -> read.scalar.psi to read.range
            }
        }.distinct().map { (element, range) -> AslVariableReadReference(element, range) }

    /** The reference on a `$name` in JSONata that reads a state machine variable; null for any other. */
    fun readReference(variable: JsonataVariableRef): PsiReference? =
        if (readVariables(variable).isEmpty()) null else AslVariableReadReference(variable, TextRange(0, variable.textLength))

    /** The references on the JSONPath string [host], one for each name in it that reads a state machine variable. */
    fun pathReadReferences(host: PsiElement): List<PsiReference> {
        val variables = variablesIn(host) ?: return emptyList()
        return variables.pathReadsAt(host).filter { variables.variableOf(it) != null }.map { it.range }.distinct()
            .map { AslVariableReadReference(host, it) }
    }

    private fun readVariables(variable: JsonataVariableRef): List<AslVariable> {
        val host = InjectedLanguageManager.getInstance(variable.project).getInjectionHost(variable) ?: return emptyList()
        val variables = variablesIn(host) ?: return emptyList()
        return variables.readsAt(variable).mapNotNull { variables.variableOf(it) }.distinct()
    }

    private fun variablesIn(element: PsiElement): AslVariables? = element.containingFile?.let(AslModel::of)?.variables

    /** The variables of the file [variable] belongs to; they know [variable] as long as the file's model is current. */
    fun modelOf(variable: AslVariable): AslVariables? = variablesIn(variable.scope.obj.psi)
}
