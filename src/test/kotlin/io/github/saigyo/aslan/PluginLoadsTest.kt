package io.github.saigyo.aslan

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

class PluginLoadsTest : AslTestCase() {
    fun `test plugin is loaded`() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("io.github.saigyo.aslan"))
        assertNotNull(plugin)
        assertEquals("Aslan ASL Editor", plugin!!.name)
    }
}
