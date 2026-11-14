package io.github.saigyo.aslan.jsonata.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import io.github.saigyo.aslan.highlight.AslHighlighting
import io.github.saigyo.aslan.jsonata.parser.JsonataLexer
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens as T

/** The colour of each token of a JSONata expression; the annotator adds what depends on the tree. */
class JsonataSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = JsonataLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        colours[tokenType]?.let { arrayOf(it) } ?: TextAttributesKey.EMPTY_ARRAY

    private companion object {
        val colours: Map<IElementType, TextAttributesKey> = buildMap {
            fun put(key: TextAttributesKey, vararg types: IElementType) = types.forEach { this[it] = key }
            put(JsonataHighlighting.KEYWORD, T.AND, T.OR, T.IN, T.TRUE, T.FALSE, T.NULL)
            put(JsonataHighlighting.NUMBER, T.NUMBER)
            put(JsonataHighlighting.STRING, T.STRING, T.BAD_STRING)
            put(JsonataHighlighting.REGEX, T.REGEX, T.BAD_REGEX)
            put(JsonataHighlighting.COMMENT, T.COMMENT, T.BAD_COMMENT)
            put(
                JsonataHighlighting.OPERATOR,
                T.DOT, T.DOT_DOT, T.COLON, T.ASSIGN, T.QUESTION, T.ELVIS, T.COALESCE, T.PLUS, T.MINUS, T.STAR, T.STAR_STAR,
                T.SLASH, T.PERCENT, T.PIPE, T.EQ, T.NE, T.LT, T.LE, T.GT, T.GE, T.CARET, T.CHAIN, T.AMP, T.AT, T.HASH, T.BANG, T.TILDE,
            )
            put(JsonataHighlighting.PARENTHESES, T.LPAREN, T.RPAREN)
            put(JsonataHighlighting.BRACKETS, T.LBRACKET, T.RBRACKET)
            put(JsonataHighlighting.BRACES, T.LBRACE, T.RBRACE)
            put(JsonataHighlighting.SEPARATOR, T.COMMA, T.SEMICOLON)
            put(JsonataHighlighting.VARIABLE, T.VARIABLE)
            put(JsonataHighlighting.FIELD, T.NAME, T.QUOTED_NAME, T.BAD_QUOTED_NAME)
            put(AslHighlighting.PLACEHOLDER, T.PLACEHOLDER)
        }
    }
}

class JsonataSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = JsonataSyntaxHighlighter()
}
