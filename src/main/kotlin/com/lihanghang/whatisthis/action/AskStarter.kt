package com.lihanghang.whatisthis.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.lihanghang.whatisthis.llm.Prompts
import com.lihanghang.whatisthis.settings.ConfigResolver
import com.lihanghang.whatisthis.settings.EffectiveConfig
import com.lihanghang.whatisthis.settings.WhatIsThisConfigurable
import com.lihanghang.whatisthis.ui.AskPopup
import com.lihanghang.whatisthis.ui.Preview
import com.lihanghang.whatisthis.util.CodeContext
import com.lihanghang.whatisthis.util.ImageCodec
import java.awt.Image

/**
 * Shared "go ask" plumbing: config resolution, popup lifecycle, threading.
 * Callers stay tiny and the popup never blocks the EDT.
 */
object AskStarter {

    fun askText(project: Project, editor: Editor?, kind: String, fileNote: String?, body: String, languageTag: String?) {
        val config = resolveConfigOrHint(project) ?: return
        val popup = AskPopup(project, editor, config)
        popup.open(kind)
        popup.askText(
            displayLabel = Preview.label(body),
            messageText = Prompts.textUserContent(kind, fileNote, body, languageTag),
            preview = Preview.block(body),
            // Only recognizable-language selections get the 🔍 语法详解 button.
            syntax = languageTag?.let { AskPopup.SyntaxSelection(kind, fileNote, body, it) },
        )
    }

    fun askVision(project: Project, editor: Editor?, image: Image) {
        val config = resolveConfigOrHint(project) ?: return
        if (!config.knownVision) {
            val choice = Messages.showYesNoDialog(
                project,
                "当前模型「${config.model}」不在已知视觉模型列表中，可能不支持图片输入。\n\n仍然发送这张截图吗？",
                "WhatIsThis",
                "仍然发送",
                "去设置",
                Messages.getWarningIcon(),
            )
            if (choice != Messages.YES) {
                openSettings(project)
                return
            }
        }
        val popup = AskPopup(project, editor, config)
        popup.open("截图")
        ApplicationManager.getApplication().executeOnPooledThread {
            val dataUrl = ImageCodec.toDataUrl(image)
            if (dataUrl == null) {
                balloon(project, "截图读取失败，无法发送。", NotificationType.WARNING, null)
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) popup.askVision(dataUrl)
            }
        }
    }

    /** Null -> user was already guided to settings. */
    private fun resolveConfigOrHint(project: Project): EffectiveConfig? {
        val config = ConfigResolver.resolve()
        if (config != null) return config
        balloon(
            project,
            "还没有配置 API Key，先去设置页选服务商并填入 Key。",
            NotificationType.WARNING,
            object : AnAction("去设置") {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) =
                    openSettings(project)
            },
        )
        return null
    }

    fun openSettings(project: Project) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, WhatIsThisConfigurable::class.java)
    }

    fun balloon(project: Project, text: String, type: NotificationType = NotificationType.INFORMATION) {
        balloon(project, text, type, null)
    }

    private fun balloon(project: Project, text: String, type: NotificationType, action: AnAction?) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("WhatIsThis")
            .createNotification("WhatIsThis", text, type)
        action?.let { notification.addAction(it) }
        notification.notify(project)
    }
}

/** File-note helpers shared by both actions. */
object Contexts {

    /** (context note, language tag) for the editor's file, if any. */
    fun fileNoteOf(editor: Editor): Pair<String?, String?>? {
        val file = editor.virtualFile ?: return null
        val note = CodeContext.fileNote(file.path, editor.document.text)
        return note to languageTagOf(file.name, file.fileType.name)
    }

    fun languageTagOf(fileName: String, fileTypeName: String): String? = when {
        fileTypeName.equals("PLAIN_TEXT", ignoreCase = true) || fileTypeName.equals("TEXT", ignoreCase = true) -> null
        else -> fileName.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() }
            ?: fileTypeName.lowercase()
    }
}
