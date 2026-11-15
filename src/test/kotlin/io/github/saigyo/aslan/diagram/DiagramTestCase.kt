package io.github.saigyo.aslan.diagram

import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.AslTestCase
import io.github.saigyo.aslan.model.AslModel

/** The small definition of the diagram tests, and how a test gets a [DiagramModel] from an inline text. */
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
}
