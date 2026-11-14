package io.github.saigyo.aslan.check

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeHighlighting.HighlightDisplayLevel.Companion.ERROR
import com.intellij.codeHighlighting.HighlightDisplayLevel.Companion.WARNING
import com.intellij.codeHighlighting.HighlightDisplayLevel.Companion.WEAK_WARNING

/**
 * Every problem the checks report, with its default level. A problem is an error only when AWS rejects the definition:
 * the definition validation of Step Functions answers with an error, or an AWS source documents the rejection.
 * Everything that deploys is a warning at most. `AwsVerdictsTest` holds the checks to the recorded answers of AWS.
 */
enum class AslProblem(val level: HighlightDisplayLevel) {
    // errors: AWS rejects the definition
    ROOT_NOT_MAPPING(ERROR), MISSING_REQUIRED_FIELD(ERROR), EMPTY_CHOICES(ERROR), UNKNOWN_STATE_TYPE(ERROR),
    MISSING_TRANSITION(ERROR), WRONG_QUERY_LANGUAGE_FIELD(ERROR), JSONPATH_STATE_IN_JSONATA_MACHINE(ERROR),
    WRONG_VALUE_KIND(ERROR), UNCLOSED_JSONATA_DELIMITER(ERROR),
    MISSING_TRANSITION_TARGET(ERROR), NO_TERMINAL_STATE(ERROR), DUPLICATE_STATE_NAME(ERROR), STATE_NAME_TOO_LONG(ERROR),
    DUPLICATE_MAP_LABEL(ERROR), INVALID_RESOURCE_ARN(ERROR), INVALID_ROLE_ARN(ERROR), UNREFERENCED_STATE(ERROR),
    UNKNOWN_FIELD(ERROR), MISPLACED_FIELD(ERROR), VALUE_OUT_OF_RANGE(ERROR), UNKNOWN_ENUM_VALUE(ERROR),
    EXCLUSIVE_FIELDS(ERROR), JSONATA_IN_STATIC_FIELD(ERROR), STATES_ALL_PLACEMENT(ERROR),
    EMPTY_LIST(ERROR), MALFORMED_CHOICE_RULE(ERROR), INVALID_PATH(ERROR),
    INVALID_TIMESTAMP(ERROR), INVALID_MAP_LABEL(ERROR), INCOMPLETE_CONFIGURATION(ERROR), INAPPLICABLE_FIELD(ERROR),
    NULL_VALUE(ERROR), MISPLACED_JSONATA_DELIMITER(ERROR),

    // warnings: AWS accepts the definition, or the plugin's own list may be behind AWS (unknown service integration)
    HEARTBEAT_NOT_BELOW_TIMEOUT(WARNING), UNREACHABLE_STATE(WARNING), UNKNOWN_PREDEFINED_ERROR(WARNING),
    UNKNOWN_SERVICE_INTEGRATION(WARNING), END_FALSE_WITHOUT_NEXT(WARNING), EMPTY_CONFIGURATION(WARNING),
    CHOICE_WITHOUT_DEFAULT(WEAK_WARNING);

    /** The inspection's short name: "Asl" and the name in upper camel case, for example `AslUnknownStateType`. */
    val shortName: String = "Asl" + name.split('_').joinToString("") { it.lowercase().replaceFirstChar(Char::uppercaseChar) }
}
