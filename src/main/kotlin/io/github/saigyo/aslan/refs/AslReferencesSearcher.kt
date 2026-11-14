package io.github.saigyo.aslan.refs

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslModel

/**
 * Finds the references to an element of an ASL file by reading the model of the file that holds it, so names are found
 * however they are quoted or escaped. The search stays in that file. [referencesTo] says what refers to a target.
 */
open class AslReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = parameters.elementToSearch
        val file = target.containingFile ?: return
        if (!AslFiles.isAslFile(file)) return
        val document = AslModel.of(file) ?: return
        for (reference in referencesTo(target, document)) {
            if (!PsiSearchScopeUtil.isInScope(parameters.effectiveSearchScope, reference.element)) continue
            if (!consumer.process(reference)) return
        }
    }

    /**
     * The references in [document] to [target]; for a state's entry, those of every value that names the state; for an
     * assignment entry, every read of the variables it assigns.
     */
    protected open fun referencesTo(target: PsiElement, document: AslDocument): List<PsiReference> =
        StateTargets.statesDeclaredBy(document, target).flatMap { StateTargets.referencesTo(document, it) } +
            AslVariableTargets.variablesAssignedAt(target).flatMap { AslVariableTargets.readReferences(it) }
}
