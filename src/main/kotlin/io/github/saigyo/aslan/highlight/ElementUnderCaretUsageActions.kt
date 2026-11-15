package io.github.saigyo.aslan.highlight

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.actions.GotoNextElementUnderCaretUsageAction
import com.intellij.codeInsight.daemon.impl.actions.GotoPrevElementUnderCaretUsageAction
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import io.github.saigyo.aslan.files.AslFiles

/**
 * Next and Previous Highlighted Usage, for a caret inside an expression of an ASL file. The platform's action works on
 * the injected editor there and looks for the highlighted usages only within the injected file's range, while the
 * usages of a state machine variable are highlighted in the host, all over the definition. With the caret in an
 * expression of an ASL file, the action therefore steps through the highlights of the host editor; everywhere else it
 * is the platform's action.
 */
abstract class ElementUnderCaretUsageAction(private val platform: AnAction, private val ordering: Comparator<Int>) : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = platform.actionUpdateThread

    override fun isDumbAware(): Boolean = platform.isDumbAware

    override fun update(e: AnActionEvent) = platform.update(e)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)?.let { InjectedLanguageEditorUtil.getTopLevelEditor(it) }
        if (project == null || editor == null || !inAslExpression(project, editor)) return platform.actionPerformed(e)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        navigate(project, editor, ordering)
    }

    private fun inAslExpression(project: Project, editor: Editor): Boolean {
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return false
        if (!AslFiles.isAslFile(file)) return false
        return InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, editor.caretModel.offset) != null
    }

    companion object {
        /**
         * Moves the caret of the host [editor] to the next highlighted usage of the element under the caret in
         * [ordering] (natural for Next, reversed for Previous), or round to the first one, unfolding and scrolling as
         * the platform's action does.
         */
        fun navigate(project: Project, editor: Editor, ordering: Comparator<Int>) {
            val caret = editor.caretModel.offset
            var next: Int? = null
            var first: Int? = null
            val markup = editor.markupModel as? MarkupModelEx ?: return
            DaemonCodeAnalyzerEx.processHighlights(markup, project, null, 0, editor.document.textLength) { info ->
                if (info.type == HighlightInfoType.ELEMENT_UNDER_CARET_READ || info.type == HighlightInfoType.ELEMENT_UNDER_CARET_WRITE) {
                    val start = info.startOffset
                    val nextSoFar = next
                    val firstSoFar = first
                    if (ordering.compare(start, caret) > 0 && ordering.compare(info.endOffset, caret) > 0 &&
                        (nextSoFar == null || ordering.compare(nextSoFar, start) > 0)
                    ) {
                        next = start
                    }
                    if (firstSoFar == null || ordering.compare(firstSoFar, start) > 0) first = start
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
class NextElementUnderCaretUsageAction :
    ElementUnderCaretUsageAction(GotoNextElementUnderCaretUsageAction(), naturalOrder())

/** Previous Highlighted Usage. */
class PrevElementUnderCaretUsageAction :
    ElementUnderCaretUsageAction(GotoPrevElementUnderCaretUsageAction(), reverseOrder())
