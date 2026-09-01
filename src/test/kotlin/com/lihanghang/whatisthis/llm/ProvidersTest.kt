package com.lihanghang.whatisthis.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {

    @Test
    fun `GLM-5_3-Flash is suggested and known vision`() {
        val preset = Providers.byId("zhipu")
        assertTrue(preset.modelSuggestions.contains("glm-5.3-flash"))
        assertTrue(Providers.isKnownVision("zhipu", "glm-5.3-flash"))
    }

    @Test
    fun `thinking cannot be disabled on always-on models`() {
        // GLM-5.3-Flash 官方文档：thinking.type 仅支持 enabled
        assertFalse(Providers.canDisableThinking("zhipu", "glm-5.3-flash"))
    }

    @Test
    fun `thinking can be disabled on switchable GLM models`() {
        assertTrue(Providers.canDisableThinking("zhipu", "glm-4.6v"))
        assertTrue(Providers.canDisableThinking("zhipu", "glm-4.5v"))
    }

    @Test
    fun `thinking can be disabled on DeepSeek models`() {
        assertTrue(Providers.canDisableThinking("deepseek", "deepseek-v4-flash-vision-exp"))
        assertTrue(Providers.canDisableThinking("deepseek", "deepseek-chat"))
    }

    @Test
    fun `thinking param is never sent to providers without the switch`() {
        assertFalse(Providers.canDisableThinking("openai", "gpt-4o-mini"))
        assertFalse(Providers.canDisableThinking(Providers.CUSTOM, "deepseek-chat"))
        assertFalse(Providers.canDisableThinking("unknown-provider", "glm-4.6v"))
    }
}
