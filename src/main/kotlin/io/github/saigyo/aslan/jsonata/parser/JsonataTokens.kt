package io.github.saigyo.aslan.jsonata.parser

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.saigyo.aslan.jsonata.JsonataLanguage

class JsonataTokenType(debugName: String) : IElementType(debugName, JsonataLanguage)

/** The token types of [JsonataScanner]. Every character of an expression belongs to exactly one token. */
object JsonataTokens {
    @JvmField val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    @JvmField val COMMENT = JsonataTokenType("COMMENT")
    @JvmField val NUMBER = JsonataTokenType("NUMBER")
    @JvmField val STRING = JsonataTokenType("STRING")
    @JvmField val NAME = JsonataTokenType("NAME")
    @JvmField val QUOTED_NAME = JsonataTokenType("QUOTED_NAME")
    /** `$name`, also `$` and `$$`. */
    @JvmField val VARIABLE = JsonataTokenType("VARIABLE")
    @JvmField val REGEX = JsonataTokenType("REGEX")
    /**
     * `${Name}`, which a deployment tool replaces before the definition reaches AWS. It is recognised only at the start
     * of a token: in `prefix${X}` or `$${Var}` the placeholder is part of a name or a variable, so whether an expression
     * holds a placeholder cannot be decided from its tokens alone.
     */
    @JvmField val PLACEHOLDER = JsonataTokenType("PLACEHOLDER")
    @JvmField val TRUE = JsonataTokenType("TRUE")
    @JvmField val FALSE = JsonataTokenType("FALSE")
    @JvmField val NULL = JsonataTokenType("NULL")
    @JvmField val AND = JsonataTokenType("AND")
    @JvmField val OR = JsonataTokenType("OR")
    @JvmField val IN = JsonataTokenType("IN")
    @JvmField val DOT = JsonataTokenType("DOT")
    @JvmField val DOT_DOT = JsonataTokenType("DOT_DOT")
    @JvmField val LBRACKET = JsonataTokenType("LBRACKET")
    @JvmField val RBRACKET = JsonataTokenType("RBRACKET")
    @JvmField val LBRACE = JsonataTokenType("LBRACE")
    @JvmField val RBRACE = JsonataTokenType("RBRACE")
    @JvmField val LPAREN = JsonataTokenType("LPAREN")
    @JvmField val RPAREN = JsonataTokenType("RPAREN")
    @JvmField val COMMA = JsonataTokenType("COMMA")
    @JvmField val SEMICOLON = JsonataTokenType("SEMICOLON")
    @JvmField val COLON = JsonataTokenType("COLON")
    @JvmField val ASSIGN = JsonataTokenType("ASSIGN")
    @JvmField val QUESTION = JsonataTokenType("QUESTION")
    /** `?:` of JSONata 2.1. */
    @JvmField val ELVIS = JsonataTokenType("ELVIS")
    /** `??` of JSONata 2.1. */
    @JvmField val COALESCE = JsonataTokenType("COALESCE")
    @JvmField val PLUS = JsonataTokenType("PLUS")
    @JvmField val MINUS = JsonataTokenType("MINUS")
    @JvmField val STAR = JsonataTokenType("STAR")
    @JvmField val STAR_STAR = JsonataTokenType("STAR_STAR")
    @JvmField val SLASH = JsonataTokenType("SLASH")
    @JvmField val PERCENT = JsonataTokenType("PERCENT")
    @JvmField val PIPE = JsonataTokenType("PIPE")
    @JvmField val EQ = JsonataTokenType("EQ")
    @JvmField val NE = JsonataTokenType("NE")
    @JvmField val LT = JsonataTokenType("LT")
    @JvmField val LE = JsonataTokenType("LE")
    @JvmField val GT = JsonataTokenType("GT")
    @JvmField val GE = JsonataTokenType("GE")
    @JvmField val CARET = JsonataTokenType("CARET")
    /** `~>` */
    @JvmField val CHAIN = JsonataTokenType("CHAIN")
    @JvmField val AMP = JsonataTokenType("AMP")
    @JvmField val AT = JsonataTokenType("AT")
    @JvmField val HASH = JsonataTokenType("HASH")
    @JvmField val BANG = JsonataTokenType("BANG")
    @JvmField val TILDE = JsonataTokenType("TILDE")
    /** An unterminated string, running to the end of the text. */
    @JvmField val BAD_STRING = JsonataTokenType("BAD_STRING")
    @JvmField val BAD_QUOTED_NAME = JsonataTokenType("BAD_QUOTED_NAME")
    @JvmField val BAD_COMMENT = JsonataTokenType("BAD_COMMENT")
    @JvmField val BAD_REGEX = JsonataTokenType("BAD_REGEX")

    @JvmField val COMMENTS: TokenSet = TokenSet.create(COMMENT)
    @JvmField val STRINGS: TokenSet = TokenSet.create(STRING, BAD_STRING)
}
