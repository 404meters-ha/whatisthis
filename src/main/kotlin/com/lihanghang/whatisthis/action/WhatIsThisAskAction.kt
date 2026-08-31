package com.lihanghang.whatisthis.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.lihanghang.whatisthis.util.ClipboardBridge

/**
 * The one shortcut to remember (Ctrl+Alt+W / Cmd+Alt+W). Smart route:
 * editor selection -> clipboard image -> clipboard text -> usage hint.
 */
class WhatIsThisAskAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)

        val selection = editor?.selectionModel?.selectedText?.trim()
        if (!selection.isNullOrEmpty()) {
            val (note, langTag) = Contexts.fileNoteOf(editor) ?: (null to null)
            AskStarter.askText(project, editor, "选中代码", note, selection, langTag)
            return
        }

        ClipboardBridge.readImage()?.let { image ->
            AskStarter.askVision(project, editor, image)
            return
        }

        val clipboardText = ClipboardBridge.readText()?.trim()
        if (!clipboardText.isNullOrEmpty()) {
            AskStarter.askText(project, editor, "剪贴板文本", null, clipboardText, null)
            return
        }

        AskStarter.balloon(
            project,
            "没有可问的内容。选中代码、或先截图 / 复制文本，再按 What Is This?（Ctrl+Alt+W）。",
        )
    }
}
