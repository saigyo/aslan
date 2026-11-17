package io.github.saigyo.aslan.diagram

import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.model.AslDocument
import io.github.saigyo.aslan.model.AslModel
import io.github.saigyo.aslan.model.AslState

class DiagramDetailsTest : DiagramTestCase() {
    private val listing = """
        QueryLanguage: JSONata
        StartAt: OpenBatch
        Definitions:
          NoRetry: &noRetry
            ErrorEquals: [States.ALL]
            MaxAttempts: 0
          RetryAll: &retryAll
            ErrorEquals: [States.ALL]
            MaxAttempts: 3
        States:
          OpenBatch:
            Type: Pass
            Assign:
              batch: '{% §states.input %}'
            Next: ListReelFolder
          ListReelFolder:
            Type: Task
            Resource: arn:aws:states:::aws-sdk:s3:listObjectsV2
            Arguments:
              Prefix: '{% "batches/" & §batch.batchId & "/" %}'
            Assign:
              hasCatalogCopy: '{% ("batches/" & §batch.batchId & "/catalog.json") in §states.result.Contents.Key %}'
              hasSeal: '{% ("batches/" & §batch.batchId & "/sealed.json") in §states.result.Contents.Key %}'
            Retry: [*noRetry, *retryAll]
            Catch:
              - ErrorEquals: [States.ALL]
                Assign:
                  fault: '{% §states.errorOutput %}'
                Next: ClassifyFault
            Next: CheckFolder
          CheckFolder:
            Type: Pass
            End: true
          ClassifyFault:
            Type: Fail
    """

    private fun doc(text: String): Pair<AslDocument, PsiFile> {
        val file = yaml(text.replace('§', '$'))
        return AslModel.of(file)!! to file
    }

    private fun rows(text: String, name: String): Pair<List<DetailRow>, PsiFile> {
        val (doc, file) = doc(text)
        val state: AslState = doc.allStates().first { it.name == name }
        return DiagramDetails.rows(doc, state) to file
    }

    private fun List<DetailRow>.row(label: String): DetailRow = first { it.label == label }

    private fun DetailRow.texts(): List<String> = items.map { it.prefix + it.text }

    private fun DiagramModel.named(name: String): DiagramState {
        fun find(m: DiagramMachine): DiagramState? =
            m.states.firstOrNull { it.name == name } ?: m.states.flatMap { it.machines }.firstNotNullOfOrNull(::find)
        return find(root!!)!!
    }

    private fun DiagramModel.path(from: String, suffix: String): DiagramEdge = edge(named(from).id + suffix)!!

    private fun List<DetailRow>.labels() = map { it.label }

    private fun List<DetailRow>.items(label: String) = row(label).items

    fun `test the rows of a Next path name its two states`() {
        val model = build(archive).first
        val rows = DiagramDetails.pathRows(model, model.path("Scan", ".n"))
        assertEquals(listOf("From", "To"), rows.labels())
        val from = rows.items("From").single()
        val to = rows.items("To").single()
        assertEquals("Scan" to "Each", from.text to to.text)
        assertEquals(DetailItem.Kind.STATE to DetailItem.Kind.STATE, from.kind to to.kind)
        assertEquals(model.named("Scan").offset, from.offset)
        assertEquals(model.named("Each").offset, to.offset)
    }

    fun `test the rows of a JSONPath Choice rule show its whole condition`() {
        val model = build(archive).first
        val rows = DiagramDetails.pathRows(model, model.path("Sort", ".c0"))
        assertEquals(listOf("From", "To", "Condition"), rows.labels())
        val condition = rows.items("Condition").single()
        assertEquals("\$.format = \"nitrate\"", condition.text)
        assertEquals(DetailItem.Kind.WRAPPED, condition.kind)
        assertNull(condition.offset)
    }

    fun `test the rows of a JSONata Choice rule show the whole expression`() {
        val expression = "§states.input.format = \"nitrate\" and   §states.input.reels > 3 and " + "x".repeat(60)
        assertTrue(expression.length > 100)
        val model = build(
            """
            QueryLanguage: JSONata
            StartAt: Sort
            States:
              Sort:
                Type: Choice
                Choices:
                  - Condition: '{% $expression %}'
                    Next: Done
                Default: Done
              Done:
                Type: Succeed
            """,
        ).first
        val rows = DiagramDetails.pathRows(model, model.path("Sort", ".c0"))
        val condition = rows.items("Condition").single()
        assertEquals(expression.replace('§', '$').replace("   ", " "), condition.text)
        assertEquals(DetailItem.Kind.WRAPPED, condition.kind)
    }

