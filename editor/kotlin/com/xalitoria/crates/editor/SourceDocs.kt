package com.xalitoria.crates.editor

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

data class ClassDocs(val doc: String, val fields: Map<String, FieldDocs>)

data class FieldDocs(val doc: String, val default: String?)

/**
 * Extracts KDoc comments and literal default expressions from Kotlin model source files so `schema.json` carries the
 * exact documentation written on `CrateConfig`, `AnimationConfig`, and their nested data classes.
 */
class SourceDocs(private val classes: Map<String, ClassDocs>) {
    fun find(serialName: String): ClassDocs? = classes[serialName]

    companion object {
        private val DECLARATION = Regex("""\b(class|interface|object)\s+(\w+)""")
        private val ANNOTATION = Regex("""@\w+(\([^)]*\))?""")
        private val MODIFIERS = setOf("data", "sealed", "enum", "abstract", "open", "internal", "private", "public", "fun", "annotation", "value")
        private val SERIAL_NAME = Regex("""@SerialName\("([^"]+)"\)""")

        fun scan(vararg roots: Path): SourceDocs {
            val found = mutableMapOf<String, ClassDocs>()
            for (root in roots) {
                if (!Files.exists(root)) continue
                Files.walk(root).use { paths ->
                    paths.filter { it.extension == "kt" }.forEach { file ->
                        scanFile(Files.readString(file)).forEach { [name, docs] -> found[name] = docs }
                    }
                }
            }
            return SourceDocs(found)
        }

        internal fun scanFile(text: String): List<Pair<String, ClassDocs>> {
            val lines = text.split('\n')
            val pkg = lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim().orEmpty()
            val result = mutableListOf<Pair<String, ClassDocs>>()
            val stack = ArrayDeque<Pair<String, Int>>()
            var index = 0
            while (index < lines.size) {
                val line = lines[index]
                val match = DECLARATION.find(line)
                val before = match?.let { line.substring(0, it.range.first).replace(ANNOTATION, " ").trim() }
                val isDecl = match != null && before!!.split(Regex("""\s+""")).all { it.isEmpty() || it in MODIFIERS } &&
                    line.trimStart().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
                if (!isDecl) {
                    index++
                    continue
                }
                val indent = line.length - line.trimStart().length
                while (stack.isNotEmpty() && stack.last().second >= indent) stack.removeLast()
                val name = match.groupValues[2]
                stack.addLast(name to indent)

                val [doc, annotations] = leadingComment(lines, index)
                val serialName = SERIAL_NAME.find(line)?.groupValues?.get(1)
                    ?: SERIAL_NAME.find(annotations)?.groupValues?.get(1)
                    ?: (listOf(pkg) + stack.map { it.first }).filter { it.isNotEmpty() }.joinToString(".")

                val open = line.indexOf('(', match.range.last)
                val fields = if (open >= 0) {
                    val [body, end, _] = balanced(lines, index, open)
                    index = end
                    parameters(body)
                } else emptyMap()

                result.add(serialName to ClassDocs(doc, fields))
                index++
            }
            return result
        }

        private fun leadingComment(lines: List<String>, at: Int): Pair<String, String> {
            var i = at - 1
            val annotations = StringBuilder()
            while (i >= 0 && lines[i].trim().startsWith("@")) {
                annotations.append(lines[i]).append(' ')
                i--
            }
            if (i < 0 || !lines[i].trim().endsWith("*/")) return "" to annotations.toString()
            var start = i
            while (start >= 0 && !lines[start].contains("/**")) start--
            if (start < 0) return "" to annotations.toString()
            return clean(lines.subList(start, i + 1).joinToString("\n")) to annotations.toString()
        }

        private fun balanced(lines: List<String>, from: Int, column: Int): Triple<String, Int, String> {
            val body = StringBuilder()
            var depth = 0
            var inString = false
            var lineIndex = from
            var col = column
            while (lineIndex < lines.size) {
                val text = lines[lineIndex]
                while (col < text.length) {
                    val c = text[col]
                    if (inString) {
                        if (c == '\\') {
                            body.append(c)
                            col++
                            if (col < text.length) body.append(text[col])
                        } else {
                            body.append(c)
                            if (c == '"') inString = false
                        }
                    } else when (c) {
                        '"' -> { inString = true; body.append(c) }
                        '(' -> { depth++; if (depth > 1) body.append(c) }
                        ')' -> {
                            depth--
                            if (depth == 0) return Triple(body.toString(), lineIndex, text.substring(col + 1))
                            body.append(c)
                        }
                        else -> body.append(c)
                    }
                    col++
                }
                body.append('\n')
                lineIndex++
                col = 0
            }
            return Triple(body.toString(), lines.lastIndex, "")
        }

        private fun parameters(body: String): Map<String, FieldDocs> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var inString = false
            var inComment = false
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    inComment -> {
                        current.append(c)
                        if (c == '*' && body.getOrNull(i + 1) == '/') {
                            current.append('/')
                            i++
                            inComment = false
                        }
                    }
                    inString -> {
                        current.append(c)
                        if (c == '\\') {
                            i++
                            current.append(body.getOrNull(i) ?: ' ')
                        } else if (c == '"') {
                            inString = false
                        }
                    }
                    c == '/' && body.getOrNull(i + 1) == '*' -> { inComment = true; current.append(c) }
                    c == '"' -> { inString = true; current.append(c) }
                    c in "([{<" -> { depth++; current.append(c) }
                    c in ")]}" -> { depth--; current.append(c) }
                    c == '>' && body.getOrNull(i - 1) != '-' -> { depth--; current.append(c) }
                    c == ',' && depth == 0 -> { parts.add(current.toString()); current.clear() }
                    else -> current.append(c)
                }
                i++
            }
            if (current.isNotBlank()) parts.add(current.toString())

            val result = mutableMapOf<String, FieldDocs>()
            for (part in parts) {
                val comment = Regex("""/\*\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).find(part)
                val serial = SERIAL_NAME.find(part)?.groupValues?.get(1)
                val rest = part.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
                val decl = Regex("""\b(?:val|var)\s+(\w+)\s*:\s*(.+?)(?:=(.*))?$""", RegexOption.DOT_MATCHES_ALL).find(rest) ?: continue
                val fieldDocs = FieldDocs(
                    doc = comment?.let { clean(it.value) }.orEmpty(),
                    default = decl.groupValues[3].trim().ifEmpty { null },
                )
                result[decl.groupValues[1]] = fieldDocs
                if (serial != null) result[serial] = fieldDocs
            }
            return result
        }

        internal fun clean(block: String): String {
            val lines = block.lines().map { it.trim().removePrefix("/**").removeSuffix("*/").trim().removePrefix("*").trim() }
            val paragraphs = mutableListOf<String>()
            val current = StringBuilder()
            for (line in lines) {
                if (line.isEmpty()) {
                    if (current.isNotEmpty()) { paragraphs.add(current.toString()); current.clear() }
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(line)
                }
            }
            if (current.isNotEmpty()) paragraphs.add(current.toString())
            return paragraphs.joinToString("\n\n").replace(Regex("""\[(\w+(?:\.\w+)*)]"""), "`$1`")
        }
    }
}
