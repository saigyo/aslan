package io.github.saigyo.aslan.spec.data

import io.github.saigyo.aslan.spec.FieldSpec
import io.github.saigyo.aslan.spec.Mode
import io.github.saigyo.aslan.spec.StructureId
import io.github.saigyo.aslan.spec.StructureSpec
import io.github.saigyo.aslan.spec.ValueKind

/** A JSONPath comparison operator, the kind of value it takes, and its description. */
private class Comparison(val name: String, val kind: ValueKind, val description: String)

private fun holdsWhen(test: String) = "JSONPath only. Holds when $test."

/** The 39 comparison operators of JSONPath Choice rules. */
private val comparisons: List<Comparison> = buildList {
    val orders = listOf("Equals" to "equals", "LessThan" to "is less than", "GreaterThan" to "is greater than",
        "LessThanEquals" to "is less than or equal to", "GreaterThanEquals" to "is greater than or equal to")
    for ((prefix, kind, noun) in listOf(
        Triple("String", ValueKind.Text, "string"),
        Triple("Numeric", ValueKind.Number, "number"),
        Triple("Timestamp", ValueKind.Text, "timestamp"),
    )) {
        for ((suffix, verb) in orders) {
            add(Comparison("$prefix$suffix", kind, holdsWhen("the $noun at Variable $verb the given $noun")))
            add(Comparison("$prefix${suffix}Path", ValueKind.Text, holdsWhen("the $noun at Variable $verb the $noun at the given path")))
        }
        if (prefix == "String") add(Comparison("StringMatches", ValueKind.Text,
            holdsWhen("the string at Variable matches the given pattern, in which * stands for any run of characters")))
    }
    add(Comparison("BooleanEquals", ValueKind.Bool, holdsWhen("the Boolean at Variable equals the given Boolean")))
    add(Comparison("BooleanEqualsPath", ValueKind.Text, holdsWhen("the Boolean at Variable equals the Boolean at the given path")))
    for ((name, what) in listOf("IsNull" to "is null", "IsPresent" to "exists in the input", "IsNumeric" to "is a number",
        "IsString" to "is a string", "IsBoolean" to "is a Boolean", "IsTimestamp" to "is a timestamp")) {
        add(Comparison(name, ValueKind.Bool,
            "JSONPath only. Set to true to test that the value at Variable $what, or to false to test that it does not."))
    }
}

internal val choiceRuleStructures = listOf(
    StructureSpec(StructureId.CHOICE_RULE, buildList {
        add(FieldSpec("Next", ValueKind.StateName,
            description = "The state to run when this rule holds; required on every rule directly under Choices and not allowed in nested rules.",
            docUrl = Doc.CHOICE))
        add(commentField(Doc.CHOICE, "this rule"))
        add(assignField(Doc.CHOICE))
        add(FieldSpec("Condition", ValueKind.Bool, Mode.JSONATA, jsonata = true,
            description = "JSONata only. The test of this rule: true, false or a {% %} expression that yields a Boolean.",
            docUrl = Doc.CHOICE))
        add(FieldSpec("Output", ValueKind.FreeForm, Mode.JSONATA, jsonata = true,
            description = "JSONata only. The output of the Choice state when this rule holds, in place of its input.",
            docUrl = Doc.CHOICE))
        add(FieldSpec("Variable", ValueKind.Text, Mode.JSONPATH,
            description = "JSONPath only. A path to the input value that the comparison operator of this rule tests.",
            docUrl = Doc.CHOICE))
        add(FieldSpec("And", ValueKind.ListOf(ValueKind.Struct(StructureId.CHOICE_RULE)), Mode.JSONPATH,
            description = "JSONPath only. Nested rules that must all hold; they may not contain Next.", docUrl = Doc.CHOICE))
        add(FieldSpec("Or", ValueKind.ListOf(ValueKind.Struct(StructureId.CHOICE_RULE)), Mode.JSONPATH,
            description = "JSONPath only. Nested rules of which at least one must hold; they may not contain Next.",
            docUrl = Doc.CHOICE))
        add(FieldSpec("Not", ValueKind.Struct(StructureId.CHOICE_RULE), Mode.JSONPATH,
            description = "JSONPath only. One nested rule whose outcome is reversed; it may not contain Next.", docUrl = Doc.CHOICE))
        for (c in comparisons) add(FieldSpec(c.name, c.kind, Mode.JSONPATH, description = c.description, docUrl = Doc.CHOICE))
    }),
)
