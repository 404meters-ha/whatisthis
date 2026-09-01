package com.lihanghang.whatisthis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTest {

    @Test
    fun `label shows first line with total line count for multi-line body`() {
        val body = "from comfy_execution.jobs import (\n    get_job,\n    cancel_job,\n)"
        assertEquals("from comfy_execution.jobs import (（共 4 行）", Preview.label(body))
    }

    @Test
    fun `label ellipsizes over-long first line`() {
        val body = "x".repeat(100)
        assertEquals("x".repeat(60) + "…", Preview.label(body))
    }

    @Test
    fun `label of blank body falls back`() {
        assertEquals("…", Preview.label("  \n "))
    }

    @Test
    fun `block is null for single-line body`() {
        assertNull(Preview.block("val x = 1"))
    }

    @Test
    fun `block shows short multi-line body in full`() {
        val body = "a\nb\nc"
        assertEquals("a\nb\nc", Preview.block(body))
    }

    @Test
    fun `block truncates long body with explicit marker`() {
        val body = (1..10).joinToString("\n") { "line$it" }
        val block = Preview.block(body)!!
        assertTrue(block.startsWith("line1\nline2\nline3\nline4\nline5\nline6"))
        assertTrue(block.endsWith("…（共 10 行，仅展示前 6 行）"))
    }

    @Test
    fun `block caps over-wide lines`() {
        val body = "a\n" + "y".repeat(200)
        val block = Preview.block(body)!!
        assertTrue(block.contains("y".repeat(120) + "…"))
    }

    @Test
    fun `block declines bodies containing a markdown fence`() {
        assertNull(Preview.block("a\n```\nb"))
    }
}