    fun `test the rows of a Default path say Default`() {
        val model = build(archive).first
        val rows = DiagramDetails.pathRows(model, model.path("Sort", ".d"))
        assertEquals(listOf("From", "To", "Condition"), rows.labels())
        val item = rows.items("Condition").single()
        assertEquals("Default" to DetailItem.Kind.PLAIN, item.text to item.kind)
        assertEquals("Done", rows.items("To").single().text)
    }

    fun `test the rows of a catch path name its errors`() {
        val model = build(archive).first
        val rows = DiagramDetails.pathRows(model, model.path("Scan", ".k0"))
        assertEquals(listOf("From", "To", "Errors"), rows.labels())
        assertEquals("Failed", rows.items("To").single().text)
        val item = rows.items("Errors").single()
        assertEquals("States.ALL" to DetailItem.Kind.PLAIN, item.text to item.kind)
        val bare = build(
            """
            StartAt: Scan
            States:
              Scan:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                Catch:
                  - Next: Failed
                End: true
              Failed:
                Type: Fail
            """,
        ).first
        assertEquals(listOf("From", "To"), DiagramDetails.pathRows(bare, bare.path("Scan", ".k0")).labels())
    }

    fun `test the rows of a path inside a Map name the inner states`() {
        val model = build(archive).first
        val rows = DiagramDetails.pathRows(model, model.path("Frame", ".n"))
        assertEquals("Frame" to "Last", rows.items("From").single().text to rows.items("To").single().text)
        assertEquals(model.named("Frame").offset, rows.items("From").single().offset)
        assertEquals(model.named("Last").offset, rows.items("To").single().offset)
    }

    fun `test a Task shows type, resource, reads, assigns, retry, catch and next`() {
        val (rows, file) = rows(listing, "ListReelFolder")
        assertEquals(listOf("Type", "Resource", "Reads", "Assigns", "Retry", "Catch", "Next"), rows.map { it.label })
        assertEquals(listOf("Task"), rows.row("Type").texts())
        assertEquals(listOf("arn:aws:states:::aws-sdk:s3:listObjectsV2"), rows.row("Resource").texts())
        assertEquals(listOf("\$batch"), rows.row("Reads").texts())
        assertEquals(listOf("\$hasCatalogCopy", "\$hasSeal", "\$fault"), rows.row("Assigns").texts())
        assertEquals(listOf("noRetry", "retryAll"), rows.row("Retry").texts())
        val catch = rows.row("Catch").items.single()
        assertEquals("States.ALL → ", catch.prefix)
        assertEquals("ClassifyFault", catch.text)
        assertEquals(DetailItem.Kind.STATE, catch.kind)
        assertTrue(file.text.substring(catch.offset!!).startsWith("ClassifyFault:"))
        val next = rows.row("Next").items.single()
        assertEquals("CheckFolder", next.text)
        assertEquals(DetailItem.Kind.STATE, next.kind)
        assertTrue(file.text.substring(next.offset!!).startsWith("CheckFolder:"))
        assertTrue(rows.row("Reads").items.all { it.kind == DetailItem.Kind.VARIABLE })
    }

    fun `test the model holds the details of every state, nested ones too`() {
        val file = yaml(
            """
            StartAt: Each
            States:
              Each:
                Type: Map
                ItemProcessor:
                  StartAt: Frame
                  States:
                    Frame:
                      Type: Pass
                      End: true
                End: true
            """,
        )
        val model = DiagramBuilder.build(AslModel.of(file)!!)
        assertEquals(setOf("s0", "s1"), model.details.keys)
        assertEquals("End", model.details.getValue("s1").row("Next").items.single().text)
    }

    fun `test each state shows only its own reads and assigns`() {
        val (doc, _) = doc(
            """
            QueryLanguage: JSONata
            StartAt: A
            States:
              A:
                Type: Pass
                Assign:
                  fromA: '{% §states.input.a %}'
                Next: B
              B:
                Type: Pass
                Assign:
                  fromB: '{% §fromA & "b" %}'
                Next: C
              C:
                Type: Pass
                Output: '{% §fromB & §fromA %}'
                End: true
            """,
        )
        val model = DiagramBuilder.build(doc)
        fun rows(name: String) = model.details.getValue(model.root!!.states.single { it.name == name }.id)
        assertEquals(listOf("\$fromA"), rows("A").row("Assigns").texts())
        assertNull(rows("A").firstOrNull { it.label == "Reads" })
        assertEquals(listOf("\$fromB"), rows("B").row("Assigns").texts())
        assertEquals(listOf("\$fromA"), rows("B").row("Reads").texts())
        assertNull(rows("C").firstOrNull { it.label == "Assigns" })
        assertEquals(listOf("\$fromB", "\$fromA"), rows("C").row("Reads").texts())
    }

