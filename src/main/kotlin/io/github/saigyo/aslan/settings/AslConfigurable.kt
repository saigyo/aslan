package io.github.saigyo.aslan.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class AslConfigurable : Configurable {
    private var area: JBTextArea? = null

    override fun getDisplayName(): String = "Amazon States Language"

    override fun createComponent(): JComponent {
        val textArea = JBTextArea(8, 40)
        area = textArea
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel("Additional file name patterns"),
                JBScrollPane(textArea),
                true,
            )
            .addComponent(JBLabel("One pattern per line, with * and ? as wildcards, for example *.statemachine.yaml"))
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
    }

    private fun enteredPatterns(): List<String> =
        area?.text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    override fun isModified(): Boolean = enteredPatterns() != AslSettings.getInstance().extraPatterns

    override fun apply() {
        AslSettings.getInstance().extraPatterns = enteredPatterns()
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("ASL file name patterns changed")
        }
    }

    override fun reset() {
        area?.text = AslSettings.getInstance().extraPatterns.joinToString("\n")
    }

    override fun disposeUIResources() {
        area = null
    }
}
