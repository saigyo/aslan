package io.github.saigyo.aslan.files

import io.github.saigyo.aslan.AslIcons
import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.settings.AslSettings

class AslFileIconTest : AslTestCase() {
    override fun tearDown() {
        try {
            AslSettings.getInstance().extraPatterns = emptyList()
        } finally {
            super.tearDown()
        }
    }

    private fun iconOf(name: String) =
        AslFileIconProvider().getIcon(myFixture.configureByText(name, "StartAt: A").virtualFile, 0, project)

    fun `test an ASL file has the plugin's icon`() {
        assertSame(AslIcons.File, iconOf("intake.asl.yaml"))
        assertSame(AslIcons.File, iconOf("intake.asl.yml"))
        assertSame(AslIcons.File, iconOf("intake.asl.json"))
        AslSettings.getInstance().extraPatterns = listOf("*.statemachine.yaml")
        assertSame(AslIcons.File, iconOf("intake.statemachine.yaml"))
    }

    fun `test another YAML or JSON file keeps its icon`() {
        assertNull(iconOf("config.yaml"))
        assertNull(iconOf("data.json"))
        assertNull(iconOf("notes.asl.txt"))
    }
}
