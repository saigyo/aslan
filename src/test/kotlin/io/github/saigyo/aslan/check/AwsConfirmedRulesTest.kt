package io.github.saigyo.aslan.check

import io.github.saigyo.aslan.check.AslProblem.EMPTY_LIST
import io.github.saigyo.aslan.check.AslProblem.EXCLUSIVE_FIELDS
import io.github.saigyo.aslan.check.AslProblem.INAPPLICABLE_FIELD
import io.github.saigyo.aslan.check.AslProblem.INCOMPLETE_CONFIGURATION
import io.github.saigyo.aslan.check.AslProblem.INVALID_MAP_LABEL
import io.github.saigyo.aslan.check.AslProblem.INVALID_PATH
import io.github.saigyo.aslan.check.AslProblem.INVALID_RESOURCE_ARN
import io.github.saigyo.aslan.check.AslProblem.INVALID_ROLE_ARN
import io.github.saigyo.aslan.check.AslProblem.MISSING_REQUIRED_FIELD
import io.github.saigyo.aslan.check.AslProblem.MISSING_TRANSITION_TARGET
import io.github.saigyo.aslan.check.AslProblem.NULL_VALUE
import io.github.saigyo.aslan.check.AslProblem.UNKNOWN_FIELD
import io.github.saigyo.aslan.check.AslProblem.UNREACHABLE_STATE
import io.github.saigyo.aslan.check.AslProblem.VALUE_OUT_OF_RANGE
import io.github.saigyo.aslan.check.AslProblem.WRONG_VALUE_KIND

/**
 * The rules that follow what the definition validation of AWS rejects and accepts, in YAML, with the cases a file does
 * not settle: placeholders, deployment tags and merge keys. [AwsVerdictsTest] holds the recorded answers themselves.
 */
class AwsConfirmedRulesTest : CheckTestCase() {
    private val dollar = "$"
    private val function = "arn:aws:lambda:us-east-1:123456789012:function:Inspect"
    private val lambdaInvoke = "arn:aws:states:::lambda:invoke"
    private val role = "arn:aws:iam::123456789012:role/scanning-team"

    /** A machine of one Task `A` with [lines] as further fields; [top] goes before `StartAt`. */
    private fun task(vararg lines: String, resource: String = function, top: String = "") =
        "${top}StartAt: A\nStates:\n  A:\n    Type: Task\n    Resource: $resource\n" + lines.joinToString("") { "    $it\n" } + "    End: true\n"

    private fun state(vararg lines: String) = "StartAt: A\nStates:\n  A:\n" + lines.joinToString("") { "    $it\n" }

    private fun map(vararg lines: String) = state(
        "Type: Map", "ItemProcessor:", "  ProcessorConfig:", "    Mode: DISTRIBUTED", "    ExecutionType: STANDARD", "  StartAt: I", "  States:",
        "    I:", "      Type: Pass", "      End: true", *lines, "End: true",
    )

    private fun nothing(text: String) = assertEquals(text, emptyList<Any>(), found(text))

    // a service integration needs its parameters

    fun `test a service integration without Parameters`() {
        assertEquals(listOf(MISSING_REQUIRED_FIELD to "A"), found(task(resource = lambdaInvoke)))
        assertEquals(listOf(MISSING_REQUIRED_FIELD to "A"), found(task(resource = "arn:aws:states:::aws-sdk:s3:listBuckets")))
        val message = AslChecker.findings(myFixture.configureByText("test.asl.yaml", task(resource = lambdaInvoke, top = "QueryLanguage: JSONata\n"))).single().message
        assertEquals("A service integration needs 'Arguments', even when the call takes no parameters", message)
    }

    fun `test resources that need no Parameters`() {
        nothing(task())
        nothing(task(resource = "arn:aws:states:us-east-1:123456789012:activity:Review"))
        nothing(task("Parameters:", "  FunctionName: fn", resource = lambdaInvoke))
        nothing(task("Parameters: {}", resource = lambdaInvoke))
    }

    fun `test Parameters are not missed where the file does not settle it`() {
        nothing(task(resource = "$dollar{InspectIntegration}"))
        nothing(task("<<: $dollar{Common}", resource = lambdaInvoke))
        nothing(task("$dollar{PayloadField}: {}", resource = lambdaInvoke))
        nothing(task(resource = "!Sub arn:aws:states:::lambda:invoke"))
        nothing("StartAt: A\nStates:\n  A:\n    Type: Task\n    <<: &call\n      Resource: $lambdaInvoke\n      Parameters:\n        FunctionName: fn\n    Next: B\n" +
            "  B:\n    Type: Task\n    <<: *call\n    End: true\n")
    }

