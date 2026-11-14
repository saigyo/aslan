package io.github.saigyo.aslan.jsonata.parser

import com.intellij.psi.tree.IElementType
import io.github.saigyo.aslan.jsonata.parser.JsonataElements as E
import io.github.saigyo.aslan.jsonata.parser.JsonataTokens as T

/*
 * Ported from the tokenizer and parser of jsonata-js 2.0.6 (src/parser.js), © Copyright IBM Corp. 2016, 2018,
 * MIT licence, see META-INF/licenses/jsonata-LICENSE.txt. Comments of the form `parser.js: …` name the reference code.
 */

class ScanToken(val type: IElementType, val start: Int, val end: Int)

class ScanNode(val type: IElementType, val start: Int, val end: Int, val children: List<ScanNode>)

/** A syntax error: [code] is the reference's error code, the range is the offending token; empty at the end of the text. */
class ScanError(val code: String, val start: Int, val end: Int)

/** The tokens cover the whole text. [root] is the tree built up to the first error, [error] that error. */
class JsonataScan(val tokens: List<ScanToken>, val root: ScanNode?, val error: ScanError?)

/** Turns JSONata text into tokens, a tree and at most one syntax error, as jsonata-js 2.0.6 parses it. */
object JsonataScanner {
    fun scan(text: CharSequence): JsonataScan = Parser(text).run()
}

/** A significant token, or `(end)`. [refType] is the token type of the reference (`name`, `variable`, `operator`, …). */
private class Tok(var kind: IElementType?, val start: Int, val end: Int, val value: String, val refType: String) {
    lateinit var sym: Sym
    val id: String get() = sym.id
}

private class Failure(val error: ScanError) : RuntimeException(null, null, false, false)

// parser.js: var operators
private val operators = mapOf(
    "." to 75, "[" to 80, "]" to 0, "{" to 70, "}" to 0, "(" to 80, ")" to 0, "," to 0, "@" to 80, "#" to 80, ";" to 80,
    ":" to 80, "?" to 20, "+" to 50, "-" to 50, "*" to 60, "/" to 60, "%" to 60, "|" to 20, "=" to 40, "<" to 40,
    ">" to 40, "^" to 40, "**" to 60, ".." to 20, ":=" to 10, "!=" to 40, "<=" to 40, ">=" to 40, "~>" to 40,
    "and" to 30, "or" to 25, "in" to 40, "&" to 50, "!" to 0, "~" to 0,
)

/** The one-character keys of [operators]: the operator characters, which also end a name. */
private const val OPERATOR_CHARS = ".[]{}(),@#;:?+-*/%|=<>^&!~"

private val operatorTokens = mapOf(
    "." to T.DOT, ".." to T.DOT_DOT, "[" to T.LBRACKET, "]" to T.RBRACKET, "{" to T.LBRACE, "}" to T.RBRACE,
    "(" to T.LPAREN, ")" to T.RPAREN, "," to T.COMMA, ";" to T.SEMICOLON, ":" to T.COLON, ":=" to T.ASSIGN,
    "?" to T.QUESTION, "?:" to T.ELVIS, "??" to T.COALESCE, "+" to T.PLUS, "-" to T.MINUS, "*" to T.STAR,
    "**" to T.STAR_STAR, "/" to T.SLASH, "%" to T.PERCENT, "|" to T.PIPE, "=" to T.EQ, "!=" to T.NE, "<" to T.LT,
    "<=" to T.LE, ">" to T.GT, ">=" to T.GE, "^" to T.CARET, "~>" to T.CHAIN, "&" to T.AMP, "@" to T.AT,
    "#" to T.HASH, "!" to T.BANG, "~" to T.TILDE, "and" to T.AND, "or" to T.OR, "in" to T.IN,
)

private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B'

private fun isDigit(c: Char): Boolean = c in '0'..'9'

private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

/** parser.js: var tokenizer. Records every token, whitespace and comments included, in [tokens]. */
private class Lexer(private val path: CharSequence, private val tokens: MutableList<Tok>) {
    var position = 0
    private val length = path.length

    /** The lexical error of the last call of [next], if any; the token it concerns is recorded. */
    var error: ScanError? = null

    private fun charAt(i: Int): Char = if (i in 0 until length) path[i] else '\u0000'

    private fun add(kind: IElementType, start: Int, value: String = "", refType: String = ""): Tok =
        Tok(kind, start, position, value, refType).also { tokens += it }

    private fun fail(code: String, start: Int) {
        if (error == null) error = ScanError(code, start, position)
    }

