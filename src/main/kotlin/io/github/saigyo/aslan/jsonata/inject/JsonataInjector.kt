package io.github.saigyo.aslan.jsonata.inject

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import io.github.saigyo.aslan.files.AslFiles
import io.github.saigyo.aslan.jsonata.JsonataLanguage
import io.github.saigyo.aslan.model.AslModel
import org.jetbrains.yaml.psi.YAMLScalar

/** Injects JSONata into exactly the strings that Step Functions evaluates as JSONata, see [io.github.saigyo.aslan.model.JsonataSites]. */
class JsonataInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(YAMLScalar::class.java, JsonStringLiteral::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val host = context as? PsiLanguageInjectionHost ?: return
        val file = context.containingFile ?: return
        if (!AslFiles.isAslFile(file) || InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return
        val sites = AslModel.of(file)?.jsonataSites?.at(context) ?: return
        if (sites.isEmpty() || !host.isValidHost) return
        val places = try {
            JsonataPlaces.of(context)
        } catch (e: RuntimeException) {
            if (e is ControlFlowException) throw e
            log.warn("JSONata places could not be computed in ${file.name}", e)
            return
        }
        if (places.isEmpty()) return
        registrar.startInjecting(JsonataLanguage)
        for (place in places) registrar.addPlace(place.prefix, place.suffix, host, place.range)
        registrar.doneInjecting()
    }

    private companion object {
        val log = logger<JsonataInjector>()
    }
}
