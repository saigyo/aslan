package io.github.saigyo.aslan.tree

import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLAlias
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLScalarList
import org.jetbrains.yaml.psi.YAMLScalarText
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue
import org.jetbrains.yaml.psi.impl.YAMLPlainTextImpl

/** Builds the value tree from YAML PSI: the top-level value of the first document. */
internal object YamlTree {
    private const val MERGE_KEY = "<<"

    fun root(file: YAMLFile): AslNode? =
        file.documents.firstOrNull()?.topLevelValue?.let { Builder().node(it, it) }

    /**
     * Holds the values on the current build path. An alias or merge key that leads back to one of them
     * resolves into itself and yields [AslUnresolved] instead of recursing.
     */
    private class Builder {
        private val inProgress = HashSet<YAMLValue>()

        fun node(value: YAMLValue, usePsi: PsiElement): AslNode {
            if (value is YAMLAlias) return alias(value)
            if (!inProgress.add(value)) return AslUnresolved(usePsi, usePsi)
            try {
                return when (value) {
                    is YAMLMapping -> AslObject(value, usePsi, entries(value))
                    is YAMLSequence -> AslArray(value, usePsi, value.items.map { item ->
                        item.value?.let { node(it, it) } ?: AslScalar(item, item, ScalarType.NULL, "")
                    })
                    is YAMLScalar -> scalar(value, usePsi)
                    else -> AslScalar(value, usePsi, ScalarType.STRING, value.text)
                }
            } finally {
                inProgress.remove(value)
            }
        }

        private fun alias(alias: YAMLAlias): AslNode {
            val target = target(alias) ?: return AslUnresolved(alias, alias)
            return node(target, alias)
        }

        private fun target(alias: YAMLAlias): YAMLValue? =
            alias.reference?.resolve()?.markedValue

        private fun entries(mapping: YAMLMapping): List<AslEntry> {
            val keyValues = mapping.keyValues.toList()
            val explicitKeys = keyValues.map { it.keyText }.filter { it != MERGE_KEY }.toSet()
            val result = ArrayList<AslEntry>()
            val mergedKeys = HashSet<String>()
            for (keyValue in keyValues) {
                val merged = if (keyValue.keyText == MERGE_KEY) merge(keyValue) else null
                if (merged == null) {
                    result += entry(keyValue)
                    continue
                }
                for (e in merged) {
                    if (e.key !in explicitKeys && mergedKeys.add(e.key)) result += e
                }
            }
            return result
        }

        private fun entry(keyValue: YAMLKeyValue): AslEntry {
            val keyPsi = keyValue.key ?: keyValue
            val value = keyValue.value?.let { node(it, it) } ?: AslScalar(keyPsi, keyPsi, ScalarType.NULL, "")
            return AslEntry(keyValue.keyText, keyPsi, keyValue, value, viaMerge = false)
        }

        /**
         * The entries a merge key contributes, marked [AslEntry.viaMerge], or null when its value is not an
         * alias to a mapping, or a list of such aliases, that resolves without leading back into itself.
         * A merge key that returns null stays an ordinary entry.
         */
        private fun merge(keyValue: YAMLKeyValue): List<AslEntry>? {
            val aliases = when (val value = keyValue.value) {
                is YAMLAlias -> listOf(value)
                is YAMLSequence -> value.items.map { it.value as? YAMLAlias ?: return null }
                else -> return null
            }
            val targets = aliases.map { alias ->
                (target(alias) as? YAMLMapping)?.takeIf { it !in inProgress } ?: return null
            }
            return targets.flatMap { target ->
                inProgress.add(target)
                try {
                    entries(target).map { AslEntry(it.key, it.keyPsi, it.entryPsi, it.value, viaMerge = true) }
                } finally {
                    inProgress.remove(target)
                }
            }
        }

        private fun scalar(scalar: YAMLScalar, usePsi: PsiElement): AslScalar {
            val text = scalar.textValue
            return when (scalar) {
                is YAMLPlainTextImpl ->
                    AslScalar(scalar, usePsi, YamlScalars.type(text), text, YamlScalars.isAmbiguous(text))
                is YAMLScalarText, is YAMLScalarList ->
                    AslScalar(scalar, usePsi, ScalarType.STRING, blockText(scalar, text))
                is YAMLQuotedText -> AslScalar(scalar, usePsi, ScalarType.STRING, text)
                else -> AslScalar(scalar, usePsi, ScalarType.STRING, text)
            }
        }

        /**
         * A clipped block scalar (no `-` or `+` in its header) that ends at the end of the file without a
         * final line break has no trailing line break, but `getTextValue()` appends one.
         */
        private fun blockText(scalar: YAMLScalar, text: String): String {
            val header = scalar.text.substringBefore('\n')
            if (header.contains('-') || header.contains('+')) return text
            if (scalar.text.endsWith('\n')) return text
            val fileText = scalar.containingFile.text
            val rest = fileText.substring(scalar.textRange.endOffset)
            return if (rest.contains('\n')) text else text.removeSuffix("\n")
        }
    }
}

/** Types plain YAML scalars by the YAML 1.2 core schema, and tells where YAML 1.1 types them differently. */
internal object YamlScalars {
    private val nullPattern = Regex("|~|null|Null|NULL")
    private val booleanPattern = Regex("true|True|TRUE|false|False|FALSE")
    private val numberPatterns = listOf(
        Regex("[-+]?[0-9]+"),
        Regex("0o[0-7]+"),
        Regex("0x[0-9a-fA-F]+"),
        Regex("[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?"),
        Regex("[-+]?\\.(inf|Inf|INF)"),
        Regex("\\.(nan|NaN|NAN)"),
    )

    private val ambiguousPatterns = listOf(
        // YAML 1.1 booleans
        Regex("y|Y|yes|Yes|YES|n|N|no|No|NO|on|On|ON|off|Off|OFF"),
        // YAML 1.1 numbers with `_` separators
        Regex("(?=.*_)[-+]?(0b[01_]+|0x[0-9a-fA-F_]+|[0-9][0-9_]*(\\.[0-9_]*)?([eE][-+]?[0-9]+)?|\\.[0-9][0-9_]*([eE][-+]?[0-9]+)?)"),
        // YAML 1.1 sexagesimal numbers
        Regex("[-+]?[0-9][0-9_]*(:[0-5]?[0-9])+(\\.[0-9_]*)?"),
        // YAML 1.1 octal with a leading 0
        Regex("[-+]?0[0-9_]+"),
        // YAML 1.1 binary
        Regex("[-+]?0b[01_]+"),
        // YAML 1.2 octal
        Regex("0o[0-7]+"),
    )

    fun type(text: String): ScalarType = when {
        nullPattern.matches(text) -> ScalarType.NULL
        booleanPattern.matches(text) -> ScalarType.BOOLEAN
        numberPatterns.any { it.matches(text) } -> ScalarType.NUMBER
        else -> ScalarType.STRING
    }

    fun isAmbiguous(text: String): Boolean = ambiguousPatterns.any { it.matches(text) }
}
