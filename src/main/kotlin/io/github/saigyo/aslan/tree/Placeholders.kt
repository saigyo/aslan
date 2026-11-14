package io.github.saigyo.aslan.tree

/** Finds `${name}` placeholders, which deployment tools replace before the definition reaches AWS. */
object Placeholders {
    private val pattern = Regex("""\$\{[\p{L}0-9_\-.:,]+}""")

    /** The range of each `${...}` occurrence in [text], including the `${` and `}`. */
    fun ranges(text: String): List<IntRange> = pattern.findAll(text).map { it.range }.toList()

    fun contains(text: String): Boolean = pattern.containsMatchIn(text)
}
