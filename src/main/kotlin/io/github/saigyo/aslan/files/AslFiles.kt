package io.github.saigyo.aslan.files

import com.intellij.json.JsonLanguage
import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.settings.AslSettings
import org.jetbrains.yaml.YAMLLanguage

/** Decides whether a file is an ASL file. */
object AslFiles {
    private val suffixes = listOf(".asl.yaml", ".asl.yml", ".asl.json")

    fun isAslFileName(name: String): Boolean {
        if (suffixes.any { name.endsWith(it, ignoreCase = true) }) return true
        return AslSettings.getInstance().matchesExtraPattern(name)
    }

    fun isAslFile(file: PsiFile): Boolean {
        val language = file.language
        if (!language.isKindOf(YAMLLanguage.INSTANCE) && !language.isKindOf(JsonLanguage.INSTANCE)) return false
        return isAslFileName(file.originalFile.name)
    }
}
