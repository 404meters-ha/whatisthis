package com.lihanghang.whatisthis.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException

/**
 * Connect-level failures must surface as actionable Chinese text, not raw
 * JDK messages like "Remote host terminated the handshake". Everything
 * unrecognized passes through untouched.
 */
class LlmClientTest {

    fun testProxyKilledHandshakeGetsActionableText() {
        val text = LlmClient.friendlyText(SSLHandshakeException("Remote host terminated the handshake"))
        assertEquals("TLS 握手被中断（多为 IDE/系统代理干扰），已自动重试仍失败，请检查代理设置", text)
    }

    fun testConnectRefusalGetsActionableText() {
        val text = LlmClient.friendlyText(ConnectException("Connection refused: connect"))
        assertEquals("无法建立连接（网络不可达或被代理阻断），请检查网络与代理设置", text)
    }

    fun testConnectionResetGetsActionableText() {
        val text = LlmClient.friendlyText(IOException("Connection reset by peer"))
        assertEquals("连接被重置（网络或代理不稳定），请重试", text)
    }

    fun testUnrecognizedErrorsPassThrough() {
        assertNull(LlmClient.friendlyText(RuntimeException("HTTP 500: boom")))
        assertNull(LlmClient.friendlyText(IOException()))
        assertNull(LlmClient.friendlyText(RuntimeException()))
    }
}
