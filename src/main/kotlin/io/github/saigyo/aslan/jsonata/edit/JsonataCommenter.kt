package io.github.saigyo.aslan.jsonata.edit

import com.intellij.codeInsight.generation.CommenterDataHolder
import com.intellij.codeInsight.generation.SelfManagingCommenter
import com.intellij.lang.Commenter
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * JSONata has block comments only. A line is commented by wrapping its text, white space around it left alone, in
 * `/* … */`, and uncommented by taking the delimiters off again, so that toggling twice restores the text exactly. The
 * platform's own handling of block-only commenters in an injected fragment does not manage that when white space
 * follows the comment on the line (`{% $x + 1 %}`).
 */
class JsonataCommenter : Commenter, SelfManagingCommenter<CommenterDataHolder> {
    override fun getLineCommentPrefix(): String? = null
    override fun getBlockCommentPrefix(): String = OPEN
    override fun getBlockCommentSuffix(): String = CLOSE
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null

    override fun createLineCommentingState(startLine: Int, endLine: Int, document: Document, file: PsiFile): CommenterDataHolder =
        State

    override fun createBlockCommentingState(selectionStart: Int, selectionEnd: Int, document: Document, file: PsiFile): CommenterDataHolder =
        State

    /** The range of the text of [line] without the white space at both ends. */
    private fun content(line: Int, document: Document): TextRange {
        var start = document.getLineStartOffset(line)
        var end = document.getLineEndOffset(line)
        val text = document.charsSequence
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return TextRange(start, end)
    }

    override fun isLineCommented(line: Int, offset: Int, document: Document, data: CommenterDataHolder): Boolean {
        val range = content(line, document)
        val text = range.substring(document.text)
        return isOneComment(text)
    }

    // One comment from end to end: the first closing delimiter is the one at the end.
    private fun isOneComment(text: String): Boolean =
        text.length >= OPEN.length + CLOSE.length && text.startsWith(OPEN) && text.endsWith(CLOSE) &&
            text.indexOf(CLOSE, OPEN.length) == text.length - CLOSE.length

    override fun commentLine(line: Int, offset: Int, document: Document, data: CommenterDataHolder) {
        val range = content(line, document)
        // JSONata comments do not nest: a line that holds a comment delimiter is left as it is.
        range.substring(document.text).let { if (it.contains(OPEN) || it.contains(CLOSE)) return }
        document.insertString(range.endOffset, " $CLOSE")
        document.insertString(range.startOffset, "$OPEN ")
    }

    override fun uncommentLine(line: Int, offset: Int, document: Document, data: CommenterDataHolder) {
        val range = content(line, document)
        val text = range.substring(document.text)
        if (!isOneComment(text)) return
        var closeLength = CLOSE.length
        if (text.length >= OPEN.length + CLOSE.length + 1 && text[text.length - CLOSE.length - 1] == ' ') closeLength++
        var openLength = OPEN.length
        if (text.length >= openLength + closeLength + 1 && text[openLength] == ' ') openLength++
        document.deleteString(range.endOffset - closeLength, range.endOffset)
        document.deleteString(range.startOffset, range.startOffset + openLength)
    }

    override fun getCommentPrefix(line: Int, document: Document, data: CommenterDataHolder): String = OPEN

    override fun getBlockCommentRange(selectionStart: Int, selectionEnd: Int, document: Document, data: CommenterDataHolder): TextRange? {
        val text = document.charsSequence
        var start = selectionStart
        var end = selectionEnd
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        val inside = text.subSequence(start, end)
        return if (inside.length >= OPEN.length + CLOSE.length && inside.startsWith(OPEN) && inside.endsWith(CLOSE)) TextRange(start, end) else null
    }

    override fun getBlockCommentPrefix(selectionStart: Int, document: Document, data: CommenterDataHolder): String = OPEN

    override fun getBlockCommentSuffix(selectionEnd: Int, document: Document, data: CommenterDataHolder): String = CLOSE

    override fun uncommentBlockComment(startOffset: Int, endOffset: Int, document: Document, data: CommenterDataHolder) {
        val range = getBlockCommentRange(startOffset, endOffset, document, data) ?: return
        document.deleteString(range.endOffset - CLOSE.length, range.endOffset)
        document.deleteString(range.startOffset, range.startOffset + OPEN.length)
    }

    override fun insertBlockComment(startOffset: Int, endOffset: Int, document: Document, data: CommenterDataHolder): TextRange {
        document.insertString(endOffset, CLOSE)
        document.insertString(startOffset, OPEN)
        return TextRange(startOffset, endOffset + OPEN.length + CLOSE.length)
    }

    private companion object {
        val State = object : CommenterDataHolder() {}
        const val OPEN = "/*"
        const val CLOSE = "*/"
    }
}