    fun `test a dynamic target is no item of Next or Catch`() {
        val (doc, _) = doc(
            """
            StartAt: A
            States:
              A:
                Type: Task
                Resource: arn:aws:states:::lambda:invoke
                Catch:
                  - ErrorEquals: [States.ALL]
                    Next: !Ref Handler
                Next: !Ref B
              B:
                Type: Pass
                Next: §{Target}
              C:
                Type: Pass
                Next: D
              D:
                Type: Succeed
            """,
        )
        val model = DiagramBuilder.build(doc)
        val details = model.root!!.states.associate { it.name to model.details.getValue(it.id) }
        for (name in listOf("A", "B")) {
            assertNull(name, details.getValue(name).firstOrNull { it.label == "Next" })
            assertNull(name, details.getValue(name).firstOrNull { it.label == "Catch" })
        }
        assertEquals(listOf("D"), details.getValue("C").row("Next").texts())
    }

    fun `test reads and assigns are linked to their first place`() {
        val (rows, file) = rows(
            """
            StartAt: Seed
            States:
              Seed:
                Type: Pass
                Assign:
                  batch.§: §.id
                Next: Read
              Read:
                Type: Pass
                Parameters:
                  id.§: §batch.id
                Assign:
                  later.§: §.x
                End: true
            """,
            "Read",
        )
        val read = rows.row("Reads").items.single()
        assertTrue(file.text.substring(read.offset!!).startsWith("\$batch.id"))
        val assigned = rows.row("Assigns").items.single()
        assertTrue(file.text.substring(assigned.offset!!).startsWith("later.\$"))

        val (jsonata, jsonataFile) = rows(listing, "ListReelFolder")
        val offset = jsonata.row("Reads").items.single().offset!!
        assertTrue(jsonataFile.text.substring(offset).startsWith("\$batch"))
        assertEquals(jsonataFile.text.indexOf("\$batch.batchId"), offset)
    }

    fun `test a variable is named once`() {
        val (rows, file) = rows(
            """
            QueryLanguage: JSONata
            StartAt: Work
            States:
              Work:
                Type: Task
                Resource: arn:aws:lambda:eu-west-1:123456789012:function:scan
                Arguments:
                  a: '{% §count + §count %}'
                  b: '{% §count %}'
                Assign:
                  count: '{% 1 %}'
                Catch:
                  - ErrorEquals: [States.ALL]
                    Assign:
                      count: '{% 2 %}'
                    Next: Work
                End: true
            """,
            "Work",
        )
        assertEquals(listOf("\$count"), rows.row("Reads").texts())
        assertEquals(listOf("\$count"), rows.row("Assigns").texts())
        val assigned = rows.row("Assigns").items.single()
        assertEquals(file.text.indexOf("count: '{% 1"), assigned.offset)
        assertEquals(file.text.indexOf("\$count + "), rows.row("Reads").items.single().offset)
    }

    fun `test a retrier is named by its alias`() {
        val (rows, file) = rows(
            """
            StartAt: A
            States:
              A:
                Type: Task
                Resource: arn:aws:lambda:eu-west-1:123456789012:function:scan
                Retry:
                  - &noRetry
                    ErrorEquals: [States.ALL]
                    MaxAttempts: 0
                Next: B
              B:
                Type: Task
                Resource: arn:aws:lambda:eu-west-1:123456789012:function:scan
                Retry: [*noRetry, {ErrorEquals: [States.Timeout, States.TaskFailed]}, {MaxAttempts: 2}]
                End: true
            """,
            "B",
        )
        val retry = rows.row("Retry")
        assertEquals(listOf("noRetry", "States.Timeout, States.TaskFailed", "Retrier 3"), retry.texts())
        assertTrue(file.text.substring(retry.items[0].offset!!).startsWith("*noRetry"))
        assertTrue(file.text.substring(retry.items[1].offset!!).startsWith("{ErrorEquals: [States.Timeout"))
    }

    fun `test a Retry list written as an alias gives its retriers in the file`() {
        val (rows, file) = rows(
            """
            StartAt: A
            Definitions:
              Common: &common
                - &noRetry
                  ErrorEquals: [States.Timeout]
                  MaxAttempts: 0
                - ErrorEquals: [States.TaskFailed, States.Permissions]
                  MaxAttempts: 2
            States:
              A:
                Type: Task
                Resource: arn:aws:lambda:eu-west-1:123456789012:function:scan
                Retry: *common
                End: true
            """,
            "A",
        )
        val retry = rows.row("Retry")
        assertEquals(listOf("States.Timeout", "States.TaskFailed, States.Permissions"), retry.texts())
        // each retrier leads to its place in the list the alias stands for
        val offsets = retry.items.map { it.offset!! }
        assertTrue(file.text.substring(offsets[0]).startsWith("&noRetry"))
        assertTrue(file.text.substring(offsets[1]).startsWith("ErrorEquals: [States.TaskFailed"))
        assertTrue(offsets.all { it < file.text.indexOf("States:\n") })
    }

