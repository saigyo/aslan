package io.github.saigyo.aslan.refs

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
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
        return AslVariableTargets.assignmentTargets(variables).takeIf { it.isNotEmpty() }?.toTypedArray()
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
        // a JSON key is a string too; an assignment is no read, in JSON as in YAML
        if (AslVariableTargets.assignmentEntryAt(host) != null) return emptyList()
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
 * The primary elements are the entries of every assignment of the variables; [AslAssignmentPresentationProvider] makes
 * the usage view show a YAML entry by the variable's name and the assignment's place. They are the PSI entries, not
 * go-to targets: a target holds a variable of the model it was made from, and any PSI change before the search runs
 * makes a new model that no longer knows it. Their usages are the same for each of them, so they are searched once,
 * from the first primary element that is still an assignment of the variable: the reads (through
 * [AslReferencesSearcher]) and the keys of the other assignments.
 *
 * The platform keeps the handler and restores the primary elements through smart pointers when the search is run
 * again (Rerun in the Find tool window), so nothing about the file is kept from the handler's creation: each search
 * reads the variables and assignments of the file as it is then.
 */
private class AslVariableFindUsagesHandler(element: PsiElement, private val variables: List<AslVariable>) : FindUsagesHandler(element) {
    /** The entry the search starts on; a go-to target stands for its entry. */
    private val start = SmartPointerManager.createPointer((element as? AslVariableTarget)?.entry ?: element)

    /** The primary elements as last handed out, in the order the platform searches them. */
    @Volatile
    private var primaries: List<SmartPsiElementPointer<PsiElement>> = emptyList()

    /**
     * When neither [start] nor a recorded primary element is still an assignment, the first element a search passes
     * since the file last changed searches, with the file's modification stamp then.
     */
    @Volatile
    private var fallback: Pair<Long, SmartPsiElementPointer<PsiElement>>? = null

    override fun getPrimaryElements(): Array<PsiElement> {
        // the variables of the current model, read from the start entry (a go-to target would hold those of an older
        // model) while it is still an assignment; those of creation otherwise
        val current = start.element?.takeIf { it.isValid }?.let { AslVariableTargets.variablesAssignedAt(it) }.orEmpty()
        val entries = AslVariableTargets.assignmentEntries(current.ifEmpty { variables })
        val pointers = SmartPointerManager.getInstance(project)
        primaries = entries.map { pointers.createSmartPsiElementPointer(it) }
        return entries.toTypedArray()
    }

    override fun processElementUsages(element: PsiElement, processor: Processor<in UsageInfo>, options: FindUsagesOptions): Boolean {
        val writes = runReadActionBlocking { writesIfSearcher(element, options) } ?: return true
        if (!super.processElementUsages(element, processor, options)) return false
        return writes.all { processor.process(it) }
    }

    /**
     * The keys of the other current assignments when [element] is the primary element that searches, the first of
     * them that is still a valid assignment of its variables; null for any other element.
     */
    private fun writesIfSearcher(element: PsiElement, options: FindUsagesOptions): List<UsageInfo>? {
        if (!element.isValid) return null
        val entries = AslVariableTargets.assignmentEntries(AslVariableTargets.variablesAssignedAt(element))
        if (element !in entries) return null
        // the entry the search started on, else the first primary element that still is one of the assignments
        val searcher = (listOf(start) + primaries).firstNotNullOfOrNull { pointer -> pointer.element?.takeIf { it.isValid && it in entries } }
            ?: fallbackSearcher(element, entries)
        if (searcher != element) return null
        return entries.filter { it != element && PsiSearchScopeUtil.isInScope(options.searchScope, it) }.map { UsageInfo(keyOf(it)) }
    }

    /** The one element that searches when no recorded one can: the first valid assignment passed since the last change. */
    @Synchronized
    private fun fallbackSearcher(element: PsiElement, entries: List<PsiElement>): PsiElement {
        val stamp = element.containingFile.modificationStamp
        val previous = fallback?.takeIf { it.first == stamp }?.second?.element?.takeIf { it.isValid && it in entries }
        if (previous != null) return previous
        fallback = stamp to SmartPointerManager.createPointer(element)
        return element
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
