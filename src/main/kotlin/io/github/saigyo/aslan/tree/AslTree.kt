package io.github.saigyo.aslan.tree

import com.intellij.json.psi.JsonFile
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLFile

/** Builds the format-independent value tree of a file. */
object AslTree {
    /** The root value of [file], or null for an empty file or an unsupported language. */
    fun root(file: PsiFile): AslNode? = when (file) {
        is YAMLFile -> YamlTree.root(file)
        is JsonFile -> JsonTree.root(file)
        else -> null
    }
}