    /** parser.js: var next. With [prefix] true a `/` is division, otherwise it starts a regular expression. */
    fun next(prefix: Boolean): Tok? {
        error = null
        if (position >= length) return null
        while (true) {
            // skip whitespace
            val spaceStart = position
            while (position < length && isSpace(path[position])) position++
            if (position > spaceStart) add(T.WHITE_SPACE, spaceStart)
            // skip comments
            if (charAt(position) == '/' && charAt(position + 1) == '*') {
                val commentStart = position
                var i = commentStart + 2
                while (i < length && !(path[i] == '*' && charAt(i + 1) == '/')) i++
                if (i >= length) {
                    // no closing tag
                    position = length
                    add(T.BAD_COMMENT, commentStart)
                    fail("S0106", commentStart)
                    return null
                }
                position = i + 2
                add(T.COMMENT, commentStart)
                continue // need this to swallow any following whitespace
            }
            break
        }
        if (position >= length) return null
        val start = position
        val currentChar = path[position]
        // test for regex
        if (!prefix && currentChar == '/') {
            position++
            return scanRegex(start)
        }
        // handle double-char operators, and those of JSONata 2.1
        val next = charAt(position + 1)
        val double = when {
            currentChar == '.' && next == '.' -> ".."
            currentChar == ':' && next == '=' -> ":="
            currentChar == '!' && next == '=' -> "!="
            currentChar == '>' && next == '=' -> ">="
            currentChar == '<' && next == '=' -> "<="
            currentChar == '*' && next == '*' -> "**"
            currentChar == '~' && next == '>' -> "~>"
            currentChar == '?' && next == ':' -> "?:"
            currentChar == '?' && next == '?' -> "??"
            else -> null
        }
        if (double != null) {
            position += 2
            return add(operatorTokens.getValue(double), start, double, "operator")
        }
        // test for single char operators
        if (currentChar in OPERATOR_CHARS) {
            position++
            val op = currentChar.toString()
            return add(operatorTokens.getValue(op), start, op, "operator")
        }
        // test for string literals
        if (currentChar == '"' || currentChar == '\'') return scanString(start, currentChar)
        // test for numbers: /^-?(0|([1-9][0-9]*))(\.[0-9]+)?([Ee][-+]?[0-9]+)?/; a leading '-' is an operator above
        if (isDigit(currentChar)) return scanNumber(start)
        // test for quoted names (backticks)
        if (currentChar == '`') {
            var end = -1
            for (i in position + 1 until length) if (path[i] == '`') { end = i; break }
            if (end != -1) {
                position = end + 1
                return add(T.QUOTED_NAME, start, path.subSequence(start + 1, end).toString(), "name")
            }
            position = length
            add(T.BAD_QUOTED_NAME, start)
            fail("S0105", start)
            return null
        }
        // a placeholder `${Name}` of a deployment tool, as in tree/Placeholders.kt
        if (currentChar == '$' && next == '{') {
            val end = placeholderEnd(position + 2)
            if (end != -1) {
                position = end
                return add(T.PLACEHOLDER, start, path.subSequence(start, end).toString(), "placeholder")
            }
        }
        // test for names
        var i = position
        while (i < length && !isSpace(path[i]) && path[i] !in OPERATOR_CHARS) i++
        position = i
        val name = path.subSequence(start, i).toString()
        if (currentChar == '$') return add(T.VARIABLE, start, name.substring(1), "variable") // variable reference
        return when (name) {
            "or", "in", "and" -> add(operatorTokens.getValue(name), start, name, "operator")
            "true" -> add(T.TRUE, start, name, "value")
            "false" -> add(T.FALSE, start, name, "value")
            "null" -> add(T.NULL, start, name, "value")
            else -> add(T.NAME, start, name, "name")
        }
    }

    /** The end of `${…}` whose name starts at [from], or -1: one or more of letter, digit, `_ - . : ,`, then `}`. */
    private fun placeholderEnd(from: Int): Int {
        var i = from
        while (i < length) {
            val c = Character.codePointAt(path, i)
            if (!(Character.isLetter(c) || c in '0'.code..'9'.code || c == '_'.code || c == '-'.code || c == '.'.code ||
                    c == ':'.code || c == ','.code)) break
            i += Character.charCount(c)
        }
        return if (i > from && i < length && path[i] == '}') i + 1 else -1
    }

