package com.lihanghang.whatisthis.ui

import com.intellij.ui.JBColor

/**
 * Tiny streaming-friendly Markdown -> HTML converter for a JEditorPane.
 * Re-rendering the whole transcript on every delta is fine for answers
 * capped at ~600 tokens, and it keeps the code trivial.
 */
object MarkdownToHtml {

    /** Some models emit thinking blocks; the user asked a quick question - drop them. */
    private val THINK_BLOCK = Regex("(?s)<think>.*?(</think>|$)")

    fun toHtml(markdown: String): String {
        val cleaned = markdown.replace(THINK_BLOCK, "")
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
        val font = javax.swing.UIManager.getFont("Label.font")?.family ?: "sans-serif"
        return """
            body { font-family: '$font'; font-size: 10pt; }
            pre  { background: $preBg; color: $preFg; font-family: '${font} Mono', monospace;
                   font-size: 9pt; padding: 4px 6px; }
            code { color: $codeColor; font-family: '${font} Mono', monospace; }
        """.trimIndent()
    }
}
