package io.github.saigyo.aslan.jsonata

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/** JSONata as AWS Step Functions evaluates it: jsonata-js 2.0.6. */
object JsonataLanguage : Language("AslJsonata")

object JsonataFileType : LanguageFileType(JsonataLanguage) {
    override fun getName(): String = "AslJsonata"
    override fun getDescription(): String = "JSONata expression in an ASL definition"
    override fun getDefaultExtension(): String = ""
    override fun getIcon(): Icon? = null
}