    /** parser.js: var scanRegex. The pattern is not compiled. */
    private fun scanRegex(tokenStart: Int): Tok? {
        // the prefix '/' will have been previously scanned. Find the end of the regex.
        // search for closing '/' ignoring any that are escaped, or within brackets
        val start = position
        var depth = 0
        fun isClosingSlash(position: Int): Boolean {
            if (path[position] == '/' && depth == 0) {
                var backslashCount = 0
                while (charAt(position - (backslashCount + 1)) == '\\') backslashCount++
                if (backslashCount % 2 == 0) return true
            }
            return false
        }
        while (position < length) {
            val currentChar = path[position]
            if (isClosingSlash(position)) {
                // end of regex found
                val empty = position == start
                position++
                // flags
                while (charAt(position) == 'i' || charAt(position) == 'm') position++
                val token = add(T.REGEX, tokenStart, path.subSequence(tokenStart, position).toString(), "regex")
                if (empty) fail("S0301", tokenStart)
                return token
            }
            if ((currentChar == '(' || currentChar == '[' || currentChar == '{') && charAt(position - 1) != '\\') depth++
            if ((currentChar == ')' || currentChar == ']' || currentChar == '}') && charAt(position - 1) != '\\') depth--
            position++
        }
        add(T.BAD_REGEX, tokenStart)
        fail("S0302", tokenStart)
        return null
    }

    private fun scanString(start: Int, quoteType: Char): Tok? {
        // the first error of the string; the string is scanned to its end all the same
        var code: String? = null
        position++
        while (position < length) {
            val currentChar = path[position]
            if (currentChar == '\\') { // escape sequence
                position++
                val escape = charAt(position)
                if (position < length && escape in "\"\\/bfnrt") {
                    // a JSON escape
                } else if (position < length && escape == 'u') {
                    // \u should be followed by 4 hex digits; the reference tests /^[0-9a-fA-F]+$/ on up to 4 characters
                    val octetsEnd = minOf(position + 5, length)
                    if (octetsEnd > position + 1 && (position + 1 until octetsEnd).all { isHex(path[it]) }) position += 4
                    else code = code ?: "S0104"
                } else {
                    code = code ?: "S0103" // illegal escape sequence
                }
            } else if (currentChar == quoteType) {
                position++
                val token = add(T.STRING, start, "", "string")
                if (code != null) fail(code, start)
                return token
            }
            position++
        }
        position = length
        add(T.BAD_STRING, start)
        fail(code ?: "S0101", start)
        return null
    }

    private fun scanNumber(start: Int): Tok {
        var i = position
        if (path[i] == '0') i++ else while (i < length && isDigit(path[i])) i++
        if (charAt(i) == '.' && isDigit(charAt(i + 1))) {
            i += 2
            while (i < length && isDigit(path[i])) i++
        }
        if (charAt(i) == 'e' || charAt(i) == 'E') {
            var j = i + 1
            if (charAt(j) == '-' || charAt(j) == '+') j++
            if (isDigit(charAt(j))) {
                while (j < length && isDigit(path[j])) j++
                i = j
            }
        }
        position = i
        val text = path.subSequence(start, i).toString()
        val token = add(T.NUMBER, start, text, "number")
        if (text.toDouble().isInfinite()) fail("S0102", start)
        return token
    }
}

/** parser.js: the symbols of the symbol table, with binding power [lbp] and the functions [nud] and [led]. */
private class Sym(val id: String) {
    var lbp = 0
    var nud: ((Tok) -> PNode)? = null
    var led: ((Tok, PNode) -> PNode)? = null
}

/** A node built, with what the reference reads of it: its `type`, its `value` and the token at its `position`. */
private class PNode(val scan: ScanNode, val refType: String, val value: String, val main: Tok)

/** A node under construction. A frame without a type is transparent: on an error its children go to the parent. */
private class Frame(var type: IElementType?, var start: Int) {
    val children = ArrayList<PNode>()
}

/** parser.js: var parser, without the recover mode and without processAST. */
private class Parser(private val source: CharSequence) {
    private val tokens = ArrayList<Tok>()
    private val lexer = Lexer(source, tokens)
    private val symbolTable = HashMap<String, Sym>()
    private val end = Tok(null, source.length, source.length, "(end)", "")

    private var node: Tok? = null
    private val current: Tok get() = node!!

    /** The end of the last token consumed. */
    private var lastEnd = 0
    private val frames = ArrayList<Frame>()

    private fun failure(code: String, token: Tok) = Failure(ScanError(code, token.start, token.end))

