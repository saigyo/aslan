package io.github.saigyo.aslan.jsonata.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.jsonata.psi.JsonataFile

/**
 * Typing `$` in a JSONata expression opens completion on its own: the functions, `$states` and the variables all start
 * with it. Inside a string or a comment of the expression completion offers nothing, so no popup appears there.
 */
class JsonataAutoPopupHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != '$' || !inExpression(project, editor, file)) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }

    /** Whether the caret is in a JSONata expression: [file] is one, or it is an ASL file with one at the caret. */
    private fun inExpression(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file is JsonataFile) return true
        if (!AslFiles.isAslFile(file)) return false
        val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, editor.caretModel.offset)
        return injected?.containingFile is JsonataFile
    }
}
