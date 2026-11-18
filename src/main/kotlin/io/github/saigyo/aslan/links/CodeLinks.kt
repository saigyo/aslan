package io.github.saigyo.aslan.links

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Computable
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiBinaryFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil

/** Resolves a path from a comment of an ASL file to a file, and finds a type in it; see [CodePaths] for what a path is. */
object CodeLinks {
    /** Where a type was found in its file: at a declaration of the name, or only at a word in the text. */
    sealed interface TypeTarget {
        val offset: Int

        class Declared(val element: PsiElement, override val offset: Int) : TypeTarget
        class Text(override val offset: Int) : TypeTarget
    }

    /**
     * The folders a path of [aslFile] is looked up in, in turn: the ASL file's folder (left out for an [absolute] path,
     * one that starts with `/`), the content roots that hold it, innermost first, the root of its Git repository and
     * the project's base folder. Called in a read action.
     */
    fun bases(project: Project, aslFile: VirtualFile, absolute: Boolean): List<VirtualFile> {
        val roots = ProjectRootManager.getInstance(project).contentRoots
            .filter { VfsUtilCore.isAncestor(it, aslFile, true) }
            .sortedByDescending { it.path.length }
        return buildList {
            if (!absolute) aslFile.parent?.let(::add)
            addAll(roots)
            repositoryRoot(aslFile)?.let(::add)
            project.guessProjectDir()?.let(::add)
        }.distinct()
    }

    /**
     * The nearest folder above [file] that holds a `.git` folder or file (a worktree has a file), found in the file
     * system alone, since the IDE's version control service is not part of every IDE the plugin runs in.
     */
    private fun repositoryRoot(file: VirtualFile): VirtualFile? =
        generateSequence(file.parent) { it.parent }.firstOrNull { it.findChild(".git") != null }

    /** The file [path] names for [aslFile], from the first of its [bases] that holds it; null for none or a folder. Read action. */
    fun resolve(project: Project, aslFile: VirtualFile, path: String): VirtualFile? {
        val absolute = path.startsWith("/")
        val relative = path.trimStart('/')
        if (relative.isEmpty()) return null
        return bases(project, aslFile, absolute).firstNotNullOfOrNull { base ->
            base.findFileByRelativePath(relative)?.takeIf { !it.isDirectory }
        }
    }

    /**
     * Where [name] is declared in [file]: the first named element in document order with that name, preferring one with
     * a name identifier that refers nowhere else (an import names a declaration elsewhere); else the first whole-word
     * occurrence in the text. Null when the name does not occur, and for a binary file. Read action.
     */
    fun findType(file: PsiFile, name: String): TypeTarget? {
        if (file is PsiBinaryFile || file.fileType.isBinary) return null
        declarations(file)[name]?.let { return it }
        val word = Regex("(?<![A-Za-z0-9_$])" + Regex.escape(name) + "(?![A-Za-z0-9_$])")
        return word.find(file.viewProvider.contents)?.let { TypeTarget.Text(it.range.first) }
    }

    /**
     * Every name declared in [file] with its declaration as [findType] picks it, read once and kept until the file
     * changes, so many links to one file walk its tree once. Read action.
     */
    internal fun declarations(file: PsiFile): Map<String, TypeTarget.Declared> =
        CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(readDeclarations(file), file) }

    private fun readDeclarations(file: PsiFile): Map<String, TypeTarget.Declared> {
        val preferred = HashMap<String, TypeTarget.Declared>()
        val any = HashMap<String, TypeTarget.Declared>()
        PsiTreeUtil.processElements(file) { element ->
            ProgressManager.checkCanceled()
            val name = (element as? PsiNamedElement)?.takeIf { it !is PsiFile }?.name
            if (name != null) {
                val owner = element as? PsiNameIdentifierOwner
                val target = TypeTarget.Declared(element, owner?.nameIdentifier?.textOffset ?: element.textOffset)
                any.putIfAbsent(name, target)
                if (owner != null && element.references.isEmpty()) preferred.putIfAbsent(name, target)
            }
            true
        }
        return any + preferred
    }

    /**
     * Opens the file [path] names for [aslFile] in an editor tab of its own, at [type] when it is found, else at its start.
     * False, and nothing opened, when the path no longer resolves. UI thread.
     */
    fun open(project: Project, aslFile: VirtualFile, path: String, type: String?): Boolean {
        val (file, offset) = ApplicationManager.getApplication().runReadAction(Computable<Pair<VirtualFile, Int>?> {
            val file = resolve(project, aslFile, path) ?: return@Computable null
            val offset = type?.let { t -> PsiManager.getInstance(project).findFile(file)?.let { findType(it, t)?.offset } } ?: 0
            file to offset
        }) ?: return false
        OpenFileDescriptor(project, file, offset).navigate(true)
        return true
    }
}
