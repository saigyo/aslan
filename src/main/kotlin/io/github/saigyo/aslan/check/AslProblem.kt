package io.github.saigyo.aslan.check

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeHighlighting.HighlightDisplayLevel.Companion.ERROR
import com.intellij.codeHighlighting.HighlightDisplayLevel.Companion.WARNING
import com.intellij.codeHighlighting.HighlightDisplayLevel.Companion.WEAK_WARNING

/**
 * Every problem the checks report, with its default level. A problem is an error only when an AWS source documents
 * that the definition is rejected; everything else is a warning at most.
 */
enum class AslProblem(val level: HighlightDisplayLevel) {
    // errors: an AWS source documents that the definition is rejected
    ROOT_NOT_MAPPING(ERROR), MISSING_REQUIRED_FIELD(ERROR), EMPTY_CHOICES(ERROR), UNKNOWN_STATE_TYPE(ERROR),
    MISSING_TRANSITION(ERROR), WRONG_QUERY_LANGUAGE_FIELD(ERROR), JSONPATH_STATE_IN_JSONATA_MACHINE(ERROR),
    WRONG_VALUE_KIND(ERROR), UNCLOSED_JSONATA_DELIMITER(ERROR),
    MISSING_TRANSITION_TARGET(ERROR), NO_TERMINAL_STATE(ERROR), DUPLICATE_STATE_NAME(ERROR), STATE_NAME_TOO_LONG(ERROR),
    // warnings: deploys, or rejection not confirmed
    UNKNOWN_FIELD(WARNING), MISPLACED_FIELD(WARNING), VALUE_OUT_OF_RANGE(WARNING), UNKNOWN_ENUM_VALUE(WARNING),
    EXCLUSIVE_FIELDS(WARNING), JSONATA_IN_STATIC_FIELD(WARNING), STATES_ALL_PLACEMENT(WARNING),
    HEARTBEAT_NOT_BELOW_TIMEOUT(WARNING), UNREACHABLE_STATE(WARNING),
    CHOICE_WITHOUT_DEFAULT(WEAK_WARNING);

    /** The inspection's short name: "Asl" and the name in upper camel case, for example `AslUnknownStateType`. */
    val shortName: String = "Asl" + name.split('_').joinToString("") { it.lowercase().replaceFirstChar(Char::uppercaseChar) }
}
