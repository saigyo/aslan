package io.github.saigyo.aslan.highlight

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import io.github.saigyo.aslan.files.AslFiles

/**
 * Next and Previous Highlighted Usage from inside an expression of an ASL file. The platform's actions work on the
 * injected editor there and look for highlights only within the injected file's range, while the usages of a state
 * machine variable are highlighted in the host, all over the definition. These actions step through the highlights of
 * the host editor in the whole host file instead. They share the platform actions' shortcuts (`use-shortcut-of`), are
 * enabled only with the caret in an injected fragment of an ASL file, and [VariableUsageNavigationPromoter] puts them
 * first there; everywhere else the shortcut runs the platform's actions.
 */
abstract class VariableUsageNavigationAction(private val ordering: Comparator<Int>) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = hostEditorInExpression(e.project, e.getData(CommonDataKeys.EDITOR)) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = hostEditorInExpression(project, e.getData(CommonDataKeys.EDITOR)) ?: return
        navigate(project, editor, ordering)
    }

    companion object {
        /** The host editor of [editor] when its caret stands in an injected fragment of an ASL file; else null. */
        fun hostEditorInExpression(project: Project?, editor: Editor?): Editor? {
            if (project == null || editor == null) return null
            val host = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
            val file = PsiDocumentManager.getInstance(project).getCachedPsiFile(host.document) ?: return null
            if (!AslFiles.isAslFile(file)) return null
            val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, host.caretModel.offset)
            return host.takeIf { injected != null }
        }

        /**
         * Moves the caret of the host [editor] to the next highlighted usage of the element under the caret in
         * [ordering] (natural for Next, reversed for Previous), or round to the first one, unfolding and scrolling as
         * the platform's actions do.
         */
        fun navigate(project: Project, editor: Editor, ordering: Comparator<Int>) {
            val caret = editor.caretModel.offset
            var next: Int? = null
            var first: Int? = null
            val markup = editor.markupModel as? MarkupModelEx ?: return
            DaemonCodeAnalyzerEx.processHighlights(markup, project, null, 0, editor.document.textLength) { info ->
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

/** Next Highlighted Usage from inside an expression. */
class NextVariableUsageAction : VariableUsageNavigationAction(naturalOrder())

/** Previous Highlighted Usage from inside an expression. */
class PrevVariableUsageAction : VariableUsageNavigationAction(reverseOrder())

/**
 * Puts the plugin's navigation actions before the platform's, which share their shortcut, when the caret stands in an
 * injected fragment of an ASL file, the one place they are enabled. Elsewhere it promotes nothing.
 */
class VariableUsageNavigationPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val ours = actions.filterIsInstance<VariableUsageNavigationAction>()
        if (ours.isEmpty()) return emptyList()
        val project = context.getData(CommonDataKeys.PROJECT)
        val editor = context.getData(CommonDataKeys.EDITOR)
        val active = runReadActionBlocking { VariableUsageNavigationAction.hostEditorInExpression(project, editor) != null }
        return if (active) ours else emptyList()
    }
}
