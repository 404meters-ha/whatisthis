package com.lihanghang.whatisthis.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.lihanghang.whatisthis.util.CodeContext

/**
 * Context-menu entry: editor selection, else the whole file under the caret /
 * the file selected in the Project View.
 */
class WhatIsThisContextAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasTarget = e.getData(CommonDataKeys.EDITOR) != null ||
            e.getData(PlatformDataKeys.VIRTUAL_FILE) != null
        e.presentation.isEnabledAndVisible = project != null && hasTarget
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

        val file = editor?.virtualFile ?: e.getData(PlatformDataKeys.VIRTUAL_FILE)
        if (file == null) {
            AskStarter.balloon(project, "没有可问的目标。")
            return
        }
        if (file.isDirectory) {
            AskStarter.balloon(project, "目录还不支持，请选一个文件。")
            return
        }
        if (file.fileType.isBinary) {
            AskStarter.balloon(project, "二进制文件没法问（当前仅支持文本文件）。")
            return
        }

        val text = ReadAction.compute<String, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)?.text
                ?: runCatching { String(file.inputStream.readNBytes(400_000), Charsets.UTF_8) }.getOrDefault("")
        }
        if (text.isBlank()) {
            AskStarter.balloon(project, "这个文件是空的。")
            return
        }

        val note = CodeContext.fileNote(file.path, text)
        AskStarter.askText(
            project, editor,
            kind = if (editor != null) "整个文件" else "文件",
            fileNote = note,
            body = CodeContext.truncate(text),
            languageTag = Contexts.languageTagOf(file.name, file.fileType.name),
        )
    }
}
