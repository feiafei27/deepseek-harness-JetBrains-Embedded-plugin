package com.deepseek.harness.jetbrains.ui

import com.deepseek.harness.jetbrains.LogLevel
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel

class HarnessToolWindowPanel(project: Project) : JPanel(BorderLayout()), Disposable {

    private val logPanel = LogPanel()

    init {
        val tabs = JBTabbedPane()
        tabs.addTab("Harness", harnessPlaceholder())
        tabs.addTab("日志", logPanel)
        add(tabs, BorderLayout.CENTER)
        logPanel.append(LogLevel.INFO, "工具窗口已创建")
    }

    private fun harnessPlaceholder(): JLabel = JLabel("正在检测 dsh 命令…")

    override fun dispose() {
        logPanel.append(LogLevel.INFO, "工具窗口已释放")
    }
}
