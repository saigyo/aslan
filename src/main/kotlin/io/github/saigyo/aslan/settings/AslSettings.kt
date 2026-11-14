package io.github.saigyo.aslan.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.ModificationTracker
import com.intellij.util.PatternUtil
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/** Application-wide plugin settings: extra file name patterns that mark a file as ASL. */
@Service(Service.Level.APP)
@State(name = "AslanSettings", storages = [Storage("aslan.xml")])
class AslSettings : PersistentStateComponent<AslSettings.State>, ModificationTracker {
    class State {
        var extraPatterns: MutableList<String> = mutableListOf()
    }

    /** The patterns as entered and compiled; immutable, so any thread may read the current one without locking. */
    private class Snapshot(val patterns: List<String>) {
        val compiled: List<Pattern> = patterns.filter { it.isNotBlank() }.map { PatternUtil.fromMask(it.trim()) }
    }

    @Volatile
    private var state = State()

    @Volatile
    private var snapshot = Snapshot(emptyList())
    private val modifications = AtomicLong()

    var extraPatterns: List<String>
        get() = snapshot.patterns
        set(value) {
            state = State().apply { extraPatterns = value.toMutableList() }
            snapshot = Snapshot(value.toList())
            modifications.incrementAndGet()
        }

    /** Whether [name] matches one of the extra patterns; the patterns are compiled once per change. */
    fun matchesExtraPattern(name: String): Boolean = snapshot.compiled.any { it.matcher(name).matches() }

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        snapshot = Snapshot(state.extraPatterns.toList())
        modifications.incrementAndGet()
    }

    override fun getModificationCount(): Long = modifications.get()

    companion object {
        fun getInstance(): AslSettings = ApplicationManager.getApplication().getService(AslSettings::class.java)
    }
}
