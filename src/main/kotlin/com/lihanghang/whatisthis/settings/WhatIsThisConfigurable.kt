package com.lihanghang.whatisthis.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ide.BrowserUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.lihanghang.whatisthis.llm.ApiException
import com.lihanghang.whatisthis.llm.LlmClient
import com.lihanghang.whatisthis.llm.ProviderPreset
import com.lihanghang.whatisthis.llm.Providers
import com.lihanghang.whatisthis.ui.DonateDialog
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Application settings: provider, model, key (PasswordSafe), base URL for
 * custom endpoints, answer language, plus a test button that fails fast.
 */
class WhatIsThisConfigurable : com.intellij.openapi.options.Configurable, com.intellij.openapi.options.Configurable.NoScroll {

    private val settings get() = WhatIsThisSettings.instance

    private var providerCombo: JComboBox<ProviderEntry>? = null
    private var modelCombo: JComboBox<String>? = null
    private var keyField: JBPasswordField? = null
    private var keyLink: ActionLink? = null
    private var baseUrlField: JBTextField? = null
    private var languageCombo: JComboBox<String>? = null
    private var testButton: JButton? = null
    private var testStatus: JBLabel? = null
    private var donateLink: ActionLink? = null

    /** providerId -> edited-but-not-yet-applied key ("" = cleared). */
    private val keyEdits = mutableMapOf<String, String>()
    private var loadedProviderId: String = ""

    override fun getDisplayName(): String = "WhatIsThis"

    override fun createComponent(): JComponent? {
        val providerCombo = JComboBox(Providers.ALL.map { ProviderEntry(it) }.toTypedArray())
        this.providerCombo = providerCombo

        val modelCombo = JComboBox<String>().apply { isEditable = true }
        this.modelCombo = modelCombo

        val keyField = JBPasswordField().apply { columns = 32 }
        this.keyField = keyField

        val keyLink = ActionLink("获取 API Key ↗") {
            val url = currentPreset().keyUrl
            if (url.isNotBlank()) BrowserUtil.browse(url)
        }
        this.keyLink = keyLink

        val baseUrlField = JBTextField().apply { columns = 36 }
        this.baseUrlField = baseUrlField

        val languageCombo = JComboBox(arrayOf("简体中文", "English"))
        this.languageCombo = languageCombo

        val testButton = JButton("测试连接")
        this.testButton = testButton
        val testStatus = JBLabel(" ")
        this.testStatus = testStatus
        testButton.addActionListener { runTest() }

        val donateLink = ActionLink("❤ 支持作者") { DonateDialog().show() }
        this.donateLink = donateLink

        reset()

        // Attach after reset() so the programmatic selection change does not
        // stash an empty key over the stored one.
        providerCombo.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED) onProviderChanged()
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("服务商:", providerCombo)
            .addLabeledComponent("模型:", modelCombo)
            .addLabeledComponent("API Key:", rowOf(keyField, keyLink))
            .addLabeledComponent("Base URL（仅自定义服务商需要）:", baseUrlField)
            .addLabeledComponent("回答语言:", languageCombo)
            .addComponent(rowOf(testButton, testStatus, donateLink))
            .addComponent(privacyNote())
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent? = modelCombo

    private fun privacyNote(): JComponent =
        JBLabel(
            "<html><body style=\"width: 560px\">" +
                "WhatIsThis 会把截图或所选内容发送至你配置的大模型服务商；" +
                "API Key 通过 IDE PasswordSafe 加密存储。<br>" +
                "快捷键：Ctrl+Alt+W（macOS：Cmd+Alt+W）。" +
                "</body></html>",
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            verticalTextPosition = javax.swing.SwingConstants.TOP
        }