    // parser.js: var symbol
    private fun symbol(id: String, bp: Int = 0): Sym {
        val existing = symbolTable[id]
        if (existing != null) {
            if (bp >= existing.lbp) existing.lbp = bp
            return existing
        }
        return Sym(id).also { it.lbp = bp; symbolTable[id] = it }
    }

    // parser.js: base_symbol.nud: the symbol has been invoked as a unary operator
    private fun nud(t: Tok): PNode = t.sym.nud?.invoke(t) ?: throw failure("S0211", t)

    private fun led(t: Tok, left: PNode): PNode = t.sym.led?.invoke(t, left) ?: throw failure("S0211", t)

    // parser.js: var advance
    private fun advance(id: String? = null, infix: Boolean = false): Tok {
        val old = node
        if (id != null && old!!.id != id) throw failure(if (old.id == "(end)") "S0203" else "S0202", old)
        if (old != null && old !== end) lastEnd = old.end
        val nextToken = lexer.next(infix)
        lexer.error?.let { throw Failure(it) }
        if (nextToken == null) {
            node = end
            return end
        }
        nextToken.sym = when (nextToken.refType) {
            "name", "variable" -> symbolTable.getValue("(name)")
            "operator" -> symbolTable[nextToken.value] ?: throw failure("S0204", nextToken)
            "regex" -> symbolTable.getValue("(regex)")
            else -> symbolTable.getValue("(literal)") // string, number, value, placeholder
        }
        node = nextToken
        return nextToken
    }

    // Pratt's algorithm. parser.js: var expression
    private fun expression(rbp: Int): PNode {
        val frame = open(null, -1)
        var t = current
        advance(null, true)
        var left = nud(t)
        while (rbp < current.sym.lbp) {
            t = current
            advance()
            left = led(t, left)
        }
        close(frame)
        return left
    }

    // --- building the tree

    private fun open(type: IElementType?, start: Int): Frame = Frame(type, start).also { frames += it }

    /** Opens a frame whose first child is [left], the last node built in the current frame. */
    private fun openWith(type: IElementType, left: PNode): Frame {
        val children = frames.last().children
        check(children.lastOrNull() === left)
        children.removeAt(children.size - 1)
        return open(type, left.scan.start).also { it.children += left }
    }

    /** Closes the innermost frame [frame] at the last token consumed and adds the node to the frame around it. */
    private fun close(frame: Frame, main: Tok? = null, refType: String = "", value: String = ""): PNode? {
        check(frames.last() === frame)
        frames.removeAt(frames.size - 1)
        val parent = frames.lastOrNull()
        val type = frame.type
        if (type == null) {
            parent?.children?.addAll(frame.children)
            return null
        }
        val start = if (frame.start >= 0) frame.start else frame.children.firstOrNull()?.scan?.start ?: return null
        val built = PNode(ScanNode(type, start, maxOf(start, lastEnd), frame.children.map { it.scan }), refType, value, main ?: end)
        parent?.children?.add(built)
        return built
    }

    private fun closeNode(frame: Frame, main: Tok, refType: String, value: String = ""): PNode = close(frame, main, refType, value)!!

    /** A node of the single token [t], added to the current frame. */
    private fun leaf(type: IElementType, t: Tok): PNode {
        val built = PNode(ScanNode(type, t.start, t.end, emptyList()), t.refType, t.value, t)
        frames.last().children += built
        return built
    }

    private fun isVariable(n: PNode) = n.refType == "variable"

    // --- the symbol table, in the order of parser.js

    private fun terminal(id: String) {
        symbol(id, 0).nud = { t -> terminalNode(t) }
    }

    private fun terminalNode(t: Tok): PNode = when (t.id) {
        // The end where a value is expected. The reference finds this in processAST (S0207).
        "(end)" -> throw failure("S0207", t)
        "(regex)" -> leaf(E.REGEX_LITERAL, t)
        "(literal)" -> leaf(if (t.kind == T.PLACEHOLDER) E.PLACEHOLDER_REF else E.LITERAL, t)
        "(name)" -> leaf(if (t.kind == T.VARIABLE) (if (t.value == "" || t.value == "$") E.CONTEXT_REF else E.VARIABLE_REF) else E.FIELD, t)
        else -> { // and, or, in used as a name
            t.kind = T.NAME
            leaf(E.FIELD, t)
        }
    }

