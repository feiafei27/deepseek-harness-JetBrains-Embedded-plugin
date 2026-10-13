package com.deepseek.harness.jetbrains.ui

import com.deepseek.harness.jetbrains.LogLevel
import com.deepseek.harness.jetbrains.settings.DshSettings
import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JPanel

class HarnessToolWindowPanel : JPanel(BorderLayout()), Disposable {

    private val logPanel = LogPanel()
    private val harnessPanel = HarnessPanel(logPanel::append)
    private val tabs = JBTabbedPane()
    private var tabsRendered = true

    init {
        tabs.addTab("Harness", harnessPanel)
        tabs.addTab("日志", logPanel)
        add(tabs, BorderLayout.CENTER)
        logPanel.append(LogLevel.INFO, "工具窗口已创建")
        applyTabsSetting()
    }

    /**
     * 按设置决定是否渲染页签栏。关闭渲染时把 Harness 主界面从页签容器里摘出来直接铺满窗口，
     * 省下页签栏占用的高度。Swing 组件同时只能有一个父容器，所以必须先从原容器移除再挂到新容器。
     */
    fun applyTabsSetting() {
        val renderTabs = DshSettings.getInstance().state.renderTabs
        if (renderTabs == tabsRendered) return
        tabsRendered = renderTabs

        removeAll()
        if (renderTabs) {
            remove(harnessPanel)
            // 用 insertTab 固定在首位：隐藏期间「日志」页签会变成第 0 个，直接 addTab 会让顺序颠倒
            tabs.insertTab("Harness", null, harnessPanel, null, 0)
            tabs.selectedIndex = 0
            add(tabs, BorderLayout.CENTER)
        } else {
            tabs.remove(harnessPanel)
            add(harnessPanel, BorderLayout.CENTER)
        }
        revalidate()
        repaint()
        logPanel.append(LogLevel.INFO, if (renderTabs) "已渲染页签栏" else "已隐藏页签栏，只显示 Harness 主界面")
    }

    override fun dispose() {
        harnessPanel.dispose()
        logPanel.append(LogLevel.INFO, "工具窗口已释放")
    }
}
