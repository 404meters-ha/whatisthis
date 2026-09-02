package com.lihanghang.whatisthis.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.Action
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 「支持作者」- one square QR, one line of text, nothing else. Opened from the
 * settings page; deliberately not anywhere near the answer popup, because the
 * popup is for answers and interruptions are the opposite of 瞬间.
 */
class DonateDialog(project: Project? = null) : DialogWrapper(project) {

    init {
        title = "支持作者"
        setOKButtonText("好的")
        isModal = true
        isResizable = false
        init()
    }

    /**
     * DialogWrapper's default south panel ships OK **and** Cancel; a donate
     * dialog has nothing to cancel - drop everything but 「好的」.
     */
    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val text = JBLabel("微信扫一扫，请作者喝杯咖啡 ☕").apply {
            horizontalAlignment = SwingConstants.CENTER
            font = JBFont.label().asBold()
        }
        val qrLabel = loadQrIcon()?.let(::JBLabel) ?: JBLabel("（资源缺失：$RESOURCE）")
        return JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 12, 0, 12)
            add(text, BorderLayout.NORTH)
            add(qrLabel, BorderLayout.CENTER)
        }
    }

    /** Smooth-scaled square icon straight from the bundled resource. */
    private fun loadQrIcon(): ImageIcon? {
        val url = javaClass.classLoader.getResource(RESOURCE) ?: return null
        val source = ImageIcon(url).image ?: return null
        val size = JBUI.scale(280)
        val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(source, 0, 0, size, size, null)
        graphics.dispose()
        return ImageIcon(scaled)
    }

    private companion object {
        const val RESOURCE = "images/donate-qr.png"
    }
}
