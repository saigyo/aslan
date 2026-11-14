package io.github.saigyo.aslan.tree

import com.intellij.psi.PsiElement

/** A value in an ASL definition, independent of whether it is written in YAML or JSON. */
sealed interface AslNode {
    /** Where the content is written; for an alias, the anchored value. */
    val psi: PsiElement

    /** Where the value is used; the alias element for an alias, else the same as [psi]. */
    val usePsi: PsiElement
}

/**
 * A YAML mapping or JSON object. Entries contributed by a YAML merge key are marked [AslEntry.viaMerge]. A merge key that could not be
 * applied (its value is not an alias, or list of aliases, to a mapping, or it leads back into itself) stays an
 * ordinary entry with key `<<`, so an object can contain such an entry.
 */
class AslObject(override val psi: PsiElement, override val usePsi: PsiElement, val entries: List<AslEntry>) : AslNode {
    /** The first entry with [key], or null. */
    operator fun get(key: String): AslEntry? = entries.firstOrNull { it.key == key }

    /** The element to report a problem about the whole object on: the first key, or the object itself when empty. */
    val anchorPsi: PsiElement get() = entries.firstOrNull()?.keyPsi ?: psi
}

/**
 * One key and its value. [entryPsi] is the `YAMLKeyValue` or `JsonProperty`, [keyPsi] its key element.
 * [viaMerge] is true for an entry contributed by a YAML merge key (`<<`). A merge key that could not be applied
 * is itself an entry with key `<<` and `viaMerge = false`.
 */
class AslEntry(val key: String, val keyPsi: PsiElement, val entryPsi: PsiElement, val value: AslNode, val viaMerge: Boolean)

class AslArray(override val psi: PsiElement, override val usePsi: PsiElement, val items: List<AslNode>) : AslNode

/**
 * A scalar value. [text] is the decoded value. [ambiguous] is true for a plain YAML scalar that YAML 1.1
 * and YAML 1.2 type differently, and for any tagged YAML scalar, so no check may judge its kind.
 */
class AslScalar(
    override val psi: PsiElement,
    override val usePsi: PsiElement,
    val type: ScalarType,
    val text: String,
    val ambiguous: Boolean = false,
) : AslNode

/** An alias that does not resolve, or resolves into itself. */
class AslUnresolved(override val psi: PsiElement, override val usePsi: PsiElement) : AslNode

enum class ScalarType { STRING, NUMBER, BOOLEAN, NULL }
