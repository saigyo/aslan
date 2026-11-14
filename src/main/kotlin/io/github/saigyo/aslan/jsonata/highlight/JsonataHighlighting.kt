package io.github.saigyo.aslan.jsonata.highlight

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/** The colours of JSONata expressions. The placeholder colour is [io.github.saigyo.aslan.highlight.AslHighlighting.PLACEHOLDER]. */
object JsonataHighlighting {
    private fun key(name: String, fallback: TextAttributesKey): TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("ASL_JSONATA_$name", fallback)

    val KEYWORD = key("KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val NUMBER = key("NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val STRING = key("STRING", DefaultLanguageHighlighterColors.STRING)
    val REGEX = key("REGEX", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE)
    val COMMENT = key("COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
    val OPERATOR = key("OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    val PARENTHESES = key("PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)
    val BRACKETS = key("BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
    val BRACES = key("BRACES", DefaultLanguageHighlighterColors.BRACES)
    val SEPARATOR = key("SEPARATOR", DefaultLanguageHighlighterColors.COMMA)
    val VARIABLE = key("VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    val FIELD = key("FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val FUNCTION = key("FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    val STATES = key("STATES", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)
}
