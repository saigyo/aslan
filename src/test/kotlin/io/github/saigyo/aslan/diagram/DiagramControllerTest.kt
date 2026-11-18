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
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import java.util.Collections
import java.util.EnumSet
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
        override var onToggleBox: (statePath: String) -> Unit = {}
        override var onLayoutMode: (LayoutMode) -> Unit = {}
        override var onOpenCode: (CodeLink) -> Unit = {}

        override val measure = TextMeasure { text, _, _ ->
            val application = ApplicationManager.getApplication()
            measured += Measured(text, Thread.currentThread().name, application.isDispatchThread, application.isReadAccessAllowed)
            hold?.let {
                held.countDown()
                it.await(30, TimeUnit.SECONDS)
            }
            stall?.enter()
            fault?.let { throw it() }
            6.0 * text.length
        }

        @Volatile
        var stall: Stall? = null

        /** While set, every measurement throws what it makes, so a layout fails. */
        @Volatile
        var fault: (() -> Throwable)? = null

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

    /**
     * Holds the first layouts that measure, one for each value of [heeds], each on its own thread, in the first
     * measurement on that thread: the n-th layout to arrive waits for the n-th release. Where its value of [heeds] is
     * true, the wait also ends once [stopped] says that the layout on its thread has been told to stop, as a layout that
     * asks its flag would; else only the release ends it. An interrupt does not end the wait; the thread is recorded in
     * [interrupted].
     */
    private class Stall(private vararg val heeds: Boolean) {
        val releases = List(heeds.size) { CountDownLatch(1) }
        val threads: MutableList<Thread> = Collections.synchronizedList(ArrayList())
        val interrupted: MutableList<Thread> = Collections.synchronizedList(ArrayList())

        /** Whether the controller has told the layout on a thread to stop; [limited] sets it. */
        @Volatile
        var stopped: (Thread) -> Boolean = { false }

        fun enter() {
            val current = Thread.currentThread()
            val n = synchronized(threads) {
                if (threads.any { it === current } || threads.size >= releases.size) return
                threads += current
                threads.size - 1
            }
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (System.nanoTime() < end) {
                try {
                    if (releases[n].await(POLL_MS, TimeUnit.MILLISECONDS)) return
                } catch (_: InterruptedException) {
                    interrupted += current
                }
                if (heeds[n] && stopped(current)) return
            }
        }

        fun releaseAll() = releases.forEach { it.countDown() }
    }

    private fun waitFor(message: String, condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching(message, condition, 30)

    private fun Recorder.staleCount() = calls.count { it === Recorder.Stale }

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

    /**
     * Lets every layout fail with what [fault] makes, twice, catching the errors and warnings logged for the failures:
     * each failure marks the diagram out of date, the first is logged as an error and the second only as a warning, and
     * neither message holds text of the file. Then the fault goes, and an edit is shown again.
     */
    private fun assertFailureMarked(fault: () -> Throwable) {
        val (controller, view) = open(archive)
        val errors: MutableList<String> = Collections.synchronizedList(ArrayList())
        val warnings: MutableList<String> = Collections.synchronizedList(ArrayList())
        val token = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<String>, t: Throwable?): Set<Action> {
                if ("Cannot lay out" !in message) return super.processError(category, message, details, t)
                errors += message
                return EnumSet.noneOf(Action::class.java)
            }

            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                if ("Cannot lay out" !in message) return super.processWarn(category, message, t)
                warnings += message
                return false
            }
        })
        try {
            view.calls.clear()
            view.fault = fault
            refresh(controller)
            waitFor("the first failure did not mark the diagram") { view.staleCount() == 1 }
            assertEquals(1, errors.size)
            assertEquals(0, warnings.size)
            refresh(controller)
            waitFor("the second failure did not mark the diagram") { view.staleCount() == 2 }
            assertEquals("only the first failure is an error", 1, errors.size)
            assertEquals(1, warnings.size)
            for (m in errors + warnings) assertTrue(m, "test.asl.yaml" in m && "Sort" !in m && "Frame" !in m)
            assertTrue(view.shows.isEmpty())

            view.fault = null
            replace("  Failed:", rewind + "  Failed:")
            refresh(controller)
            assertTrue(view.calls.last() is Recorder.Shown)
            assertTrue(view.last.model.names().contains("Rewind"))
        } finally {
            view.fault = null
            token.finish()
        }
    }

    fun `test a layout that fails with an exception marks the diagram out of date`() {
        assertFailureMarked { IllegalStateException("a fault of the test") }
    }

    fun `test a layout that fails with an error marks the diagram out of date`() {
        // as when a later IDE no longer gives the layout library a class it needs
        assertFailureMarked { NoClassDefFoundError("com/google/common/base/Preconditions") }
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

    fun `test collapsing one of two boxes of the same name leaves the other open`() {
        val (controller, view) = open(twins)
        view.onToggleBox(secondEach)
        PlatformTestUtil.waitForFuture(controller.latest)
        val boxes = view.last.layout.nodes.filter { it.collapsible && it.state!!.name == "Each" }
        assertEquals(listOf(firstEach, secondEach), boxes.map { it.state!!.path })
        assertEquals(listOf(false, true), boxes.map { it.collapsed })
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

    /**
     * Runs [block] with the short time limit and [stall] in place, catching the warnings of abandoned layouts, and checks
     * that each abandoned layout, which marks the diagram out of date, logged one warning with the file's name and the
     * limit, and that no layout thread was interrupted. The stall is released at the end in any case.
     */
    private fun limited(controller: DiagramController, view: Recorder, stall: Stall, block: () -> Unit) {
        controller.timeLimitMs = LIMIT_MS
        stall.stopped = controller::cancelledOn
        view.stall = stall
        val warnings: MutableList<String> = Collections.synchronizedList(ArrayList())
        val token = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                if ("was left behind" !in message) return super.processWarn(category, message, t)
                warnings += message
                return false
            }
        })
        try {
            view.calls.clear()
            block()
            // the warning is logged before the mark is posted
            assertEquals("one warning per abandoned layout", view.staleCount(), warnings.size)
            for (w in warnings) assertTrue(w, "test.asl.yaml" in w && "$LIMIT_MS ms" in w)
            assertEquals("a layout thread was interrupted", emptyList<Thread>(), stall.interrupted.toList())
        } finally {
            stall.releaseAll()
            token.finish()
        }
    }

    fun `test a layout over the time limit is abandoned, and the next one does not wait for it`() {
        val (controller, view) = open(archive)
        val stall = Stall(false)
        limited(controller, view, stall) {
            PlatformTestUtil.waitForFuture(controller.refresh())
            waitFor("the diagram is not marked out of date") { view.staleCount() == 1 }
            assertTrue(view.shows.isEmpty())
            val stuck = stall.threads.single()

            replace("  Failed:", rewind + "  Failed:")
            delayed.last().run()
            PlatformTestUtil.waitForFuture(controller.latest)
            assertTrue(view.last.model.names().contains("Rewind"))
            assertTrue("the first layout is still held", stuck.isAlive)

            stall.releaseAll()
            waitFor("the abandoned layout did not end") { !stuck.isAlive }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals("the abandoned result is not shown", 1, view.shows.size)
        }
    }

    fun `test a layout over the time limit ends when its flag is set`() {
        val (controller, view) = open(archive)
        val stall = Stall(true)
        limited(controller, view, stall) {
            PlatformTestUtil.waitForFuture(controller.refresh())
            waitFor("the diagram is not marked out of date") { view.staleCount() == 1 }
            val stuck = stall.threads.single()
            waitFor("the abandoned layout did not end") { !stuck.isAlive }
            assertEquals("it ended without a release", 1L, stall.releases.single().count)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(view.shows.isEmpty())
        }
    }

    fun `test while two abandoned layouts run, no further layout starts`() {
        val (controller, view) = open(archive)
        val stall = Stall(false, false)
        limited(controller, view, stall) {
            controller.refresh()
            waitFor("the first layout was not abandoned") { view.staleCount() == 1 }
            controller.refresh()
            waitFor("the second layout was not abandoned") { view.staleCount() == 2 }
            assertEquals(2, stall.threads.size)

            val measured = view.measured.size
            PlatformTestUtil.waitForFuture(controller.refresh())
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals("a third layout started", measured, view.measured.size)
            assertTrue(view.shows.isEmpty())

            // an edit while the bound holds is not lost: once one abandoned layout ends, the file is laid out again
            replace("  Failed:", rewind + "  Failed:")
            delayed.last().run()
            PlatformTestUtil.waitForFuture(controller.latest)
            assertTrue(view.shows.isEmpty())
            stall.releases[0].countDown()
            waitFor("the edit was not shown after the bound lifted") { view.shows.isNotEmpty() }
            assertTrue(view.shows.single().model.names().contains("Rewind"))
            assertEquals(2, view.staleCount())
        }
    }

    fun `test disposal sets the flag of every running layout`() {
        val parent = Disposer.newDisposable(testRootDisposable, "controller")
        val (controller, view) = control(file(archive), parent)
        // the first layout is abandoned and does not heed its flag; the second runs within the limit and heeds it
        val stall = Stall(false, true)
        limited(controller, view, stall) {
            controller.refresh()
            waitFor("the first layout was not abandoned") { view.staleCount() == 1 }
            val abandoned = stall.threads.single()
            assertTrue(controller.cancelledOn(abandoned))
            controller.timeLimitMs = 600_000
            controller.refresh()
            waitFor("the second layout did not start") { stall.threads.size == 2 }
            val running = stall.threads[1]
            assertFalse(controller.cancelledOn(running))

            Disposer.dispose(parent)
            waitFor("the running layout did not end at disposal") { !running.isAlive }
            assertEquals("it ended without a release", 1L, stall.releases[1].count)
            assertTrue(abandoned.isAlive)
            assertTrue(controller.cancelledOn(abandoned))
            stall.releaseAll()
            waitFor("the abandoned layout did not end") { !abandoned.isAlive }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(view.shows.isEmpty())
        }
    }

    fun `test disposal while a layout is held leaves no thread behind`() {
        val parent = Disposer.newDisposable(testRootDisposable, "controller")
        val (controller, view) = control(file(archive), parent)
        val stall = Stall(false)
        view.stall = stall
        try {
            view.calls.clear()
            val pending = controller.refresh()
            waitFor("the layout did not start") { stall.threads.size == 1 }
            Disposer.dispose(parent)
            stall.releaseAll()
            PlatformTestUtil.waitForFuture(pending)
            val stuck = stall.threads.single()
            waitFor("the layout thread is still running") { !stuck.isAlive }
            waitFor("a layout thread is still running") {
                Thread.getAllStackTraces().keys.none { it.name == "Aslan diagram layout" && it.isAlive }
            }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(view.calls.isEmpty())
        } finally {
            stall.releaseAll()
        }
    }

    private companion object {
        /**
         * The time limit of the tests of the limit. A layout of the small definition takes a few milliseconds, so a layout
         * that must succeed stays well inside it even on a busy machine; each abandonment costs the test this long.
         */
        const val LIMIT_MS = 2_000L

        /** How often a held layout looks at its flag. */
        const val POLL_MS = 10L
    }
}
