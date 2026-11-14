package io.github.saigyo.aslan.tree

/**
 * CloudFormation functions in their long form: a mapping with the single key `Ref` or `Fn::Name`, such as
 * `Fn::GetAtt: [Role, Arn]`. CloudFormation replaces such a mapping before the definition reaches Step Functions, for
 * example in a file that a template pulls in with `AWS::Include`, so the file does not settle the value.
 */
internal object CloudFormationFunctions {
    private const val STATES = "States"

    fun isCall(keys: List<String>): Boolean = keys.size == 1 && (keys[0] == "Ref" || keys[0].startsWith("Fn::"))

    /** The value of `States` holds state names, and a machine may have a single state named `Ref`. */
    fun mayHoldCall(key: String): Boolean = key != STATES
}
