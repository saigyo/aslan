package io.github.saigyo.aslan.refs

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import io.github.saigyo.aslan.AslIcons
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import io.github.saigyo.aslan.model.AslVariable
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import javax.swing.Icon

/**
 * One assignment of [variable] as a target of go to declaration: it navigates to the assignment's [entry] and is
 * presented by the variable's name with the place of the assignment ([io.github.saigyo.aslan.model.AslAssignment.placeText])
 * as its location, so the targets of a read that several states assign can be told apart.
 */
internal class AslVariableTarget(val variable: AslVariable, val entry: PsiElement, private val place: String) : FakePsiElement() {
    override fun getParent(): PsiElement = entry

    override fun getNavigationElement(): PsiElement = entry

    override fun getTextRange(): TextRange = entry.textRange

    override fun isValid(): Boolean = entry.isValid

    override fun getName(): String = variable.name

    override fun getLocationString(): String = place

    override fun getIcon(open: Boolean): Icon = AslIcons.Assign

    override fun equals(other: Any?): Boolean =
        other is AslVariableTarget && other.variable == variable && other.entry == entry && other.place == place

    override fun hashCode(): Int = (variable.hashCode() * 31 + entry.hashCode()) * 31 + place.hashCode()
}

/**
 * Go to declaration from a read of a state machine variable lists every assignment of it with its place (decision 5),
 * whether the read is a `$name` in JSONata or in a string JSONPath evaluates.
 */
class AslVariableGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val variables = sourceElement?.let { readVariablesAt(it, offset) }.orEmpty()
        if (variables.isEmpty()) return null
        val targets = variables.flatMap { variable ->
            AslVariableTargets.modelOf(variable)?.assignmentsOf(variable).orEmpty()
                .map { AslVariableTarget(variable, it.entry.entryPsi, it.placeText) }
        }.distinct().sortedBy { it.entry.textRange.startOffset }
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /**
     * The variables read at [offset] of the file of [element], the element there: a `$name` of an injected expression,
     * in its own file or, given the host file, at the offset in the host, or a name in a JSONPath string.
     */
    private fun readVariablesAt(element: PsiElement, offset: Int): List<AslVariable> {
        val injected = InjectedLanguageManager.getInstance(element.project).findInjectedElementAt(element.containingFile, offset)
        for (candidate in listOfNotNull(element, injected)) {
            PsiTreeUtil.getNonStrictParentOfType(candidate, JsonataVariableRef::class.java)?.let { return AslVariableTargets.variablesAt(it) }
        }
        val host = PsiTreeUtil.getNonStrictParentOfType(element, YAMLScalar::class.java, JsonStringLiteral::class.java) ?: return emptyList()
        return AslVariableTargets.variablesAt(host, offset - host.textRange.startOffset)
    }
}

/** Find usages of a state machine variable, started on an assignment entry, on a go-to target or (through the reference) on a read. */
class AslVariableFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean = AslVariableTargets.variablesAssignedAt(element).isNotEmpty()

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? {
        val variables = AslVariableTargets.variablesAssignedAt(element)
        if (variables.isEmpty() || AslVariableTargets.assignmentEntries(variables).isEmpty()) return null
        return AslVariableFindUsagesHandler(element, variables)
    }
}

/**
 * The primary elements are the entries of every assignment of the variables. Their usages are the same for each of
 * them, so they are searched once, from the entry the search starts on: the reads (through [AslReferencesSearcher])
 * and the keys of the other assignments.
 */
private class AslVariableFindUsagesHandler(element: PsiElement, variables: List<AslVariable>) : FindUsagesHandler(element) {
    private val entries = AslVariableTargets.assignmentEntries(variables)
    private val start = (element as? AslVariableTarget)?.entry?.takeIf { it in entries } ?: element.takeIf { it in entries } ?: entries.first()

    override fun getPrimaryElements(): Array<PsiElement> = entries.toTypedArray()

    override fun processElementUsages(element: PsiElement, processor: Processor<in UsageInfo>, options: FindUsagesOptions): Boolean {
        if (element != start) return true
        if (!super.processElementUsages(element, processor, options)) return false
        val writes = ReadAction.compute<List<UsageInfo>, RuntimeException> {
            entries.filter { it != start && PsiSearchScopeUtil.isInScope(options.searchScope, it) }.map { UsageInfo(keyOf(it)) }
        }
        return writes.all { processor.process(it) }
    }

    private fun keyOf(entry: PsiElement): PsiElement = (entry as? YAMLKeyValue)?.key ?: (entry as? JsonProperty)?.nameElement ?: entry
}

/** An assignment entry, or its key, writes the variable; a read reads it. */
class AslVariableReadWriteAccessDetector : ReadWriteAccessDetector() {
    override fun isReadWriteAccessible(element: PsiElement): Boolean = AslVariableTargets.variablesAssignedAt(element).isNotEmpty()

    override fun isDeclarationWriteAccess(element: PsiElement): Boolean = isReadWriteAccessible(element)

    override fun getReferenceAccess(referencedElement: PsiElement, reference: PsiReference): Access =
        if (reference is AslVariableReadReference) Access.Read else getExpressionAccess(reference.element)

    override fun getExpressionAccess(expression: PsiElement): Access =
        if (AslVariableTargets.assignmentEntryAt(expression) != null) Access.Write else Access.Read
}
