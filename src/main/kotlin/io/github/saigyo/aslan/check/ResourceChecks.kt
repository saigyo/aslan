package io.github.saigyo.aslan.check

import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.tree.AslNode
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.ScalarType

/** Rules on the text of the `Resource` of a Task state and of the role in its `Credentials`. */
internal object ResourceChecks {
    private const val ARN_PARTS = 6
    private const val INTEGRATION_PREFIX = "arn:aws:states:::"
    private const val SDK_PREFIX = "aws-sdk:"
    private const val ROLE_PREFIX = "role/"

    /** Each known optimised integration and the HTTP task, as the text after `arn:aws:states:::`. */
    private val known: Set<String> = AslSpec.taskResourceArns.map { it.name.removePrefix(INTEGRATION_PREFIX) }.toSet()
    private val sdkIntegration = Regex("""aws-sdk:[A-Za-z0-9-]+:[A-Za-z0-9]+(\.waitForTaskToken)?""")
    private val syncSuffix = Regex("""\.sync(:\d+)?$""")

    /**
     * The text of [node] when it is certainly the resource the file says: a string without a placeholder that is not
     * read as JSONata; null otherwise.
     */
    fun certainText(node: AslNode, ctx: StructureChecks.Context): String? {
        val scalar = node as? AslScalar ?: return null
        if (scalar.type != ScalarType.STRING || StructureChecks.exemptFromKind(scalar)) return null
        if (ctx.jsonataPossible && StructureChecks.isJsonata(scalar)) return null
        return scalar.text
    }

    /** Whether [text] has the form `arn:partition:service:region:account:resource`; region and account may be empty. */
    fun isArn(text: String): Boolean {
        val parts = text.split(':', limit = ARN_PARTS)
        return parts.size == ARN_PARTS && parts[0] == "arn" && parts[1].isNotEmpty() && parts[2].isNotEmpty() && parts[5].isNotEmpty()
    }

    /**
     * Whether [text] has the form `arn:partition:iam::account:role/name` of an IAM role ARN. Region and account are not
     * judged: AWS does not publish the rule it applies to them.
     */
    fun isRoleArn(text: String): Boolean {
        if (!isArn(text)) return false
        val parts = text.split(':', limit = ARN_PARTS)
        return parts[2] == "iam" && parts[5].startsWith(ROLE_PREFIX) && parts[5].length > ROLE_PREFIX.length
    }

    /**
     * What is wrong with [text] as a service integration, in any partition; null when it is a known one or no
     * integration ARN at all, such as the ARN of a Lambda function or an activity. An AWS SDK integration is judged by
     * its form only: the plugin has no list of the services and actions.
     */
    fun integrationProblem(text: String): String? {
        if (!isArn(text)) return null
        val parts = text.split(':', limit = ARN_PARTS)
        if (parts[2] != "states" || parts[3].isNotEmpty() || parts[4].isNotEmpty()) return null
        val integration = parts[5]
        return when {
            !integration.startsWith(SDK_PREFIX) -> if (integration in known) null else "'$integration' is not a service integration the plugin knows"
            sdkIntegration.matches(integration) -> null
            syncSuffix.containsMatchIn(integration) -> "An AWS SDK integration cannot use '.sync'"
            else -> "An AWS SDK integration is written aws-sdk:service:action"
        }
    }
}
