package io.github.saigyo.aslan.files

import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.settings.AslSettings

class AslFilesTest : AslTestCase() {
    override fun tearDown() {
        try {
            AslSettings.getInstance().extraPatterns = emptyList()
        } finally {
            super.tearDown()
        }
    }

    fun `test built-in suffixes`() {
        assertTrue(AslFiles.isAslFileName("intake.asl.yaml"))
        assertTrue(AslFiles.isAslFileName("intake.asl.yml"))
        assertTrue(AslFiles.isAslFileName("intake.asl.json"))
        assertTrue(AslFiles.isAslFileName("INTAKE.ASL.YAML"))
        assertFalse(AslFiles.isAslFileName("intake.yaml"))
        assertFalse(AslFiles.isAslFileName("asl.yaml"))
        assertFalse(AslFiles.isAslFileName("intake.asl.txt"))
    }

    fun `test extra patterns from settings`() {
        AslSettings.getInstance().extraPatterns = listOf("*.statemachine.yaml", "workflow-?.json")
        assertTrue(AslFiles.isAslFileName("intake.statemachine.yaml"))
        assertTrue(AslFiles.isAslFileName("workflow-a.json"))
        assertFalse(AslFiles.isAslFileName("workflow-ab.json"))
    }

    fun `test changing patterns advances the modification count`() {
        val before = AslSettings.getInstance().modificationCount
        AslSettings.getInstance().extraPatterns = listOf("*.sm.yaml")
        assertTrue(AslSettings.getInstance().modificationCount > before)
    }

    fun `test psi files`() {
        assertTrue(AslFiles.isAslFile(yaml("StartAt: A")))
        assertFalse(AslFiles.isAslFile(yaml("services: {}", "docker-compose.yaml")))
        assertFalse(AslFiles.isAslFile(myFixture.configureByText("notes.asl.txt", "StartAt: A")))
    }
}
