package io.github.saigyo.aslan.jsonata.docs

import io.github.saigyo.aslan.spec.JsonataFunction

/** The call form of [function]: `$substring(str, start[, length])`. */
internal fun callForm(function: JsonataFunction): String {
    val text = StringBuilder()
    for (parameter in function.parameters) {
        if (!parameter.optional) {
            if (text.isNotEmpty()) text.append(", ")
            text.append(parameter.name)
        } else {
            text.append(if (text.isEmpty()) "[" else "[, ").append(parameter.name).append("]")
        }
    }
    return "$" + function.name + "(" + text + ")"
}
