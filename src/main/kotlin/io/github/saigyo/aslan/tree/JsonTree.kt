package io.github.saigyo.aslan.tree

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonBooleanLiteral
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonNullLiteral
import com.intellij.json.psi.JsonNumberLiteral
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue

/** Builds the value tree from JSON PSI. */
internal object JsonTree {
    fun root(file: JsonFile): AslNode? = file.topLevelValue?.let { node(it) }

    private fun node(value: JsonValue, mayBeCall: Boolean = true): AslNode = when (value) {
        is JsonObject ->
            if (mayBeCall && CloudFormationFunctions.isCall(value.propertyList.map { it.name })) {
                AslUnresolved(value, value)
            } else {
                AslObject(value, value, value.propertyList.map { property ->
                    val keyPsi = property.nameElement
                    val v = property.value
                    AslEntry(
                        key = property.name,
                        keyPsi = keyPsi,
                        entryPsi = property,
                        value = if (v != null) node(v, CloudFormationFunctions.mayHoldCall(property.name)) else AslScalar(keyPsi, keyPsi, ScalarType.NULL, ""),
                        viaMerge = false,
                    )
                })
            }
        is JsonArray -> AslArray(value, value, value.valueList.map { node(it) })
        is JsonStringLiteral -> AslScalar(value, value, ScalarType.STRING, value.value)
        is JsonNumberLiteral -> AslScalar(value, value, ScalarType.NUMBER, value.text)
        is JsonBooleanLiteral -> AslScalar(value, value, ScalarType.BOOLEAN, value.text)
        is JsonNullLiteral -> AslScalar(value, value, ScalarType.NULL, value.text)
        else -> AslScalar(value, value, ScalarType.STRING, value.text)
    }
}
