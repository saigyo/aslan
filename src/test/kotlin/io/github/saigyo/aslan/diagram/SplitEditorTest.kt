package io.github.saigyo.aslan.diagram

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.files.AslFiles

class SplitEditorTest : AslTestCase() {
    private val files = ArrayList<VirtualFile>()

    override fun tearDown() {
        try {
            // the light project and its properties are shared with other test classes
            val properties = PropertiesComponent.getInstance(project)
            for (file in files) properties.unsetValue(DiagramPreviewEditor.layoutKey(file))
        } finally {
            super.tearDown()
        }
    }

    private fun file(name: String, text: String = "StartAt: A"): VirtualFile =
        myFixture.configureByText(name, text).virtualFile.also { files += it }

    /** A split editor for [file], with its user interface built: the platform builds it in a later event. */
    private fun split(file: VirtualFile): TextEditorWithPreview {
        val editor = AslSplitEditorProvider().createEditor(project, file)
        Disposer.register(testRootDisposable, editor)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return editor as TextEditorWithPreview
    }

    private fun TextEditorWithPreview.diagram(): DiagramPreviewEditor = previewEditor as DiagramPreviewEditor

    fun `test an ASL file is accepted and another YAML file is not`() {
        val provider = DiagramPreviewProvider()
        for ((name, accepted) in listOf("intake.asl.yaml" to true, "intake.asl.json" to true, "intake.yaml" to false, "intake.asl.txt" to false)) {
            val file = file(name)
            assertEquals(name, accepted, AslFiles.isAslFile(file))
            assertEquals(name, accepted, provider.accept(project, file))
        }
    }

    fun `test the split editor wraps the text editor and the diagram pane`() {
        val editor = AslSplitEditorProvider().createEditor(project, file("intake.asl.yaml"))
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertInstanceOf(editor, TextEditorWithPreview::class.java)
            editor as TextEditorWithPreview
            assertInstanceOf(editor.previewEditor, DiagramPreviewEditor::class.java)
            assertEquals(TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW, editor.getLayout())
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun `test the chosen layout survives closing the file`() {
        val switched = file("switched.asl.yaml")
        val editor = split(switched)
        editor.diagram().layoutMode = LayoutMode.COMPACT
        Disposer.dispose(editor)
        assertEquals(LayoutMode.COMPACT, split(switched).diagram().layoutMode)

        assertNull(split(file("untouched.asl.yaml")).diagram().layoutMode)
    }

    fun `test an older or empty editor state leaves the chosen layout as it is`() {
        val file = file("switched.asl.yaml")
        val editor = split(file)
        val before = editor.getState(FileEditorStateLevel.NAVIGATION)
        editor.diagram().layoutMode = LayoutMode.COMPACT
        editor.setState(before)
        editor.diagram().setState(FileEditorState.INSTANCE)
        assertEquals(LayoutMode.COMPACT, editor.diagram().layoutMode)
        Disposer.dispose(editor)
        assertEquals(LayoutMode.COMPACT, split(file).diagram().layoutMode)
    }

    fun `test the size rule`() {
        assertEquals(LayoutMode.LOOSE, LayoutMode.defaultFor(25))
        assertEquals(LayoutMode.COMPACT, LayoutMode.defaultFor(26))
    }

    fun `test the layout library runs`() {
        val root = layOutTwoNodes()
        val (first, second) = root.children.also { assertEquals(2, it.size) }
        assertTrue("the second node lies below the first", second.y >= first.y + first.height)
        val section = root.containedEdges.single().sections.single()
        assertEquals(first.y + first.height, section.startY, 0.001)
        assertEquals(second.y, section.endY, 0.001)
    }
}
