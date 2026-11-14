package io.github.saigyo.aslan.jsonata.refs

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.openapi.project.Project

/**
 * A new name for a local variable, without `$`: a letter or underscore followed by letters, digits and underscores,
 * and not `states`, which Step Functions reserves.
 */
class JsonataNamesValidator : NamesValidator {
    override fun isKeyword(name: String, project: Project?): Boolean = name == RESERVED

    override fun isIdentifier(name: String, project: Project?): Boolean = name != RESERVED && IDENTIFIER.matches(name)

    private companion object {
        const val RESERVED = "states"
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
