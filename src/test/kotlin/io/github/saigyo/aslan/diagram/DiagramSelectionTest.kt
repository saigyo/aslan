package io.github.saigyo.aslan.diagram

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import javax.swing.JComponent
import javax.swing.JPanel

class DiagramSelectionTest : DiagramTestCase() {
    /** A [DiagramView] that records what it is given, in order. */
    private class Recorder : DiagramView {
        class Shown(val model: DiagramModel, val selected: String?)

        val calls = ArrayList<Any>()
        val shows: List<Shown> get() = calls.filterIsInstance<Shown>()
        val selects: List<String> get() = calls.filterIsInstance<String>()

        override val component: JComponent = JPanel()
        override var onSelect: (id: String?) -> Unit = {}
        override var onNavigate: (offset: Int) -> Unit = {}
        override var onToggleBox: (stateName: String) -> Unit = {}
        override var onLayoutMode: (LayoutMode) -> Unit = {}
        override val measure = TextMeasure { text, _, _ -> 6.0 * text.length }

        override fun show(model: DiagramModel, layout: DiagramLayout, mode: LayoutMode, selected: String?) {
            calls += Shown(model, selected)
        }

        override fun select(id: String?) {
            calls += "select $id"
        }

        override fun setStale() {
            calls += "stale"
        }
    }

    private val editor: Editor get() = myFixture.editor
    private val text: String get() = editor.document.text
    private val caret: Int get() = editor.caretModel.offset

    private fun noDelay(task: Runnable): Future<*> = FutureTask(task, null)

    /** Opens [text] with the caret at the first occurrence of [at], when given; the first diagram has arrived. */
    private fun open(source: String = archive, at: String? = null): Pair<DiagramController, Recorder> {
        val psi: PsiFile = file(source)
        if (at != null) editor.caretModel.moveToOffset(text.indexOf(at))
        val view = Recorder()
        val controller = DiagramController(project, psi.virtualFile, editor, view, testRootDisposable)
        controller.delay = ::noDelay
        controller.caretDelay = ::noDelay
        PlatformTestUtil.waitForFuture(controller.latest)
        return controller to view
    }

    private fun moveCaret(controller: DiagramController, offset: Int) {
        editor.caretModel.moveToOffset(offset)
        controller.caretMovedNow()
    }

