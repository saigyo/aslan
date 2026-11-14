package io.github.saigyo.aslan.jsonata.edit

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens as T

/** Parentheses, square brackets and braces of a JSONata expression. */
class JsonataBraceMatcher : PairedBraceMatcher {
    private val pairs = arrayOf(
        BracePair(T.LPAREN, T.RPAREN, false),
        BracePair(T.LBRACKET, T.RBRACKET, false),
        BracePair(T.LBRACE, T.RBRACE, false),
    )

    override fun getPairs(): Array<BracePair> = pairs

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean =
        contextType == null || contextType in FOLLOWERS

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        /** The tokens before which a typed opening bracket is paired: white space, closing brackets and separators. */
        val FOLLOWERS = setOf(T.WHITE_SPACE, T.RPAREN, T.RBRACKET, T.RBRACE, T.COMMA, T.SEMICOLON, T.COLON)
    }
}