    // match infix operators: <expression> <operator> <expression>, left associative
    private fun infix(id: String, bp: Int = 0, led: ((Tok, PNode) -> PNode)? = null): Sym {
        val bindingPower = if (bp != 0) bp else operators.getValue(id)
        val s = symbol(id, bindingPower)
        s.led = led ?: { t, left ->
            val frame = openWith(if (id == ".") E.PATH else E.BINARY, left)
            expression(bindingPower)
            closeNode(frame, t, "binary")
        }
        return s
    }

    // match infix operators, right associative
    private fun infixr(id: String, bp: Int, led: (Tok, PNode) -> PNode): Sym = symbol(id, bp).also { it.led = led }

    // match prefix operators: <operator> <expression>
    private fun prefix(id: String, nud: ((Tok) -> PNode)? = null): Sym {
        val s = symbol(id)
        s.nud = nud ?: { t ->
            val frame = open(E.NEGATION, t.start)
            expression(70)
            closeNode(frame, t, "unary")
        }
        return s
    }

    init {
        terminal("(end)")
        end.sym = symbolTable.getValue("(end)")
        terminal("(name)")
        terminal("(literal)")
        terminal("(regex)")
        symbol(":")
        symbol(";")
        symbol(",")
        symbol(")")
        symbol("]")
        symbol("}")
        symbol("..") // range operator
        infix(".") // map operator
        infix("+") // numeric addition
        infix("-") // numeric subtraction
        infix("*") // numeric multiplication
        infix("/") // numeric division
        infix("%") // numeric modulus
        infix("=") // equality
        infix("<") // less than
        infix(">") // greater than
        infix("!=") // not equal to
        infix("<=") // less than or equal
        infix(">=") // greater than or equal
        infix("&") // string concatenation
        infix("and") // Boolean AND
        infix("or") // Boolean OR
        infix("in") // is member of array
        terminal("and") // the 'keywords' can also be used as terminals (field names)
        terminal("or")
        terminal("in")
        prefix("-") // unary numeric negation
        infix("~>") // function application
        // the operators of JSONata 2.1, which jsonata-js 2.0.6 does not know
        infix("?:", 40)
        infix("??", 40)

        // field wildcard (single level)
        prefix("*") { t -> leaf(E.WILDCARD, t) }
        // descendant wildcard (multi-level)
        prefix("**") { t -> leaf(E.DESCENDANTS, t) }
        // parent operator
        prefix("%") { t -> leaf(E.PARENT, t) }

        // function invocation
        infix("(", operators.getValue("(")) { t, left -> invocation(t, left) }

        // parenthesis - block expression
        prefix("(") { t ->
            val frame = open(E.BLOCK, t.start)
            while (current.id != ")") {
                expression(0)
                if (current.id != ";") break
                advance(";")
            }
            advance(")", true)
            closeNode(frame, t, "block")
        }

        // array constructor
        prefix("[") { t ->
            val frame = open(E.ARRAY, t.start)
            if (current.id != "]") {
                while (true) {
                    val item = expression(0)
                    if (current.id == "..") {
                        // range operator
                        val range = openWith(E.RANGE, item)
                        val rangeToken = current
                        advance("..")
                        expression(0)
                        closeNode(range, rangeToken, "binary")
                    }
                    if (current.id != ",") break
                    advance(",")
                }
            }
            advance("]", true)
            closeNode(frame, t, "unary")
        }

        // filter - predicate or array index
        infix("[", operators.getValue("[")) { t, left ->
            val frame = openWith(E.FILTER, left)
            if (current.id == "]") {
                // empty predicate means maintain singleton arrays in the output; the reference returns left itself
                advance("]")
                closeNode(frame, left.main, left.refType, left.value)
            } else {
                expression(operators.getValue("]"))
                advance("]", true)
                closeNode(frame, t, "binary")
            }
        }

        // order-by
        infix("^", operators.getValue("^")) { t, left ->
            val frame = openWith(E.ORDER_BY, left)
            advance("(")
            while (true) {
                val term = open(E.SORT_TERM, -1)
                if (current.id == "<") {
                    // ascending sort
                    term.start = current.start
                    advance("<")
                } else if (current.id == ">") {
                    // descending sort
                    term.start = current.start
                    advance(">")
                }
                expression(0)
                close(term)
                if (current.id != ",") break
                advance(",")
            }
            advance(")")
            closeNode(frame, t, "binary")
        }

        // object constructor
        prefix("{") { t -> objectParser(t, open(E.OBJECT, t.start), "unary") }

        // object grouping
        infix("{", operators.getValue("{")) { t, left -> objectParser(t, openWith(E.GROUP_BY, left), "binary") }

        // bind variable
        infixr(":=", operators.getValue(":=")) { t, left ->
            if (!isVariable(left)) throw failure("S0212", left.main)
            val frame = openWith(E.ASSIGNMENT, left)
            expression(operators.getValue(":=") - 1) // subtract 1 from bindingPower for right associative operators
            closeNode(frame, t, "binary")
        }

        // focus variable bind
        infix("@", operators.getValue("@")) { t, left -> binding(t, left, E.FOCUS_BINDING) }

        // index (position) variable bind
        infix("#", operators.getValue("#")) { t, left -> binding(t, left, E.INDEX_BINDING) }

        // if/then/else ternary operator ?:
        infix("?", operators.getValue("?")) { t, left ->
            val frame = openWith(E.CONDITIONAL, left)
            expression(0)
            if (current.id == ":") {
                // else condition
                advance(":")
                expression(0)
            }
            closeNode(frame, t, "condition")
        }

        // object transformer
        prefix("|") { t ->
            val frame = open(E.TRANSFORM, t.start)
            expression(0)
            advance("|")
            expression(0)
            if (current.id == ",") {
                advance(",")
                expression(0)
            }
            advance("|")
            closeNode(frame, t, "transform")
        }
    }

