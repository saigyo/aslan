package io.github.saigyo.aslan.jsonata.edit

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.injected.editor.DocumentWindow
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens as T

/**
 * Pairs the quotes of JSONata strings, except the quote that is also the host string's own: in `'{% … %}'` or
 * `"{% … %}"` a typed quote of the host's kind ends the host string, so no closing quote is added.
 */
class JsonataQuoteHandler : SimpleTokenSetQuoteHandler(T.STRING, T.BAD_STRING) {
    override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        if (!super.isOpeningQuote(iterator, offset)) return false
        val quote = hostQuote(iterator.document) ?: return true
        val text = iterator.document.charsSequence
        return offset !in text.indices || text[offset] != quote
    }
}

/**
 * The character that opens the host string of the injected [document]: the one right before the `{%`, or null for a
 * block or plain scalar. A quoted host holds an expression only when its value starts with `{%`, so its quote stands
 * right before it; a quote further back, in the comment of a block scalar header (`|- # "`), is not the host's.
 */
internal fun hostQuote(document: Document): Char? {
    val window = document as? DocumentWindow ?: return null
    val host = window.delegate.charsSequence
    val start = window.injectedToHost(0)
    if (start < 0 || start > host.length) return null
    val i = host.subSequence(0, minOf(host.length, start + 2)).lastIndexOf("{%") - 1
    val c = host.getOrNull(i) ?: return null
    return if (c == '\'' || c == '"') c else null
}
