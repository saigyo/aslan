package io.github.saigyo.aslan.variables

import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.JsonPathReads
import io.github.saigyo.aslan.model.PathName

class JsonPathReadsTest : VariableTestCase() {
    private fun d(text: String) = text.replace("§", "$")

    private fun names(text: String) = JsonPathReads.names(d(text))

    private fun sites(text: String): List<Pair<String, String>> =
        JsonPathReads.sites(AslModel.of(yaml(text))!!).map { it.first.text to it.second.name }

    fun `test a path that starts with a variable`() {
        assertEquals(listOf(PathName("reel", 0, 5)), names("§reel"))
        assertEquals(listOf(PathName("reel", 0, 5)), names("§reel.title[0]"))
        assertEquals(listOf(PathName("bobine_nº", 0, 10)), names("§bobine_nº.x"))
    }

    fun `test paths that read no variable`() {
        for (text in listOf("§", "§§", "§.reel", "§§.Execution.Id", "§[0]", "reel", "")) assertEquals(text, emptyList<PathName>(), names(text))
    }

    fun `test intrinsic arguments`() {
        val text = d("States.Format('{} §no', §a, States.ArrayGetItem(§b, 0))")
        fun read(name: String) = d("§$name").let { PathName(name, text.indexOf(it), text.indexOf(it) + it.length) }
        assertEquals(listOf(read("a"), read("b")), JsonPathReads.names(text))
    }

    fun `test quoted text in an intrinsic is no read`() {
        val text = "States.Format('it\\'s \$no', \$c)"
        assertEquals(listOf(PathName("c", text.indexOf("\$c"), text.indexOf("\$c") + 2)), JsonPathReads.names(text))
    }

    fun `test the strings JSONPath evaluates`() {
        val text = machine(
            """
            Work:
              Type: Task
              Resource: arn:aws:states:::lambda:invoke
              Parameters:
                Payload.§: §a.b
                Plain: Payload
                Nested:
                  - x.§: §n
              ResultSelector:
                x.§: §c
              ResultPath: §notread
              InputPath: §d
              OutputPath: §e
              Assign:
                y.§: §f
              Next: Pick
            Pick:
              Type: Choice
              Choices:
                - Variable: §g
                  StringEqualsPath: §h
                  Next: Each
                - Not:
                    Variable: §i
                    StringEquals: §notread2
                  Next: Each
              Default: Each
            Each:
              Type: Map
              ItemsPath: §j
              ItemSelector:
                z.§: §k
              ItemProcessor:
                StartAt: One
                States:
                  One:
                    Type: Pass
                    OutputPath: §l
                    End: true
              End: true
            """.replace("§", "$"),
        )
        assertEquals(
            listOf("a.b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "n").map { "$$it" },
            sites(text).map { it.first }.sortedBy { it.substring(1) },
        )
    }

    fun `test JSONata states have no JSONPath strings`() {
        val text = machine(
            """
            Work:
              Type: Pass
              Output:
                x.§: §a
              End: true
            """.replace("§", "$"),
            "QueryLanguage: JSONata",
        )
        assertEquals(emptyList<Pair<String, String>>(), sites(text))
    }

    fun `test an aliased path is a site for each state`() {
        val text = machine(
            """
            First:
              Type: Pass
              Parameters: &shared
                x.§: §a
              Next: Second
            Second:
              Type: Pass
              Parameters: *shared
              End: true
            """.replace("§", "$"),
        )
        assertEquals(listOf("\$a" to "First", "\$a" to "Second"), sites(text))
    }
}