    // the Resource of a Task

    fun `test resources that AWS rejects`() {
        val rejected = listOf(
            "arn:aws:s3:::scans", "arn:aws:sns:us-east-1:123456789012:topic", "arn:aws:lambda:us-east-1:123456789012:function",
            "arn:aws:states:us-east-1:123456789012:activity", "arn:aws:states:::aws-sdk:s3", "arn:aws:states:::aws-sdk:s3:getObject.sync",
        )
        for (resource in rejected) assertEquals(resource, listOf(INVALID_RESOURCE_ARN to resource), found(task("Parameters: {}", resource = resource)))
        val unnamed = "'arn:aws:lambda:us-east-1:123456789012:function:'"
        assertEquals(listOf(INVALID_RESOURCE_ARN to unnamed), found(task(resource = unnamed)))
    }

    fun `test resources that AWS accepts`() {
        val accepted = listOf(
            "arn:aws:lambda:us-east-1:123456789012:function:Inspect:live", "arn:aws:lambda:::function:Inspect", "arn:foo:lambda:us-east-1:123456789012:function:Inspect",
            "arn:aws:states:::activity:Review", "arn:aws:states:us-east-1:123456789012:activity:Review",
        )
        for (resource in accepted) assertEquals(resource, emptyList<Any>(), found(task(resource = resource)))
        nothing(task("Parameters: {}", resource = "arn:aws:states:us-east-1:123456789012:lambda:invoke"))
    }

    // Credentials

    fun `test Credentials that AWS rejects`() {
        assertEquals(listOf(MISSING_REQUIRED_FIELD to "Credentials"), found(task("Credentials: {}")))
        assertEquals(listOf(UNKNOWN_FIELD to "Role"), found(task("Credentials:", "  RoleArn: $role", "  Role: x")))
        assertEquals(listOf(EXCLUSIVE_FIELDS to "RoleArn.$dollar"), found(task("Credentials:", "  RoleArn: $role", "  RoleArn.$dollar: $dollar.role")))
        assertEquals(listOf(WRONG_VALUE_KIND to "[a]"), found(task("Credentials: [a]")))
        assertEquals(listOf(NULL_VALUE to "null"), found(task("Credentials: null")))
        assertEquals(listOf(WRONG_VALUE_KIND to "12"), found(task("Credentials:", "  RoleArn: 12")))
        assertEquals(listOf(INVALID_PATH to "role"), found(task("Credentials:", "  RoleArn.$dollar: role")))
        assertEquals(listOf(INAPPLICABLE_FIELD to "Credentials"),
            found(task("Credentials:", "  RoleArn: $role", resource = "arn:aws:states:us-east-1:123456789012:activity:Review")))
    }

    fun `test role ARNs that AWS rejects`() {
        val rejected = listOf(
            "arn:aws:iam:us-east-1:123456789012:role/x", "arn:aws:iam:::role/x", "arn:aws:iam::123456789012:role/a*b", "arn:aws:iam::123456789012:role/a/",
            "arn:aws:iam::123456789012:role/rôle",
        )
        for (arn in rejected) assertEquals(arn, listOf(INVALID_ROLE_ARN to arn), found(task("Credentials:", "  RoleArn: $arn")))
        assertEquals(listOf(INVALID_ROLE_ARN to "'arn:aws:iam::123456789012:role/a b'"), found(task("Credentials:", "  RoleArn: 'arn:aws:iam::123456789012:role/a b'")))
    }

    fun `test Credentials that AWS accepts or the file does not settle`() {
        val bodies = listOf(
            "RoleArn: $role", "RoleArn: arn:aws:iam::123:role/p//a+b=c,d.e@f-g_h", "RoleArn: arn:foo:iam::abc:role/x", "RoleArn.$dollar: $dollar.role",
            "RoleArn.$dollar: States.Format('arn:aws:iam::{}:role/x', $dollar.account)", "RoleArn: $dollar{RoleArn}", "RoleArn: !GetAtt Role.Arn",
            "$dollar{Key}: x", "<<: $dollar{Common}", "RoleArn.$dollar: $dollar{RolePath}",
        )
        for (body in bodies) assertEquals(body, emptyList<Any>(), found(task("Credentials:", "  $body")))
        nothing(task("Credentials: $dollar{Credentials}"))
        nothing(task("Credentials: !Ref Credentials"))
    }

