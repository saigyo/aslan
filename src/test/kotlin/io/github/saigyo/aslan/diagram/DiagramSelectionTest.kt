package io.github.saigyo.aslan.diagram

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import java.awt.event.HierarchyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
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
        override var onToggleBox: (statePath: String) -> Unit = {}
        override var onLayoutMode: (LayoutMode) -> Unit = {}
        override var onOpenCode: (CodeLink) -> Unit = {}
        /** While set, the first measurement waits for it, so a layout can be held on its thread. */
        @Volatile
        var hold: CountDownLatch? = null
        val held = CountDownLatch(1)

        override val measure = TextMeasure { text, _, _ ->
            hold?.let {
                held.countDown()
                it.await(30, TimeUnit.SECONDS)
            }
            6.0 * text.length
        }

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

    /** The delays the controller asked for after a caret move, in order; none runs until a test runs it. */
    private val caretTasks = ArrayList<Runnable>()

    /** Records the task itself, so running it later also stands for a task that was posted before a cancel. */
    private fun caretDelay(task: Runnable): Future<*> {
        caretTasks += task
        return FutureTask(task, null)
    }

    /** Opens [text] with the caret at the first occurrence of [at], when given; the first diagram has arrived. */
    private fun open(source: String = archive, at: String? = null): Pair<DiagramController, Recorder> {
        val psi: PsiFile = file(source)
        if (at != null) editor.caretModel.moveToOffset(text.indexOf(at))
        val view = Recorder()
        val controller = DiagramController(project, psi.virtualFile, editor, view, testRootDisposable)
        controller.delay = ::noDelay
        controller.caretDelay = ::caretDelay
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
        moveCaret(controller, text.indexOf(".format") + 2)   // inside the JSONPath string of the state
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
        caretTasks.clear()
        view.onSelect(id)
        assertEquals(model.offsets.getValue(id), caret)
        assertTrue(text.startsWith("Scan", caret))
        assertTrue("the click's own caret move schedules no selection", caretTasks.isEmpty())
        assertEquals(emptyList<String>(), view.selects)
    }

    fun `test a click on a path moves the caret to its line and keeps the selection`() {
        val (controller, view) = open()
        val model = last(view).model
        val edge = model.edgeFrom("Sort")
        caretTasks.clear()
        view.onSelect(edge.id)
        assertEquals(model.offsets.getValue(edge.id), caret)
        assertTrue("the click's own caret move schedules no selection", caretTasks.isEmpty())
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
        caretTasks.clear()
        view.onNavigate(target)
        assertEquals(target, caret)
        caretTasks.single().run()
        assertEquals("select ${model.id("Done")}", view.selects.single())
    }

    fun `test a link in the details of a path selects that state`() {
        val (_, view) = open()
        val model = last(view).model
        val edge = model.edgeFrom("Sort")
        view.onSelect(edge.id)
        val to = model.state(edge.to)!!
        caretTasks.clear()
        view.onNavigate(to.offset)
        assertEquals(to.offset, caret)
        caretTasks.single().run()
        assertEquals("select ${to.id}", view.selects.single())
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

    fun `test a delayed caret selection that was posted before a click does not override it`() {
        val (controller, view) = open()
        val edge = last(view).model.edgeFrom("Sort")
        caretTasks.clear()
        editor.caretModel.moveToOffset(text.indexOf("Type: Succeed"))
        val posted = caretTasks.single()
        view.onSelect(edge.id)
        posted.run()
        assertEquals(emptyList<String>(), view.selects)
        controller.chosenMode = LayoutMode.COMPACT
        relayout(controller)
        assertEquals(edge.id, last(view).selected)
    }

    fun `test a click on an outdated diagram does not move the caret`() {
        val (_, view) = open(at = "Resource")
        val id = last(view).model.id("Done")
        replace("Film archive intake", "Film archive intake, revised and longer")
        val before = caret
        view.onSelect(id)
        assertEquals(before, caret)
    }

    fun `test a link in an outdated diagram does not move the caret`() {
        val (_, view) = open(at = "Resource")
        val target = text.indexOf("Type: Succeed")
        replace("Film archive intake", "Film archive intake, revised and longer")
        val before = caret
        view.onNavigate(target)
        assertEquals(before, caret)
    }

    fun `test a click and a link leave one caret and no selection`() {
        val (_, view) = open()
        val model = last(view).model
        fun spread() {
            editor.caretModel.addCaret(editor.offsetToVisualPosition(text.indexOf("Failed:")))
            editor.selectionModel.setSelection(text.indexOf("Failed:"), text.indexOf("Failed:") + 6)
            editor.caretModel.allCarets.first().setSelection(0, 7)
            assertEquals(2, editor.caretModel.caretCount)
            assertTrue(editor.caretModel.allCarets.all { it.hasSelection() })
        }

        spread()
        val id = model.id("Scan")
        caretTasks.clear()
        view.onSelect(id)
        assertEquals(1, editor.caretModel.caretCount)
        assertFalse(editor.selectionModel.hasSelection())
        assertEquals(model.offsets.getValue(id), caret)
        assertTrue("the click's own caret move schedules no selection", caretTasks.isEmpty())

        spread()
        val target = text.indexOf("Type: Succeed")
        view.onNavigate(target)
        assertEquals(1, editor.caretModel.caretCount)
        assertFalse(editor.selectionModel.hasSelection())
        assertEquals(target, caret)
    }

    /** The offsets the primary caret moves to from now on, in order. */
    private fun caretMoves(): MutableList<Int> {
        val moves = ArrayList<Int>()
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                moves += editor.logicalPositionToOffset(event.newPosition)
            }
        }, testRootDisposable)
        return moves
    }

    fun `test the caret moved while the editor was hidden moves again when it shows`() {
        val (controller, view) = open()
        val model = last(view).model
        val id = model.id("Scan")
        controller.editorShowing = { false }
        view.onSelect(id)
        val moves = caretMoves()
        caretTasks.clear()
        controller.editorShowing = { true }
        controller.editorShown()
        val at = model.offsets.getValue(id)
        assertEquals(at, caret)
        assertEquals("the platform's highlighting of the name under the caret follows a caret move", at, moves.lastOrNull())
        assertTrue("the moves schedule no selection", caretTasks.isEmpty())
        assertEquals(emptyList<String>(), view.selects)

        moves.clear()
        controller.editorShown()
        assertEquals("only once", emptyList<Int>(), moves)
    }

    fun `test the caret moved by a link while the editor was hidden moves again when it shows`() {
        val (controller, view) = open()
        val target = text.indexOf("Type: Succeed")
        controller.editorShowing = { false }
        view.onNavigate(target)
        val moves = caretMoves()
        controller.editorShowing = { true }
        controller.editorShown()
        assertEquals(target, caret)
        assertEquals(target, moves.lastOrNull())
    }

    fun `test the editor coming on screen moves the caret again`() {
        val (controller, view) = open()
        controller.editorShowing = { false }
        view.onSelect(last(view).model.id("Scan"))
        val moves = caretMoves()
        controller.editorShowing = { true }
        val component = editor.contentComponent
        component.dispatchEvent(HierarchyEvent(component, HierarchyEvent.HIERARCHY_CHANGED, component, component.parent, HierarchyEvent.SHOWING_CHANGED.toLong()))
        assertEquals(caret, moves.lastOrNull())
    }

    fun `test the caret moved while the editor was shown does not move again`() {
        val (controller, view) = open()
        controller.editorShowing = { true }
        view.onSelect(last(view).model.id("Scan"))
        val moves = caretMoves()
        controller.editorShown()
        assertEquals(emptyList<Int>(), moves)
    }

    fun `test the first show of a new model after a toggle during its layout selects by the new model`() {
        val (controller, view) = open(at = "Resource")
        val old = last(view)
        view.onSelect(old.model.id("Scan"))
        replace("  Scan:", "  Early:\n    Type: Pass\n    End: true\n  Scan:")
        editor.caretModel.moveToOffset(text.indexOf("Type: Succeed"))
        view.calls.clear()
        val hold = CountDownLatch(1)
        view.hold = hold
        val refresh = controller.refresh()
        PlatformTestUtil.waitWithEventsDispatching("the layout did not start", { view.held.count == 0L }, 30)
        view.onToggleBox("Each")
        view.hold = null
        hold.countDown()
        PlatformTestUtil.waitForFuture(refresh)
        relayout(controller)
        val shown = view.shows.first()
        assertNotNull(shown.model.root!!.states.firstOrNull { it.name == "Early" })
        assertEquals(shown.model.id("Done"), shown.selected)
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

    private val commented = """
        StartAt: Scan
        States:
          Scan:
            Type: Pass
            # Input: lambdas/src/contracts.json#IntakeInput
            End: true
    """

    fun `test a link to code opens the file at its type`() {
        myFixture.addFileToProject("lambdas/src/contracts.json", "{\"Other\": {}, \"IntakeInput\": {}}")
        val (_, view) = open(commented)
        val model = last(view).model
        val link = model.details.getValue(model.id("Scan")).first { it.label == "Notes" }.items.single().links.single()
        val asl = editor.virtualFile
        view.onOpenCode(link)
        val manager = FileEditorManager.getInstance(project)
        val opened = manager.selectedTextEditor!!
        assertEquals("contracts.json", opened.virtualFile!!.name)
        assertEquals(opened.document.text.indexOf("\"IntakeInput\""), opened.caretModel.offset)
        assertTrue(asl in manager.openFiles)
    }

    fun `test a link to code that no longer resolves does nothing`() {
        val target = myFixture.addFileToProject("lambdas/src/contracts.json", "{\"IntakeInput\": {}}")
        val (_, view) = open(commented)
        val model = last(view).model
        val link = model.details.getValue(model.id("Scan")).first { it.label == "Notes" }.items.single().links.single()
        WriteCommandAction.runWriteCommandAction(project) { target.virtualFile.delete(this) }
        val before = FileEditorManager.getInstance(project).openFiles.toList()
        view.onOpenCode(link)
        assertEquals(before, FileEditorManager.getInstance(project).openFiles.toList())
    }
}
