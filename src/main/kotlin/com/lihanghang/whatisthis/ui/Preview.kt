package com.lihanghang.whatisthis.ui

/**
 * Bounded transcript preview of what was asked: a one-line title plus an
 * optional fenced code block, with honest truncation markers - the user
 * must always see what was actually sent, never a silently clipped line.
 */
object Preview {

    private const val LABEL_CHARS = 60
    private const val BLOCK_LINES = 6
    private const val LINE_CHARS = 120

    /** One-line title, e.g. `from x import y…（共 12 行）`. */
    fun label(body: String): String {
        val first = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return "…"
        val lines = body.lines().size
        val head = if (first.length > LABEL_CHARS) first.take(LABEL_CHARS) + "…" else first
        return if (lines > 1) "$head（共 $lines 行）" else head
    }

    /** First lines for a fenced code block; null when a block adds nothing. */
    fun block(body: String): String? {
        val lines = body.lines()
        if (lines.size <= 1) return null
        val shown = lines.take(BLOCK_LINES).map { line ->
            val trimmed = line.trimEnd()
            if (trimmed.length > LINE_CHARS) trimmed.take(LINE_CHARS) + "…" else trimmed
        }
        // A fence inside the selection would toggle the renderer's own fence.
        if (shown.any { it.trimStart().startsWith("```") }) return null
        return if (lines.size > BLOCK_LINES) {
            shown.joinToString("\n") + "\n…（共 ${lines.size} 行，仅展示前 $BLOCK_LINES 行）"
        } else {
            shown.joinToString("\n")
        }
    }
}
