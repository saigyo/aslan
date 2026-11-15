package io.github.saigyo.aslan.diagram

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JPanel

class DiagramControllerTest : DiagramTestCase() {
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

    /** A [DiagramView] that records what it is given, and a [TextMeasure] that records where the layout measures. */
    private class Recorder : DiagramView {
        class Shown(val model: DiagramModel, val layout: DiagramLayout, val mode: LayoutMode, val selected: String?, val uiThread: Boolean)
        class Measured(val text: String, val thread: String, val uiThread: Boolean, val readAccess: Boolean)

        object Stale

        val calls = ArrayList<Any>()
        val measured: MutableList<Measured> = Collections.synchronizedList(ArrayList())

        /** While set, the first measurement waits for it, so a layout can be held on its thread. */
        @Volatile
        var hold: CountDownLatch? = null
        val held = CountDownLatch(1)

        val shows: List<Shown> get() = calls.filterIsInstance<Shown>()
        val last: Shown get() = shows.last()

        override val component: JComponent = JPanel()
        override var onSelect: (id: String?) -> Unit = {}
        override var onNavigate: (offset: Int) -> Unit = {}
        override var onToggleBox: (stateName: String) -> Unit = {}
        override var onLayoutMode: (LayoutMode) -> Unit = {}

        override val measure = TextMeasure { text, _, _ ->
            val application = ApplicationManager.getApplication()
            measured += Measured(text, Thread.currentThread().name, application.isDispatchThread, application.isReadAccessAllowed)
            hold?.let {
                held.countDown()
                it.await(30, TimeUnit.SECONDS)
            }
            6.0 * text.length
        }

        override fun show(model: DiagramModel, layout: DiagramLayout, mode: LayoutMode, selected: String?) {
            calls += Shown(model, layout, mode, selected, ApplicationManager.getApplication().isDispatchThread)
        }

        override fun select(id: String?) {
            calls += "select $id"
        }

        override fun setStale() {
            assertTrue(ApplicationManager.getApplication().isDispatchThread)
            calls += Stale
        }
    }

    /** The delays the controller asked for, in order; none runs until a test runs it. */
    private val delayed = ArrayList<FutureTask<*>>()

    private fun fakeDelay(task: Runnable): Future<*> = FutureTask(task, null).also { delayed += it }

    /** A controller for [psi] in the fixture's editor, whose first diagram has arrived; its delays wait for the test. */
    private fun control(psi: PsiFile, parent: Disposable = testRootDisposable, realDelay: Boolean = false): Pair<DiagramController, Recorder> {
        val view = Recorder()
        val controller = DiagramController(project, psi.virtualFile, myFixture.editor, view, parent)
        if (!realDelay) controller.delay = ::fakeDelay
        PlatformTestUtil.waitForFuture(controller.latest)
        return controller to view
    }

    private fun open(text: String, name: String = "test.asl.yaml") = control(file(text, name))