    // null

    fun `test null where AWS rejects it in free-form fields`() {
        assertEquals(listOf(NULL_VALUE to "null"), found(task("Parameters: null", resource = lambdaInvoke)))
        assertEquals(listOf(NULL_VALUE to "~"), found(task("Assign: ~")))
        assertEquals(listOf(NULL_VALUE to "null"), found(task("Catch:", "  - ErrorEquals: [States.ALL]", "    Assign: null", "    Next: A")))
    }

    fun `test null where AWS accepts it`() {
        nothing(task("ResultSelector: null"))
        nothing(state("Type: Pass", "Parameters: null", "Result: null", "End: true"))
        nothing(state("Type: Fail", "Error: null"))
        nothing(map("ItemSelector: null"))
    }

    fun `test null parameters of an ItemReader and a ResultWriter`() {
        assertEquals(listOf(NULL_VALUE to "null"), found(map("ItemReader:", "  Resource: arn:aws:states:::s3:listObjectsV2", "  Parameters: null")))
        assertEquals(listOf(NULL_VALUE to "null"), found(map("ResultWriter:", "  Resource: arn:aws:states:::s3:putObject", "  Parameters: null")))
    }

    fun `test a null CSVDelimiter and a JSONata string as ItemsPointer are accepted by AWS`() {
        val get = arrayOf("ItemReader:", "  Resource: arn:aws:states:::s3:getObject", "  Arguments:", "    Bucket: b", "    Key: k", "  ReaderConfig:")
        val jsonata = "QueryLanguage: JSONata\n"
        nothing(jsonata + map(*get, "    InputType: CSV", "    CSVHeaderLocation: FIRST_ROW", "    CSVDelimiter: null"))
        nothing(jsonata + map(*get, "    InputType: JSON", "    ItemsPointer: '{% 1 %}'"))
    }

    fun `test a JSONata string as QueryLanguage`() =
        assertEquals(listOf(AslProblem.UNKNOWN_ENUM_VALUE to "'{% 1 %}'"), found("QueryLanguage: '{% 1 %}'\n" + state("Type: Succeed")))

    fun `test null Cause on a Fail state`() = assertEquals(listOf(NULL_VALUE to "null"), found(state("Type: Fail", "Cause: null")))

    // whole numbers and their bounds

    fun `test a whole number written with a decimal point`() {
        assertEquals(listOf(WRONG_VALUE_KIND to "1.5"), found(task("Retry:", "  - ErrorEquals: [States.Timeout]", "    MaxAttempts: 1.5")))
        assertEquals(listOf(WRONG_VALUE_KIND to "5.50"), found(task("TimeoutSeconds: 5.50")))
        nothing(task("Retry:", "  - ErrorEquals: [States.Timeout]", "    BackoffRate: 1.5"))
        nothing(task("TimeoutSeconds: $dollar{Timeout}"))
    }

    fun `test a whole number with point zero is only certain to fail in JSON`() {
        // A tool that converts YAML to JSON may send 5.0 as 5, which AWS accepts, so YAML is not judged.
        nothing(task("TimeoutSeconds: 5.0"))
        nothing(task("TimeoutSeconds: 5.00", "HeartbeatSeconds: 3."))
        val json = """{"StartAt": "A", "States": {"A": {"Type": "Task", "Resource": "$function", "TimeoutSeconds": 5.0, "End": true}}}"""
        assertEquals(listOf(WRONG_VALUE_KIND to "5.0"), found(json, "test.asl.json"))
    }

    fun `test a fraction written with an exponent`() {
        // In YAML an exponent without a decimal point is a number for some tools and a string for others, so it is not judged.
        nothing(task("TimeoutSeconds: 15e-1"))
        assertEquals(listOf(WRONG_VALUE_KIND to "1.5e+0"), found(task("TimeoutSeconds: 1.5e+0")))
        val json = """{"StartAt": "A", "States": {"A": {"Type": "Task", "Resource": "$function", "TimeoutSeconds": 15e-1, "End": true}}}"""
        assertEquals(listOf(WRONG_VALUE_KIND to "15e-1"), found(json, "test.asl.json"))
        // AWS takes no exponent at all for a whole number, even when the value is whole.
        assertEquals(listOf(WRONG_VALUE_KIND to "1e2"), found(json.replace("15e-1", "1e2"), "test.asl.json"))
        nothing(task("TimeoutSeconds: 1.5e+1"))
    }

