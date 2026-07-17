package com.akashic.mobile.ui.conversation

internal sealed interface RichMessageSegment {
    data class Markdown(val content: String) : RichMessageSegment

    data class BlockMath(val content: String) : RichMessageSegment
}

private data class MarkdownFence(val marker: Char, val length: Int, val tail: String)

private fun markdownFence(line: String): MarkdownFence? {
    val indent = line.takeWhile { it == ' ' }.length
    if (indent > 3 || indent == line.length) return null
    val marker = line[indent]
    if (marker != '`' && marker != '~') return null
    val length = line.drop(indent).takeWhile { it == marker }.length
    if (length < 3) return null
    val tail = line.drop(indent + length)
    if (marker == '`' && '`' in tail) return null
    return MarkdownFence(marker, length, tail)
}

/** 将 Markdown 中的块级公式切成独立原生渲染段。 */
internal fun richMessageSegments(content: String): List<RichMessageSegment> {
    val segments = mutableListOf<RichMessageSegment>()
    val markdown = StringBuilder()
    val math = StringBuilder()
    var mathClosing: String? = null
    var codeFence: MarkdownFence? = null

    // 1. 先保护 fenced/缩进代码，再识别独占一行的块级公式边界
    content.lineSequence().forEach { line ->
        val trimmed = line.trim()
        val fence = if (mathClosing == null) markdownFence(line) else null
        val activeFence = codeFence
        if (activeFence != null) {
            markdown.appendLine(line)
            if (
                fence?.marker == activeFence.marker &&
                fence.length >= activeFence.length &&
                fence.tail.isBlank()
            ) {
                codeFence = null
            }
            return@forEach
        }
        if (fence != null) {
            codeFence = fence
            markdown.appendLine(line)
            return@forEach
        }
        val indentedCode = line.startsWith("    ") || line.startsWith('\t')
        val singleLineMath = when {
            !indentedCode && mathClosing == null &&
                trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length > 4 -> {
                trimmed.removePrefix("\\[").removeSuffix("\\]").trim()
            }
            !indentedCode && mathClosing == null &&
                trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 4 -> {
                trimmed.removePrefix("$$").removeSuffix("$$").trim()
            }
            else -> null
        }
        if (singleLineMath != null) {
            markdown.takeIf { it.isNotEmpty() }?.let {
                segments += RichMessageSegment.Markdown(it.toString().trim('\n', '\r'))
                it.clear()
            }
            segments += RichMessageSegment.BlockMath(singleLineMath)
        } else if (!indentedCode && mathClosing == null && trimmed in setOf("\\[", "$$")) {
            markdown.takeIf { it.isNotEmpty() }?.let {
                segments += RichMessageSegment.Markdown(it.toString().trim('\n', '\r'))
                it.clear()
            }
            mathClosing = if (trimmed == "\\[") "\\]" else "$$"
        } else if (mathClosing != null && trimmed == mathClosing) {
            segments += RichMessageSegment.BlockMath(math.toString().trim())
            math.clear()
            mathClosing = null
        } else if (mathClosing != null) {
            math.appendLine(line)
        } else {
            markdown.appendLine(line)
        }
    }

    // 2. 不完整公式按原文展示，避免流式阶段闪烁或丢字
    if (mathClosing != null) {
        val incomplete = buildString {
            appendLine(if (mathClosing == "\\]") "\\[" else "$$")
            append(math)
        }.trimEnd()
        val previous = segments.lastOrNull() as? RichMessageSegment.Markdown
        if (previous != null) {
            segments[segments.lastIndex] = RichMessageSegment.Markdown("${previous.content}\n$incomplete")
        } else {
            markdown.append(incomplete)
        }
    }
    markdown.takeIf { it.isNotEmpty() }?.let {
        segments += RichMessageSegment.Markdown(it.toString().trim('\n', '\r'))
    }
    return segments
}
