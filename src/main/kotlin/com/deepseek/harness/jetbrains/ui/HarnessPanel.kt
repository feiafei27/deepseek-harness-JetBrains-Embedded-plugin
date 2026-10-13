package com.deepseek.harness.jetbrains.ui

import com.deepseek.harness.jetbrains.DshCli
import com.deepseek.harness.jetbrains.LogLevel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Harness tab：四态卡片。
 *
 * CHECKING → (GUIDE | STARTING→BROWSER)，任何失败进 ERROR；每种异常态都提供刷新按钮。
 * 进程与内嵌浏览器的生命周期都绑在本面板的 [dispose] 上。
 */
class HarnessPanel(private val log: (LogLevel, String) -> Unit) : JPanel(CardLayout()), Disposable {

    private enum class Card { CHECKING, GUIDE, BROWSER, ERROR }

    private val dsh = DshCli(log)
    private val refreshButtons = mutableListOf<JButton>()
    private val browserHolder = JPanel(BorderLayout())
    private val errorReason = JLabel()

    private var browser: JBCefBrowser? = null
    private var currentUrl: String? = null

    init {
        add(buildCheckingCard(), Card.CHECKING.name)
        add(buildGuideCard(), Card.GUIDE.name)
        add(buildBrowserCard(), Card.BROWSER.name)
        add(buildErrorCard(), Card.ERROR.name)
        start()
    }

    override fun dispose() {
        dsh.stop()
        browser?.dispose()
        browser = null
    }

    // ---- 状态机 ----

    private fun start() {
        showChecking()
        ApplicationManager.getApplication().executeOnPooledThread {
            when (val result = dsh.detect()) {
                is DshCli.DetectResult.Unavailable -> {
                    log(LogLevel.WARN, "未检测到可用的 dsh 命令：${result.reason}")
                    onEdt { showGuide() }
                }
                is DshCli.DetectResult.Available -> {
                    log(LogLevel.INFO, "dsh 命令可用，版本：${result.version}")
                    startService()
                }
            }
        }
    }

    /** 已在后台线程中被调用。 */
    private fun startService() {
        when (val result = dsh.startWeb()) {
            is DshCli.StartResult.Failed -> onEdt { showError(result.reason) }
            is DshCli.StartResult.Started -> {
                currentUrl = result.url
                onEdt { showBrowser(result.url) }
            }
        }
    }

    private fun refresh() {
        val url = currentUrl
        if (dsh.isRunning() && url != null) {
            log(LogLevel.INFO, "已有存活的 dsh web 进程，复用它，不重新检测")
            showBrowser(url)
            return
        }
        start()
    }

    private fun showChecking() {
        setRefreshEnabled(false)
        show(Card.CHECKING)
    }

    private fun showGuide() {
        setRefreshEnabled(true)
        show(Card.GUIDE)
    }

    private fun showError(reason: String) {
        errorReason.text = "<html>$reason</html>"
        setRefreshEnabled(true)
        show(Card.ERROR)
    }

    private fun showBrowser(url: String) {
        if (!JBCefApp.isSupported()) {
            log(LogLevel.ERROR, "JBCefApp.isSupported() = false，当前 IDE 不支持内嵌浏览器")
            showError("当前 IDE 不支持内嵌浏览器（JCEF），请手动在浏览器打开：$url")
            return
        }

        val existing = browser
        if (existing != null) {
            log(LogLevel.INFO, "复用已有内嵌浏览器，重新加载：$url")
            existing.loadURL(url)
        } else {
            log(LogLevel.INFO, "JBCefApp.isSupported() = true，开始加载：$url")
            val created = JBCefBrowser(url)
            created.getJBCefClient().addLoadHandler(
                object : CefLoadHandlerAdapter() {
                    override fun onLoadingStateChange(
                        b: CefBrowser,
                        isLoading: Boolean,
                        canGoBack: Boolean,
                        canGoForward: Boolean,
                    ) {
                        if (!isLoading) {
                            log(LogLevel.INFO, "页面加载完成：${b.url}")
                        }
                    }
                },
                created.getCefBrowser(),
            )
            browser = created
            browserHolder.add(created.component, BorderLayout.CENTER)
            browserHolder.revalidate()
            browserHolder.repaint()
        }

        setRefreshEnabled(true)
        show(Card.BROWSER)
    }

    private fun show(card: Card) {
        (layout as CardLayout).show(this, card.name)
    }

    private fun setRefreshEnabled(enabled: Boolean) {
        refreshButtons.forEach { it.isEnabled = enabled }
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(Runnable { action() })
    }

    // ---- 卡片构建 ----

    private fun buildCheckingCard(): JPanel = JPanel(BorderLayout()).apply {
        add(JLabel("正在检测 dsh 命令…", JLabel.CENTER), BorderLayout.CENTER)
    }

    private fun buildGuideCard(): JPanel {
        val refreshButton = JButton("刷新")
        refreshButton.addActionListener { refresh() }
        refreshButtons.add(refreshButton)

        return stack(
            text("<html><b>未检测到 dsh 命令</b></html>"),
            gap(8),
            text("请先安装 DeepSeek Harness："),
            text("<html>1. 打开官网 https://harness.deepseek.com 下载并安装桌面端。</html>"),
            text("<html>2. 安装完成后，在桌面端左上角菜单「应用 → 管理 dsh 命令…」中安装 dsh 命令行工具。</html>"),
            gap(12),
            text("dsh 命令完成后，点击下方刷新按钮"),
            gap(8),
            refreshButton,
        )
    }

    private fun buildBrowserCard(): JPanel = JPanel(BorderLayout()).apply {
        add(browserHolder, BorderLayout.CENTER)
    }

    private fun buildErrorCard(): JPanel {
        val refreshButton = JButton("刷新")
        refreshButton.addActionListener { refresh() }
        refreshButtons.add(refreshButton)

        val reason = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(errorReason, BorderLayout.CENTER)
        }

        return stack(
            text("<html><b>Harness 启动失败</b></html>"),
            gap(8),
            reason,
            gap(12),
            refreshButton,
        )
    }

    /**
     * 把可换行的 HTML 文本包一层 BorderLayout：BoxLayout 只按首选宽度摆放子组件，
     * 而 JPanel 的最大宽度不受限，所以会被撑满，里面的 HTML 文本才能按窗口宽度换行。
     */
    private fun text(html: String): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(JLabel(html), BorderLayout.CENTER)
    }

    // createVerticalStrut 声明返回 Component，实际是 Box.Filler（JComponent 的子类）——
    // setAlignmentX 只定义在 JComponent 上，所以这里需要提升类型。
    private fun gap(height: Int): JComponent =
        Box.createVerticalStrut(JBUI.scale(height)) as JComponent

    private fun stack(vararg components: JComponent): JPanel {
        val box = JPanel()
        box.layout = BoxLayout(box, BoxLayout.Y_AXIS)
        box.border = JBUI.Borders.empty(16)
        components.forEach { component ->
            component.alignmentX = Component.LEFT_ALIGNMENT
            box.add(component)
        }
        return JPanel(BorderLayout()).apply { add(box, BorderLayout.NORTH) }
    }
}