    // the value of a key that ends in .$

    fun `test a mapping or a list as the value of an evaluated key`() {
        assertEquals(listOf(WRONG_VALUE_KIND to "{}"), found(task("ResultSelector:", "  value.$dollar: {}")))
        assertEquals(listOf(WRONG_VALUE_KIND to "[a, b]"), found(task("ResultSelector:", "  nested:", "    value.$dollar: [a, b]")))
        assertEquals(listOf(WRONG_VALUE_KIND to "{}"), found(task("Credentials:", "  RoleArn.$dollar: {}")))
    }

    fun `test a tagged list is not judged`() {
        nothing(task("ResultSelector:", "  value.$dollar: !Join ['.', [$dollar, id]]"))
        nothing(task("Credentials:", "  RoleArn.$dollar: !Join ['.', [$dollar, role]]"))
        nothing(task("Comment: !Join ['', [a, b]]", "TimeoutSeconds: !If [Slow, 60, 30]", "Credentials: !If [Prod, {RoleArn: $role}, {RoleArn: $role}]"))
        nothing(task(resource = "!Join [':', [arn, aws, lambda]]"))
    }

    fun `test the bounds that AWS applies`() {
        nothing(task("Retry:", "  - ErrorEquals: [States.Timeout]", "    IntervalSeconds: 0"))
        nothing(task("TimeoutSeconds: 99999999", "HeartbeatSeconds: 5"))
        nothing(task(top = "TimeoutSeconds: 0\n"))
        nothing(map("ItemBatcher:", "  MaxItemsPerBatch: 0", "  MaxInputBytesPerBatch: 0"))
        assertEquals(listOf(VALUE_OUT_OF_RANGE to "100000000"), found(task("TimeoutSeconds: 100000000")))
        assertEquals(listOf(VALUE_OUT_OF_RANGE to "100000000"), found(task("TimeoutSeconds: 99999999", "HeartbeatSeconds: 100000000")).filter { it.first == VALUE_OUT_OF_RANGE })
        assertEquals(listOf(VALUE_OUT_OF_RANGE to "-1"), found(task(top = "TimeoutSeconds: -1\n")))
        assertEquals(listOf(VALUE_OUT_OF_RANGE to "-1"), found(task("Retry:", "  - ErrorEquals: [States.Timeout]", "    IntervalSeconds: -1")))
    }

    // keys that end in .$

    fun `test a key that ends in dot dollar needs a path or an intrinsic function`() {
        assertEquals(listOf(INVALID_PATH to "reel"), found(task("Parameters:", "  FunctionName: fn", "  Payload:", "    reel.$dollar: reel", resource = lambdaInvoke)))
        assertEquals(listOf(WRONG_VALUE_KIND to "7"), found(task("ResultSelector:", "  count.$dollar: 7")))
        assertEquals(listOf(INVALID_PATH to "reel"), found(map("ItemSelector:", "  list:", "    - reel.$dollar: reel")))
    }

    fun `test keys that end in dot dollar and are fine or not evaluated`() {
        nothing(task("ResultSelector:", "  a.$dollar: $dollar.a", "  b.$dollar: $dollar$dollar.Execution.Id", "  c.$dollar: States.Format('{}', $dollar.a)", "  d.$dollar: $dollar{Path}",
            "  e.$dollar: !Sub x", "  f: plain"))
        nothing(state("Type: Pass", "Result:", "  a.$dollar: plain", "End: true"))
    }

    // timestamps

    fun `test timestamps that AWS accepts`() {
        val accepted = listOf(
            "2026-03-14T01:59:00Z", "2026-03-14t01:59:00z", "2026-03-14T01:59Z", "2026-03-14T01:59:00.123Z", "2026-03-14T01:59:00+01:00",
            "2026-03-14T01:59:00-08:00", "2028-02-29T00:00:00Z",
        )
        for (t in accepted) assertEquals(t, emptyList<Any>(), found(state("Type: Wait", "Timestamp: '$t'", "End: true")))
    }

