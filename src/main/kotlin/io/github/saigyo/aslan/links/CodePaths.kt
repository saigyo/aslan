package io.github.saigyo.aslan.links

/**
 * A file path found in a comment's text. [start] and [end] cover the path and its `#Type` in the text that was searched,
 * [path] is the path without the type, and [typeStart] is the offset of the type's first character, or -1 without a type.
 */
data class CodePath(val start: Int, val end: Int, val path: String, val type: String?, val typeStart: Int)

/**
 * Finds the words of a comment that count as file paths: a word with at least one `/` whose last part has a file
 * extension (letters and digits after a dot), and no `://`, so a URL is never one. Backticks, quotes and brackets
 * around a word and punctuation after it are not part of it. A `#` and an identifier after the extension name a type.
 */
object CodePaths {
    private val word = Regex("""\S+""")
    private val identifier = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
    private val lastPart = Regex(""".+\.[A-Za-z0-9]+""")
    private const val OPENING = "`'\"([<"
    private const val CLOSING = "`'\")]>,.:;"

    fun find(text: String): List<CodePath> = word.findAll(text).mapNotNull { match ->
        var from = match.range.first
        var to = match.range.last + 1
        while (from < to && text[from] in OPENING) from++
        while (to > from && text[to - 1] in CLOSING) to--
        path(text.substring(from, to), from)
    }.toList()

    private fun path(word: String, at: Int): CodePath? {
        if ("://" in word) return null
        val hash = word.lastIndexOf('#')
        val type = if (hash >= 0) word.substring(hash + 1).takeIf { identifier.matches(it) } else null
        val path = if (hash >= 0) word.substring(0, hash) else word
        if ('/' !in path || !lastPart.matches(path.substringAfterLast('/'))) return null
        val end = at + path.length + (if (type != null) type.length + 1 else 0)
        return CodePath(at, end, path, type, if (type != null) at + path.length + 1 else -1)
    }
}
