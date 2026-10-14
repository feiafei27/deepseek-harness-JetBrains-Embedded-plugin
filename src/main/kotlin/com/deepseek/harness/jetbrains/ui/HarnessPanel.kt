package com.deepseek.harness.jetbrains.ui

import com.deepseek.harness.jetbrains.DshCli
import com.deepseek.harness.jetbrains.LogLevel
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Harness tab：四态卡片。
 *
 * CHECKING → (GUIDE | STARTING→BROWSER)，任何失败进 ERROR；每种异常态都提供刷新按钮。
 * 进程与内嵌浏览器的生命周期都绑在本面板的 [dispose] 上。
 */
class HarnessPanel(private val log: (LogLevel, String) -> Unit) : JPanel(CardLayout()), Disposable {

    private enum class Card { CHECKING, GUIDE, BROWSER, ERROR }

    private val dsh = DshCli(log, ::onStage)
    private val refreshButtons = mutableListOf<JButton>()
    private val browserHolder = JPanel(BorderLayout())
    private val errorReason = JLabel()

    private var browser: JBCefBrowser? = null

    /** CHECKING 卡片的步骤清单容器，每次启动流程重建内容。 */
    private val stepsBox = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(16)
    }

    private val stepRows = LinkedHashMap<DshCli.Stage, StepRow>()
    private var runningStage: DshCli.Stage? = null
    private var runningSince = 0L

    /** 只给"等待服务输出地址"走秒表：它是唯一会久等、且不阻塞 EDT 的阶段。 */
    private val stepTimer = Timer(1000) {
        val stage = runningStage ?: return@Timer
        stepRows[stage]?.update(StepState.RUNNING, elapsedSeconds())
    }

    init {
        add(buildCheckingCard(), Card.CHECKING.name)
        add(buildGuideCard(), Card.GUIDE.name)
        add(buildBrowserCard(), Card.BROWSER.name)
        add(buildErrorCard(), Card.ERROR.name)
        start()
    }

    override fun dispose() {
        // Swing Timer 会一直持有监听器（进而持有本面板），不停掉就是泄漏。
        stepTimer.stop()
        dsh.stop()
        browser?.dispose()
        browser = null
    }

    // ---- 状态机 ----

    /** 必须在 EDT 调用。 */
    private fun start() {
        prepareSteps(withStop = false)
        showChecking()
        ApplicationManager.getApplication().executeOnPooledThread { detectAndStart() }
    }

    /**
     * 标题栏的刷新按钮与卡片内的「刷新」按钮共用：先关掉现有 dsh 服务，再完整重跑一次启动流程，
     * 用新抓到的地址重新加载页面。必须在 EDT 调用。
     */
    fun restart() {
        log(LogLevel.INFO, "手动刷新：先关闭 dsh 服务，再重新执行启动流程")
        prepareSteps(withStop = true)
        showChecking()
        ApplicationManager.getApplication().executeOnPooledThread {
            dsh.stop()
            detectAndStart()
        }
    }

    /** 已在后台线程中被调用。 */
    private fun detectAndStart() {
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

    /** 已在后台线程中被调用。 */
    private fun startService() {
        when (val result = dsh.startWeb()) {
            is DshCli.StartResult.Failed -> onEdt { showError(result.reason) }
            is DshCli.StartResult.Started -> onEdt { showBrowser(result.url) }
        }
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

        // 创建 JBCefBrowser 只能在 EDT 上进行，而 JCEF 首次初始化会把 EDT 卡住几秒，
        // 所以先把"加载界面"这一步同步画出来，否则用户看到的还停在上一步。
        applyStage(DshCli.Stage.LOADING_UI)
        paintImmediately(0, 0, width, height)

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
        if (card != Card.CHECKING) stepTimer.stop()
        (layout as CardLayout).show(this, card.name)
    }

    private fun setRefreshEnabled(enabled: Boolean) {
        refreshButtons.forEach { it.isEnabled = enabled }
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(Runnable { action() })
    }

    // ---- 步骤清单 ----

    /** [DshCli] 与 [showBrowser] 上报阶段进度；可能来自任意线程。 */
    private fun onStage(stage: DshCli.Stage) = onEdt { applyStage(stage) }

    /** 必须在 EDT 调用。 */
    private fun applyStage(stage: DshCli.Stage) {
        if (stage !in stepRows) return
        runningStage?.let { stepRows[it]?.update(StepState.DONE, null) }
        runningStage = stage
        runningSince = System.currentTimeMillis()
        stepRows[stage]?.update(StepState.RUNNING, elapsedSeconds())
        if (stage == DshCli.Stage.WAITING_URL) stepTimer.start() else stepTimer.stop()
    }

    /**
     * 重建步骤清单。首次打开没有旧服务可关，[withStop] 为 false 时就不渲染那一步。
     * 必须在 EDT 调用。
     */
    private fun prepareSteps(withStop: Boolean) {
        stepsBox.removeAll()
        stepRows.clear()
        DshCli.Stage.entries
            .filter { withStop || it != DshCli.Stage.STOPPING }
            .forEach { stage ->
                val row = StepRow(stageText(stage))
                row.update(StepState.PENDING, null)
                row.alignmentX = Component.LEFT_ALIGNMENT
                stepsBox.add(row)
                stepRows[stage] = row
            }
        runningStage = null
        stepsBox.revalidate()
        stepsBox.repaint()
    }

    private fun stageText(stage: DshCli.Stage): String = when (stage) {
        DshCli.Stage.STOPPING -> "关闭旧的 dsh 服务"
        DshCli.Stage.DETECTING -> "检测 dsh 命令"
        DshCli.Stage.PICKING_PORT -> "选择可用端口"
        DshCli.Stage.STARTING -> "启动 dsh web 服务"
        DshCli.Stage.WAITING_URL -> "等待服务输出地址"
        DshCli.Stage.LOADING_UI -> "加载 Harness 界面"
    }

    private fun elapsedSeconds(): Int = ((System.currentTimeMillis() - runningSince) / 1000).toInt()

    // ---- 卡片构建 ----

    private fun buildCheckingCard(): JPanel = JPanel(BorderLayout()).apply {
        add(stepsBox, BorderLayout.NORTH)
    }

    private fun buildGuideCard(): JPanel {
        val refreshButton = JButton("刷新")
        refreshButton.addActionListener { restart() }
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
        refreshButton.addActionListener { restart() }
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

private enum class StepState { DONE, RUNNING, PENDING }

/** CHECKING 卡片里的一行步骤：左侧固定留出图标位，保证三态下文字左边界对齐。 */
private class StepRow(private val base: String) : JPanel(BorderLayout(6, 0)) {

    private val iconLabel = JLabel().apply {
        preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
    }
    private val textLabel = JLabel(base)

    init {
        isOpaque = false
        add(iconLabel, BorderLayout.WEST)
        add(textLabel, BorderLayout.CENTER)
    }

    fun update(state: StepState, elapsedSeconds: Int?) {
        iconLabel.icon = if (state == StepState.DONE) AllIcons.Actions.Checked else null
        textLabel.font = JBUI.Fonts.label()
            .deriveFont(if (state == StepState.RUNNING) Font.BOLD else Font.PLAIN)
        textLabel.foreground =
            if (state == StepState.RUNNING) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
        textLabel.text = when {
            state != StepState.RUNNING -> base
            elapsedSeconds == null -> "$base…"
            else -> "$base…  已等待 $elapsedSeconds 秒"
        }
    }
}
