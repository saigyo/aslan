package io.github.saigyo.aslan.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import org.jetbrains.yaml.YAMLLanguage
import javax.swing.Icon

/** The colour settings page for the semantic highlighting of ASL files. */
class AslColorSettingsPage : ColorSettingsPage {
    private val descriptors = arrayOf(
        AttributesDescriptor("Field name", AslHighlighting.FIELD),
        AttributesDescriptor("State name", AslHighlighting.STATE_NAME),
        AttributesDescriptor("State reference", AslHighlighting.STATE_REFERENCE),
        AttributesDescriptor("Placeholder", AslHighlighting.PLACEHOLDER),
    )

    override fun getDisplayName(): String = "Amazon States Language"

    override fun getIcon(): Icon? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getHighlighter(): SyntaxHighlighter =
        SyntaxHighlighterFactory.getSyntaxHighlighter(YAMLLanguage.INSTANCE, null, null) ?: PlainSyntaxHighlighter()

    override fun getDemoText(): String = """
        <field>Comment</field>: Take in a batch of reels
        <field>StartAt</field>: <reference>Receive</reference>
        <field>States</field>:
          <state>Receive</state>:
            <field>Type</field>: Task
            <field>Resource</field>: <placeholder>${'$'}{ReceiveFn}</placeholder>
            <field>Parameters</field>:
              Shelf: archive-<placeholder>${'$'}{Stage}</placeholder>
            <field>Next</field>: <reference>Catalogue</reference>
          <state>Catalogue</state>:
            <field>Type</field>: Pass
            <field>End</field>: true
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "field" to AslHighlighting.FIELD,
        "state" to AslHighlighting.STATE_NAME,
        "reference" to AslHighlighting.STATE_REFERENCE,
        "placeholder" to AslHighlighting.PLACEHOLDER,
    )
}
