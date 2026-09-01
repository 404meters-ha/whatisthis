package com.lihanghang.whatisthis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownToHtmlTest {

    @Test
    fun `stripThinking removes a closed think block`() {
        assertEquals("答案", MarkdownToHtml.stripThinking("<think>推理</think>答案"))
    }

    @Test
    fun `stripThinking removes an unterminated think block`() {
        // Truncated mid-CoT: the whole visible answer was thinking only.
        assertEquals("", MarkdownToHtml.stripThinking("<think>模型还在思考，被 max_tokens 截断"))
    }

    @Test
    fun `stripThinking keeps plain answers untouched`() {
        assertEquals("**要点** `code`", MarkdownToHtml.stripThinking("**要点** `code`"))
    }

    @Test
    fun `toHtml drops thinking but keeps the answer`() {
        val html = MarkdownToHtml.toHtml("<think>chain</think>【这是什么】一个规则引擎")
        assertTrue(html.contains("一个规则引擎"))
        assertTrue(!html.contains("chain"))
    }
}
