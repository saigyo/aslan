package io.github.saigyo.aslan.model

import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.ScalarType
import org.jetbrains.yaml.YAMLLanguage

/**
 * The description and the notes of the states of one document, read from its comments, which are collected once.
 *
 * The description of a state is the text of its `Comment` field, then the block of `#` lines directly above its key: lines
 * that hold only a comment, start in the key's column and follow each other with no other line between. The lines of
 * the block are joined into paragraphs; a line that is only `#` starts a new one.
 *
 * Its notes are the comments after the start of its key and before the first later line that holds more than a comment
 * and starts in the key's column or further left, in a column to the right of the key, one per line; comments inside
 * the `States` of a machine nested in the state are left out, since they belong to the nested states. JSON has no
 * comments, so a state there has only its `Comment` field. Called in a read action.
 */
class StateComments private constructor(private val text: CharSequence, private val comments: Map<Int, Line>) {
    /** [description] holds paragraphs, [notes] one entry per comment line, each without `#` and one space after it. */
    data class Comments(val description: List<String>, val notes: List<String>)

    /** One comment: its column, whether only blanks precede it on its line, and its text without `#`. */
    private class Line(val column: Int, val alone: Boolean, val body: String)

    private val starts: IntArray = lineStarts(text)

    /**
     * The lines of the `States` of a machine nested in a state, and the column of its state names: a comment there in
     * that column or further right belongs to the nested states, one further left to the outer state.
     */
    private class Nested(val lines: IntRange, val column: Int)

    fun of(state: AslState): Comments {
        val field = (state.obj?.get(COMMENT)?.value as? AslScalar)
            ?.takeIf { it.type == ScalarType.STRING && !it.dynamic }?.text?.trim()?.takeIf { it.isNotEmpty() }
        if (comments.isEmpty()) return Comments(listOfNotNull(field), emptyList())
        val key = state.entry.keyPsi.textRange.startOffset
        val line = lineOf(key)
        val column = key - starts[line]
        return Comments(listOfNotNull(field) + paragraphs(block(line, column)), notes(state, line, column))
    }

    /** The bodies of the description block above the key on [line] in [column], top to bottom. */
    private fun block(line: Int, column: Int): List<String> {
        val lines = ArrayList<String>()
        var l = line - 1
        while (l >= 0) {
            val comment = comments[l] ?: break
            if (!comment.alone || comment.column != column) break
            lines += comment.body
            l--
        }
        return lines.asReversed()
    }

    private fun notes(state: AslState, line: Int, column: Int): List<String> {
        val end = end(line, column)
        val nested = state.children.mapNotNull { machine ->
            val states = machine.statesEntry ?: return@mapNotNull null
            val last = machine.states.lastOrNull() ?: return@mapNotNull null
            val lastKey = last.entry.keyPsi.textRange.startOffset
            val lastLine = lineOf(lastKey)
            val firstKey = machine.states.first().entry.keyPsi.textRange.startOffset
            val nestedColumn = firstKey - starts[lineOf(firstKey)]
            Nested(lineOf(states.keyPsi.textRange.startOffset) + 1 until end(lastLine, lastKey - starts[lastLine]), nestedColumn)
        }
        return (line until end).mapNotNull { l ->
            comments[l]?.takeIf { comment -> comment.column > column && nested.none { l in it.lines && comment.column >= it.column } }?.body
        }
    }

    /** The first line after [line] that holds more than a comment and starts in [column] or further left, or the line count. */
    private fun end(line: Int, column: Int): Int {
        for (l in line + 1 until starts.size) {
            val from = starts[l]
            val to = if (l + 1 < starts.size) starts[l + 1] else text.length
            var i = from
            while (i < to && (text[i] == ' ' || text[i] == '\t')) i++
            if (i >= to || text[i] == '\n' || text[i] == '\r') continue
            if (comments[l]?.alone == true) continue
            if (i - from <= column) return l
        }
        return starts.size
    }

    private fun lineOf(offset: Int): Int {
        val at = starts.binarySearch(offset)
        return if (at >= 0) at else -at - 2
    }

    companion object {
        private const val COMMENT = "Comment"

        fun of(doc: AslDocument): StateComments {
            val file = doc.file
            val text = file.viewProvider.contents
            val starts = lineStarts(text)
            if (!file.language.isKindOf(YAMLLanguage.INSTANCE)) return StateComments(text, emptyMap())
            val comments = HashMap<Int, Line>()
            for (comment in PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java)) {
                val offset = comment.textRange.startOffset
                var line = starts.binarySearch(offset)
                if (line < 0) line = -line - 2
                val alone = (starts[line] until offset).all { text[it] == ' ' || text[it] == '\t' }
                val body = comment.text.removePrefix("#").removePrefix(" ").trimEnd()
                comments[line] = Line(offset - starts[line], alone, body)
            }
            return StateComments(text, comments)
        }

        private fun lineStarts(text: CharSequence): IntArray {
            val starts = ArrayList<Int>()
            starts += 0
            for (i in text.indices) if (text[i] == '\n') starts += i + 1
            return starts.toIntArray()
        }

        /** Joins the bodies of a block into paragraphs; an empty body starts a new one. */
        private fun paragraphs(lines: List<String>): List<String> {
            val result = ArrayList<String>()
            val current = StringBuilder()
            for (line in lines) {
                if (line.isBlank()) {
                    if (current.isNotEmpty()) result += current.toString()
                    current.clear()
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(line.trim())
                }
            }
            if (current.isNotEmpty()) result += current.toString()
            return result
        }
    }
}
