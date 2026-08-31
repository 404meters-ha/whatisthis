package com.lihanghang.whatisthis.llm

/**
 * The two system prompts decide the whole product: classify + answer in a
 * SINGLE call (never a classify-then-route two-call pipeline - that would
 * double the latency we are selling against).
 */
object Prompts {

    private const val TEXT_SYSTEM = """
你是 IDE 插件 WhatIsThis 的解释引擎。用户在 IDE 中选中了一段代码/配置/文件。
要求：要点式 Markdown，总长 ≤200 字，不寒暄，不复读代码。
结构：【这是什么】一句话；【干嘛用的】要点；【何时会用到】要点。
若看到 import、依赖名、注解（如 EasyRules、MyBatis），结合你的知识推断它在此项目中的角色（例：引入 EasyRules → 规则引擎，做公式/规则计算）。
不确定就直说，禁止编造。
"""

    private const val VISION_SYSTEM = """
你是 IDE 插件 WhatIsThis 的解释引擎。用户粘贴了一张截图。先识别类别，分门别类回答：
①按钮/界面控件 → 是什么、点击后会发生什么、典型使用场景；
②代码截图 → 这段代码干嘛的、用了什么语法糖、常见用途；
③报错信息 → 什么错、最可能原因、排查方向；
④图表/其他 → 一两句话说明它展示什么。
要求：要点式 Markdown，≤200 字，不寒暄。不确定就直说，禁止编造。
"""

    fun systemPrompt(vision: Boolean, language: String): String {
        val languageLine = when (language) {
            "en" -> "语言要求：用英文回答。"
            else -> "语言要求：用简体中文回答。"
        }
        return (if (vision) VISION_SYSTEM else TEXT_SYSTEM).trim() + "\n" + languageLine
    }

    fun visionUserText(): String =
        "这是什么？请说明它的用途与使用场景；如果是代码，请解释作用与用到的语法糖。"

    /**
     * Builds the user message for the text pipeline: a short context header
     * (file path + imports is what enables "轻推理" like EasyRules -> 规则计算)
     * followed by the actual content in a fenced block.
     */
    fun textUserContent(kind: String, fileNote: String?, body: String, languageTag: String?): String =
        buildString {
            append("【类型】").append(kind).append('\n')
            if (!fileNote.isNullOrBlank()) {
                append("【上下文】\n").append(fileNote).append('\n')
            }
            append("【内容】\n```")
            if (!languageTag.isNullOrBlank()) append(languageTag)
            append('\n').append(body).append("\n```\n")
        }
}
