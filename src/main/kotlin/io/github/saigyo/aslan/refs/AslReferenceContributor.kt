package io.github.saigyo.aslan.refs

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Puts an [AslStateReference] on every value that names a state in an ASL file, and an [AslVariableReadReference] on
 * every `$name` in a JSONPath string that reads a state machine variable.
 */
class AslReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        for (type in listOf(YAMLScalar::class.java, JsonStringLiteral::class.java)) {
            registrar.registerReferenceProvider(PlatformPatterns.psiElement(type), StateNameProvider)
            registrar.registerReferenceProvider(PlatformPatterns.psiElement(type), PathReadProvider)
        }
    }

    private object StateNameProvider : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> =
            StateTargets.referenceAt(element)?.let { arrayOf<PsiReference>(it) } ?: PsiReference.EMPTY_ARRAY
    }

    private object PathReadProvider : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> =
            AslVariableTargets.pathReadReferences(element).toTypedArray()
    }
}