    private fun binding(t: Tok, left: PNode, type: IElementType): PNode {
        val frame = openWith(type, left)
        val rhs = expression(operators.getValue(t.id))
        if (!isVariable(rhs)) throw failure("S0214", rhs.main)
        return closeNode(frame, t, "binary")
    }

    private fun objectParser(t: Tok, frame: Frame, refType: String): PNode {
        if (current.id != "}") {
            while (true) {
                val pair = open(E.PAIR, -1)
                expression(0)
                advance(":")
                expression(0)
                close(pair) // holds the name/value expression pair
                if (current.id != ",") break
                advance(",")
            }
        }
        advance("}", true)
        return closeNode(frame, t, refType)
    }

    private fun invocation(t: Tok, left: PNode): PNode {
        // left is what we are trying to invoke
        val frame = openWith(E.CALL, left)
        val arguments = ArrayList<PNode>()
        if (current.id != ")") {
            while (true) {
                if (current.refType == "operator" && current.id == "?") {
                    // partial function application
                    arguments += leaf(E.PARTIAL_ARGUMENT, current)
                    advance("?")
                } else {
                    arguments += expression(0)
                }
                if (current.id != ",") break
                advance(",")
            }
        }
        advance(")", true)
        // if the name of the function is 'function' or λ, then this is function definition (lambda function)
        if (left.refType == "name" && (left.value == "function" || left.value == "λ")) {
            // all of the args must be VARIABLE tokens
            for (arg in arguments) if (!isVariable(arg)) throw failure("S0208", arg.main)
            frame.type = E.LAMBDA
            frame.children.clear()
            for (arg in arguments) frame.children += PNode(ScanNode(E.PARAMETER, arg.main.start, arg.main.end, emptyList()), "variable", arg.value, arg.main)
            // is the next token a '<' - if so, parse the function signature
            if (current.id == "<") {
                val signature = open(E.SIGNATURE, current.start)
                var depth = 1
                while (depth > 0 && current.id != "{" && current.id != "(end)") {
                    val tok = advance()
                    if (tok.id == ">") depth-- else if (tok.id == "<") depth++
                }
                advance(">")
                close(signature) // the signature is not validated
            }
            // parse the function body
            advance("{")
            expression(0)
            advance("}")
            return closeNode(frame, t, "lambda")
        }
        return closeNode(frame, t, "function")
    }

    fun run(): JsonataScan {
        val root = open(null, -1)
        var error: ScanError? = null
        try {
            // now invoke the tokenizer and the parser
            advance()
            expression(0)
            if (current.id != "(end)") throw failure("S0201", current)
        } catch (failure: Failure) {
            error = failure.error
        } catch (_: StackOverflowError) {
            // nested too deeply to follow; not reported, since the reference may still parse it
        }
        // close the nodes under construction at the last token consumed
        while (frames.size > 1) close(frames.last())
        frames.clear()
        // the rest of the text, with every '/' read as division
        while (lexer.position < source.length) {
            val before = lexer.position
            lexer.next(true)
            if (lexer.position == before) break
        }
        return JsonataScan(tokens.map { ScanToken(it.kind!!, it.start, it.end) }, root.children.firstOrNull()?.scan, error)
    }
}
