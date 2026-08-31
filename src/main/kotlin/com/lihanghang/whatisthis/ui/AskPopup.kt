package com.lihanghang.whatisthis.ui

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
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.KeyStroke

/**
 * The one and only surface: a popup that streams the answer, accepts
 * follow-up questions in the same conversation, and dies on Esc - nothing
 * lingers, because lingering is the opposite of 瞬间.
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
        font = font.deriveFont(Font.BOLD, 12f)
    }
    private val loadingLabel = JBLabel(" 思考中…", AnimatedIcon.Default(), SwingConstants.LEFT)
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

    init {
        loadingLabel.isVisible = false
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(headerLabel, BorderLayout.WEST)
            add(loadingLabel, BorderLayout.EAST)
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
        val builder = JBPopupFactory.getInstance()
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
        popup = builder.createPopup()
        Disposer.register(popup, this)
        if (editor != null) {
            popup.showInBestPositionFor(editor)
        } else {
            popup.showInFocusCenter()
        }
    }

    /**
     * Adds a user turn and starts streaming. [messageText] must already be the
     * final user message (the action layer builds it via [Prompts]); [displayLabel]
     * is the short line shown in the transcript.
     */
    fun askText(displayLabel: String, messageText: String) {
        history += "user" to messageText
        transcript.append("**❓ ").append(displayLabel.take(200)).append("**\n\n")
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
        askText(displayLabel = text, messageText = text)
    }

    private fun startStream() {
        streaming = true
        setLoading(true)

        val request = buildJsonObject {
            put("model", config.model)
            put("stream", true)
            put("temperature", 0.3)
            put("max_tokens", 600)
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
                if (cancelled) {
                    transcript.append("\n\n<i>（已取消）</i>")
                } else if (error != null) {
                    transcript.append("\n\n<font color=\"#e5534b\">⚠️ ").append(escape(describe(error))).append("</font>")
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
        inputArea.isEnabled = !loading
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
    }
}
