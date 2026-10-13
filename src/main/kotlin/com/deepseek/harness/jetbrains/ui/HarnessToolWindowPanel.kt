package com.deepseek.harness.jetbrains.ui

import com.deepseek.harness.jetbrains.LogLevel
import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JPanel

class HarnessToolWindowPanel : JPanel(BorderLayout()), Disposable {

    private val logPanel = LogPanel()
    private val harnessPanel = HarnessPanel(logPanel::append)

    init {
        val tabs = JBTabbedPane()
        tabs.addTab("Harness", harnessPanel)
        tabs.addTab("日志", logPanel)
        add(tabs, BorderLayout.CENTER)
        logPanel.append(LogLevel.INFO, "工具窗口已创建")
    }

    override fun dispose() {
        harnessPanel.dispose()
        logPanel.append(LogLevel.INFO, "工具窗口已释放")
    }
}
