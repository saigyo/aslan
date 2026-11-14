package io.github.saigyo.aslan.structure

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.json.psi.JsonFile
import com.intellij.json.structureView.JsonCustomStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import io.github.saigyo.aslan.model.AslModel
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.structureView.YAMLCustomStructureViewFactory

private fun builderFor(file: PsiFile): StructureViewBuilder? {
    if (AslModel.of(file)?.root == null) return null
    return object : TreeBasedStructureViewBuilder() {
        override fun createStructureViewModel(editor: Editor?): StructureViewModel = AslStructureViewModel(file, editor)
        override fun isRootNodeShown(): Boolean = true
    }
}

class AslYamlStructureViewFactory : YAMLCustomStructureViewFactory {
    override fun getStructureViewBuilder(yamlFile: YAMLFile): StructureViewBuilder? = builderFor(yamlFile)
}

class AslJsonStructureViewFactory : JsonCustomStructureViewFactory {
    override fun getStructureViewBuilder(jsonFile: JsonFile): StructureViewBuilder? = builderFor(jsonFile)
}
