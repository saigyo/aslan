package io.github.saigyo.aslan.jsonata.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import io.github.saigyo.aslan.jsonata.JsonataFileType
import io.github.saigyo.aslan.jsonata.JsonataLanguage

class JsonataFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, JsonataLanguage) {
    override fun getFileType(): FileType = JsonataFileType
}
