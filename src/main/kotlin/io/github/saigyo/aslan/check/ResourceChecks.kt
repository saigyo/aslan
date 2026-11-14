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
    private const val STATES = "states"
    private const val ACTIVITY = "activity:"

    /** Each known optimised integration and the HTTP task, as the text after `arn:aws:states:::`. */
    private val known: Set<String> = AslSpec.taskResourceArns.map { it.name.removePrefix(INTEGRATION_PREFIX) }.toSet()
    private val sdkIntegration = Regex("""aws-sdk:[A-Za-z0-9-]+:[A-Za-z0-9]+(\.waitForTaskToken)?""")
    private val syncSuffix = Regex("""\.sync(:\d+)?$""")
    private val namedResource = Regex("""[^:]+:.+""")
    private val roleResource = Regex("""role/[\w+=,.@/-]*[\w+=,.@-]""")

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
     * Whether [text] has the form `arn:partition:iam::account:role/name` of an IAM role ARN, as the definition validation
     * of AWS applies it: no region, some account, and a role name or path of the characters IAM allows.
     */
    fun isRoleArn(text: String): Boolean {
        if (!isArn(text)) return false
        val parts = text.split(':', limit = ARN_PARTS)
        return parts[2] == "iam" && parts[3].isEmpty() && parts[4].isNotEmpty() && roleResource.matches(parts[5])
    }

    /** `service:action` and its pattern suffix, when [text] is a service integration written without region and account. */
    private fun integration(text: String): String? {
        if (!isArn(text)) return null
        val parts = text.split(':', limit = ARN_PARTS)
        return parts[5].takeIf { parts[2] == STATES && parts[3].isEmpty() && parts[4].isEmpty() && !it.startsWith(ACTIVITY) }
    }

    /**
     * Whether [text] is a service integration that needs `Parameters` or `Arguments`: AWS rejects each one without them,
     * also those whose call has no required parameter.
     */
    fun needsParameters(text: String): Boolean {
        val integration = integration(text) ?: return false
        return integration in known || sdkIntegration.matches(integration)
    }

    /** Whether [text] is the ARN of an activity. */
    fun isActivity(text: String): Boolean = isArn(text) && text.split(':', limit = ARN_PARTS).let { it[2] == STATES && it[5].startsWith(ACTIVITY) }

    /**
     * What makes [text] a resource that AWS rejects for a Task; null when nothing certain is wrong. A Task takes a service
     * integration, the ARN of a Lambda resource or the ARN of an activity. Whether a function or an activity exists is not
     * judged, and an integration name the plugin does not know is left to [unknownIntegration].
     */
    fun resourceProblem(text: String): String? {
        if (!isArn(text)) return "Not an ARN; a Task needs the ARN of a service integration, a Lambda function or an activity"
        val parts = text.split(':', limit = ARN_PARTS)
        val resource = parts[5]
        return when (parts[2]) {
            "lambda" -> if (namedResource.matches(resource)) null else "The ARN of a Lambda function ends with function:name"
            STATES -> when {
                resource == "activity" || resource == ACTIVITY -> "The ARN of an activity ends with activity:name"
                integration(text) == null || !resource.startsWith(SDK_PREFIX) || sdkIntegration.matches(resource) -> null
                syncSuffix.containsMatchIn(resource) -> "An AWS SDK integration cannot use '.sync'"
                else -> "An AWS SDK integration is written aws-sdk:service:action"
            }
            else -> "A Task cannot call '${parts[2]}' by an ARN; it needs a service integration, a Lambda function or an activity"
        }
    }

    /**
     * The text for a service integration, in any partition, whose name is not in the plugin's list; null for a known one,
     * for an AWS SDK integration, which is judged by its form in [resourceProblem], and for any other resource.
     */
    fun unknownIntegration(text: String): String? {
        if (!isArn(text)) return null
        val parts = text.split(':', limit = ARN_PARTS)
        val name = parts[5]
        if (parts[2] != STATES || name.startsWith(ACTIVITY) || name.startsWith(SDK_PREFIX) || name in known) return null
        return "'$name' is not a service integration the plugin knows"
    }
}
