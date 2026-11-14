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
            val explicitKeys = keyValues.map { keyText(it) }.filter { it != MERGE_KEY }.toSet()
            val result = ArrayList<AslEntry>()
            val mergedKeys = HashSet<String>()
            for (keyValue in keyValues) {
                val merged = if (keyText(keyValue) == MERGE_KEY) merge(keyValue) else null
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

        /**
         * The decoded value of a one-line quoted scalar, read from its own text: the platform's `textValue` has been
         * seen to keep `''` undecoded after an incremental reparse. Multi-line scalars keep the platform's folding.
         */
        private fun quotedText(raw: String, platform: String): String = when {
            raw.contains('\n') || raw.length < 2 -> platform
            raw.startsWith("'") && raw.endsWith("'") -> raw.substring(1, raw.length - 1).replace("''", "'")
            raw.startsWith("\"") && raw.endsWith("\"") -> unescapeDoubleQuoted(raw.substring(1, raw.length - 1))
            else -> platform
        }

        /** The decoded text of a key: quotes removed and, for quoted keys, escapes resolved. */
        private fun keyText(keyValue: YAMLKeyValue): String {
            val raw = keyValue.key?.text?.trim() ?: return keyValue.keyText
            if (raw.length >= 2 && raw.startsWith("'") && raw.endsWith("'")) return raw.substring(1, raw.length - 1).replace("''", "'")
            if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) return unescapeDoubleQuoted(raw.substring(1, raw.length - 1))
            return keyValue.keyText
        }

        private fun unescapeDoubleQuoted(body: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i++]
                if (c != '\\' || i >= body.length) { sb.append(c); continue }
                val e = body[i++]
                val digits = when (e) { 'x' -> 2; 'u' -> 4; 'U' -> 8; else -> 0 }
                if (digits > 0) {
                    val code = body.substring(i, minOf(i + digits, body.length)).toIntOrNull(16)
                    if (code != null && i + digits <= body.length && Character.isValidCodePoint(code)) {
                        sb.appendCodePoint(code); i += digits
                    } else {
                        sb.append('\\').append(e)
                    }
                    continue
                }
                when (e) {
                    '0' -> sb.append('\u0000'); 'a' -> sb.append('\u0007'); 'b' -> sb.append('\b'); 't', '\t' -> sb.append('\t')
                    'n' -> sb.append('\n'); 'v' -> sb.append('\u000B'); 'f' -> sb.append('\u000C'); 'r' -> sb.append('\r')
                    'e' -> sb.append('\u001B'); ' ' -> sb.append(' '); '"' -> sb.append('"'); '/' -> sb.append('/')
                    '\\' -> sb.append('\\'); 'N' -> sb.append('\u0085'); '_' -> sb.append('\u00A0')
                    'L' -> sb.append('\u2028'); 'P' -> sb.append('\u2029')
                    else -> sb.append('\\').append(e)
                }
            }
            return sb.toString()
        }

        private fun entry(keyValue: YAMLKeyValue): AslEntry {
            val keyPsi = keyValue.key ?: keyValue
            val value = keyValue.value?.let { node(it, it) } ?: AslScalar(keyPsi, keyPsi, ScalarType.NULL, "")
            return AslEntry(keyText(keyValue), keyPsi, keyValue, value, viaMerge = false)
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
                is YAMLPlainTextImpl -> AslScalar(
                    scalar, usePsi, YamlScalars.type(text), text,
                    ambiguous = scalar.tag != null || YamlScalars.isAmbiguous(text),
                )
                is YAMLScalarText, is YAMLScalarList ->
                    AslScalar(scalar, usePsi, ScalarType.STRING, blockText(scalar, text))
                is YAMLQuotedText -> AslScalar(scalar, usePsi, ScalarType.STRING, quotedText(scalar.text, text))
                else -> AslScalar(scalar, usePsi, ScalarType.STRING, text)
            }
        }

        /**
         * A clipped block scalar (no `-` or `+` in its indicator) that ends at the end of the file without a
         * final line break has no trailing line break, but `getTextValue()` appends one.
         */
        private fun blockText(scalar: YAMLScalar, text: String): String {
            // The scalar's element also holds any tag, anchor and header comment; the indicator is its own
            // token, the first child starting with `>` or `|`.
            val indicator = generateSequence(scalar.node.firstChildNode) { it.treeNext }
                .map { it.text }
                .firstOrNull { it.startsWith('>') || it.startsWith('|') }
                ?: return text
            if (indicator.contains('-') || indicator.contains('+')) return text
            if (scalar.text.endsWith('\n')) return text
            val rest = scalar.containingFile.text.substring(scalar.textRange.endOffset)
            return if (rest.contains('\n')) text else text.removeSuffix("\n")
        }
    }
}

