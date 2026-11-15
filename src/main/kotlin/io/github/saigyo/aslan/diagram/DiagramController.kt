package io.github.saigyo.aslan.diagram

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
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
 * have changed since it was made, when a newer result has been handed over, or once [parent] is disposed.
 *
 * [chosen] is the layout chosen for the file before, and [remember] keeps a choice made with the layout switch. Create
 * and use it on the UI thread; [refresh] may be called from any thread.
 */
class DiagramController(
    private val project: Project,
    private val file: VirtualFile,
    editor: Editor,
    private val view: DiagramView,
    private val parent: Disposable,
    chosen: LayoutMode? = null,
    private val remember: (LayoutMode) -> Unit = {},
) {
    /** What a layout depends on besides the model; replaced as a whole, so a result can tell whether it is current. */
    private class Choices(val mode: LayoutMode?, val collapsed: Set<String>)

    /** A model and the modification stamp of the document it was built from; [model] is null for an unreadable file. */
    private class Built(val model: DiagramModel?, val stamp: Long)

    private class Result(val request: Long, val built: Built, val choices: Choices, val layout: DiagramLayout?, val mode: LayoutMode)

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

    /** The newest model a refresh has laid out, which a new layout of the same model takes. Layout thread only. */
    private var newest: Built? = null

    /** The delayed refresh after the last change, if it has not run. UI thread. */
    private var pending: Future<*>? = null

    /** The thread of the layouts, used for nothing else; it ends when idle and when [parent] is disposed. */
    private val layoutThread: ExecutorService = ThreadPoolExecutor(0, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, LAYOUT_THREAD).apply { isDaemon = true }
    }

    /** Schedules a task after the delay; tests replace it to run the delay themselves. */
    internal var delay: (Runnable) -> Future<*> = { task ->
        AppExecutorUtil.getAppScheduledExecutorService().schedule(task, DELAY_MS, TimeUnit.MILLISECONDS)
    }

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
            // a layout that runs finishes, and the ones queued end at once; the thread ends after them
            layoutThread.shutdown()
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
        refresh()
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
        try {
            layoutThread.execute {
                var passed = false
                try {
                    val result = if (disposed) null else work()
                    if (result != null) {
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
                    if (!disposed) log.error("Cannot lay out the diagram of ${file.name}", e)
                } finally {
                    if (!passed) done.complete(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            done.complete(false)   // disposed meanwhile
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
                shown == null -> show(EMPTY, EMPTY_LAYOUT, result.mode)
            }
            return true
        }
        // the choices changed after this layout was made: the new layout they asked for follows
        if (result.choices !== choices) return false
        handedOver = result.request
        show(model, result.layout!!, result.mode)
        return true
    }

    private fun show(model: DiagramModel, layout: DiagramLayout, mode: LayoutMode) {
        shown = model
        view.show(model, layout, mode, selected)
    }

    private companion object {
        const val DELAY_MS = 300L
        const val IDLE_SECONDS = 30L
        const val LAYOUT_THREAD = "Aslan diagram layout"
        val EMPTY = DiagramModel(null, emptyMap(), 0)
        val EMPTY_LAYOUT = DiagramLayout(0.0, 0.0, emptyList(), emptyList())
    }
}
