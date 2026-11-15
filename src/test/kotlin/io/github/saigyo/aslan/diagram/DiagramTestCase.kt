package io.github.saigyo.aslan.diagram

import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.model.AslModel
import java.io.File

/**
 * The small definition of the diagram tests, how a test gets a [DiagramModel] from an inline text, and the models of
 * all definitions the tests run over.
 */
abstract class DiagramTestCase : AslTestCase() {
    protected val archive = """
        Comment: Film archive intake
        StartAt: Sort
        States:
          Sort:
            Type: Choice
            Choices:
              - Variable: §.format
                StringEquals: nitrate
                Next: Scan
            Default: Done
          Scan:
            Type: Task
            Resource: arn:aws:states:::lambda:invoke
            Catch:
              - ErrorEquals: [States.ALL]
                Next: Failed
            Next: Each
          Each:
            Type: Map
            ItemProcessor:
              StartAt: Frame
              States:
                Frame:
                  Type: Pass
                  Next: Last
                Last:
                  Type: Pass
                  End: true
            Next: Both
          Both:
            Type: Parallel
            Branches:
              - StartAt: Left
                States:
                  Left:
                    Type: Pass
                    End: true
              - StartAt: Right
                States:
                  Right:
                    Type: Pass
                    End: true
            Next: Done
          Done:
            Type: Succeed
          Failed:
            Type: Fail
    """

    protected fun file(text: String, name: String = "test.asl.yaml"): PsiFile = yaml(text.replace('§', '$'), name)

    protected fun build(text: String): Pair<DiagramModel, PsiFile> {
        val file = file(text)
        return DiagramBuilder.build(AslModel.of(file)!!) to file
    }

    /** The small definition and every committed definition, by name. */
    protected fun definitions(): List<Pair<String, DiagramModel>> {
        val files = File("src/test/testData/clean").listFiles().orEmpty().filter { ".asl." in it.name }.sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        val committed = files.map { f -> f.name to DiagramBuilder.build(AslModel.of(myFixture.configureByText(f.name, f.readText()))!!) }
        return listOf("small" to build(archive).first) + committed
    }
}
