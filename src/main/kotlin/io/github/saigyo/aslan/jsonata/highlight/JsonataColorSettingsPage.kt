package io.github.saigyo.aslan.jsonata.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import io.github.saigyo.aslan.highlight.AslHighlighting
import javax.swing.Icon

/** The colour settings page for JSONata expressions. */
class JsonataColorSettingsPage : ColorSettingsPage {
    private val descriptors = arrayOf(
        AttributesDescriptor("Keyword", JsonataHighlighting.KEYWORD),
        AttributesDescriptor("Number", JsonataHighlighting.NUMBER),
        AttributesDescriptor("String", JsonataHighlighting.STRING),
        AttributesDescriptor("Regular expression", JsonataHighlighting.REGEX),
        AttributesDescriptor("Comment", JsonataHighlighting.COMMENT),
        AttributesDescriptor("Operator", JsonataHighlighting.OPERATOR),
        AttributesDescriptor("Parentheses", JsonataHighlighting.PARENTHESES),
        AttributesDescriptor("Brackets", JsonataHighlighting.BRACKETS),
        AttributesDescriptor("Braces", JsonataHighlighting.BRACES),
        AttributesDescriptor("Separator", JsonataHighlighting.SEPARATOR),
        AttributesDescriptor("Variable", JsonataHighlighting.VARIABLE),
        AttributesDescriptor("Field name", JsonataHighlighting.FIELD),
        AttributesDescriptor("Function", JsonataHighlighting.FUNCTION),
        AttributesDescriptor("\$states", JsonataHighlighting.STATES),
    )

    override fun getDisplayName(): String = "Amazon States Language JSONata"

    override fun getIcon(): Icon? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getHighlighter(): SyntaxHighlighter = JsonataSyntaxHighlighter()

    override fun getDemoText(): String = """
        /* the reels of a shelf, newest first */
        (
          ${'$'}shelf := <states>${'$'}states</states>.input.shelf;
          <function>${'$'}count</function>(${'$'}shelf.reels[year > 1950 and noir = true]) & ' in <placeholder>${'$'}{Bucket}</placeholder>';
          <function>${'$'}map</function>(${'$'}shelf.reels, <keyword>function</keyword>(${'$'}reel){ {"title": ${'$'}reel.title, "length": ${'$'}reel.minutes * 2.5} });
          <function>${'$'}match</function>(title, /^noir/i)
        )
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "function" to JsonataHighlighting.FUNCTION,
        "states" to JsonataHighlighting.STATES,
        "keyword" to JsonataHighlighting.KEYWORD,
        "placeholder" to AslHighlighting.PLACEHOLDER,
    )
}
