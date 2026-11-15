package io.github.saigyo.aslan.diagram

import io.github.saigyo.aslan.spec.QueryLanguage
import io.github.saigyo.aslan.spec.data.comparisonOperatorNames
import io.github.saigyo.aslan.tree.AslArray
import io.github.saigyo.aslan.tree.AslObject
import io.github.saigyo.aslan.tree.AslScalar
import io.github.saigyo.aslan.tree.Placeholders
import io.github.saigyo.aslan.tree.ScalarType

/**
 * The short texts the diagram shows for a Task resource, a Choice rule and a Catcher. They run while the user types, so
 * none of them throws on a missing field, a value of the wrong kind or an empty mapping. Labels are not cut here: the
 * layouter cuts them to the limit of its layout.
 */
object DiagramLabels {
    /** [text] is the whole label; [title] a longer form for hover, or null. */
    data class Label(val text: String, val title: String?)

    private const val ARN_PARTS = 6
    private const val TITLE_MAX = 200
    private const val ELLIPSIS = "…"
    private const val ACTIVITY = "activity:"
    private const val FUNCTION = "function:"
    private const val SDK = "aws-sdk"

    private val displayNames = mapOf(
        "s3" to "S3", "dynamodb" to "DynamoDB", "lambda" to "Lambda", "sqs" to "SQS", "sns" to "SNS", "ecs" to "ECS",
        "eks" to "EKS", "events" to "EventBridge", "eventbridge" to "EventBridge", "states" to "Step Functions",
        "sfn" to "Step Functions", "glue" to "Glue", "batch" to "Batch", "athena" to "Athena", "bedrock" to "Bedrock",
        "codebuild" to "CodeBuild", "apigateway" to "API Gateway", "sagemaker" to "SageMaker", "emr" to "EMR",
        "mediaconvert" to "MediaConvert", "http" to "HTTP", "ssm" to "Systems Manager", "secretsmanager" to "Secrets Manager",
        "kms" to "KMS", "ses" to "SES", "sesv2" to "SES", "kinesis" to "Kinesis", "firehose" to "Firehose",
        "textract" to "Textract", "comprehend" to "Comprehend", "rekognition" to "Rekognition", "translate" to "Translate",
    )

    private val signs = mapOf(
        "Equals" to "=", "LessThan" to "<", "GreaterThan" to ">", "LessThanEquals" to "<=", "GreaterThanEquals" to ">=",
        "Matches" to "matches",
    )
    private val typeTests = mapOf(
        "IsNull" to "null", "IsPresent" to "present", "IsNumeric" to "numeric", "IsString" to "string",
        "IsBoolean" to "boolean", "IsTimestamp" to "timestamp",
    )
    private val operatorPrefix = Regex("""^(String|Numeric|Timestamp|Boolean)""")
    private val whiteSpace = Regex("""\s+""")

    /** The label of a Task's `Resource`: the service and action of an integration, an activity or a function, else the text as written. */
    fun resource(text: String): String {
        val parts = text.split(':', limit = ARN_PARTS)
        if (parts.size != ARN_PARTS || parts[0] != "arn") return text
        val rest = parts[5]
        return when (parts[2]) {
            "states" -> if (rest.startsWith(ACTIVITY)) named("Activity", rest.removePrefix(ACTIVITY)) else integration(rest)
            "lambda" -> if (rest.startsWith(FUNCTION)) named("Lambda", rest.removePrefix(FUNCTION).substringBefore(':')) else null
            else -> null
        } ?: text
    }

    private fun named(kind: String, name: String): String? = if (name.isEmpty()) null else "$kind $name"

    /** `service:action` with an optional pattern suffix, or `aws-sdk:service:action`; null when it is neither or not settled. */
    private fun integration(rest: String): String? {
        val segments = rest.split(':')
        val offset = if (segments[0] == SDK) 1 else 0
        val service = segments.getOrNull(offset)?.takeIf { it.isNotEmpty() } ?: return null
        val action = segments.getOrNull(offset + 1)?.substringBefore('.')?.takeIf { it.isNotEmpty() } ?: return null
        if (Placeholders.contains(service) || Placeholders.contains(action)) return null
        return "${displayNames[service.lowercase()] ?: service} $action"
    }

    /** The label of the Choice rule [rule], the [index]th of its list. */
    fun rule(rule: AslObject, index: Int, language: QueryLanguage): Label {
        val said = if (language == QueryLanguage.JSONATA) jsonataRule(rule) else jsonPathRule(rule)
        if (said != null) return said
        // an empty mapping such as `{}` has nothing to show on hover
        val written = if (rule.entries.isEmpty()) "" else collapse(rule.psi.text)
        return Label("Rule ${index + 1}", written.takeIf { it.isNotEmpty() }?.let { shorten(it, TITLE_MAX) })
    }

    private fun jsonataRule(rule: AslObject): Label? {
        val condition = (rule["Condition"]?.value as? AslScalar)?.takeIf { it.type == ScalarType.STRING } ?: return null
        val text = condition.text.trim()
        if (text.length < 4 || !text.startsWith("{%") || !text.endsWith("%}")) return null
        return collapse(text.substring(2, text.length - 2)).takeIf { it.isNotEmpty() }?.let { Label(it, null) }
    }

    private fun jsonPathRule(rule: AslObject): Label? {
        val variable = (rule["Variable"]?.value as? AslScalar)?.takeIf { it.type == ScalarType.STRING && !it.dynamic } ?: return null
        if (Placeholders.contains(variable.text)) return null
        val operator = rule.entries.singleOrNull { it.key in comparisonOperatorNames } ?: return null
        val value = operator.value as? AslScalar ?: return null
        if (value.dynamic) return null
        val name = operator.key
        val condition = typeTests[name]?.let { test ->
            if (value.type != ScalarType.BOOLEAN) return null
            val on = value.text.equals("true", ignoreCase = true)
            if (!on && !value.text.equals("false", ignoreCase = true)) return null
            if (on) "is $test" else "is not $test"
        } ?: run {
            val sign = signs[name.removeSuffix("Path").replace(operatorPrefix, "")] ?: return null
            val quoted = value.type == ScalarType.STRING && !name.endsWith("Path")
            "$sign ${if (quoted) "\"${value.text}\"" else value.text}"
        }
        val shown = variable.text.removePrefix("$.")
        return Label("$shown $condition", "${variable.text} $condition")
    }

    /** The label of a Catcher: "Catch", and the names in its `ErrorEquals` for hover. */
    fun catcher(catcher: AslObject): Label {
        val names = (catcher["ErrorEquals"]?.value as? AslArray)?.items.orEmpty()
            .mapNotNull { (it as? AslScalar)?.takeIf { s -> s.type == ScalarType.STRING }?.text }
        return Label("Catch", names.takeIf { it.isNotEmpty() }?.joinToString(", "))
    }

    /** [text] cut to [max] code points, the last one an ellipsis when something was cut. */
    fun shorten(text: String, max: Int): String {
        if (text.codePointCount(0, text.length) <= max) return text
        if (max <= 0) return ""
        return text.substring(0, text.offsetByCodePoints(0, max - 1)) + ELLIPSIS
    }

    private fun collapse(text: String) = text.trim().replace(whiteSpace, " ")
}
