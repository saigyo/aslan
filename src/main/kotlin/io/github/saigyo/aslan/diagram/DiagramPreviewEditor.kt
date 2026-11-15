package io.github.saigyo.aslan.diagram

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * The diagram pane of the split editor: a [DiagramPane] kept in step with the file by a [DiagramController]. The
 * platform creates it on the UI thread, so the pane is built in the constructor; the pane and the controller are
 * disposed with this editor.
 */
class DiagramPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val properties = PropertiesComponent.getInstance(project)
    private val layoutKey = layoutKey(file)

    internal val pane = DiagramPane(this)

    /** Created in [attach]. */
    internal var controller: DiagramController? = null
        private set

    /**
     * The layout chosen for this file with the layout switch; null until the switch has been used. It is kept in the
     * project's properties under the file's URL, so it survives closing the file and restarting the IDE. The editor
     * state does not carry it: the navigation history restores old states, which would roll the choice back. The
     * controller starts with it and sets it when the switch is used; setting it gives the controller the choice.
     */
    var layoutMode: LayoutMode?
        get() = properties.getValue(layoutKey)?.let { name -> LayoutMode.entries.firstOrNull { it.name == name } }
        set(value) {
            if (value == null) properties.unsetValue(layoutKey) else properties.setValue(layoutKey, value.name)
            controller?.chosenMode = value
        }

    /** Connects the pane to the text editor beside it; called once, when the split editor is created. */
    fun attach(textEditor: TextEditor) {
        controller = DiagramController(project, file, textEditor.editor, pane, this, layoutMode) { layoutMode = it }
    }

    override fun getComponent(): JComponent = pane.component

    override fun getPreferredFocusedComponent(): JComponent = pane.diagram

    override fun getName(): String = "Diagram"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {}

    internal companion object {
        /** The key of the project property that holds the chosen layout of [file]. */
        fun layoutKey(file: VirtualFile): String = "aslan.diagram.layout." + file.url
    }
}
