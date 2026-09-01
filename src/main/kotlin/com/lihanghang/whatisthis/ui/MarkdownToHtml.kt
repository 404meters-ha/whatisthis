package com.lihanghang.whatisthis.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI

/**
 * Tiny streaming-friendly Markdown -> HTML converter for a JEditorPane.
 * Re-rendering the whole transcript on every delta is fine for answers
 * capped at ~600 tokens, and it keeps the code trivial.
 */
object MarkdownToHtml {

    /** Some models emit thinking blocks; the user asked a quick question - drop them. */
    private val THINK_BLOCK = Regex("(?s)<think>.*?(</think>|$)")

    /** What the user actually gets to read - thinking blocks removed. */
    fun stripThinking(markdown: String): String = markdown.replace(THINK_BLOCK, "")

    fun toHtml(markdown: String): String {
        val cleaned = stripThinking(markdown)
        val css = css()
        val body = StringBuilder()
        var inFence = false
        for (rawLine in cleaned.lines()) {
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                body.append(if (inFence) "<pre>" else "</pre>")
                continue
            }
            if (inFence) {
                body.append(escape(line)).append('\n')
                continue
            }
            val text = line.trim()
            when {
                text.isEmpty() -> body.append("<br>")
                text.startsWith("#") -> {
                    val content = text.dropWhile { it == '#' }.trim()
                    body.append("<b>").append(inline(escape(content))).append("</b><br>")
                }
                text.startsWith("- ") || text.startsWith("* ") ->
                    body.append("&nbsp;&nbsp;• ").append(inline(escape(text.substring(2)))).append("<br>")
                text.startsWith(">") ->
                    body.append("<i>").append(inline(escape(text.trimStart('>', ' ')))).append("</i><br>")
                else -> body.append(inline(escape(text))).append("<br>")
            }
        }
        if (inFence) body.append("</pre>")
        return "<html><head><style>$css</style></head><body>$body</body></html>"
    }

    private fun inline(s: String): String = s
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
        .replace(Regex("(?<!\\*)\\*([^*\\s][^*]*?)\\*(?!\\*)"), "<i>$1</i>")

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun css(): String {
        val bright = JBColor.isBright()
        val codeColor = if (bright) "#8250df" else "#d2a8ff"
        val preBg = if (bright) "#f6f8fa" else "#2b2d30"
        val preFg = if (bright) "#24292f" else "#dfe1e5"
        // Follow the IDE's fonts and scale (HiDPI, IDE zoom, custom font sizes)
        // instead of hardcoding pt sizes that ignore all of it.
        val bodyFont = JBFont.label()
        val editorFont = runCatching {
            EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        }.getOrNull()
        val monoFamily = editorFont?.family ?: "monospace"
        val monoSize = editorFont?.let { JBUI.scale(it.size) } ?: bodyFont.size
        return """
            body { font-family: '${bodyFont.family}'; font-size: ${bodyFont.size}px; }
            pre  { background: $preBg; color: $preFg; font-family: '$monoFamily', monospace;
                   font-size: ${monoSize}px; padding: 4px 6px; }
            code { color: $codeColor; font-family: '$monoFamily', monospace; }
        """.trimIndent()
    }
}
