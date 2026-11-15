package io.github.saigyo.aslan.highlight

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.actions.BaseCodeInsightAction
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import io.github.saigyo.aslan.files.AslFiles

/**
 * Next and Previous Highlighted Usage. They replace the platform's actions of the same ids, whose classes are internal
 * API, and do what those do (`GotoElementUnderCaretUsageBase`): step to the next highlight of the element under the
 * caret in the editor and file the action is given, the injected ones with the caret in an injection.
 *
 * With the caret in an expression of an ASL file, the injected file's range is too small: the usages of a state
 * machine variable are highlighted in the host, all over the definition. There the actions step through the highlights
 * of the host editor in the whole host file.
 */
abstract class ElementUnderCaretUsageAction(private val ordering: Comparator<Int>) : BaseCodeInsightAction(), CodeInsightActionHandler {
    override fun getHandler(): CodeInsightActionHandler = this

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, psiFile: PsiFile) {
        val host = InjectedLanguageManager.getInstance(project).getTopLevelFile(psiFile)
        if (host !== psiFile && AslFiles.isAslFile(host)) {
            val hostEditor = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
            navigate(project, hostEditor, 0, hostEditor.document.textLength, ordering)
        } else {
            navigate(project, editor, psiFile.textRange.startOffset, psiFile.textRange.endOffset, ordering)
        }
    }

    companion object {
        /**
         * Moves the caret of [editor] to the next highlighted usage of the element under the caret between [start] and
         * [end] in [ordering] (natural for Next, reversed for Previous), or round to the first one, unfolding and
         * scrolling as the platform's action does.
         */
        fun navigate(project: Project, editor: Editor, start: Int, end: Int, ordering: Comparator<Int>) {
            val caret = editor.caretModel.offset
            var next: Int? = null
            var first: Int? = null
            val markup = editor.markupModel as? MarkupModelEx ?: return
            DaemonCodeAnalyzerEx.processHighlights(markup, project, null, start, end) { info ->
                if (info.type == HighlightInfoType.ELEMENT_UNDER_CARET_READ || info.type == HighlightInfoType.ELEMENT_UNDER_CARET_WRITE) {
                    val offset = info.startOffset
                    val nextSoFar = next
                    val firstSoFar = first
                    if (ordering.compare(offset, caret) > 0 && ordering.compare(info.endOffset, caret) > 0 &&
                        (nextSoFar == null || ordering.compare(nextSoFar, offset) > 0)
                    ) {
                        next = offset
                    }
                    if (firstSoFar == null || ordering.compare(firstSoFar, offset) > 0) first = offset
                }
                true
            }
            moveCaret(editor, next ?: first ?: return)
        }

        private fun moveCaret(editor: Editor, offset: Int) {
            val caret = editor.caretModel.currentCaret
            caret.removeSelection()
            caret.moveToOffset(offset)
            val folding = editor.foldingModel
            folding.getCollapsedRegionAtOffset(offset)?.let { region -> folding.runBatchFoldingOperation { region.isExpanded = true } }
            if (caret == editor.caretModel.primaryCaret) editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
    }
}

/** Next Highlighted Usage. */
class NextElementUnderCaretUsageAction : ElementUnderCaretUsageAction(naturalOrder())

/** Previous Highlighted Usage. */
class PrevElementUnderCaretUsageAction : ElementUnderCaretUsageAction(reverseOrder())
