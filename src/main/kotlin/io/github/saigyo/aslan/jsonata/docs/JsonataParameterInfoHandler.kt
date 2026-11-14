package io.github.saigyo.aslan.jsonata.docs

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.saigyo.aslan.jsonata.parser.JsonataElements
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens
import io.github.saigyo.aslan.jsonata.psi.JsonataCall
import io.github.saigyo.aslan.jsonata.psi.JsonataElement
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef
import io.github.saigyo.aslan.spec.AslSpec
import io.github.saigyo.aslan.spec.JsonataFunction

/** Shows the call form of a built-in function while its arguments are typed, with the current parameter emphasised. */
class JsonataParameterInfoHandler : ParameterInfoHandler<JsonataCall, JsonataFunction> {
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): JsonataCall? {
        val call = callAt(context.file, context.offset) ?: return null
        context.itemsToShow = arrayOf<Any>(functionOf(call) ?: return null)
        return call
    }

    override fun showParameterInfo(element: JsonataCall, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    /** The call the hint was opened for, while the caret is in its arguments; a hint for another call is closed. */
    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): JsonataCall? {
        val call = callAt(context.file, context.offset)
        val owner = context.parameterOwner
        if (call == null || (owner != null && owner !== call)) {
            context.removeHint()
            return null
        }
        return call
    }

    override fun updateParameterInfo(parameterOwner: JsonataCall, context: UpdateParameterInfoContext) {
        context.setCurrentParameter(argumentIndex(parameterOwner, context.offset))
    }

    override fun updateUI(function: JsonataFunction?, context: ParameterInfoUIContext) {
        if (function == null) return
        val text = callForm(function)
        var start = -1
        var end = -1
        val index = context.currentParameterIndex
        if (index in function.parameters.indices) {
            var from = text.indexOf('(') + 1
            for ((i, parameter) in function.parameters.withIndex()) {
                val at = text.indexOf(parameter.name, from)
                if (i == index) {
                    start = at
                    end = at + parameter.name.length
                }
                from = at + parameter.name.length
            }
        }
        context.setupUIComponentPresentation(text, start, end, false, false, false, context.defaultParameterColor)
    }

    companion object {
        /** The call of a built-in function whose argument list holds [offset], the innermost one. */
        internal fun callAt(file: PsiFile, offset: Int): JsonataCall? {
            if (file !is JsonataFile) return null
            // After the last character of an unfinished call (`$f(1, `) the caret is behind the call and its error element:
            // the last token before the caret is where the call is found then.
            var before = file.findElementAt((offset - 1).coerceAtLeast(0))
            while (before != null && (before is PsiWhiteSpace || before is PsiErrorElement || before.textLength == 0)) before = PsiTreeUtil.prevLeaf(before)
            for (start in listOf(file.findElementAt(offset), file.findElementAt((offset - 1).coerceAtLeast(0)), before)) {
                var element: PsiElement? = start
                while (element != null && element !is PsiFile) {
                    if (element is JsonataCall) {
                        val open = element.node.findChildByType(JsonataTokens.LPAREN)
                        val close = element.node.findChildByType(JsonataTokens.RPAREN)
                        if (open != null && offset >= open.startOffset + 1 && (close == null || offset <= close.startOffset) && functionOf(element) != null) {
                            return element
                        }
                    }
                    element = element.parent
                }
            }
            return null
        }

        private fun functionOf(call: JsonataCall): JsonataFunction? {
            val callee = call.callee as? JsonataVariableRef ?: return null
            if (callee.node.elementType != JsonataElements.VARIABLE_REF || JsonataScopes.declarations(callee).isNotEmpty()) return null
            return AslSpec.jsonataFunction(callee.variableName)
        }

        /**
         * The number of the call's own argument separators before [offset], plus one on the right of `~>`, whose left
         * side is the first argument. A partial application there (`x ~> $f(?, 1)`) is a function the left side fills
         * the first `?` of, so its arguments are counted as written.
         */
        internal fun argumentIndex(call: JsonataCall, offset: Int): Int {
            val separators = generateSequence(call.node.firstChildNode) { it.treeNext }.count { it.elementType == JsonataTokens.COMMA && it.textRange.endOffset <= offset }
            return separators + if (isChained(call)) 1 else 0
        }

        private fun isChained(call: JsonataCall): Boolean {
            val chain = call.parent as? JsonataElement ?: return false
            return chain.node.findChildByType(JsonataTokens.CHAIN) != null && chain.operands.lastOrNull() === call &&
                call.arguments.none { it.node.elementType == JsonataElements.PARTIAL_ARGUMENT }
        }
    }
}
