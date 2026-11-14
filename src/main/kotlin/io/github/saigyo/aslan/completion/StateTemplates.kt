package io.github.saigyo.aslan.completion

import io.github.saigyo.aslan.spec.QueryLanguage

/** The fields written for a new YAML state when its type is chosen. */
object StateTemplates {
    /** Marks where the caret goes in a line of [yamlBody]. */
    const val CARET = "<caret>"

    /**
     * The lines inserted after `Type: <type>`, relative to the indentation of `Type`. At most one line contains
     * [CARET]. Empty for types that need no further field.
     */
    fun yamlBody(type: String, queryLanguage: QueryLanguage): List<String> = when (type) {
        "Task" -> listOf("Resource: $CARET", "Next: ")
        "Pass" -> listOf("Next: $CARET")
        "Wait" -> listOf("Seconds: $CARET", "Next: ")
        "Choice" -> when (queryLanguage) {
            QueryLanguage.JSONATA -> listOf("Choices:", "  - Condition: '{% $CARET %}'", "    Next: ")
            QueryLanguage.JSONPATH -> listOf("Choices:", "  - Variable: $CARET", "    Next: ")
        }
        "Parallel" -> listOf("Branches:", "  - StartAt: $CARET", "    States:", "Next: ")
        "Map" -> listOf("ItemProcessor:", "  StartAt: $CARET", "  States:", "Next: ")
        else -> emptyList()
    }
}
