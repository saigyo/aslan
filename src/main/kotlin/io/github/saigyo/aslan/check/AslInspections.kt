package io.github.saigyo.aslan.check

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.files.AslFiles

/**
 * Shows the findings of one [AslProblem], so that each problem can be switched off or given another severity on its own.
 * The checks run once per file in [AslChecker]; each inspection only picks out its own problem.
 */
open class AslProblemInspection(val problem: AslProblem) : LocalInspectionTool() {
    override fun getShortName(): String = problem.shortName

    override fun runForWholeFile(): Boolean = true

    override fun isAvailableForFile(file: PsiFile): Boolean = AslFiles.isAslFile(file)

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val own = AslChecker.findings(file).filter { it.problem == problem }
        if (own.isEmpty()) return null
        return own.map {
            manager.createProblemDescriptor(it.element, it.message, isOnTheFly, LocalQuickFix.EMPTY_ARRAY, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        }.toTypedArray()
    }
}

class AslRootNotMappingInspection : AslProblemInspection(AslProblem.ROOT_NOT_MAPPING)
class AslMissingRequiredFieldInspection : AslProblemInspection(AslProblem.MISSING_REQUIRED_FIELD)
class AslEmptyChoicesInspection : AslProblemInspection(AslProblem.EMPTY_CHOICES)
class AslUnknownStateTypeInspection : AslProblemInspection(AslProblem.UNKNOWN_STATE_TYPE)
class AslMissingTransitionInspection : AslProblemInspection(AslProblem.MISSING_TRANSITION)
class AslWrongQueryLanguageFieldInspection : AslProblemInspection(AslProblem.WRONG_QUERY_LANGUAGE_FIELD)
class AslJsonpathStateInJsonataMachineInspection : AslProblemInspection(AslProblem.JSONPATH_STATE_IN_JSONATA_MACHINE)
class AslWrongValueKindInspection : AslProblemInspection(AslProblem.WRONG_VALUE_KIND)
class AslUnclosedJsonataDelimiterInspection : AslProblemInspection(AslProblem.UNCLOSED_JSONATA_DELIMITER)
class AslMissingTransitionTargetInspection : AslProblemInspection(AslProblem.MISSING_TRANSITION_TARGET)
class AslNoTerminalStateInspection : AslProblemInspection(AslProblem.NO_TERMINAL_STATE)
class AslDuplicateStateNameInspection : AslProblemInspection(AslProblem.DUPLICATE_STATE_NAME)
class AslStateNameTooLongInspection : AslProblemInspection(AslProblem.STATE_NAME_TOO_LONG)
class AslUnknownFieldInspection : AslProblemInspection(AslProblem.UNKNOWN_FIELD)
class AslMisplacedFieldInspection : AslProblemInspection(AslProblem.MISPLACED_FIELD)
class AslValueOutOfRangeInspection : AslProblemInspection(AslProblem.VALUE_OUT_OF_RANGE)
class AslUnknownEnumValueInspection : AslProblemInspection(AslProblem.UNKNOWN_ENUM_VALUE)
class AslExclusiveFieldsInspection : AslProblemInspection(AslProblem.EXCLUSIVE_FIELDS)
class AslJsonataInStaticFieldInspection : AslProblemInspection(AslProblem.JSONATA_IN_STATIC_FIELD)
class AslStatesAllPlacementInspection : AslProblemInspection(AslProblem.STATES_ALL_PLACEMENT)
class AslHeartbeatNotBelowTimeoutInspection : AslProblemInspection(AslProblem.HEARTBEAT_NOT_BELOW_TIMEOUT)
class AslUnreachableStateInspection : AslProblemInspection(AslProblem.UNREACHABLE_STATE)
class AslEmptyListInspection : AslProblemInspection(AslProblem.EMPTY_LIST)
class AslUnknownPredefinedErrorInspection : AslProblemInspection(AslProblem.UNKNOWN_PREDEFINED_ERROR)
class AslMalformedChoiceRuleInspection : AslProblemInspection(AslProblem.MALFORMED_CHOICE_RULE)
class AslInvalidPathInspection : AslProblemInspection(AslProblem.INVALID_PATH)
class AslInvalidTimestampInspection : AslProblemInspection(AslProblem.INVALID_TIMESTAMP)
class AslInvalidMapLabelInspection : AslProblemInspection(AslProblem.INVALID_MAP_LABEL)
class AslIncompleteConfigurationInspection : AslProblemInspection(AslProblem.INCOMPLETE_CONFIGURATION)
class AslInapplicableFieldInspection : AslProblemInspection(AslProblem.INAPPLICABLE_FIELD)
class AslChoiceWithoutDefaultInspection : AslProblemInspection(AslProblem.CHOICE_WITHOUT_DEFAULT)

object AslInspections {
    /** One inspection per [AslProblem], in the order of the enum. */
    fun all(): List<AslProblemInspection> = listOf(
        AslRootNotMappingInspection(),
        AslMissingRequiredFieldInspection(),
        AslEmptyChoicesInspection(),
        AslUnknownStateTypeInspection(),
        AslMissingTransitionInspection(),
        AslWrongQueryLanguageFieldInspection(),
        AslJsonpathStateInJsonataMachineInspection(),
        AslWrongValueKindInspection(),
        AslUnclosedJsonataDelimiterInspection(),
        AslMissingTransitionTargetInspection(),
        AslNoTerminalStateInspection(),
        AslDuplicateStateNameInspection(),
        AslStateNameTooLongInspection(),
        AslUnknownFieldInspection(),
        AslMisplacedFieldInspection(),
        AslValueOutOfRangeInspection(),
        AslUnknownEnumValueInspection(),
        AslExclusiveFieldsInspection(),
        AslJsonataInStaticFieldInspection(),
        AslStatesAllPlacementInspection(),
        AslHeartbeatNotBelowTimeoutInspection(),
        AslUnreachableStateInspection(),
        AslEmptyListInspection(),
        AslUnknownPredefinedErrorInspection(),
        AslMalformedChoiceRuleInspection(),
        AslInvalidPathInspection(),
        AslInvalidTimestampInspection(),
        AslInvalidMapLabelInspection(),
        AslIncompleteConfigurationInspection(),
        AslInapplicableFieldInspection(),
        AslChoiceWithoutDefaultInspection(),
    )
}
