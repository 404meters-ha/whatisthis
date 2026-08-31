package com.lihanghang.whatisthis.util

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/**
 * Clipboard reads that never throw: other apps may own the clipboard
 * for a few milliseconds, and a crash there would be embarrassing.
 */
object ClipboardBridge {

    fun readImage(): Image? = runCatching {
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return null
        if (!contents.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
        contents.getTransferData(DataFlavor.imageFlavor) as? Image
    }.getOrNull()

    fun readText(): String? = runCatching {
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return null
        if (!contents.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
        contents.getTransferData(DataFlavor.stringFlavor) as? String
    }.getOrNull()
}
