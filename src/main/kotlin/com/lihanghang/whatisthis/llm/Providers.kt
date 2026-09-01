package com.lihanghang.whatisthis.llm

/**
 * OpenAI-compatible provider presets. All providers share one protocol,
 * so the whole catalog is just data.
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val modelSuggestions: List<String> = listOf(defaultModel),
    /** Prefixes of models known to accept image input. */
    val visionModelPrefixes: List<String> = listOf(),
    val keyUrl: String = "",
    /**
     * Provider accepts `{"thinking": {"type": "disabled"}}` to switch off
     * built-in chain-of-thought. Only set for providers that document the
     * parameter - strict endpoints (OpenAI) reject unknown body fields.
     */
    val thinkingSwitch: Boolean = false,
    /**
     * Model prefixes whose `thinking.type` only accepts `enabled` (e.g.
     * GLM-5.3-Flash 不支持关闭思考) - never send `disabled` to these.
     */
    val thinkingAlwaysOnPrefixes: List<String> = listOf(),
)

object Providers {
    const val CUSTOM = "custom"

    private val customPreset = ProviderPreset(
        id = CUSTOM,
        displayName = "自定义（OpenAI 兼容）",
        baseUrl = "",
        defaultModel = "",
        modelSuggestions = emptyList(),
        visionModelPrefixes = emptyList(),
    )

    val ALL: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-v4-flash-vision-exp",
            modelSuggestions = listOf(
                "deepseek-v4-flash-vision-exp",
                "deepseek-v4-pro",
                "deepseek-chat",
            ),
            visionModelPrefixes = listOf("deepseek-v4-flash-vision", "deepseek-v4-pro"),
            keyUrl = "https://platform.deepseek.com/api_keys",
            thinkingSwitch = true,
        ),
        ProviderPreset(
            id = "doubao",
            displayName = "豆包 · 火山方舟",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            defaultModel = "doubao-seed-1-6-vision-250815",
            modelSuggestions = listOf(
                "doubao-seed-1-6-vision-250815",
                "doubao-1-5-vision-pro-32k-250115",
            ),
            visionModelPrefixes = listOf(
                "doubao-seed-1-6-vision",
                "doubao-1-5-vision",
                "doubao-vision",
            ),
            keyUrl = "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey",
            thinkingSwitch = true,
        ),
        ProviderPreset(
            id = "zhipu",
            displayName = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4.5v",
            modelSuggestions = listOf("glm-5.3-flash", "glm-4.5v", "glm-4.6v", "glm-4.5-air"),
            visionModelPrefixes = listOf("glm-5.3-flash", "glm-4.5v", "glm-4.6v", "glm-4v"),
            keyUrl = "https://open.bigmodel.cn/usercenter/apikeys",
            thinkingSwitch = true,
            thinkingAlwaysOnPrefixes = listOf("glm-5.3-flash"),
        ),
        ProviderPreset(
            id = "moonshot",
            displayName = "月之暗面 Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "moonshot-v1-8k-vision-preview",
            modelSuggestions = listOf(
                "moonshot-v1-8k-vision-preview",
                "moonshot-v1-32k-vision-preview",
            ),
            visionModelPrefixes = listOf("vision"),
            keyUrl = "https://platform.moonshot.cn/console/api-keys",
        ),
        ProviderPreset(
            id = "qwen",
            displayName = "阿里百炼 Qwen",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-vl-max",
            modelSuggestions = listOf("qwen-vl-max", "qwen-vl-plus", "qwen3-vl-plus"),
            visionModelPrefixes = listOf("qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen3-vl"),
            keyUrl = "https://bailian.console.aliyun.com/?apiKey=1",
        ),
        ProviderPreset(
            id = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            modelSuggestions = listOf("gpt-4o-mini", "gpt-4o"),
            visionModelPrefixes = listOf("gpt-4o", "gpt-4.1", "chatgpt-4o", "o4"),
            keyUrl = "https://platform.openai.com/api-keys",
        ),
        customPreset,
    )

    fun byId(id: String): ProviderPreset = ALL.firstOrNull { it.id == id } ?: customPreset

    /**
     * Whether [model] is known to accept image input under provider [id].
     * Unknown names (new models, custom endpoints) return false -> UI warns but still allows sending.
     */
    fun isKnownVision(id: String, model: String): Boolean =
        byId(id).visionModelPrefixes.any { prefix -> model.startsWith(prefix, ignoreCase = true) }

    /**
     * Whether we should send `{"thinking": {"type": "disabled"}}` for [model]:
     * provider documents the switch AND this model can actually turn thinking off.
     */
    fun canDisableThinking(id: String, model: String): Boolean =
        byId(id).let { preset ->
            preset.thinkingSwitch &&
                preset.thinkingAlwaysOnPrefixes.none { model.startsWith(it, ignoreCase = true) }
        }
}
