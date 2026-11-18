package io.github.saigyo.aslan.links

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.github.saigyo.aslan.files.AslFiles

/** Marks a path in a comment of an ASL file that names no file, and a type that its file neither declares nor holds. */
class AslCodeLinkInspection : LocalInspectionTool() {
    override fun isAvailableForFile(file: PsiFile): Boolean = AslFiles.isAslFile(file)

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            val sites = CodeLinkSites.texts(element)
            if (sites.isEmpty()) return
            val asl = CodeLinkSites.aslFile(element) ?: return
            for ((range, text) in sites) {
                for (path in CodePaths.find(text)) check(holder, element, asl, range.startOffset, path)
            }
        }
    }

    private fun check(holder: ProblemsHolder, element: PsiElement, asl: VirtualFile, at: Int, path: CodePath) {
        val project = element.project
        val file = CodeLinks.resolve(project, asl, path.path)
        if (file == null) {
            val range = TextRange(at + path.start, at + path.start + path.path.length)
            holder.registerProblem(element, "File not found: ${path.path}", ProblemHighlightType.WEAK_WARNING, range)
            return
        }
        val type = path.type ?: return
        val psi = PsiManager.getInstance(project).findFile(file)
        if (psi != null && CodeLinks.findType(psi, type) != null) return
        holder.registerProblem(element, "Type $type not found in ${file.name}", ProblemHighlightType.WEAK_WARNING, TextRange(at + path.typeStart, at + path.end))
    }
}
