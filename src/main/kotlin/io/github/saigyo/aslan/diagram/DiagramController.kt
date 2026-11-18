package io.github.saigyo.aslan.diagram

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.UIUtil
import io.github.saigyo.aslan.links.CodeLinks
import io.github.saigyo.aslan.model.AslModel
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val log = logger<DiagramController>()

/**
 * Keeps [view] in step with [file] (decision 4): after a change of the document and a delay, it builds the model in a
 * non-blocking read action, lays it out on a thread of its own, outside any read action, and hands both to the view on
 * the UI thread. A result is dropped when the document has changed since its model was built, when the layout's choices
 * have changed since it was made, when a newer result has been handed over, or once [parent] is disposed. A layout that
 * takes longer than [timeLimitMs] is told to stop and left behind, and the diagram is marked out of date; the layouts
 * after it run on a new thread. A read or a layout that fails also marks the diagram out of date.
 *
 * [chosen] is the layout chosen for the file before, and [remember] keeps a choice made with the layout switch. Create
 * and use it on the UI thread; [refresh] may be called from any thread.
 */
class DiagramController(
    private val project: Project,
    private val file: VirtualFile,
    private val editor: Editor,
    private val view: DiagramView,
    private val parent: Disposable,
    chosen: LayoutMode? = null,
    private val remember: (LayoutMode) -> Unit = {},
) {
    /** What a layout depends on besides the model; replaced as a whole, so a result can tell whether it is current. */
    private class Choices(val mode: LayoutMode?, val collapsed: Set<String>)

    /** A model and the modification stamp of the document it was built from; [model] is null for an unreadable file. */
    private class Built(val model: DiagramModel?, val stamp: Long)

    private class Result(
        val request: Long,
        val built: Built,
        val choices: Choices,
        val layout: DiagramLayout?,
        val mode: LayoutMode,
    )

    private val document = editor.document
    private val measure = view.measure

    @Volatile
    private var disposed = false

    @Volatile
    private var choices = Choices(chosen, emptySet())

    /** The number of the last request; a request is a refresh or a new layout of the last model. */
    private val requests = AtomicLong()

    /** The number of the last request whose result was handed to the view. UI thread. */
    private var handedOver = 0L

    /** The last model shown, null before the first show. UI thread. */
    private var shown: DiagramModel? = null

    /** The state or edge ID of [shown] that is selected, or null; every show is given it. UI thread. */
    private var selected: String? = null

    /** The newest model a refresh has laid out, which a new layout of the same model takes. Set on a layout thread. */
    @Volatile
    private var newest: Built? = null

    /** The modification stamp of the document that [shown] was built from. UI thread. */
    private var shownStamp = 0L

    /** Counts caret moves and clicks; a delayed selection that was posted before the latest one does nothing. UI thread. */
    private var caretMoves = 0

    /** The delayed selection after the last caret move, if it has not run. UI thread. */
    private var pendingCaret: Future<*>? = null

    /** Whether the controller itself is moving the caret, which changes no selection. UI thread. */
    private var movingCaret = false

    /** Whether the text editor is on screen; tests replace it. UI thread. */
    internal var editorShowing: () -> Boolean = { UIUtil.isShowing(editor.contentComponent) }

    /** Whether the controller moved the caret while the text editor was not on screen, as in the preview alone. UI thread. */
    private var movedWhileHidden = false

    /** The delayed refresh after the last change, if it has not run. UI thread. */
    private var pending: Future<*>? = null

    /** Guards [layoutThread], [abandoned], [running] and [droppedUnderBound], which the time limit changes from the scheduler's thread. */
    private val lock = Any()

    /**
     * The thread of the layouts, used for nothing else; it ends when idle and when [parent] is disposed. A layout over
     * the time limit is left behind on it, and a new one takes the layouts after it.
     */
    private var layoutThread: ThreadPoolExecutor = newLayoutThread()

    /** The number of layouts left behind over the time limit that have not ended yet. */
    private var abandoned = 0

    /** The layouts that run now, left behind ones included; each is told to stop at disposal. */
    private val running = HashSet<LayoutTask>()

    /** Whether a layout was dropped because [abandoned] reached its bound; a refresh follows once it falls below. */
    private var droppedUnderBound = false

    /** Whether a read or a layout has failed before; only the first failure is logged as an error. */
    private val failedBefore = AtomicBoolean()

    /** Schedules a task after the delay; tests replace it to run the delay themselves. */
    internal var delay: (Runnable) -> Future<*> = { task ->
        AppExecutorUtil.getAppScheduledExecutorService().schedule(task, DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /** Schedules the selection after a caret move; tests replace it. */
    internal var caretDelay: (Runnable) -> Future<*> = { task ->
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { ApplicationManager.getApplication().invokeLater(task, ModalityState.any()) },
            CARET_DELAY_MS, TimeUnit.MILLISECONDS,
        )
    }

    /** How long one layout may take before it is told to stop and left behind; tests set a short one. */
    @Volatile
    internal var timeLimitMs = TIME_LIMIT_MS

    /** The future of the last request, for tests. */
    @Volatile
    internal var latest: CompletableFuture<*> = CompletableFuture.completedFuture(false)
        private set

    /** The layout chosen with the layout switch; null while the size rule decides. Setting it lays out the last model again. UI thread. */
    var chosenMode: LayoutMode?
        get() = choices.mode
        set(value) {
            if (disposed || value == choices.mode) return
            choices = Choices(value, choices.collapsed)
            relayout()
        }

    init {
        Disposer.register(parent) {
            disposed = true
            pending?.cancel(false)
            pending = null
            pendingCaret?.cancel(false)
            pendingCaret = null
            // the layouts queued end at once, and the running ones, left behind ones included, are told to stop. No
            // thread is interrupted, since an interrupted thread may fail to load a class; each ends when its layout returns.
            synchronized(lock) {
                drain(layoutThread).forEach { it.done.complete(false) }
                layoutThread.shutdown()
                running.forEach { it.cancelled = true }
            }
        }
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (disposed) return
                pending?.cancel(false)
                pending = delay(Runnable { refresh() })
            }
        }, parent)
        view.onLayoutMode = { mode ->
            if (!disposed) {
                chosenMode = mode
                remember(mode)
            }
        }
        view.onToggleBox = { path ->
            if (!disposed) {
                val collapsed = choices.collapsed
                choices = Choices(choices.mode, if (path in collapsed) collapsed - path else collapsed + path)
                relayout()
            }
        }
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (disposed || movingCaret) return
                pendingCaret?.cancel(false)
                val move = ++caretMoves
                pendingCaret = caretDelay(Runnable { if (move == caretMoves) caretMovedNow() })
            }
        }, parent)
        val shows = HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && editorShowing()) editorShown()
        }
        editor.contentComponent.addHierarchyListener(shows)
        Disposer.register(parent) { editor.contentComponent.removeHierarchyListener(shows) }
        view.onSelect = { id -> if (!disposed) selectFromView(id) }
        view.onOpenCode = { link -> if (!disposed) CodeLinks.open(project, file, link.path, link.type) }
        view.onNavigate = { offset ->
            // the offsets are those of the model shown; the document may have changed since
            if (!disposed && document.modificationStamp == shownStamp) moveCaret(offset, selecting = true)
        }
        refresh()
    }

    /** Selects the state at the caret of the last model shown, unless the document has changed since. UI thread. */
    internal fun caretMovedNow() {
        val model = shown
        if (disposed || model == null || document.modificationStamp != shownStamp) return
        selected = model.stateAt(editor.caretModel.offset)
        view.select(selected)
    }

    /** A click in the diagram: the selection is [id], and the caret goes to its place without changing it. */
    private fun selectFromView(id: String?) {
        pendingCaret?.cancel(false)
        pendingCaret = null
        caretMoves++
        if (id == null) {
            selected = null
            return
        }
        val offset = shown?.offsets?.get(id) ?: return
        selected = id
        // the offsets are those of the model shown; the document may have changed since
        if (document.modificationStamp == shownStamp) moveCaret(offset, selecting = false)
    }

    /** Leaves one caret at [offset] and no selection, as the platform's own navigation does. */
    private fun moveCaret(offset: Int, selecting: Boolean) {
        val target = offset.coerceIn(0, document.textLength)
        movingCaret = !selecting
        try {
            editor.caretModel.removeSecondaryCarets()
            editor.selectionModel.removeSelection()
            editor.caretModel.moveToOffset(target)
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        } finally {
            movingCaret = false
        }
        movedWhileHidden = !editorShowing()
    }

    /**
     * Called when the text editor comes on screen. The platform marks the name under the caret only after a caret move
     * in an editor on screen, so a move made while the editor was hidden left the old mark in place. The caret steps
     * aside and back, which changes no selection.
     */
    internal fun editorShown() {
        if (disposed || !movedWhileHidden) return
        movedWhileHidden = false
        val offset = editor.caretModel.offset
        val aside = if (offset > 0) offset - 1 else offset + 1
        if (aside > document.textLength) return
        movingCaret = true
        try {
            editor.caretModel.moveToOffset(aside)
            editor.caretModel.moveToOffset(offset)
        } finally {
            movingCaret = false
        }
    }

    /** Whether the layout that runs on [thread] has been told to stop; for tests. */
    internal fun cancelledOn(thread: Thread): Boolean = synchronized(lock) { running.any { it.thread === thread && it.cancelled } }

    /** Reads the file and lays it out again; the future is done when the view has the result or it was dropped. */
    internal fun refresh(): CompletableFuture<*> {
        val done = CompletableFuture<Boolean>()
        if (disposed) return done.also { it.complete(false) }
        val request = requests.incrementAndGet()
        latest = done
        ReadAction.nonBlocking<Built> { read() }
            .withDocumentsCommitted(project)
            .coalesceBy(this)
            .expireWith(parent)
            .submit(AppExecutorUtil.getAppExecutorService())
            .onSuccess { built -> onLayoutThread(done) { cancelled -> lay(request, built, refreshed = true, cancelled) } }
            .onError { e ->
                // a cancellation, a ProcessCanceledException among them, is no failure
                if (e !is CancellationException) failed("Cannot read the diagram of ${file.name}", e)
                done.complete(false)
            }
        return done
    }

    /** Lays out the newest model again with the current choices, without reading the file. UI thread. */
    private fun relayout() {
        val done = CompletableFuture<Boolean>()
        val request = requests.incrementAndGet()
        latest = done
        onLayoutThread(done) { cancelled -> newest?.let { lay(request, it, refreshed = false, cancelled) } }
    }

    /**
     * A read or a layout failed: the diagram is marked out of date. The first failure of this controller is logged as an
     * error, later ones only as a warning, so the IDE does not report an error at every pause in typing. The messages
     * name the file and hold none of its text.
     */
    private fun failed(message: String, e: Throwable) {
        if (failedBefore.compareAndSet(false, true)) log.error(message, e) else log.warn("$message: ${e.javaClass.name}")
        ApplicationManager.getApplication().invokeLater({ if (!disposed) view.setStale() }, ModalityState.any())
    }

    /** Called in the read action. */
    private fun read(): Built {
        val psi = if (file.isValid) PsiManager.getInstance(project).findFile(file) else null
        val doc = psi?.let(AslModel::of)
        val stamp = document.modificationStamp
        return Built(if (doc != null && DiagramBuilder.readable(doc)) DiagramBuilder.build(doc) else null, stamp)
    }

    /**
     * Runs [work] on the layout thread and hands its result over; a null result, or none at all, completes [done] as
     * dropped. [work] is given the flag that tells it to stop.
     */
    private fun onLayoutThread(done: CompletableFuture<Boolean>, work: (cancelled: () -> Boolean) -> Result?) {
        synchronized(lock) {
            if (!disposed) {
                try {
                    layoutThread.execute(LayoutTask(done, work))
                    return
                } catch (_: RejectedExecutionException) {
                    // disposed meanwhile
                }
            }
        }
        done.complete(false)
    }

    private fun newLayoutThread() = ThreadPoolExecutor(0, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, LAYOUT_THREAD).apply { isDaemon = true }
    }

    /** Takes the layouts queued on [executor] off it, in order. Under [lock], which every queueing holds. */
    private fun drain(executor: ThreadPoolExecutor): List<LayoutTask> =
        ArrayList<Runnable>().also { executor.queue.drainTo(it) }.map { it as LayoutTask }

    /**
     * One layout on a layout thread, watched by the time limit. When the limit passes first, the layout is told to stop
     * and left behind ([abandon]); its result is dropped if it ever ends. While [MAX_ABANDONED] layouts are left behind
     * and still running, no layout starts; when one of them ends after a layout was dropped so, a refresh follows, which
     * reads the file again and covers edits and changed choices alike.
     */
    private inner class LayoutTask(val done: CompletableFuture<Boolean>, private val work: (cancelled: () -> Boolean) -> Result?) : Runnable {
        /** [RUNNING] until the layout ends ([ENDED]) or is left behind ([ABANDONED]), whichever comes first. Guarded by [lock]. */
        private var state = RUNNING

        /** Set when the layout is left behind or the controller is disposed; ELK's progress monitor reads it between phases. */
        @Volatile
        var cancelled = false

        /** The thread the layout runs on, once it has started. */
        @Volatile
        var thread: Thread? = null

        override fun run() {
            val started = synchronized(lock) {
                when {
                    disposed -> false
                    abandoned >= MAX_ABANDONED -> false.also { droppedUnderBound = true }
                    else -> true.also {
                        thread = Thread.currentThread()
                        running += this
                    }
                }
            }
            if (!started) {
                done.complete(false)
                return
            }
            val limit = timeLimitMs
            val watchdog = AppExecutorUtil.getAppScheduledExecutorService().schedule({ abandon(limit) }, limit, TimeUnit.MILLISECONDS)
            var passed = false
            try {
                val result = work { cancelled }
                if (result != null && end()) {
                    ApplicationManager.getApplication().invokeLater({
                        try {
                            done.complete(handOver(result))
                        } finally {
                            done.complete(false)   // no effect when handed over; a waiting test ends if the view threw
                        }
                    }, ModalityState.any())
                    passed = true
                }
            } catch (e: Throwable) {
                // the flag ends the layout with a cancellation, and a layout left behind may end with anything. An Error is
                // caught too: the thread is the plugin's own, and a later IDE without a class the layout needs throws one
                if (!disposed && e !is CancellationException && end()) failed("Cannot lay out the diagram of ${file.name}", e)
            } finally {
                watchdog.cancel(false)
                val lifted = synchronized(lock) {
                    running -= this
                    if (state == RUNNING) state = ENDED else if (state == ABANDONED) abandoned--
                    // a request dropped under the bound is made up for now: the file may have changed, or the choices
                    (droppedUnderBound && abandoned < MAX_ABANDONED).also { if (it) droppedUnderBound = false }
                }
                if (!passed) done.complete(false)
                if (lifted) refresh()
            }
        }

        /** Whether the layout ended before the time limit, and so its result counts. */
        private fun end(): Boolean = synchronized(lock) {
            if (state == RUNNING) state = ENDED
            state == ENDED
        }

        /**
         * On the scheduler's thread, when [limit] has passed. The layout is told to stop, and its thread is shut down
         * without an interrupt, so it ends when the layout returns; the layouts queued behind it move to a new thread,
         * and the diagram is marked out of date before any of them can be shown.
         */
        private fun abandon(limit: Long) {
            synchronized(lock) {
                if (disposed || state != RUNNING) return
                state = ABANDONED
                cancelled = true
                abandoned++
                // logged before the mark is posted, so whoever sees the mark finds the warning logged
                log.warn("The layout of the diagram of ${file.name} took longer than $limit ms and was left behind")
                ApplicationManager.getApplication().invokeLater({ if (!disposed) view.setStale() }, ModalityState.any())
                val old = layoutThread
                layoutThread = newLayoutThread()
                val queued = drain(old)
                old.shutdown()
                queued.forEach(layoutThread::execute)
            }
            done.complete(false)
        }
    }

    /** On the layout thread. A refresh makes its model the newest one before laying it out, so a later new layout takes it. */
    private fun lay(request: Long, built: Built, refreshed: Boolean, cancelled: () -> Boolean): Result {
        val model = built.model
        val choices = choices
        if (model == null) return Result(request, built, choices, null, choices.mode ?: LayoutMode.defaultFor(0))
        if (refreshed) newest = built
        val mode = choices.mode ?: LayoutMode.defaultFor(model.stateCount)
        return Result(request, built, choices, DiagramLayouter.layout(model, mode, choices.collapsed, measure, cancelled), mode)
    }

    /** On the UI thread; whether the view was given the result. */
    private fun handOver(result: Result): Boolean {
        if (disposed || result.request < handedOver || result.built.stamp != document.modificationStamp) return false
        val model = result.built.model
        if (model == null) {
            handedOver = result.request
            when {
                shown?.root != null -> view.setStale()
                shown == null -> show(EMPTY, EMPTY_LAYOUT, result.mode, result.built.stamp)
            }
            return true
        }
        // the choices changed after this layout was made: the new layout they asked for follows
        if (result.choices !== choices) return false
        handedOver = result.request
        // a model not shown before selects the state at the caret; a new layout of the model shown keeps the selection
        if (model !== shown) selected = model.stateAt(editor.caretModel.offset)
        show(model, result.layout!!, result.mode, result.built.stamp)
        return true
    }

    private fun show(model: DiagramModel, layout: DiagramLayout, mode: LayoutMode, stamp: Long) {
        shown = model
        shownStamp = stamp
        view.show(model, layout, mode, selected)
    }

    private companion object {
        const val DELAY_MS = 300L
        const val CARET_DELAY_MS = 100L
        const val IDLE_SECONDS = 30L
        /** The large synthetic definition takes well under a second. */
        const val TIME_LIMIT_MS = 10_000L
        const val MAX_ABANDONED = 2
        const val RUNNING = 0
        const val ENDED = 1
        const val ABANDONED = 2
        const val LAYOUT_THREAD = "Aslan diagram layout"
        val EMPTY = DiagramModel(null, emptyMap(), 0)
        val EMPTY_LAYOUT = DiagramLayout(0.0, 0.0, emptyList(), emptyList())
    }
}
