package io.github.saigyo.aslan.jsonata.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

/**
 * Replays the tree of [JsonataScanner] into the builder. The scan's one error becomes one error element, placed where
 * the builder reaches the start of the error, inside the node open at that moment. The replay uses an explicit stack,
 * since a tree can be some hundreds of levels deep.
 */
class JsonataParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val scan = JsonataScanner.scan(builder.originalText)
        Replay(builder, scan.error).run(root, scan.root)
        return builder.treeBuilt
    }

    private class Frame(val node: ScanNode, val marker: PsiBuilder.Marker) { var next = 0 }

    private class Replay(val builder: PsiBuilder, var error: ScanError?) {
        fun run(fileType: IElementType, top: ScanNode?) {
            val file = builder.mark()
            if (top != null) {
                val stack = ArrayList<Frame>()
                stack.add(open(top))
                while (stack.isNotEmpty()) {
                    val frame = stack.last()
                    if (frame.next < frame.node.children.size) {
                        stack.add(open(frame.node.children[frame.next++]))
                    } else {
                        advanceTo(frame.node.end)
                        frame.marker.done(frame.node.type)
                        stack.removeAt(stack.size - 1)
                    }
                }
            }
            advanceTo(Int.MAX_VALUE)
            reportError()
            file.done(fileType)
        }

        private fun open(node: ScanNode): Frame {
            advanceTo(node.start)
            if (error?.let { builder.currentOffset >= it.start } == true) reportError()
            return Frame(node, builder.mark())
        }

        /** Consumes the tokens that start before [offset]; the error is reported before the first token at or after its start. */
        private fun advanceTo(offset: Int) {
            while (!builder.eof() && builder.currentOffset < offset) {
                if (error?.let { builder.currentOffset >= it.start } == true) reportError()
                builder.advanceLexer()
            }
        }

        private fun reportError() {
            val e = error ?: return
            error = null
            builder.error(message(e.code))
        }
    }

    companion object {
        /** The reference's error code is the message; the messages of the reference are not reproduced. */
        fun message(code: String): String = "JSONata syntax error $code"
    }
}
