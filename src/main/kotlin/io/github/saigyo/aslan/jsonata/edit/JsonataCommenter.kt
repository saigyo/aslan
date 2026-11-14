package io.github.saigyo.aslan.jsonata.edit

import com.intellij.lang.Commenter

/** JSONata has block comments only. */
class JsonataCommenter : Commenter {
    override fun getLineCommentPrefix(): String? = null
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}
