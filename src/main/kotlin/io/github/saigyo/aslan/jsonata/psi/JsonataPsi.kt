package io.github.saigyo.aslan.jsonata.psi

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.JsonataSite

/** Between a JSONata file injected into an ASL definition and the string it is injected into. */
object JsonataPsi {
    /** The file injected into a YAML scalar or JSON string, or null; null also for the empty expression of `{%%}`. */
    fun injectedFile(host: PsiElement): JsonataFile? {
        if (host !is PsiLanguageInjectionHost || !host.isValid) return null
        return InjectedLanguageManager.getInstance(host.project).getInjectedPsiFiles(host)?.firstNotNullOfOrNull { it.first as? JsonataFile }
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
