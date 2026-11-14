package io.github.saigyo.aslan.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.ModificationTracker
import java.util.concurrent.atomic.AtomicLong

/** Application-wide plugin settings: extra file name patterns that mark a file as ASL. */
@Service(Service.Level.APP)
@State(name = "AslanSettings", storages = [Storage("aslan.xml")])
class AslSettings : PersistentStateComponent<AslSettings.State>, ModificationTracker {
    class State {
        var extraPatterns: MutableList<String> = mutableListOf()
    }

    private var state = State()
    private val modifications = AtomicLong()

    var extraPatterns: List<String>
        get() = state.extraPatterns.toList()
        set(value) {
            state.extraPatterns = value.toMutableList()
            modifications.incrementAndGet()
        }

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        modifications.incrementAndGet()
    }

    override fun getModificationCount(): Long = modifications.get()

    companion object {
        fun getInstance(): AslSettings = ApplicationManager.getApplication().getService(AslSettings::class.java)
    }
}
