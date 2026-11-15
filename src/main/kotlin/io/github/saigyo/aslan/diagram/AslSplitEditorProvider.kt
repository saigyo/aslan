package io.github.saigyo.aslan.diagram

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.TextEditorWithPreviewProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.saigyo.aslan.files.AslFiles
import javax.swing.SwingUtilities

private val log = logger<AslSplitEditorProvider>()

// Says on which thread the platform creates the editors, for the smoke check in the sandbox IDE; removed after it.
private fun logThread(method: String) {
    log.info("$method on thread ${Thread.currentThread().name}, event dispatch thread: ${SwingUtilities.isEventDispatchThread()}")
}

/** Opens an ASL file in the text editor with the diagram beside it. */
class AslSplitEditorProvider : TextEditorWithPreviewProvider(DiagramPreviewProvider()) {
    override fun createSplitEditor(firstEditor: TextEditor, secondEditor: FileEditor): FileEditor {
        logThread("createSplitEditor")
        val preview = secondEditor as DiagramPreviewEditor
        preview.attach(firstEditor)
        return TextEditorWithPreview(firstEditor, preview, "Aslan Diagram", TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW, false)
    }
}

/**
 * The provider of the diagram pane, used only through [AslSplitEditorProvider]; not registered on its own. It has no
 * readState and writeState: the readState that 2026.2 calls is deprecated in 2026.3, and its replacement does not exist
 * in 2026.2. The pane keeps the chosen layout itself ([DiagramPreviewEditor.layoutMode]).
 */
class DiagramPreviewProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = AslFiles.isAslFile(file)

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        logThread("DiagramPreviewProvider.createEditor")
        return DiagramPreviewEditor(project, file)
    }

    override fun getEditorTypeId(): String = "aslan-diagram"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}
