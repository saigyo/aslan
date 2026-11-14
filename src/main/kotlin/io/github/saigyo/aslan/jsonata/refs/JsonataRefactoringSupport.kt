package io.github.saigyo.aslan.jsonata.refs

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.injected.editor.DocumentWindow
import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.inplace.VariableInplaceRenameHandler
import com.intellij.refactoring.rename.inplace.VariableInplaceRenamer
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import io.github.saigyo.aslan.jsonata.psi.JsonataScopes
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef

/**
 * Refactoring support for JSONata. In-place rename of a local variable goes through [JsonataInplaceRenameHandler], not
 * through the platform's handler that [isInplaceRenameAvailable] would enable: the platform's renamer edits the whole
 * declaration token and would take the `$` away while the user types. Were both handlers available, the IDE would ask
 * which one to use.
 */
class JsonataRefactoringSupport : RefactoringSupportProvider() {
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean = false

    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean = false

    override fun isSafeDeleteAvailable(element: PsiElement): Boolean = false
}

/** In-place rename of a local JSONata variable, on the name without `$`. */
class JsonataInplaceRenameHandler : VariableInplaceRenameHandler() {
    override fun isAvailable(element: PsiElement?, editor: Editor, file: PsiFile): Boolean =
        element != null && element.isValid && isLocalVariable(element) && editor.settings.isVariableInplaceRenameEnabled

    override fun createRenamer(elementToRename: PsiElement, editor: Editor): VariableInplaceRenamer =
        JsonataInplaceRenamer(elementToRename as PsiNamedElement, editor)
}

/** Edits each occurrence after its `$`, so the template shows and takes the name alone. */
class JsonataInplaceRenamer(element: PsiNamedElement, editor: Editor, initialName: String? = element.name, oldName: String? = element.name) :
    VariableInplaceRenamer(element, editor, element.project, initialName, oldName) {
    override fun getRangeToRename(element: PsiElement): TextRange = afterDollar(element) ?: super.getRangeToRename(element)

    override fun getRangeToRename(reference: PsiReference): TextRange = afterDollar(reference.element) ?: super.getRangeToRename(reference)

    /**
     * The platform checks the new name in the language of the template's scope, which for an injected expression is
     * the host file's (YAML accepts any name); a name that is no JSONata variable name rolls the template back.
     */
    override fun isIdentifier(newName: String?, language: Language?): Boolean =
        newName != null && JsonataNamesValidator().isIdentifier(newName, myProject)

    /**
     * A name that is no variable name is rolled back here, before the platform checks it: the platform checks it
     * later, in a task that expires with the injected editor, and a quote typed into a quoted host ends the injection
     * and so that editor. The host would keep the bad name.
     */
    override fun performRefactoring(): Boolean {
        val name = myInsertedName
        if (name == null || isIdentifier(name, null)) return super.performRefactoring()
        tryRollback()
        val editor = InjectedLanguageEditorUtil.getTopLevelEditor(myEditor)
        if (!ApplicationManager.getApplication().isUnitTestMode) HintManager.getInstance().showErrorHint(editor, "'$name' is not a valid variable name")
        return false
    }

    /**
     * On cancel the platform leaves the typed text in place. A text that is no variable name (a quote, for one) can
     * break the host string, so it is put back to the old name.
     */
    override fun finish(success: Boolean) {
        if (!success) {
            val state = TemplateManagerImpl.getTemplateState(InjectedLanguageEditorUtil.getTopLevelEditor(myEditor))
            val typed = state?.getVariableValue(PRIMARY_VARIABLE_NAME)?.text
            if (typed != null && typed != myOldName && !isIdentifier(typed, null)) revertState()
        }
        super.finish(success)
    }

    override fun createInplaceRenamerToRestart(variable: PsiNamedElement, editor: Editor, initialName: String): VariableInplaceRenamer =
        JsonataInplaceRenamer(variable, editor, initialName, myOldName)

    private fun afterDollar(element: PsiElement): TextRange? =
        if (element.textLength > 1 && element.text.startsWith("$")) TextRange(1, element.textLength) else null
}

/**
 * A `$name` that is not a local variable is no named element for the IDE: with the caret on `$sum` or `$states`
 * there is nothing to rename, find or highlight.
 */
class JsonataTargetElementEvaluator : TargetElementEvaluatorEx2() {
    override fun isAcceptableNamedParent(parent: PsiElement): Boolean = parent !is JsonataVariableRef || isLocalVariable(parent)
}

/**
 * Renames a local variable with one edit per occurrence of the expression's document. In an injected file that is the
 * platform's document window, which writes each edit into the host document at the place it maps to; the name holds
 * no escapes, so the raw host text receives exactly `$` and the new name. Changing the injected PSI one occurrence
 * after the other does not work for YAML hosts: the first change replaces the host scalar, and the platform then
 * maps the following changes through the stale places of the old one and damages the host text.
 */
class JsonataVariableRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = isLocalVariable(element)

    override fun renameElement(element: PsiElement, newName: String, usages: Array<out UsageInfo>, listener: RefactoringElementListener?) {
        val variable = element as JsonataVariableRef
        if (!JsonataNamesValidator().isIdentifier(newName, variable.project)) throw IncorrectOperationException("not a valid variable name: $newName")
        val file = variable.containingFile
        val documents = PsiDocumentManager.getInstance(file.project)
        if (!InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) {
            for (occurrence in JsonataScopes.occurrences(variable).asReversed()) occurrence.setName(newName)
            listener?.elementRenamed(variable)
            return
        }
        // changing the injected PSI instead would damage a YAML host, so without the document nothing is renamed
        val document = documents.getDocument(file) ?: throw IncorrectOperationException("the expression cannot be edited")
        val ranges = JsonataScopes.occurrences(variable).mapNotNull { it.nameIdentifier?.textRange }
        val first = ranges.first().startOffset
        for (range in ranges.asReversed()) document.replaceString(range.startOffset, range.endOffset, "$$newName")
        val host = (document as? DocumentWindow)?.delegate ?: document
        documents.commitDocument(host)
        val renamed = documents.getPsiFile(document)?.findElementAt(first)?.parent as? JsonataVariableRef
        if (renamed != null) listener?.elementRenamed(renamed)
    }
}