    private fun rowOf(vararg components: JComponent): JComponent =
        JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(components[0], BorderLayout.CENTER)
            val east = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                components.drop(1).forEach { add(it) }
            }
            add(east, BorderLayout.EAST)
        }

    private fun currentPreset(): ProviderPreset =
        (providerCombo?.selectedItem as? ProviderEntry)?.preset ?: Providers.byId(Providers.CUSTOM)

    private fun selectedProviderId(): String = currentPreset().id

    private fun onProviderChanged() {
        val keyField = keyField ?: return
        val modelCombo = modelCombo ?: return
        val baseUrlField = baseUrlField ?: return
        val keyLink = keyLink ?: return

        // Stash the key typed for the provider we are leaving.
        if (loadedProviderId.isNotBlank()) {
            keyEdits[loadedProviderId] = String(keyField.password)
        }

        val preset = currentPreset()
        loadedProviderId = preset.id
        modelCombo.model = DefaultComboBoxModel(preset.modelSuggestions.toTypedArray())
        modelCombo.selectedItem = preset.defaultModel
        modelCombo.isEditable = true
        baseUrlField.isEnabled = preset.id == Providers.CUSTOM
        baseUrlField.text = preset.baseUrl
        keyField.text = keyEdits[preset.id] ?: ApiKeyStore.get(preset.id) ?: ""
        keyLink.isVisible = preset.keyUrl.isNotBlank()
    }

    private fun runTest() {
        val status = testStatus ?: return
        val button = testButton ?: return
        val preset = currentPreset()
        val baseUrl = if (preset.id == Providers.CUSTOM) baseUrlField?.text?.trim().orEmpty() else preset.baseUrl
        val model = (modelCombo?.selectedItem as? String)?.trim().orEmpty().ifBlank { preset.defaultModel }
        val editedKey = keyField?.let { String(it.password) }.orEmpty()
        val apiKey = editedKey.ifBlank { ApiKeyStore.get(preset.id).orEmpty() }

        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            status.text = "✗ 请先填写 Base URL、模型和 API Key"
            status.foreground = JBColor.RED
            return
        }
        button.isEnabled = false
        status.text = "测试中…"
        status.foreground = JBColor.GRAY
        LlmClient.test(baseUrl, apiKey, model) { error ->
            SwingUtilities.invokeLater {
                button.isEnabled = true
                if (error == null) {
                    status.text = "✓ 连接成功"
                    status.foreground = JBColor.GREEN
                } else {
                    status.text = "✗ " + describe(error)
                    status.foreground = JBColor.RED
                }
            }
        }
    }

    private fun describe(error: Throwable): String =
        (error as? ApiException)?.friendlyMessage()
            ?: error.message?.takeIf { it.isNotBlank() }?.take(120)
            ?: error.javaClass.simpleName

    override fun isModified(): Boolean {
        val state = settings.current
        val providerId = selectedProviderId()
        val preset = Providers.byId(providerId)
        if (state.providerId != providerId) return true

        val uiModel = (modelCombo?.selectedItem as? String)?.trim().orEmpty()
        if (uiModel.ifBlank { preset.defaultModel } != state.model.ifBlank { Providers.byId(state.providerId).defaultModel }) return true

        val uiBase = if (providerId == Providers.CUSTOM) baseUrlField?.text?.trim().orEmpty() else preset.baseUrl
        val storedBase = if (state.providerId == Providers.CUSTOM) state.customBaseUrl.trim() else Providers.byId(state.providerId).baseUrl
        if (uiBase != storedBase) return true

        val uiLang = if ((languageCombo?.selectedIndex ?: 0) == 1) "en" else "zh"
        if (uiLang != state.language) return true

        pendingKeyEdits().forEach { (id, value) ->
            if (value != (ApiKeyStore.get(id) ?: "")) return true
        }
        return false
    }

    private fun pendingKeyEdits(): Map<String, String> {
        val edits = keyEdits.filterKeys { it.isNotBlank() }.toMutableMap()
        val currentId = selectedProviderId()
        keyField?.let { edits[currentId] = String(it.password) }
        return edits
    }

    override fun apply() {
        val state = settings.current
        state.providerId = selectedProviderId()
        val preset = Providers.byId(state.providerId)
        val uiModel = (modelCombo?.selectedItem as? String)?.trim().orEmpty()
        val resolvedModel = uiModel.ifBlank { preset.defaultModel }
        state.model = if (resolvedModel == preset.defaultModel) "" else resolvedModel
        state.customBaseUrl = baseUrlField?.text?.trim().orEmpty()
        state.language = if ((languageCombo?.selectedIndex ?: 0) == 1) "en" else "zh"

        pendingKeyEdits().forEach { (id, value) ->
            if (value != (ApiKeyStore.get(id) ?: "")) ApiKeyStore.set(id, value.ifBlank { null })
        }
        keyEdits.clear()
        loadedProviderId = state.providerId
    }

    override fun reset() {
        val state = settings.current
        val preset = Providers.byId(state.providerId)
        keyEdits.clear()
        loadedProviderId = state.providerId
        providerCombo?.selectedItem = ProviderEntry(preset)
        modelCombo?.model = DefaultComboBoxModel(preset.modelSuggestions.toTypedArray())
        modelCombo?.selectedItem = state.model.ifBlank { preset.defaultModel }
        modelCombo?.isEditable = true
        keyField?.text = ApiKeyStore.get(state.providerId) ?: ""
        baseUrlField?.isEnabled = state.providerId == Providers.CUSTOM
        baseUrlField?.text = if (state.providerId == Providers.CUSTOM) state.customBaseUrl else preset.baseUrl
        languageCombo?.selectedIndex = if (state.language == "en") 1 else 0
        testStatus?.text = " "
    }

    override fun disposeUIResources() {
        providerCombo = null
        modelCombo = null
        keyField = null
        keyLink = null
        baseUrlField = null
        languageCombo = null
        testButton = null
        testStatus = null
        donateLink = null
        keyEdits.clear()
        loadedProviderId = ""
    }

    private data class ProviderEntry(val preset: ProviderPreset) {
        override fun toString(): String = preset.displayName
    }
}
