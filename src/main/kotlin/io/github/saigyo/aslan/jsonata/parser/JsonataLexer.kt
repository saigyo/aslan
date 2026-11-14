package io.github.saigyo.aslan.jsonata.parser

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Replays the tokens of [JsonataScanner]. A token type depends on what precedes it (`/` is a division or the start of a
 * regular expression), so the lexer cannot restart in the middle of a text: every token but the first has a non-zero
 * state, which makes an incremental highlighter restart from the beginning.
 */
class JsonataLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var base = 0
    private var endOffset = 0
    private var tokens: List<ScanToken> = emptyList()
    private var index = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.base = startOffset
        this.endOffset = endOffset
        tokens = JsonataScanner.scan(buffer.subSequence(startOffset, endOffset)).tokens
        index = 0
    }

    override fun getState(): Int = if (index == 0) 0 else 1
    override fun getTokenType(): IElementType? = tokens.getOrNull(index)?.type
    override fun getTokenStart(): Int = tokens.getOrNull(index)?.let { base + it.start } ?: endOffset
    override fun getTokenEnd(): Int = tokens.getOrNull(index)?.let { base + it.end } ?: endOffset
    override fun advance() { if (index < tokens.size) index++ }
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset
}
