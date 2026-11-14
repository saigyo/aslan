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

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}
