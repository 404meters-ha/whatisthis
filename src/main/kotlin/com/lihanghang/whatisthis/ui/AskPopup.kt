package com.lihanghang.whatisthis.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.lihanghang.whatisthis.WhatIsThisIcons
import com.lihanghang.whatisthis.llm.LlmClient
import com.lihanghang.whatisthis.llm.Prompts
import com.lihanghang.whatisthis.llm.StreamHandle
import com.lihanghang.whatisthis.settings.EffectiveConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.HierarchyEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * The one and only surface: a popup that streams the answer, accepts
 * follow-up questions in the same conversation, and dies on Esc, the close
 * button, or the moment focus moves elsewhere - nothing lingers, because
 * lingering is the opposite of 瞬间.
 *
 * Closing deliberately does not rely on any single platform mechanism: some
 * IDE builds (notably 2026.1 EAP with the experimental UI) have flaky popup
 * dismissal, so we stack the platform's own click-outside/Esc handling with
 * our own Esc binding, a focus-loss listener, and an always-visible ✕.
 */
class AskPopup(
    private val project: Project,
    private val editor: Editor?,
    private val config: EffectiveConfig,
) : Disposable {

    /** role -> content (String for text turns, [JsonElement] for vision turns). */
    private val history = mutableListOf<Pair<String, Any>>()
    private val transcript = StringBuilder()
    private var handle: StreamHandle? = null

    @Volatile
    private var streaming = false
    private var visionConversation = false

    private val answerPane = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        isOpaque = false
        runCatching { editorKit = HTMLEditorKitBuilder().build() }
    }
    private val scrollPane = JBScrollPane(answerPane).apply {
        border = JBUI.Borders.empty()
        preferredSize = Dimension(560, 320)
    }
    private val headerLabel = JBLabel().apply {
        font = JBFont.label().asBold()
    }
    private val loadingLabel = JBLabel(" 思考中…", AnimatedIcon.Default(), SwingConstants.LEFT)
    private val closeButton = JButton(AllIcons.Actions.Close).apply {
        toolTipText = "关闭（Esc）"
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isFocusable = false
        preferredSize = JBUI.size(22, 22)
        addActionListener { closePopup() }
    }
    private val inputArea = JBTextArea(1, 40).apply {
        emptyText.setText("追问…（Enter 发送，Shift+Enter 换行）")
        lineWrap = true
        border = JBUI.Borders.empty(4, 6)
    }
    private val sendButton = JButton("发送")
    private val rootPanel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
        border = JBUI.Borders.empty(6, 8, 8, 8)
        background = JBColor.background()
    }
    private lateinit var popup: JBPopup

    /** Window the focus-loss safety net is attached to; null until shown. */
    private var focusNetWindow: Window? = null

    /** True once our window has actually held focus; earlier losses are show noise. */
    private var sawFocusGain = false

    private val focusNet = object : WindowFocusListener {
        override fun windowGainedFocus(e: WindowEvent?) {
            sawFocusGain = true
        }

        override fun windowLostFocus(e: WindowEvent?) {
            // A loss from a window that never held focus is show noise, not
            // the user moving away.
            if (!sawFocusGain) return
            // Some builds bounce focus to the main frame for one cycle while
            // the popup is being shown. Re-check once the shuffle settles and
            // close only if focus really ended up elsewhere.
            SwingUtilities.invokeLater {
                if (SwingUtilities.getWindowAncestor(rootPanel)?.isFocused != true) closePopup()
            }
        }
    }

    init {
        loadingLabel.isVisible = false
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(headerLabel, BorderLayout.WEST)
            add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(loadingLabel, BorderLayout.WEST)
                add(closeButton, BorderLayout.EAST)
            }, BorderLayout.EAST)
        }
        val inputRow = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(inputArea, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }
        rootPanel.add(header, BorderLayout.NORTH)
        rootPanel.add(scrollPane, BorderLayout.CENTER)
        rootPanel.add(inputRow, BorderLayout.SOUTH)

        sendButton.addActionListener { sendFollowUp() }
        inputArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "wit-send")
        inputArea.actionMap.put("wit-send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = sendFollowUp()
        })
    }

    /** Builds and shows the popup. Call once, before submitting the first turn. */
    fun open(kind: String) {
        headerLabel.icon = WhatIsThisIcons.Action16
        headerLabel.text = " $kind · ${config.providerName}/${config.model}"
        popup = createPopup()
        Disposer.register(popup, this)
        if (editor != null) {
            popup.showInBestPositionFor(editor)
        } else {
            popup.showInFocusCenter()
        }
    }

    /** Builds the popup without showing it - the part tests can drive directly. */
    internal fun createPopup(): JBPopup {
        val escapeAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = closePopup()
        }
        // Safety net: if the platform forgets to cancel the popup on focus
        // loss, close it ourselves the moment its window loses focus. The
        // window only exists once the popup is shown, hence the hierarchy hook.
        rootPanel.addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong() == 0L) return@addHierarchyListener
            val window = SwingUtilities.getWindowAncestor(rootPanel) ?: return@addHierarchyListener
            if (window === focusNetWindow) return@addHierarchyListener
            focusNetWindow?.removeWindowFocusListener(focusNet)
            focusNetWindow = window
            sawFocusGain = false
            window.addWindowFocusListener(focusNet)
        }
        val built = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(rootPanel, inputArea)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .setCancelCallback {
                cancelActive()
                true
            }
            .setDimensionServiceKey(project, "WhatIsThis.AskPopup", false)
            .createPopup()
        // Bind Esc on both our panel and the popup's content wrapper: the
        // focused component's WHEN_IN_FOCUSED_WINDOW chain walks both, and it
        // keeps working even if the platform's own cancel-key path goes silent.
        listOf(rootPanel, built.content).forEach { host ->
            host.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ESCAPE_ACTION)
            host.actionMap.put(ESCAPE_ACTION, escapeAction)
        }
        popup = built
        return built
    }

    private fun closePopup() {
        if (::popup.isInitialized && !popup.isDisposed) popup.cancel()
    }

    internal companion object {
        internal const val ESCAPE_ACTION = "wit-escape"
    }

    /**
     * Adds a user turn and starts streaming. [messageText] must already be the
     * final user message (the action layer builds it via [Prompts]); [displayLabel]
     * is the short line shown in the transcript, [preview] an optional fenced
     * code block under it (see [Preview]) so long selections are never a mystery.
     */
    fun askText(displayLabel: String, messageText: String, preview: String? = null) {
        history += "user" to messageText
        transcript.append("**❓ ").append(displayLabel).append("**\n\n")
        if (!preview.isNullOrBlank()) {
            transcript.append("```\n").append(preview).append("\n```\n\n")
        }
        render(currentAssistant = "")
        startStream()
    }

    fun askVision(imageDataUrl: String) {
        if (history.isEmpty()) visionConversation = true
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", imageDataUrl) })
            })
            add(buildJsonObject {
                put("type", "text")
                put("text", Prompts.visionUserText())
            })
        }
        history += "user" to content
        startStream()
    }

    private fun sendFollowUp() {
        val text = inputArea.text.trim()
        if (text.isEmpty() || streaming) return
        inputArea.text = ""
        askText(displayLabel = Preview.label(text), messageText = text, preview = Preview.block(text))
    }

    private fun startStream() {
        streaming = true
        setLoading(true)

        val request = buildJsonObject {
            put("model", config.model)
            put("stream", true)
            put("temperature", 0.3)
            // A cap, not a target - the prompt asks for ≤200 字. It must leave
            // room for chain-of-thought: on thinking models max_tokens covers
            // reasoning too, and a tight cap spent on CoT yields an EMPTY
            // answer that still finishes without error.
            put("max_tokens", 4096)
            // 对话+思考一体模型默认开思考（DeepSeek V4 effort=high）：关掉它，
            // 秒答才是本插件的卖点；temperature 在思考模式下本来也不生效。
            if (config.disableThinking) {
                put("thinking", buildJsonObject { put("type", "disabled") })
            }
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", Prompts.systemPrompt(vision = visionConversation, language = config.language))
                })
                history.forEach { (role, content) ->
                    add(buildJsonObject {
                        put("role", role)
                        put("content", when (content) {
                            is JsonElement -> content
                            is String -> JsonPrimitive(content)
                            else -> JsonPrimitive(content.toString())
                        })
                    })
                }
            })
        }

        val answer = StringBuilder()
        handle = LlmClient.chatStream(
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            requestBody = request,
            onStart = {},
            onDelta = { delta ->
                answer.append(delta)
                val snapshot = answer.toString()
                onEdt { render(currentAssistant = snapshot) }
            },
        ) { error ->
            onEdt {
                streaming = false
                val cancelled = handle?.isCancelled == true
                transcript.append(answer)
                when {
                    cancelled -> transcript.append("\n\n<i>（已取消）</i>")
                    error != null -> transcript.append("\n\n<font color=\"#e5534b\">⚠️ ")
                        .append(escape(describe(error)))
                        .append("</font>")
                    // A clean finish with nothing visible is a real failure mode:
                    // thinking-only models can burn the whole budget on CoT.
                    // Never show a silent blank - same honesty rule as Preview.
                    MarkdownToHtml.stripThinking(answer.toString()).isBlank() -> transcript.append("\n\n<font color=\"#e5534b\">⚠️ ")
                        .append("模型未返回答案正文（输出可能被思维链耗尽），请重试或在设置中更换模型。")
                        .append("</font>")
                }
                render(currentAssistant = "")
                setLoading(false)
                inputArea.requestFocusInWindow()
            }
        }
    }

    private fun describe(error: Throwable): String =
        (error as? com.lihanghang.whatisthis.llm.ApiException)?.friendlyMessage()
            ?: (error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)

    private fun cancelActive() {
        handle?.cancel()
    }

    private fun setLoading(loading: Boolean) {
        loadingLabel.isVisible = loading
        sendButton.isEnabled = !loading
        // inputArea stays enabled: it is the popup's focus target, and
        // disabling it in the same dispatch as show() breaks the focus grant
        // (focus snaps back to the editor, the focus net sees a loss, and the
        // popup kills itself). sendFollowUp already guards on [streaming], so
        // typing ahead while the answer streams is fine.
    }

    private fun render(currentAssistant: String) {
        answerPane.text = MarkdownToHtml.toHtml(transcript.toString() + currentAssistant)
        runCatching { answerPane.caretPosition = answerPane.document.length }
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication()
            .invokeLater(block, ModalityState.stateForComponent(rootPanel))
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun dispose() {
        cancelActive()
        focusNetWindow?.removeWindowFocusListener(focusNet)
    }
}
