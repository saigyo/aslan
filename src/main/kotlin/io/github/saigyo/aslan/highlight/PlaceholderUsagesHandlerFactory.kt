package io.github.saigyo.aslan.highlight

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Consumer
import io.github.saigyo.aslan.files.AslFiles

/**
 * Highlight Usages with the caret on a `${name}` placeholder marks every occurrence of that placeholder in the file.
 * Elsewhere it does nothing, so the platform's own usage highlighting applies.
 */
class PlaceholderUsagesHandlerFactory : HighlightUsagesHandlerFactory, DumbAware {
    override fun createHighlightUsagesHandler(editor: Editor, file: PsiFile): HighlightUsagesHandlerBase<*>? {
        if (!AslFiles.isAslFile(file)) return null
        val offset = editor.caretModel.offset
        for (at in listOf(offset, offset - 1)) {
            val leaf = file.findElementAt(at) ?: continue
            val element = generateSequence(leaf) { it.parent.takeIf { p -> p !is PsiFile } }
                .firstNotNullOfOrNull { AslHighlighting.textElement(it) } ?: continue
            val range = AslHighlighting.placeholderRanges(element).firstOrNull { offset >= it.startOffset && offset <= it.endOffset }
                ?: continue
            return PlaceholderHandler(editor, file, element, range)
        }
        return null
    }

    private class PlaceholderHandler(editor: Editor, private val file: PsiFile, private val element: PsiElement, private val range: TextRange) :
        HighlightUsagesHandlerBase<PsiElement>(editor, file) {
        override fun getTargets(): List<PsiElement> = listOf(element)

        override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<in List<PsiElement>>) =
            selectionConsumer.consume(targets)

        override fun computeUsages(targets: List<PsiElement>) {
            val fileText = file.text
            val name = range.substring(fileText)
            for (candidate in PsiTreeUtil.collectElements(file) { AslHighlighting.textElement(it) === it }) {
                for (r in AslHighlighting.placeholderRanges(candidate)) {
                    if (r.substring(fileText) == name) myReadUsages.add(r)
                }
            }
        }
    }
}
