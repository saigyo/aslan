package io.github.saigyo.aslan.links

import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.FakePsiElement
import com.intellij.util.ProcessingContext
import io.github.saigyo.aslan.files.AslFiles
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * The places in an ASL file whose text may hold paths to code: its YAML comments and the values of `Comment` fields of
 * states, machines, Choice rules, retriers and catchers, told from a `Comment` in payload data by a field of their own
 * beside it.
 */
object CodeLinkSites {
    private const val COMMENT = "Comment"
    private val owners = setOf("Type", "States", "StartAt", "Next", "ErrorEquals")

    /** The ranges within [element] to search for paths, with their text; empty for anything else. Read action. */
    fun texts(element: PsiElement): List<Pair<TextRange, String>> {
        if (!isSite(element)) return emptyList()
        val file = element.containingFile ?: return emptyList()
        if (!AslFiles.isAslFile(file)) return emptyList()
        return listOf(TextRange(0, element.textLength) to element.text)
    }

    private fun isSite(element: PsiElement): Boolean = when (element) {
        is PsiComment -> element.language.isKindOf(YAMLLanguage.INSTANCE)
        is YAMLScalar -> (element.parent as? YAMLKeyValue)?.let { entry ->
            entry.keyText == COMMENT && entry.value === element && owners.any { entry.parentMapping?.getKeyValueByKey(it) != null }
        } == true
        is JsonStringLiteral -> (element.parent as? JsonProperty)?.let { property ->
            property.name == COMMENT && property.value === element && owners.any { (property.parent as? JsonObject)?.findProperty(it) != null }
        } == true
        else -> false
    }

    /** The ASL file [element] lies in, as a file on disk; null for one held in memory only. */
    fun aslFile(element: PsiElement): VirtualFile? = element.containingFile?.originalFile?.virtualFile
}

/** Puts a [CodeFileReference] on every path in a [CodeLinkSites] text, and a [CodeTypeReference] on its type. */
class CodeLinkReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        for (type in listOf(PsiComment::class.java, YAMLScalar::class.java, JsonStringLiteral::class.java)) {
            registrar.registerReferenceProvider(PlatformPatterns.psiElement(type), Provider)
        }
    }

    private object Provider : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
            val sites = CodeLinkSites.texts(element)
            if (sites.isEmpty()) return PsiReference.EMPTY_ARRAY
            val asl = CodeLinkSites.aslFile(element) ?: return PsiReference.EMPTY_ARRAY
            return sites.flatMap { (range, text) ->
                CodePaths.find(text).flatMap { path ->
                    val at = range.startOffset
                    val file = CodeFileReference(element, TextRange(at + path.start, at + path.start + path.path.length), asl, path.path)
                    val type = path.type?.let { CodeTypeReference(element, TextRange(at + path.typeStart, at + path.end), asl, path.path, it) }
                    listOfNotNull(file, type)
                }
            }.toTypedArray()
        }
    }
}

/** A path in a comment: resolves to the file it names, never changes the comment. */
class CodeFileReference(element: PsiElement, range: TextRange, private val asl: VirtualFile, private val path: String) :
    PsiReferenceBase<PsiElement>(element, range, true) {
    override fun resolve(): PsiFile? =
        CodeLinks.resolve(element.project, asl, path)?.let { PsiManager.getInstance(element.project).findFile(it) }

    override fun handleElementRename(newElementName: String): PsiElement = element
    override fun bindToElement(element: PsiElement): PsiElement = this.element
}

/** The `#Type` after a path: resolves to its declaration, or to the place of the word in a file without one. */
class CodeTypeReference(
    element: PsiElement,
    range: TextRange,
    private val asl: VirtualFile,
    private val path: String,
    private val type: String,
) : PsiReferenceBase<PsiElement>(element, range, true) {
    override fun resolve(): PsiElement? {
        val file = CodeLinks.resolve(element.project, asl, path)?.let { PsiManager.getInstance(element.project).findFile(it) } ?: return null
        return when (val target = CodeLinks.findType(file, type)) {
            is CodeLinks.TypeTarget.Declared -> target.element
            is CodeLinks.TypeTarget.Text -> TextTarget(file, target.offset, type)
            // the name does not occur: the jump goes to the start of the file, as from the details panel
            null -> TextTarget(file, 0, type)
        }
    }

    override fun handleElementRename(newElementName: String): PsiElement = element
    override fun bindToElement(element: PsiElement): PsiElement = this.element
}

/** A word in a file the IDE finds no declaration in; navigating to it opens the file there. */
private class TextTarget(private val file: PsiFile, private val offset: Int, private val word: String) : FakePsiElement() {
    override fun getParent(): PsiElement = file
    override fun getContainingFile(): PsiFile = file
    override fun getName(): String = word
    override fun getTextOffset(): Int = offset
    override fun getTextRange(): TextRange = TextRange(offset, offset + word.length)
    override fun canNavigate(): Boolean = file.virtualFile != null
    override fun navigate(requestFocus: Boolean) {
        file.virtualFile?.let { OpenFileDescriptor(file.project, it, offset).navigate(requestFocus) }
    }

    override fun equals(other: Any?): Boolean = other is TextTarget && other.file == file && other.offset == offset
    override fun hashCode(): Int = file.hashCode() * 31 + offset
}
