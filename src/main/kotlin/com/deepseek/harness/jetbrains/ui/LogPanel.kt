package com.deepseek.harness.jetbrains.ui

import com.deepseek.harness.jetbrains.LogLevel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.JPanel

/**
 * 调试日志面板。追加操作可从任意线程调用：UI 更新统一通过 invokeLater 切回 EDT。
 */
class LogPanel : JPanel(BorderLayout()) {

    private val area = JBTextArea()

    init {
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = false
        // 必须用逻辑字体族 "Monospaced"：逻辑字体族是复合字体，缺字时会回退到系统中文字体。
        // 实测（Java 24 / Windows 11）：物理字体族如 "Segoe UI" 的 canDisplay('中') == false，
        // 而 JetBrains IDE 会把 JetBrains Mono 注册成物理字体，此前用它导致中文全渲染成方块。
        area.font = JBUI.Fonts.create("Monospaced", 12)
        add(JBScrollPane(area), BorderLayout.CENTER)
        border = JBUI.Borders.empty(6)
    }

    fun append(level: LogLevel, message: String) {
        when (level) {
            LogLevel.INFO -> LOG.info(message)
            LogLevel.WARN -> LOG.warn(message)
            LogLevel.ERROR -> LOG.error(message)
        }
        val line = "[${LocalTime.now().format(TIME_FORMAT)}] [${level.name}] $message"
        // IDE 关闭过程中 stop() 仍会写日志，此时 invokeLater 可能抛已释放异常，忽略即可。
        runCatching {
            ApplicationManager.getApplication().invokeLater { appendLine(line) }
        }
    }

    private fun appendLine(line: String) {
        area.append(line)
        area.append("\n")
        while (area.lineCount > MAX_LINES) {
            val doc = area.document
            val firstBreak = doc.getText(0, doc.length).indexOf('\n')
            if (firstBreak < 0) break
            doc.remove(0, firstBreak + 1)
        }
        area.caretPosition = area.document.length
    }

    companion object {
        private val LOG = Logger.getInstance(LogPanel::class.java)
        private const val MAX_LINES = 5000
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
