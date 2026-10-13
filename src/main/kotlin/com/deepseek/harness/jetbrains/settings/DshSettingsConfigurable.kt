package com.deepseek.harness.jetbrains.settings

import com.deepseek.harness.jetbrains.ui.HarnessToolWindowFactory
import com.deepseek.harness.jetbrains.ui.HarnessToolWindowPanel
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 「设置 → 工具」下的插件配置页，目前只有「是否渲染页签」一项。
 */
class DshSettingsConfigurable : SearchableConfigurable {

    private var renderTabsCheckBox: JBCheckBox? = null

    override fun getId(): String = "com.deepseek.harness.jetbrains.settings"

    override fun getDisplayName(): String = "DeepSeek Harness Embedded"

    override fun createComponent(): JComponent {
        val checkBox = JBCheckBox("在工具窗口上方渲染「Harness」和「日志」页签", DshSettings.getInstance().state.renderTabs)
        renderTabsCheckBox = checkBox
        return FormBuilder.createFormBuilder()
            .addComponent(checkBox)
            .addComponent(descriptionLabel())
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun descriptionLabel(): JBLabel {
        val lines = listOf(
            "勾选（默认）：工具窗口上方显示「Harness」与「日志」两个页签，可在 Harness 主界面和调试日志之间切换。",
            "取消勾选：不渲染页签栏，只显示 Harness 主界面，把页签栏占用的高度让给内容区域，" +
                "适合屏幕高度有限或不需要在 IDE 内查看日志的场景；日志仍会照常写入 IDE 的 idea.log。",
            "本项修改立即生效，无需重启 IDE。"
        )
        return JBLabel("<html>${lines.joinToString("<br/>")}</html>").apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(JBUI.scale(24))
        }
    }

    override fun isModified(): Boolean =
        renderTabsCheckBox?.isSelected != DshSettings.getInstance().state.renderTabs

    override fun apply() {
        val selected = renderTabsCheckBox?.isSelected ?: return
        DshSettings.getInstance().state.renderTabs = selected
        // 让已经打开的工具窗口立即生效，省得用户手动关掉再打开
        for (project in ProjectManager.getInstance().openProjects) {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(HarnessToolWindowFactory.TOOL_WINDOW_ID) ?: continue
            for (content in toolWindow.contentManager.contents) {
                (content.component as? HarnessToolWindowPanel)?.applyTabsSetting()
            }
        }
    }

    override fun reset() {
        renderTabsCheckBox?.isSelected = DshSettings.getInstance().state.renderTabs
    }

    override fun disposeUIResources() {
        renderTabsCheckBox = null
    }
}
