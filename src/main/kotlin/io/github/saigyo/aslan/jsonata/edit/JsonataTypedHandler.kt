package io.github.saigyo.aslan.jsonata.edit

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.injected.editor.DocumentWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataPsi
import org.jetbrains.annotations.TestOnly
import java.lang.ref.WeakReference

/**
 * A quote typed in an expression that is the host string's own quote would end the host string. It is written in its
 * escaped form into the host instead: `''` in a single-quoted YAML scalar, `\"` in a double-quoted one or a JSON string.
 * A selection is replaced. Only inside the injected part of the host of an ASL file; elsewhere the platform types as usual.
 *
 * With several carets the quote is escaped only when all of them are inside an expression, see [escapeAtAllCarets].
 */
class JsonataTypedHandler : TypedHandlerDelegate() {
    override fun beforeSelectionRemoved(c: Char, project: Project, editor: Editor, file: PsiFile): Result = escape(c, project, editor, file)

    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result =
        escape(c, project, editor, file)

    private fun escape(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '\'' && c != '"') return Result.CONTINUE
        // Everything else is for ASL files only; the top-level file is known without a commit.
        val top = InjectedLanguageManager.getInstance(project).getTopLevelFile(file) ?: file
        if (!AslFiles.isAslFile(top)) return Result.CONTINUE
        val hostEditor = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        return if (hostEditor.caretModel.caretCount > 1) escapeAtAllCarets(c, project, hostEditor, top) else escapeAtOneCaret(c, project, editor, hostEditor, file)
    }

    private fun escapeAtOneCaret(c: Char, project: Project, editor: Editor, hostEditor: Editor, file: PsiFile): Result {
        val window: DocumentWindow
        val from: Int
        val to: Int
        val caret = hostEditor.caretModel.currentCaret
        if (file is JsonataFile) {
            window = editor.document as? DocumentWindow ?: return Result.CONTINUE
            if (hostEditor === editor) return Result.CONTINUE
            val selection = editor.selectionModel
            val start = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
            val end = if (selection.hasSelection()) selection.selectionEnd else start
            from = window.injectedToHost(start)
            to = window.injectedToHost(end)
        } else {
            // The platform hands out the host editor when the typed character is the host's own quote.
            if (hostEditor !== editor) return Result.CONTINUE
            PsiDocumentManager.getInstance(project).commitDocument(hostEditor.document)
            from = if (caret.hasSelection()) caret.selectionStart else caret.offset
            to = if (caret.hasSelection()) caret.selectionEnd else from
            window = windowAt(file, from, project) ?: return Result.CONTINUE
        }
        if (!isOurs(window, c, from, to)) return Result.CONTINUE
        write(c, hostEditor, from, to)
        return Result.STOP
    }

    /** Writes the escaped [c] over [from] to [to] of the host, widened to whole escapes, and puts the caret after it. */
    private fun write(c: Char, hostEditor: Editor, from: Int, to: Int) {
        val (start, end) = widened(c, hostEditor.document.charsSequence, from, to)
        val escaped = escaped(c)
        val caret = hostEditor.caretModel.currentCaret
        caret.removeSelection()
        hostEditor.document.replaceString(start, end, escaped)
        caret.moveToOffset(start + escaped.length)
    }

    /**
     * Several carets: the quote is escaped only when every caret, with its selection, lies inside the injected part of an
     * expression whose host quote it is. Otherwise nothing is done for any caret and the platform types the plain quote
     * everywhere.
     *
     * The platform calls the handler once per caret for one typed character, and after the first caret has been written
     * the document is no longer committed, and a commit would invalidate the injected editor under the loop. So the
     * answer is worked out once, on the first call of the command that types the character, and reused for the other
     * carets of the same command; it is keyed to the current command and so cannot outlive the keystroke.
     */
    private fun escapeAtAllCarets(c: Char, project: Project, hostEditor: Editor, top: PsiFile): Result {
        val command = CommandProcessor.getInstance().currentCommand
        val carets = hostEditor.caretModel.caretCount
        val remembered = decision
        val allInside: Boolean
        if (command != null && remembered != null && remembered.command.get() === command && remembered.editor.get() === hostEditor &&
            remembered.char == c && remembered.carets == carets
        ) {
            allInside = remembered.allInside
        } else {
            PsiDocumentManager.getInstance(project).commitDocument(hostEditor.document)
            allInside = hostEditor.caretModel.allCarets.all { each ->
                val from = if (each.hasSelection()) each.selectionStart else each.offset
                val to = if (each.hasSelection()) each.selectionEnd else from
                val window = windowAt(top, from, project)
                window != null && isOurs(window, c, from, to)
            }
            decision = if (command != null && carets > 1) Decision(WeakReference(command), WeakReference(hostEditor), c, carets, allInside) else null
        }
        if (!allInside) return Result.CONTINUE
        val caret = hostEditor.caretModel.currentCaret
        finished()
        val from = if (caret.hasSelection()) caret.selectionStart else caret.offset
        val to = if (caret.hasSelection()) caret.selectionEnd else from
        write(c, hostEditor, from, to)
        return Result.STOP
    }

    /** The platform has typed the character at the current caret: when it has done so at the last caret, the decision ends. */
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        finished()
        return Result.CONTINUE
    }

    private fun finished() {
        val current = decision ?: return
        if (++current.done >= current.carets) decision = null
    }

    private class Decision(
        val command: WeakReference<Any>, val editor: WeakReference<Editor>, val char: Char, val carets: Int, val allInside: Boolean,
        /** How many carets are finished: written here, or typed by the platform ([charTyped]). */
        var done: Int = 0,
    )

    @Volatile private var decision: Decision? = null

    /** Whether a decision is remembered; none is after a keystroke has been answered for all carets. */
    @TestOnly internal fun remembersDecision(): Boolean = decision != null

    private fun escaped(c: Char): String = if (c == '\'') "''" else "\\\""

    /**
     * [from] to [to] widened to whole backslash escapes of the host text [text], for the double quote: typing it between
     * the backslash of `\"` or `A` and the rest would split the escape and break the host string. A caret inside
     * an escape moves to its start; a selection grows to cover the escapes it cuts. A single-quoted YAML scalar has no
     * backslash escapes, and `''` split by a typed `''` stays two whole escapes.
     */
    private fun widened(c: Char, text: CharSequence, from: Int, to: Int): Pair<Int, Int> {
        if (c != '"') return from to to
        val start = escapeAround(text, from)?.first ?: from
        if (from == to) return start to start
        return start to (escapeAround(text, to)?.last?.plus(1) ?: to)
    }

    /** The range of the backslash escape that [offset] stands strictly inside (after its first character), or null. */
    private fun escapeAround(text: CharSequence, offset: Int): IntRange? {
        val at = (offset - 1 downTo maxOf(0, offset - 10)).firstOrNull { text[it] == '\\' } ?: return null
        var run = 0
        var i = at
        while (i >= 0 && text[i] == '\\') { run++; i-- }
        // an even run ends with the second backslash of `\\`, an escape that ends at or before the offset
        if (run % 2 == 0) return null
        val length = when (text.getOrNull(at + 1)) { 'u' -> 6; 'x' -> 4; 'U' -> 10; else -> 2 }
        return if (offset < at + length) at until at + length else null
    }

    /** The injected document of the JSONata expression whose host string holds [offset] of the host file [file], or null. */
    private fun windowAt(file: PsiFile, offset: Int, project: Project): DocumentWindow? {
        val host = generateSequence(file.findElementAt(offset) ?: file.findElementAt(offset - 1)) { it.parent }
            .takeWhile { it !is PsiFile }.firstOrNull { it is PsiLanguageInjectionHost } ?: return null
        val injected = JsonataPsi.injectedFile(host) ?: return null
        return PsiDocumentManager.getInstance(project).getCachedDocument(injected) as? DocumentWindow
    }

    /** Whether [c] is the host's own quote and [from] to [to] lies inside the injected part of the host, between `{%` and `%}`. */
    private fun isOurs(window: DocumentWindow, c: Char, from: Int, to: Int): Boolean =
        from >= 0 && to >= from && hostQuote(window) == c && window.hostRanges.any { from >= it.startOffset && to <= it.endOffset }
}
