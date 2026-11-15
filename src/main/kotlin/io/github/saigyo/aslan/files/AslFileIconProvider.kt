package io.github.saigyo.aslan.files

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.saigyo.aslan.AslIcons
import javax.swing.Icon

/** Gives ASL files the plugin's icon; the files keep their YAML or JSON file type. */
class AslFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (AslFiles.isAslFile(file)) AslIcons.File else null
}
