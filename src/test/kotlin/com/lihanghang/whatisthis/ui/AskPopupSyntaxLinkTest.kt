package com.lihanghang.whatisthis.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.testFramework.LightPlatformTestCase
import com.lihanghang.whatisthis.settings.EffectiveConfig
import javax.swing.AbstractButton

/**
 * The 🔍 语法详解 button is gated by an explicit rule (see
 * [AskPopup.syntaxLinkVisible]): only for recognizable-language text
 * selections, hidden while streaming, and gone forever once the dive ran.
 * The predicate is asserted directly; the arming path is driven through
 * [AskPopup.askText] + [AskPopup.updateSyntaxLink] (the stream-finish hook)
 * so no network is involved.
 */
class AskPopupSyntaxLinkTest : LightPlatformTestCase() {

    private val config = EffectiveConfig(
        providerId = "test", providerName = "Test", baseUrl = "http://localhost",
        model = "test-model", apiKey = "k", language = "zh", knownVision = false,
        disableThinking = false,
    )

    private val syntax = AskPopup.SyntaxSelection(
        kind = "选中代码", fileNote = null, body = "def main(x: str) -> dict:", languageTag = "py",
    )

    fun testPredicateCoversTheDecidedRule() {
        assertTrue("eligible + unused + finished -> visible",
            AskPopup.syntaxLinkVisible(hasSelection = true, used = false, streaming = false))
        assertFalse("mid-stream -> hidden",
            AskPopup.syntaxLinkVisible(hasSelection = true, used = false, streaming = true))
        assertFalse("already used -> never again",
            AskPopup.syntaxLinkVisible(hasSelection = true, used = true, streaming = false))
        assertFalse("no selection (screenshot/plain text) -> never",
            AskPopup.syntaxLinkVisible(hasSelection = false, used = false, streaming = false))
    }

    fun testLinkStaysHiddenWithoutSelectionAndAppearsOnceArmed() {
        val askPopup = AskPopup(project, null, config)
        val popup = askPopup.createPopup()
        val link = findSyntaxLink(popup)
        assertNotNull("popup must contain the syntax link component", link)
        assertFalse("hidden before anything runs", link!!.isVisible)

        askPopup.askText(displayLabel = "q", messageText = "msg", syntax = null)
        askPopup.updateSyntaxLink()
        assertFalse("plain turn without selection -> still hidden", link.isVisible)

        askPopup.askText(displayLabel = "q", messageText = "msg", syntax = syntax)
        assertFalse("mid-stream -> hidden", link.isVisible)
        askPopup.streaming = false // what setLoading(false) flips at stream finish
        askPopup.updateSyntaxLink()
        assertTrue("armed selection -> visible after the answer finishes", link.isVisible)
    }

    private fun findSyntaxLink(popup: JBPopup): AbstractButton? {
        val button = arrayOfNulls<AbstractButton>(1)
        fun walk(c: java.awt.Component) {
            if (button[0] != null) return
            if (c is AbstractButton && c.text == "🔍 语法详解") {
                button[0] = c
                return
            }
            (c as? java.awt.Container)?.components?.forEach(::walk)
        }
        walk(popup.content)
        return button[0]
    }
}
