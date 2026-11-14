package io.github.saigyo.aslan.docs

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Shows [AslDocumentation] for ASL field names and values on hover and in the Quick Documentation action. */
class AslDocumentationProvider : AbstractDocumentationProvider() {
    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? =
        contextElement?.takeIf { AslDocumentation.html(it) != null }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? =
        element?.let { AslDocumentation.html(it) }

    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? = AslDocumentation.html(element)
}
