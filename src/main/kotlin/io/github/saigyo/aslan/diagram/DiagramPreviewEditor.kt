package io.github.saigyo.aslan.diagram

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The diagram pane of the split editor. For now a placeholder: it lays out two nodes off the UI thread and says when the
 * layout is ready. The platform creates it on the UI thread, so its Swing component is built in the constructor.
 */
class DiagramPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val properties = PropertiesComponent.getInstance(project)
    private val layoutKey = layoutKey(file)

    /**
     * The layout chosen for this file with the layout switch; null until the switch has been used. It is kept in the
     * project's properties under the file's URL, so it survives closing the file and restarting the IDE. The editor
     * state does not carry it: the navigation history restores old states, which would roll the choice back.
     */
    var layoutMode: LayoutMode? = properties.getValue(layoutKey)?.let { name -> LayoutMode.entries.firstOrNull { it.name == name } }
        set(value) {
            field = value
            if (value == null) properties.unsetValue(layoutKey) else properties.setValue(layoutKey, value.name)
        }

    private val label = JBLabel("", SwingConstants.CENTER)
    private val panel = JPanel(BorderLayout()).apply { add(label, BorderLayout.CENTER) }
    private var textEditor: TextEditor? = null

    @Volatile
    private var disposed = false

    /** Connects the pane to the text editor beside it; called once, when the split editor is created. */
    fun attach(textEditor: TextEditor) {
        this.textEditor = textEditor
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val states = layOutTwoNodes().children.size
            application.invokeLater({ label.text = "Layout ready: $states states" }, ModalityState.any()) { disposed || project.isDisposed }
        }
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun getName(): String = "Diagram"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        disposed = true
        textEditor = null
    }

    internal companion object {
        /** The key of the project property that holds the chosen layout of [file]. */
        fun layoutKey(file: VirtualFile): String = "aslan.diagram.layout." + file.url
    }
}