    private fun edit(change: (Document) -> Unit) {
        WriteCommandAction.runWriteCommandAction(project) { change(myFixture.editor.document) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun replace(old: String, new: String) = edit { d ->
        val at = d.text.indexOf(old)
        assertTrue(old, at >= 0)
        d.replaceString(at, at + old.length, new)
    }

    private fun refresh(controller: DiagramController) {
        PlatformTestUtil.waitForFuture(controller.refresh())
    }

    private fun DiagramModel.names(): List<String> = root!!.states.map { it.name }

    private fun DiagramModel.id(name: String): String = root!!.states.single { it.name == name }.id

    private fun chain(n: Int): String = buildString {
        append("StartAt: S1\nStates:\n")
        for (i in 1..n) {
            append("  S$i:\n    Type: Pass\n")
            append(if (i < n) "    Next: S${i + 1}\n" else "    End: true\n")
        }
    }

    private val rewind = "  Rewind:\n    Type: Pass\n    End: true\n"

    private fun large(): PsiFile = myFixture.configureByFile("clean/film-archive-intake.asl.yaml")

    private fun selected(action: ToggleAction) = action.isSelected(TestActionEvent.createTestEvent(action))

    fun `test opening shows the diagram`() {
        val (_, view) = open(archive)
        val shown = view.shows.single()
        assertTrue(shown.uiThread)
        assertEquals(listOf("Sort", "Scan", "Each", "Both", "Done", "Failed"), shown.model.names())
        assertEquals(LayoutMode.LOOSE, shown.mode)
        assertNull(shown.selected)
        assertEquals(shown.model.id("Sort"), shown.layout.nodes.first().id)
        assertFalse(Recorder.Stale in view.calls)
    }

    fun `test an edit shows a new diagram`() {
        val (controller, view) = open(archive)
        replace("  Failed:", rewind + "  Failed:")
        assertEquals("one delayed refresh per change", 1, delayed.size)
        refresh(controller)
        assertEquals(2, view.shows.size)
        assertTrue(view.last.model.names().contains("Rewind"))
        assertTrue(view.last.layout.nodes.any { it.state?.name == "Rewind" })

        // with the real delay of 300 ms, the refresh follows by itself
        val (_, real) = control(file(archive, "real.asl.yaml"), realDelay = true)
        replace("  Failed:", rewind + "  Failed:")
        PlatformTestUtil.waitWithEventsDispatching("no diagram after the delay", { real.shows.size == 2 }, 30)
        assertTrue(real.last.model.names().contains("Rewind"))
    }

    fun `test an unreadable file marks the diagram out of date`() {
        // a syntax error in a JSON file
        val json = """
            {
              "StartAt": "Scan",
              "States": {
                "Scan": { "Type": "Pass", "End": true }
              }
            }
        """
        val (jsonController, jsonView) = open(json, "test.asl.json")
        val good = jsonView.last
        edit { d -> d.deleteString(d.textLength - 1, d.textLength) }
        refresh(jsonController)
        assertSame(Recorder.Stale, jsonView.calls.last())
        assertSame(good, jsonView.last)
        edit { d -> d.insertString(d.textLength, "}") }
        refresh(jsonController)
        assertNotSame(good, jsonView.last)
        assertTrue(jsonView.calls.last() is Recorder.Shown)

        // States written as a placeholder in a YAML file (Review Focus 1)
        val states = "States:\n  Scan:\n    Type: Pass\n    End: true"
        val (controller, view) = open("StartAt: Scan\n$states\n")
        val before = view.last
        replace(states, "States: §{States}".replace('§', '$'))
        refresh(controller)
        assertSame(Recorder.Stale, view.calls.last())
        assertSame(before, view.last)
        replace("States: §{States}".replace('§', '$'), states)
        refresh(controller)
        assertTrue(view.calls.last() is Recorder.Shown)
        assertEquals(listOf("Scan"), view.last.model.names())

        // a state without Type and a state that is not a mapping are problems the checks report; the diagram follows them
        replace(states, "States:\n  Scan:\n    End: true\n  Odd: 3")
        refresh(controller)
        assertTrue(view.calls.last() is Recorder.Shown)
        assertTrue(view.last.model.names().contains("Scan"))
        assertEquals(1, view.calls.count { it === Recorder.Stale })
    }

    fun `test a file that was never readable shows an empty diagram`() {
        val (controller, view) = open("StartAt: Scan\n")
        val shown = view.shows.single()
        assertNull(shown.model.root)
        assertTrue(shown.layout.nodes.isEmpty())
        assertFalse(Recorder.Stale in view.calls)
        refresh(controller)
        assertEquals(1, view.calls.size)
    }

    fun `test a small machine opens loose and a large one compact`() {
        val (_, small) = open(chain(25))
        assertEquals(25, small.last.model.stateCount)
        assertEquals(LayoutMode.LOOSE, small.last.mode)
        val (_, larger) = open(chain(26), "larger.asl.yaml")
        assertEquals(26, larger.last.model.stateCount)
        assertEquals(LayoutMode.COMPACT, larger.last.mode)
        val (_, synthetic) = control(large())
        assertEquals(LayoutMode.COMPACT, synthetic.last.mode)
    }

    fun `test the chosen layout overrides the size rule and stays`() {
        val file = large().virtualFile.also { files += it }
        fun split(): TextEditorWithPreview {
            val editor = AslSplitEditorProvider().createEditor(project, file) as TextEditorWithPreview
            Disposer.register(testRootDisposable, editor)
            val preview = editor.previewEditor as DiagramPreviewEditor
            PlatformTestUtil.waitForFuture(preview.controller!!.latest)
            return editor
        }

        val first = split()
        val preview = first.previewEditor as DiagramPreviewEditor
        val controller = preview.controller!!
        assertNull(controller.chosenMode)
        assertTrue(selected(preview.pane.layoutSwitch(LayoutMode.COMPACT)))
        val loose = preview.pane.layoutSwitch(LayoutMode.LOOSE)
        loose.actionPerformed(TestActionEvent.createTestEvent(loose))
        PlatformTestUtil.waitForFuture(controller.latest)
        assertTrue(selected(loose))
        assertEquals(LayoutMode.LOOSE, controller.chosenMode)
        assertEquals(LayoutMode.LOOSE, preview.layoutMode)

        // a later edit keeps it
        edit { d -> d.insertString(0, "# rescanned\n") }
        PlatformTestUtil.waitForFuture(controller.refresh())
        assertTrue(selected(loose))

        // and so does a new editor for the file
        Disposer.dispose(first)
        val second = split().previewEditor as DiagramPreviewEditor
        assertEquals(LayoutMode.LOOSE, second.controller!!.chosenMode)
        assertTrue(selected(second.pane.layoutSwitch(LayoutMode.LOOSE)))
    }

    fun `test collapsing a box lays out again without reading the file`() {
        val (controller, view) = open(archive)
        val model = view.last.model
        val each = model.id("Each")
        assertEquals(LaidNode.Kind.GROUP, view.last.layout.nodes.single { it.id == each }.kind)

        view.onToggleBox("Each")
        PlatformTestUtil.waitForFuture(controller.latest)
        assertSame(model, view.last.model)
        val box = view.last.layout.nodes.single { it.id == each }
        assertEquals(LaidNode.Kind.STATE, box.kind)
        assertTrue(box.collapsed)
        assertFalse(view.last.layout.nodes.any { it.state?.name == "Frame" })

        view.onToggleBox("Each")
        PlatformTestUtil.waitForFuture(controller.latest)
        assertSame(model, view.last.model)
        assertEquals(LaidNode.Kind.GROUP, view.last.layout.nodes.single { it.id == each }.kind)
        assertEquals(3, view.shows.size)
    }

    fun `test the layout runs off the UI thread and outside a read action`() {
        val (controller, view) = open(archive)
        view.onToggleBox("Each")
        PlatformTestUtil.waitForFuture(controller.latest)
        view.onLayoutMode(LayoutMode.COMPACT)
        PlatformTestUtil.waitForFuture(controller.latest)
        edit { d -> d.insertString(0, "# rescanned\n") }
        refresh(controller)
        assertEquals(LayoutMode.COMPACT, view.last.mode)
        assertEquals(4, view.shows.size)
        assertTrue(view.shows.all { it.uiThread })
        val measured = view.measured.toList()
        assertTrue(measured.isNotEmpty())
        for (m in measured) {
            assertFalse(m.text, m.uiThread)
            assertFalse(m.text, m.readAccess)
            assertEquals("Aslan diagram layout", m.thread)
        }
    }

    fun `test a burst of edits leads to one layout, and an outdated result is dropped`() {
        val (controller, view) = open(archive)
        view.calls.clear()
        view.measured.clear()

        // five changes: each cancels the delay before it, and only the last delay is left to run
        repeat(5) { i -> edit { d -> d.insertString(0, "# take $i\n") } }
        assertEquals(5, delayed.size)
        assertTrue(delayed.dropLast(1).all { it.isCancelled })
        assertFalse(delayed.last().isCancelled)
        assertTrue(view.calls.isEmpty())
        delayed.last().run()
        PlatformTestUtil.waitForFuture(controller.latest)
        assertEquals(1, view.shows.size)
        assertEquals("one layout", 1, view.measured.count { it.text == "Default" })

        // a change while a layout runs: its result is dropped, and the refresh the change scheduled replaces it
        view.calls.clear()
        val hold = CountDownLatch(1)
        view.hold = hold
        val outdated = controller.refresh()
        PlatformTestUtil.waitWithEventsDispatching("the layout did not start", { view.held.count == 0L }, 30)
        replace("  Failed:", rewind + "  Failed:")
        view.hold = null
        hold.countDown()
        PlatformTestUtil.waitForFuture(outdated)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(view.calls.isEmpty())
        delayed.last().run()
        PlatformTestUtil.waitForFuture(controller.latest)
        assertTrue(view.shows.single().model.names().contains("Rewind"))
    }

    fun `test nothing is shown after disposal`() {
        val parent = Disposer.newDisposable(testRootDisposable, "controller")
        val (controller, view) = control(file(archive), parent)
        view.calls.clear()
        val hold = CountDownLatch(1)
        view.hold = hold
        val pending = controller.refresh()
        PlatformTestUtil.waitWithEventsDispatching("the layout did not start", { view.held.count == 0L }, 30)
        Disposer.dispose(parent)
        view.hold = null
        hold.countDown()
        PlatformTestUtil.waitForFuture(pending)

        // the callbacks, a refresh and an edit after disposal do nothing
        view.onToggleBox("Each")
        view.onLayoutMode(LayoutMode.COMPACT)
        PlatformTestUtil.waitForFuture(controller.refresh())
        replace("  Failed:", rewind + "  Failed:")
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(view.calls.isEmpty())
        assertTrue(delayed.isEmpty())
        assertNull(controller.chosenMode)
        PlatformTestUtil.waitWithEventsDispatching(
            "the layout thread is still running",
            { Thread.getAllStackTraces().keys.none { it.name == "Aslan diagram layout" && it.isAlive } },
            30,
        )
    }
}
