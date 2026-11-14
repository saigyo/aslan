package io.github.saigyo.aslan.structure

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.util.Collections
import java.util.IdentityHashMap

/** The structure view model of an ASL file. Elements keep file order unless the user turns on sorting. */
class AslStructureViewModel(file: PsiFile, editor: Editor?) :
    StructureViewModelBase(file, editor, AslFileElement(file)), StructureViewModel.ElementInfoProvider {

    init {
        withSorters(Sorter.ALPHA_SORTER)
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = false

    /** The innermost PSI ancestor of [element] that is the value of a tree node, so "select element at caret" finds it. */
    override fun findAcceptableElement(element: PsiElement?): PsiElement? {
        val values = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun collect(node: StructureViewTreeElement) {
            node.value?.let { values += it }
            for (child in node.children) (child as? StructureViewTreeElement)?.let { collect(it) }
        }
        collect(root)
        var current = element
        while (current != null && current !is PsiFile) {
            if (current in values) return current
            current = current.parent
        }
        return null
    }
}
