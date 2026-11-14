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

/** Puts an [AslStateReference] on every value that names a state in an ASL file. */
class AslReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(YAMLScalar::class.java), StateNameProvider)
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(JsonStringLiteral::class.java), StateNameProvider)
    }

    private object StateNameProvider : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> =
            StateTargets.referenceAt(element)?.let { arrayOf<PsiReference>(it) } ?: PsiReference.EMPTY_ARRAY
    }
}