    fun `test timestamps that AWS rejects`() {
        val rejected = listOf(
            "2026-13-40T25:61:61Z", "2026-02-30T00:00:00Z", "2026-02-29T00:00:00Z", "2026-03-14T24:00:00Z", "2026-03-14T01:59:60Z", "2026-03-14T01:59:00",
            "2026-03-14 01:59:00Z", "2026-03-14T01:59:00+0100", "2026-03-14",
        )
        for (t in rejected) {
            assertEquals(t, listOf(AslProblem.INVALID_TIMESTAMP to "'$t'"), found(state("Type: Wait", "Timestamp: '$t'", "End: true")))
        }
    }

    // exclusive fields, empty values and names

    fun `test Parameters beside ItemSelector on a Map`() =
        assertEquals(listOf(EXCLUSIVE_FIELDS to "ItemSelector"), found(map("Parameters:", "  a: 1", "ItemSelector:", "  a: 1")))

    fun `test empty Branches and empty CSVHeaders`() {
        assertEquals(listOf(EMPTY_LIST to "Branches"), found(state("Type: Parallel", "Branches: []", "End: true")))
        val reader = arrayOf("ItemReader:", "  Resource: arn:aws:states:::s3:getObject", "  Parameters:", "    Bucket: b", "    Key: k", "  ReaderConfig:",
            "    InputType: CSV", "    CSVHeaderLocation: GIVEN", "    CSVHeaders: []")
        assertEquals(listOf(EMPTY_LIST to "CSVHeaders"), found(map(*reader)))
    }

    fun `test an empty label and a label with a slash`() {
        assertEquals(listOf(INVALID_MAP_LABEL to "''"), found(map("Label: ''")))
        assertEquals(listOf(INVALID_MAP_LABEL to "a/b"), found(map("Label: a/b")))
        nothing(map("Label: a-b_c.d(e)'f+g=h@i!"))
    }

    fun `test an empty state name as a target`() {
        val text = "StartAt: A\nStates:\n  A:\n    Type: Pass\n    Next: ''\n  '':\n    Type: Succeed\n"
        assertEquals(listOf(MISSING_TRANSITION_TARGET to "''"), found(text))
    }

    fun `test a state that only itself names deploys`() {
        val text = "StartAt: A\nStates:\n  A:\n    Type: Succeed\n  B:\n    Type: Pass\n    Next: B\n"
        assertEquals(listOf(UNREACHABLE_STATE to "B"), found(text))
    }

    // reader and writer configuration

    fun `test reader and writer configurations that AWS rejects`() {
        val get = arrayOf("ItemReader:", "  Resource: arn:aws:states:::s3:getObject", "  Parameters:", "    Bucket: b", "    Key: k")
        assertEquals(listOf(INCOMPLETE_CONFIGURATION to "ItemReader"), found(map(*get)))
        assertEquals(listOf(INCOMPLETE_CONFIGURATION to "ReaderConfig"), found(map(*get, "  ReaderConfig:", "    InputType: CSV")))
        assertEquals(listOf(INCOMPLETE_CONFIGURATION to "ReaderConfig"), found(map(*get, "  ReaderConfig:", "    ManifestType: ATHENA_DATA")))
        assertEquals(listOf(INCOMPLETE_CONFIGURATION to "ResultWriter"), found(map("ResultWriter:", "  Resource: arn:aws:states:::s3:putObject")))
    }

    fun `test reader configurations that AWS accepts or the file does not settle`() {
        nothing(map("ItemReader:", "  Resource: arn:aws:states:::s3:listObjectsV2", "  Parameters:", "    Bucket: b"))
        val get = arrayOf("ItemReader:", "  Resource: arn:aws:states:::s3:getObject", "  Parameters:", "    Bucket: b", "    Key: k")
        nothing(map(*get, "  ReaderConfig:", "    InputType: MANIFEST"))
        nothing(map(*get, "  ReaderConfig:", "    ManifestType: S3_INVENTORY"))
        nothing(map(*get, "  ReaderConfig:", "    InputType: $dollar{InputType}"))
        nothing(map(*get, "  <<: $dollar{ReaderDefaults}"))
        nothing(map(*get, "  ReaderConfig:", "    InputType: CSV", "    $dollar{HeaderField}: FIRST_ROW"))
    }
}