    private fun replace(old: String, new: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val at = text.indexOf(old)
            assertTrue(old, at >= 0)
            editor.document.replaceString(at, at + old.length, new)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun relayout(controller: DiagramController) {
        PlatformTestUtil.waitForFuture(controller.latest)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun DiagramModel.id(name: String): String = root!!.states.single { it.name == name }.id

    private fun DiagramModel.inner(name: String): String =
        root!!.states.flatMap { it.machines }.flatMap { it.states }.first { it.name == name }.id

    private fun DiagramModel.edgeFrom(name: String): DiagramEdge = root!!.edges.first { it.from == id(name) }

    private fun last(view: Recorder) = view.shows.last()

    fun `test the caret in a state selects it`() {
        val (controller, view) = open()
        val model = last(view).model
        moveCaret(controller, text.indexOf("StringEquals"))
        assertEquals("select ${model.id("Sort")}", view.selects.last())
        moveCaret(controller, text.indexOf("Frame:"))
        assertEquals("select ${model.inner("Frame")}", view.selects.last())
        assertEquals(2, view.selects.size)
    }

    fun `test the caret outside every state selects nothing`() {
        val (controller, view) = open()
        val model = last(view).model
        moveCaret(controller, text.indexOf("Type: Choice"))
        moveCaret(controller, text.indexOf("StartAt"))
        assertEquals("select null", view.selects.last())
        moveCaret(controller, text.indexOf("Type: Choice"))
        moveCaret(controller, text.indexOf("Film archive"))
        assertEquals(listOf("select ${model.id("Sort")}", "select null", "select ${model.id("Sort")}", "select null"), view.selects)
    }

    fun `test a click on a state moves the caret to its key`() {
        val (_, view) = open()
        val model = last(view).model
        val id = model.id("Scan")
        view.onSelect(id)
        assertEquals(model.offsets.getValue(id), caret)
        assertTrue(text.startsWith("Scan", caret))
        assertEquals(emptyList<String>(), view.selects)
    }

    fun `test a click on a path moves the caret to its line and keeps the selection`() {
        val (controller, view) = open()
        val model = last(view).model
        val edge = model.edgeFrom("Sort")
        view.onSelect(edge.id)
        assertEquals(model.offsets.getValue(edge.id), caret)
        assertEquals(emptyList<String>(), view.selects)
        controller.chosenMode = LayoutMode.COMPACT
        relayout(controller)
        assertEquals(edge.id, last(view).selected)
        assertEquals(emptyList<String>(), view.selects)
    }

    fun `test a link in the details moves the caret and selects the state there`() {
        val (controller, view) = open()
        val model = last(view).model
        val target = text.indexOf("Type: Succeed")
        view.onNavigate(target)
        assertEquals(target, caret)
        controller.caretMovedNow()
        assertEquals("select ${model.id("Done")}", view.selects.single())
    }

    fun `test a new diagram after an edit selects the state at the caret`() {
        val (controller, view) = open(at = "Resource")
        assertEquals(last(view).model.id("Scan"), last(view).selected)
        editor.caretModel.moveToOffset(text.indexOf("Type: Succeed"))
        replace("Film archive intake", "Film archive intake, revised")
        PlatformTestUtil.waitForFuture(controller.refresh())
        assertEquals(2, view.shows.size)
        assertEquals(last(view).model.id("Done"), last(view).selected)
    }

    fun `test a selected path survives a layout switch and a box toggle, and not an edit`() {
        val (controller, view) = open()
        val edge = last(view).model.edgeFrom("Sort")
        view.onSelect(edge.id)
        controller.chosenMode = LayoutMode.COMPACT
        relayout(controller)
        assertEquals(edge.id, last(view).selected)
        view.onToggleBox("Each")
        relayout(controller)
        assertEquals(edge.id, last(view).selected)

        replace("Type: Fail", "Type: Fail\n    Cause: none")
        PlatformTestUtil.waitForFuture(controller.refresh())
        val shown = last(view)
        assertEquals(shown.model.stateAt(caret), shown.selected)
        assertFalse(edge.id == shown.selected)
    }

    fun `test a caret move during an edit selects nothing until the new diagram`() {
        val (controller, view) = open(at = "Type: Succeed")
        replace("  Scan:", "  Early:\n    Type: Pass\n    End: true\n  Scan:")
        moveCaret(controller, text.indexOf("Type: Succeed") + 1)
        assertEquals(emptyList<String>(), view.selects)
        PlatformTestUtil.waitForFuture(controller.refresh())
        val shown = last(view)
        assertEquals(shown.model.id("Done"), shown.selected)
        assertNotNull(shown.model.root!!.states.firstOrNull { it.name == "Early" })
    }

    fun `test a click on the background clears the selection and leaves the caret`() {
        val (controller, view) = open()
        val id = last(view).model.id("Scan")
        view.onSelect(id)
        val at = caret
        view.onSelect(null)
        assertEquals(at, caret)
        controller.chosenMode = LayoutMode.COMPACT
        relayout(controller)
        assertNull(last(view).selected)
    }

    fun `test an unknown ID and an offset beyond the file do no harm`() {
        val (controller, view) = open(at = "Resource")
        val before = caret
        val selected = last(view).selected
        view.onSelect("no such state")
        assertEquals(before, caret)
        controller.chosenMode = LayoutMode.COMPACT
        relayout(controller)
        assertEquals(selected, last(view).selected)

        view.onNavigate(text.length + 1000)
        assertEquals(text.length, caret)
        view.onNavigate(-5)
        assertEquals(0, caret)
        controller.caretMovedNow()
        assertTrue(ApplicationManager.getApplication().isDispatchThread)
        assertFalse(editor.contentComponent.hasFocus())
    }
}
