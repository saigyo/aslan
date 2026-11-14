package io.github.saigyo.aslan.jsonata.inject

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import io.github.saigyo.aslan.model.JsonataSites
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.impl.YAMLQuotedTextImpl
import org.jetbrains.yaml.psi.impl.YAMLScalarImpl

/**
 * Where the expression of a `{% … %}` string lies in its host, as places to inject: ranges relative to the host's
 * start, each decoded by the host's own escaper, with a prefix or suffix that stands for what YAML makes of a line
 * break of a quoted scalar.
 */
internal object JsonataPlaces {
    private val QUOTED = TokenSet.create(YAMLTokenTypes.SCALAR_STRING, YAMLTokenTypes.SCALAR_DSTRING)

    class Place(val range: TextRange, val prefix: String?, val suffix: String?)

    /** The places of the expression in [host]; empty when the expression is empty or cannot be located with certainty. */
    fun of(host: PsiElement): List<Place> {
        val text = host.text
        return when (host) {
            is JsonStringLiteral -> {
                if (text.length < 2) return emptyList()
                single(text, listOf(TextRange(1, text.length - 1)))
            }
            is YAMLQuotedTextImpl -> {
                val token = host.node.findChildByType(QUOTED) ?: return emptyList()
                val double = token.elementType == YAMLTokenTypes.SCALAR_DSTRING
                val lines = withFirstLineAfterQuote(host.contentRanges, token.startOffset - host.node.startOffset + 1) ?: return emptyList()
                val places = if (lines.size > 1) quotedLines(text, lines, double) else single(text, lines)
                // The escaper decodes by the quote style the element reports, which is wrong for a single-quoted scalar
                // with an anchor in front (it looks at the first character) and stale after an incremental reparse that
                // changed the quotes. Then only text that both styles leave as it is can be injected.
                val misread = double == host.isSingleQuote
                if (misread && places.any { place -> place.range.substring(text).any { it == '\\' || it == '\'' } }) emptyList() else places
            }
            is YAMLScalarImpl -> single(text, host.contentRanges)
            else -> emptyList()
        }
    }

    /**
     * The content ranges of a quoted scalar start at its first child that is not a tag or white space, so with an anchor
     * in front (`&name '…'`) the first one starts inside the anchor. Its start is moved to [start], just after the
     * opening quote; the escaper decodes any range inside a content range, so the places stay decodable.
     */
    private fun withFirstLineAfterQuote(ranges: List<TextRange>, start: Int): List<TextRange>? {
        val first = ranges.firstOrNull() ?: return null
        if (start < first.startOffset || start > first.endOffset) return null
        return listOf(TextRange(start, first.endOffset)) + ranges.drop(1)
    }

    private fun single(text: String, ranges: List<TextRange>): List<Place> {
        val expression = expression(text, ranges) ?: return emptyList()
        return if (expression.isEmpty) emptyList() else listOf(Place(expression, null, null))
    }

    /**
     * The range from just after the `{%` at the start of the first content range to just before the last `%}`, or null
     * when the content does not start with `{%` or has more than line breaks and indentation after the last `%}`; then
     * the value got its delimiters from an escape, and its expression is not a range of the host.
     */
    private fun expression(text: String, ranges: List<TextRange>): TextRange? {
        if (ranges.isEmpty()) return null
        val start = ranges.first().startOffset
        val end = ranges.last().endOffset
        if (start < 0 || end > text.length || start > end) return null
        if (!text.startsWith(JsonataSites.OPEN, start)) return null
        val close = text.lastIndexOf(JsonataSites.CLOSE, end - JsonataSites.CLOSE.length)
        val from = start + JsonataSites.OPEN.length
        if (close < from) return null
        if (!text.substring(close + JsonataSites.CLOSE.length, end).isBlank()) return null
        return TextRange(from, close)
    }

    /**
     * A quoted scalar over several lines: one place per line, so that each line break can be replaced by what YAML folds
     * it into. The YAML plugin's content range of a line that is not the last one runs to the end of the line, line
     * break included, and its escaper keeps that line break as it is; a place therefore ends at the last character of
     * its line that is not white space. Its suffix is the folding: a space, or one line feed per empty line that
     * follows; after an escaped line break only those line feeds, and after an escaped space or tab that character
     * first. A line that holds nothing of the expression passes its suffix on to the place before, or to the first
     * place as its prefix.
     */
    private fun quotedLines(text: String, contentRanges: List<TextRange>, double: Boolean): List<Place> {
        val expression = expression(text, contentRanges) ?: return emptyList()
        val lines = contentRanges.mapIndexed { i, range -> if (i == contentRanges.lastIndex) range else trimEnd(text, range) }
        val places = ArrayList<Place>()
        var leading = ""
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            var next = i + 1
            while (next < lines.lastIndex && lines[next].isEmpty) next++
            var piece = line.intersection(expression) ?: TextRange.EMPTY_RANGE
            var fold = when {
                i == lines.lastIndex -> ""
                next - i > 1 -> "\n".repeat(next - i - 1)
                else -> " "
            }
            if (double && i != lines.lastIndex && endsWithEscape(text, line)) {
                val escaped = text.getOrNull(line.endOffset) ?: '\n'
                // After an escaped line break only the empty lines that follow count.
                fold = if (escaped == '\n' || escaped == '\r') "\n".repeat(next - i - 1) else escaped + fold
                if (!piece.isEmpty && piece.endOffset == line.endOffset) piece = TextRange(piece.startOffset, piece.endOffset - 1)
            }
            when {
                !piece.isEmpty -> {
                    places += Place(piece, leading.ifEmpty { null }, fold.ifEmpty { null })
                    leading = ""
                }
                places.isEmpty() -> leading += fold
                else -> places.last().let { places[places.lastIndex] = Place(it.range, it.prefix, ((it.suffix ?: "") + fold).ifEmpty { null }) }
            }
            i = next
        }
        return places
    }

    private fun trimEnd(text: String, range: TextRange): TextRange {
        var end = range.endOffset
        while (end > range.startOffset && text[end - 1].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) end--
        return TextRange(range.startOffset, end)
    }

    /** Whether [line] ends with a backslash that is not itself escaped. */
    private fun endsWithEscape(text: String, line: TextRange): Boolean {
        var count = 0
        var j = line.endOffset - 1
        while (j >= line.startOffset && text[j] == '\\') { count++; j-- }
        return count % 2 == 1
    }
}
