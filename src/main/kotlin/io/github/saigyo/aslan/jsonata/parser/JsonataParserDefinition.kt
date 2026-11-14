package io.github.saigyo.aslan.jsonata.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.github.saigyo.aslan.jsonata.psi.JsonataCall
import io.github.saigyo.aslan.jsonata.psi.JsonataElement
import io.github.saigyo.aslan.jsonata.psi.JsonataFile
import io.github.saigyo.aslan.jsonata.psi.JsonataLambda
import io.github.saigyo.aslan.jsonata.psi.JsonataPath
import io.github.saigyo.aslan.jsonata.psi.JsonataVariableRef

class JsonataParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = JsonataLexer()
    override fun createParser(project: Project?): PsiParser = JsonataParser()
    override fun getFileNodeType(): IFileElementType = JsonataElements.FILE
    override fun getWhitespaceTokens(): TokenSet = TokenSet.create(TokenType.WHITE_SPACE)
    override fun getCommentTokens(): TokenSet = TokenSet.create(JsonataTokens.COMMENT, JsonataTokens.BAD_COMMENT)
    override fun getStringLiteralElements(): TokenSet = JsonataTokens.STRINGS
    override fun createFile(viewProvider: FileViewProvider): PsiFile = JsonataFile(viewProvider)

    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        JsonataElements.VARIABLE_REF, JsonataElements.PARAMETER -> JsonataVariableRef(node)
        JsonataElements.CALL -> JsonataCall(node)
        JsonataElements.LAMBDA -> JsonataLambda(node)
        JsonataElements.PATH -> JsonataPath(node)
        else -> JsonataElement(node)
    }
}
