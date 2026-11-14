package io.github.saigyo.aslan.jsonata.edit

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.injected.editor.DocumentWindow
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import io.github.saigyo.aslan.jsonata.psi.JsonataFile

/**
 * A quote typed in an expression that is the host string's own quote would end the host string. It is written in its
 * escaped form into the host instead: `''` in a single-quoted YAML scalar, `\"` in a double-quoted one or a JSON string.
 * A selection is replaced.
 */
class JsonataTypedHandler : TypedHandlerDelegate() {
    override fun beforeSelectionRemoved(c: Char, project: Project, editor: Editor, file: PsiFile): Result = escape(c, project, editor, file)

    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result =
        escape(c, project, editor, file)

    private fun escape(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '\'' && c != '"') return Result.CONTINUE
        // The platform hands out the host editor when the typed character is the host's own quote.
        val hostEditor = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        val window: DocumentWindow
        val from: Int
        val to: Int
        if (file is JsonataFile) {
            window = editor.document as? DocumentWindow ?: return Result.CONTINUE
            if (hostEditor === editor) return Result.CONTINUE
            val selection = editor.selectionModel
            val start = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
            val end = if (selection.hasSelection()) selection.selectionEnd else start
            from = window.injectedToHost(start)
            to = window.injectedToHost(end)
        } else {
            if (hostEditor !== editor) return Result.CONTINUE
            val at = editor.selectionModel.let { if (it.hasSelection()) it.selectionStart else editor.caretModel.offset }
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            val host = generateSequence(file.findElementAt(at) ?: file.findElementAt(at - 1)) { it.parent }
                .takeWhile { it !is PsiFile }.firstOrNull { it is PsiLanguageInjectionHost } ?: return Result.CONTINUE
            val injected = JsonataPsi.injectedFile(host) ?: return Result.CONTINUE
            window = PsiDocumentManager.getInstance(project).getCachedDocument(injected) as? DocumentWindow ?: return Result.CONTINUE
            val selection = editor.selectionModel
            from = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
            to = if (selection.hasSelection()) selection.selectionEnd else from
        }
        if (hostQuote(window) != c || from < 0 || to < from) return Result.CONTINUE
        val escaped = if (c == '\'') "''" else "\\\""
        hostEditor.selectionModel.removeSelection()
        hostEditor.document.replaceString(from, to, escaped)
        hostEditor.caretModel.moveToOffset(from + escaped.length)
        return Result.STOP
    }
}