    fun `test an assignment in a Choice rule is one of the state's assigns`() {
        val (rows, file) = rows(
            """
            QueryLanguage: JSONata
            StartAt: Sort
            States:
              Sort:
                Type: Choice
                Choices:
                  - Condition: '{% §states.input.format = "nitrate" %}'
                    Assign:
                      hazard: '{% true %}'
                    Next: Done
                Default: Done
              Done:
                Type: Succeed
            """,
            "Sort",
        )
        assertEquals(listOf("Type", "Assigns", "Next"), rows.map { it.label })
        val assigned = rows.row("Assigns").items.single()
        assertEquals("\$hazard", assigned.text)
        assertEquals(DetailItem.Kind.VARIABLE, assigned.kind)
        assertEquals(file.text.indexOf("hazard:"), assigned.offset)
    }

    fun `test an inline retrier is named by its error names`() {
        val (rows, _) = rows(
            """
            StartAt: A
            States:
              A:
                Type: Task
                Resource: arn:aws:lambda:eu-west-1:123456789012:function:scan
                Retry:
                  - ErrorEquals: [States.Timeout]
                End: true
            """,
            "A",
        )
        assertEquals(listOf("States.Timeout"), rows.row("Retry").texts())
    }

    fun `test a Choice lists every target once`() {
        val (rows, file) = rows(
            """
            StartAt: Sort
            States:
              Sort:
                Type: Choice
                Choices:
                  - Variable: §.format
                    StringEquals: nitrate
                    Next: Scan
                  - Variable: §.format
                    StringEquals: acetate
                    Next: Scan
                  - Variable: §.format
                    StringEquals: safety
                    Next: Inspect
                Default: Scan
              Scan:
                Type: Pass
                End: true
              Inspect:
                Type: Pass
                End: true
            """,
            "Sort",
        )
        assertEquals(listOf("Type", "Next"), rows.map { it.label })
        val next = rows.row("Next")
        assertEquals(listOf("Scan", "Inspect"), next.texts())
        assertTrue(next.items.all { it.kind == DetailItem.Kind.STATE })
        assertTrue(file.text.substring(next.items[1].offset!!).startsWith("Inspect:"))
    }

    fun `test a terminal state ends`() {
        val (rows, _) = rows(
            """
            StartAt: Done
            States:
              Done:
                Type: Succeed
            """,
            "Done",
        )
        val next = rows.row("Next")
        assertEquals(listOf("End"), next.texts())
        assertEquals(DetailItem.Kind.PLAIN, next.items.single().kind)
        assertNull(next.items.single().offset)
    }

    fun `test rows without content are left out`() {
        val (rows, _) = rows(
            """
            StartAt: Wait
            States:
              Wait:
                Type: Pass
                Next: Wait2
              Wait2:
                Type: Pass
                End: true
            """,
            "Wait",
        )
        assertEquals(listOf("Type", "Next"), rows.map { it.label })
    }

    fun `test a target that is missing is plain`() {
        val (rows, _) = rows(
            """
            StartAt: A
            States:
              A:
                Type: Task
                Resource: arn:aws:lambda:eu-west-1:123456789012:function:scan
                Catch:
                  - ErrorEquals: [States.ALL]
                    Next: Nowhere
                Next: Missing
            """,
            "A",
        )
        for (label in listOf("Catch", "Next")) {
            val item = rows.row(label).items.single()
            assertEquals(DetailItem.Kind.PLAIN, item.kind)
            assertNull(item.offset)
        }
        assertEquals("Nowhere", rows.row("Catch").items.single().text)
        assertEquals("Missing", rows.row("Next").items.single().text)
    }

    fun `test a half-typed state gives rows and no exception`() {
        val (doc, _) = doc(
            """
            StartAt: A
            States:
              A: nothing
              B:
                Type: [1]
                Resource: [x]
                Retry: oops
                Catch: [1, {Next: [x]}]
                Choices: 3
                Next: {a: b}
              C:
                Type: Choice
                Choices: [7, {Next: 5}]
            """,
        )
        for (state in doc.allStates()) DiagramDetails.rows(doc, state)
    }
}
