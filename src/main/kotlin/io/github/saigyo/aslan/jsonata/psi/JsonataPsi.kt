package io.github.saigyo.aslan.jsonata.psi

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.JsonataSite

/** Between a JSONata file injected into an ASL definition and the string it is injected into. */
object JsonataPsi {
    /** The file injected into a YAML scalar or JSON string, or null; null also for the empty expression of `{%%}`. */
    fun injectedFile(host: PsiElement): JsonataFile? {
        if (host !is PsiLanguageInjectionHost || !host.isValid) return null
        return InjectedLanguageManager.getInstance(host.project).getInjectedPsiFiles(host)?.firstNotNullOfOrNull { it.first as? JsonataFile }
    }

    /**
     * Whether the expression in [file] is to be checked. It is not when it holds a deployment-time placeholder outside a
     * string literal: a `PLACEHOLDER` token, or the two characters `${` anywhere outside a `STRING` token, whatever
     * follows them (`${ssm:/path}`, `${opt:stage, 'dev'}`, `${file(./x.yml):k}` and `${jsonencode(x)}` are replaced
     * by deployment tools, so the expression AWS sees differs from the text). A placeholder inside a string literal
     * changes nothing. The search runs on the decoded text, which is what Step Functions and the deployment tools see:
     * in a JSON host `${` is a placeholder too.
     */
    fun judged(file: JsonataFile): Boolean {
        val injected = InjectedLanguageManager.getInstance(file.project).takeIf { it.isInjectedFragment(file) }
        val text = StringBuilder()
        val strings = ArrayList<TextRange>()
        var leaf: PsiElement? = PsiTreeUtil.firstChild(file)
        while (leaf != null) {
            if (leaf.firstChild == null && leaf.textLength > 0) {
                val type = leaf.node.elementType
                if (type == JsonataTokens.PLACEHOLDER) return false
                val start = text.length
                text.append(injected?.getUnescapedText(leaf) ?: leaf.text)
                if (type == JsonataTokens.STRING) strings += TextRange(start, text.length)
            }
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        var at = text.indexOf("\${")
        while (at >= 0) {
            if (strings.none { it.startOffset <= at && at + 1 < it.endOffset }) return false
            at = text.indexOf("\${", at + 1)
        }
        return true
    }

    /**
     * The text of [element] as Step Functions reads it: decoded from the host's escapes (`$a` in JSON is `$a`).
     * Text without a backslash is returned as it is, since a name holds no other escape.
     */
    fun decodedText(element: PsiElement): String {
        val text = element.text
        if ('\\' !in text) return text
        val manager = InjectedLanguageManager.getInstance(element.project)
        return if (manager.isInjectedFragment(element.containingFile)) manager.getUnescapedText(element) else text
    }

    /** The sites of the host of [file], one per state that uses it; empty for any file that is not injected JSONata. */
    fun sites(file: PsiFile): List<JsonataSite> {
        if (file !is JsonataFile) return emptyList()
        val host = InjectedLanguageManager.getInstance(file.project).getInjectionHost(file) ?: return emptyList()
        return AslModel.of(host.containingFile)?.jsonataSites?.at(host).orEmpty()
    }

    /**
     * The host of [file] and the range of its text that [range] of the injected file stands for, relative to the host's
     * start: from the first character of [range] that comes from the host to the last. A range that holds only text that
     * stands for a line break, and an empty range, give an empty range at the corresponding position.
     *
     * The text of an injected file is the host's text as written, escapes included, with the prefix and suffix of each
     * place around it; only the lexer and parser see the decoded text (see [InjectedLanguageManager.getUnescapedText]).
     * So each place maps character for character onto its range in the host.
     */
    fun rangeInHost(file: JsonataFile, range: TextRange): Pair<PsiElement, TextRange>? {
        val manager = InjectedLanguageManager.getInstance(file.project)
        val host = manager.getInjectionHost(file) ?: return null
        var shreds: List<PsiLanguageInjectionHost.Shred>? = null
        manager.enumerate(host) { injected, places -> if (injected == file) shreds = places }
        val parts = ArrayList<Part>()
        var offset = 0
        for (shred in shreds ?: return null) {
            val inHost = shred.rangeInsideHost
            val start = offset + shred.prefix.length
            parts += Part(shred.host ?: return null, start, start + inHost.length, inHost.startOffset)
            offset = start + inHost.length + shred.suffix.length
        }
        if (parts.isEmpty() || offset != file.textLength) return null
        val covered = parts.filter { maxOf(range.startOffset, it.start) < minOf(range.endOffset, it.end) }
        if (covered.isEmpty()) {
            val part = parts.firstOrNull { range.startOffset <= it.end } ?: parts.last()
            return part.host to TextRange.from(part.toHost(range.startOffset.coerceIn(part.start, part.end)), 0)
        }
        val first = covered.first()
        val last = covered.last()
        if (first.host != last.host) return null
        return first.host to TextRange(first.toHost(maxOf(range.startOffset, first.start)), last.toHost(minOf(range.endOffset, last.end)))
    }

    /** The part of a place that comes from its host: offsets [start] to [end] of the injected file, from [hostStart] on in the host. */
    private class Part(val host: PsiLanguageInjectionHost, val start: Int, val end: Int, val hostStart: Int) {
        fun toHost(offset: Int): Int = hostStart + offset - start
    }
}
