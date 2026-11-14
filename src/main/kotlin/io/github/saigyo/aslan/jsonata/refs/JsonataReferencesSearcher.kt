package io.github.saigyo.aslan.jsonata.refs

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiReference
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef

/**
 * Finds the references to a local JSONata variable from [JsonataScopes]. The platform's own search looks for the name
 * as a whole word, and `total` inside `$total` is not one.
 */
class JsonataReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = parameters.elementToSearch as? JsonataVariableRef ?: return
        for (occurrence in JsonataScopes.occurrences(target)) {
            val reference = occurrence.reference ?: continue
            if (!PsiSearchScopeUtil.isInScope(parameters.effectiveSearchScope, occurrence)) continue
            if (!consumer.process(reference)) return
        }
    }
}
