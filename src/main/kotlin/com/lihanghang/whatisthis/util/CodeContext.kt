package com.lihanghang.whatisthis.util

/**
 * Cheap context extraction - the whole "轻推理" story (EasyRules → 规则计算)
 * hangs on the model seeing the imports. No indexing, no PSI walking, no latency.
 */
object CodeContext {

    private val IMPORT_LINE = Regex("^\\s*(import\\s|from\\s+\\S+\\s+import\\s|using\\s|require\\s*\\(|#include)")

    fun importsOf(text: String, max: Int = 30): List<String> =
        text.lineSequence().filter { IMPORT_LINE.containsMatchIn(it) }.take(max).toList()

    /** Builds the context header for a selection: file path, language, imports. */
    fun fileNote(path: String?, fullText: String?): String? {
        if (path == null && fullText == null) return null
        return buildString {
            if (path != null) append("文件：").append(path)
            fullText?.let {
                val imports = importsOf(it)
                if (imports.isNotEmpty()) {
                    if (path != null) append('\n')
                    append("相关 imports：\n").append(imports.joinToString("\n"))
                }
            }
        }
    }

    fun truncate(text: String, maxLines: Int = 500, maxChars: Int = 60_000): String {
        val lines = text.lines()
        val byLines = if (lines.size > maxLines) {
            lines.take(maxLines).joinToString("\n") + "\n…（后 ${lines.size - maxLines} 行已截断）"
        } else {
            text
        }
        return if (byLines.length > maxChars) byLines.take(maxChars) + "\n…（内容过长已截断）" else byLines
    }
}