/**
 * Types plain YAML scalars by the YAML 1.2 core schema, and tells where YAML 1.1 types them differently.
 * YAML 1.1 is checked both as its type repository defines it and as PyYAML's resolver applies it.
 */
internal object YamlScalars {
    private val nullPattern = Regex("|~|null|Null|NULL")
    private val booleanPattern = Regex("true|True|TRUE|false|False|FALSE")
    private val numberPattern = Regex(
        "[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+" +
            "|[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?" +
            "|[-+]?\\.(inf|Inf|INF)|\\.(nan|NaN|NAN)",
    )

    private enum class Yaml11Kind { NULL, BOOLEAN, NUMBER, TIMESTAMP, STRING }

    private val yaml11Booleans = Regex("y|Y|yes|Yes|YES|n|N|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF")
    private val pyYamlBooleans = Regex("yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF")
    private val yaml11Int = "[-+]?0b[0-1_]+|[-+]?0[0-7_]+|[-+]?(0|[1-9][0-9_]*)|[-+]?0x[0-9a-fA-F_]+|[-+]?[1-9][0-9_]*(:[0-5]?[0-9])+"
    private val yaml11Infinity = "[-+]?\\.(inf|Inf|INF)|\\.(nan|NaN|NAN)"
    private val yaml11Numbers = Regex(
        "$yaml11Int|[-+]?([0-9][0-9_]*)?\\.[0-9.]*([eE][-+][0-9]+)?|[-+]?[0-9][0-9_]*(:[0-5]?[0-9])+\\.[0-9_]*|$yaml11Infinity",
    )
    private val pyYamlNumbers = Regex(
        "$yaml11Int|[-+]?[0-9][0-9_]*\\.[0-9_]*([eE][-+][0-9]+)?|\\.[0-9][0-9_]*([eE][-+][0-9]+)?" +
            "|[-+]?[0-9][0-9_]*(:[0-5]?[0-9])+\\.[0-9_]*|$yaml11Infinity",
    )
    private val yaml11Timestamp = Regex(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}" +
            "|[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}([Tt]|[ \\t]+)[0-9]{1,2}:[0-9]{2}:[0-9]{2}(\\.[0-9]*)?" +
            "([ \\t]*(Z|[-+][0-9]{1,2}(:[0-9]{2})?))?",
    )

    /** Leading-zero octal: an integer under both versions, but with a different value. */
    private val leadingZeroOctal = Regex("[-+]?0[0-7]+")

    fun type(text: String): ScalarType = when {
        nullPattern.matches(text) -> ScalarType.NULL
        booleanPattern.matches(text) -> ScalarType.BOOLEAN
        numberPattern.matches(text) -> ScalarType.NUMBER
        else -> ScalarType.STRING
    }

    /** True when YAML 1.1 types or reads [text] differently from YAML 1.2. */
    fun isAmbiguous(text: String): Boolean {
        val yaml12 = when (type(text)) {
            ScalarType.NULL -> Yaml11Kind.NULL
            ScalarType.BOOLEAN -> Yaml11Kind.BOOLEAN
            ScalarType.NUMBER -> Yaml11Kind.NUMBER
            ScalarType.STRING -> Yaml11Kind.STRING
        }
        return yaml11Kind(text, yaml11Booleans, yaml11Numbers) != yaml12 ||
            yaml11Kind(text, pyYamlBooleans, pyYamlNumbers) != yaml12 ||
            leadingZeroOctal.matches(text)
    }

    private fun yaml11Kind(text: String, booleans: Regex, numbers: Regex): Yaml11Kind = when {
        nullPattern.matches(text) -> Yaml11Kind.NULL
        booleans.matches(text) -> Yaml11Kind.BOOLEAN
        numbers.matches(text) -> Yaml11Kind.NUMBER
        yaml11Timestamp.matches(text) -> Yaml11Kind.TIMESTAMP
        else -> Yaml11Kind.STRING
    }
}
