package io.github.saigyo.aslan.model

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * The strings of a file that Step Functions may evaluate, decoded, for finding `$name`s the model does not know as
 * reads. Every string counts but the key of an entry and a `Comment` that is no JSONata expression, since Step
 * Functions evaluates neither.
 */
internal class VariableMentions(file: PsiFile) {
    private val strings: List<Pair<PsiElement, String>> =
        PsiTreeUtil.findChildrenOfAnyType(file, YAMLScalar::class.java, JsonStringLiteral::class.java).mapNotNull { string ->
            val text = decoded(string) ?: return@mapNotNull null
            if (isKey(string) || (isCommentValue(string) && !text.startsWith(JsonataSites.OPEN))) null else string to text
        }

    /**
     * The strings that hold `$name` (followed by no letter, digit or `_`) more often than [reads] has known reads of
     * [name] in them. Comparing counts suffices: each known read is such a `$name` in its string, at its own place. A
     * read in an expression with a syntax error does not count as known, since what the expression reads once it is
     * complete is not known (Review Focus 5).
     */
    fun unknown(name: String, reads: List<AslRead>): List<PsiElement> {
        val known = HashMap<PsiElement, HashSet<Any>>()
        for (read in reads) {
            if (read.name != name) continue
            when (read) {
                is JsonataRead -> if (!PsiTreeUtil.hasErrorElements(read.variable.containingFile)) {
                    known.getOrPut(read.site.scalar.psi) { HashSet() } += read.variable
                }
                is PathRead -> known.getOrPut(read.scalar.psi) { HashSet() } += read.range.startOffset
            }
        }
        return strings.filter { (string, text) -> mentions(text, name) > known[string].orEmpty().size }.map { it.first }
    }

    private companion object {
        fun decoded(string: PsiElement): String? = when (string) {
            is YAMLScalar -> string.textValue
            is JsonStringLiteral -> string.value
            else -> null
        }

        fun isKey(string: PsiElement): Boolean {
            val parent = string.parent
            return (parent is JsonProperty && parent.nameElement == string) || (parent is YAMLKeyValue && parent.key == string)
        }

        fun isCommentValue(string: PsiElement): Boolean = when (val parent = string.parent) {
            is JsonProperty -> parent.name == "Comment" && parent.value == string
            is YAMLKeyValue -> parent.keyText == "Comment" && parent.value == string
            else -> false
        }

        /** How many times [text] holds `$name` followed by no letter, digit or `_`. */
        fun mentions(text: String, name: String): Int {
            val needle = "$$name"
            var count = 0
            var at = text.indexOf(needle)
            while (at >= 0) {
                val next = text.getOrNull(at + needle.length)
                if (next == null || !(Character.isLetterOrDigit(next) || next == '_')) count++
                at = text.indexOf(needle, at + 1)
            }
            return count
        }
    }
}
