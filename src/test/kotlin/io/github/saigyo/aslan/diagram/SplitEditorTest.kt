package io.github.saigyo.aslan.diagram

import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.files.AslFiles
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.jdom.Element

class SplitEditorTest : AslTestCase() {
    private fun file(name: String, text: String = "StartAt: A"): VirtualFile =
        myFixture.configureByText(name, text).virtualFile

    /** A split editor for [file], with its user interface built: the platform builds it in a later event. */
    private fun split(file: VirtualFile): TextEditorWithPreview {
        val editor = AslSplitEditorProvider().createEditor(project, file)
        Disposer.register(testRootDisposable, editor)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return editor as TextEditorWithPreview
    }

    private fun TextEditorWithPreview.diagram(): DiagramPreviewEditor = previewEditor as DiagramPreviewEditor

    /** The state of [editor] after it has been written to XML and read back, as when the IDE closes and reopens a file. */
    private fun storedAndRead(editor: TextEditorWithPreview, file: VirtualFile): TextEditorWithPreview {
        val provider = AslSplitEditorProvider()
        val element = Element("provider")
        provider.writeState(editor.getState(FileEditorStateLevel.FULL), project, element)
        val reopened = split(file)
        reopened.setState(provider.readState(element, project, file))
        return reopened
    }

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
        assertEquals(LayoutMode.COMPACT, storedAndRead(editor, switched).diagram().layoutMode)

        val untouched = file("untouched.asl.yaml")
        assertNull(storedAndRead(split(untouched), untouched).diagram().layoutMode)
    }

    fun `test the size rule`() {
        assertEquals(LayoutMode.LOOSE, LayoutMode.defaultFor(25))
        assertEquals(LayoutMode.COMPACT, LayoutMode.defaultFor(26))
    }

    fun `test the layout library runs`() {
        registerLayeredAlgorithm()
        val root = ElkGraphUtil.createGraph()
        root.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
        root.setProperty(CoreOptions.DIRECTION, Direction.DOWN)
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        val first = ElkGraphUtil.createNode(root).apply { setDimensions(190.0, 40.0) }
        val second = ElkGraphUtil.createNode(root).apply { setDimensions(190.0, 40.0) }
        val edge = ElkGraphUtil.createSimpleEdge(first, second)

        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

        assertTrue("the second node lies below the first", second.y >= first.y + first.height)
        val section = edge.sections.single()
        assertEquals(first.y + first.height, section.startY, 0.001)
        assertEquals(second.y, section.endY, 0.001)
    }
}
