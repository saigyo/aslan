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
import io.github.saigyo.aslan.model.AslModel
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val log = logger<DiagramController>()

/**
 * Keeps [view] in step with [file] (decision 4): after a change of the document and a delay, it builds the model in a
 * non-blocking read action, lays it out on a thread of its own, outside any read action, and hands both to the view on
 * the UI thread. A result is dropped when the document has changed since its model was built, when the layout's choices
 * have changed since it was made, when a newer result has been handed over, or once [parent] is disposed. A layout that
 * takes longer than [timeLimitMs] is interrupted and left behind, and the diagram is marked out of date; the layouts
 * after it run on a new thread.
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

    /** The delayed refresh after the last change, if it has not run. UI thread. */
    private var pending: Future<*>? = null

    /** Guards [layoutThread] and [abandoned], which the time limit changes from the scheduler's thread. */
    private val lock = Any()

    /**
     * The thread of the layouts, used for nothing else; it ends when idle and when [parent] is disposed. A layout over
     * the time limit is left behind on it, and a new one takes the layouts after it.
     */
    private var layoutThread: ExecutorService = newLayoutThread()

    /** The number of layouts left behind over the time limit that have not ended yet. */
    private var abandoned = 0

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

    /** How long one layout may take before it is interrupted and left behind; tests set a short one. */
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
            // the layout that runs is interrupted, and the ones queued end at once; the thread ends after them
            synchronized(lock) {
                layoutThread.shutdownNow().filterIsInstance<LayoutTask>().forEach { it.done.complete(false) }
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
        view.onToggleBox = { name ->
            if (!disposed) {
                val collapsed = choices.collapsed
                choices = Choices(choices.mode, if (name in collapsed) collapsed - name else collapsed + name)
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
        view.onSelect = { id -> if (!disposed) selectFromView(id) }
        view.onNavigate = { offset ->
            if (!disposed) moveCaret(offset, selecting = true)
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

    private fun moveCaret(offset: Int, selecting: Boolean) {
        val target = offset.coerceIn(0, document.textLength)
        movingCaret = !selecting
        try {
            editor.caretModel.moveToOffset(target)
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        } finally {
            movingCaret = false
        }
    }

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
            .onSuccess { built -> onLayoutThread(done) { lay(request, built, refreshed = true) } }
            .onError { e ->
                if (e !is CancellationException) log.error("Cannot read the diagram of ${file.name}", e)
                done.complete(false)
            }
        return done
    }

    /** Lays out the newest model again with the current choices, without reading the file. UI thread. */
    private fun relayout() {
        val done = CompletableFuture<Boolean>()
        val request = requests.incrementAndGet()
        latest = done
        onLayoutThread(done) { newest?.let { lay(request, it, refreshed = false) } }
    }

    /** Called in the read action. */
    private fun read(): Built {
        val psi = if (file.isValid) PsiManager.getInstance(project).findFile(file) else null
        val doc = psi?.let(AslModel::of)
        val stamp = document.modificationStamp
        return Built(if (doc != null && DiagramBuilder.readable(doc)) DiagramBuilder.build(doc) else null, stamp)
    }

    /** Runs [work] on the layout thread and hands its result over; a null result, or none at all, completes [done] as dropped. */
    private fun onLayoutThread(done: CompletableFuture<Boolean>, work: () -> Result?) {
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

    private fun newLayoutThread(): ExecutorService = ThreadPoolExecutor(0, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, LAYOUT_THREAD).apply { isDaemon = true }
    }

    /**
     * One layout on a layout thread, watched by the time limit. When the limit passes first, the layout is interrupted
     * and left behind ([abandon]); its result is dropped if it ever ends. While [MAX_ABANDONED] layouts are left behind
     * and still running, no layout starts.
     */
    private inner class LayoutTask(val done: CompletableFuture<Boolean>, private val work: () -> Result?) : Runnable {
        /** [RUNNING] until the layout ends ([ENDED]) or is left behind ([ABANDONED]), whichever comes first. Guarded by [lock]. */
        private var state = RUNNING

        override fun run() {
            if (disposed || synchronized(lock) { abandoned >= MAX_ABANDONED }) {
                done.complete(false)
                return
            }
            val limit = timeLimitMs
            val watchdog = AppExecutorUtil.getAppScheduledExecutorService().schedule({ abandon(limit) }, limit, TimeUnit.MILLISECONDS)
            var passed = false
            try {
                val result = work()
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
            } catch (e: Exception) {
                // an interrupt ends the layout with a cancellation; a layout left behind may end with anything
                if (!disposed && e !is CancellationException && end()) log.error("Cannot lay out the diagram of ${file.name}", e)
            } finally {
                watchdog.cancel(false)
                synchronized(lock) {
                    if (state == RUNNING) state = ENDED else if (state == ABANDONED) abandoned--
                }
                if (!passed) done.complete(false)
            }
        }

        /** Whether the layout ended before the time limit, and so its result counts. */
        private fun end(): Boolean = synchronized(lock) {
            if (state == RUNNING) state = ENDED
            state == ENDED
        }

        /**
         * On the scheduler's thread, when [limit] has passed. The thread of the layout is shut down, which interrupts it;
         * the layouts queued behind it move to a new thread, and the diagram is marked out of date before any of them
         * can be shown.
         */
        private fun abandon(limit: Long) {
            synchronized(lock) {
                if (disposed || state != RUNNING) return
                state = ABANDONED
                abandoned++
                ApplicationManager.getApplication().invokeLater({ if (!disposed) view.setStale() }, ModalityState.any())
                val old = layoutThread
                layoutThread = newLayoutThread()
                old.shutdownNow().forEach(layoutThread::execute)
            }
            done.complete(false)
            log.warn("The layout of the diagram of ${file.name} took longer than $limit ms and was left behind")
        }
    }

    /** On the layout thread. A refresh makes its model the newest one before laying it out, so a later new layout takes it. */
    private fun lay(request: Long, built: Built, refreshed: Boolean): Result {
        val model = built.model
        val choices = choices
        if (model == null) return Result(request, built, choices, null, choices.mode ?: LayoutMode.defaultFor(0))
        if (refreshed) newest = built
        val mode = choices.mode ?: LayoutMode.defaultFor(model.stateCount)
        return Result(request, built, choices, DiagramLayouter.layout(model, mode, choices.collapsed, measure), mode)
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
