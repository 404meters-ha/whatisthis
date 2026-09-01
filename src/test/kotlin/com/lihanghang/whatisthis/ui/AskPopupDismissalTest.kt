package com.lihanghang.whatisthis.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.lihanghang.whatisthis.settings.EffectiveConfig
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractButton
import javax.swing.Action
import javax.swing.SwingUtilities

/**
 * The popup must be closable through our own escape routes, independent of
 * the platform's dismissal machinery (flaky on some 2026.1 EAP builds):
 * the Esc key binding and the header close button both have to reach
 * [JBPopup.cancel] on their own. A never-shown popup does not flip
 * [JBPopup.isDisposed], so cancellation is observed via a Disposer child.
 */
class AskPopupDismissalTest : LightPlatformTestCase() {

    private val config = EffectiveConfig(
        providerId = "test", providerName = "Test", baseUrl = "http://localhost",
        model = "test-model", apiKey = "k", language = "zh", knownVision = false,
        disableThinking = false,
    )

    fun testEscapeActionCancelsPopup() {
        val askPopup = AskPopup(project, null, config)
        val popup = askPopup.createPopup()
        val cancelled = cancelledProbe(popup)

        val escape = findEscapeAction(popup)
        assertNotNull("Esc action must be bound on the popup content", escape)
        escape!!.actionPerformed(null)

        assertTrue("Esc must cancel the popup", cancelled.get())
    }

    fun testCloseButtonCancelsPopup() {
        val askPopup = AskPopup(project, null, config)
        val popup = askPopup.createPopup()
        val cancelled = cancelledProbe(popup)

        val button = findCloseButton(popup)
        assertNotNull("header must contain the close button", button)
        button!!.doClick()

        assertTrue("✕ must cancel the popup", cancelled.get())
    }

    /** Registers a Disposer child on the popup; fires exactly when cancel() disposes it. */
    private fun cancelledProbe(popup: JBPopup): AtomicBoolean {
        val fired = AtomicBoolean(false)
        Disposer.register(popup, Disposable { fired.set(true) })
        return fired
    }

    private fun findEscapeAction(popup: JBPopup): Action? =
        popup.content.actionMap[AskPopup.ESCAPE_ACTION]

    private fun findCloseButton(popup: JBPopup): AbstractButton? {
        val button = arrayOfNulls<AbstractButton>(1)
        fun walk(c: java.awt.Component) {
            if (button[0] != null) return
            if (c is AbstractButton && c.toolTipText == "关闭（Esc）") {
                button[0] = c
                return
            }
            (c as? java.awt.Container)?.components?.forEach(::walk)
        }
        walk(popup.content)
        return button[0]
    }
}
