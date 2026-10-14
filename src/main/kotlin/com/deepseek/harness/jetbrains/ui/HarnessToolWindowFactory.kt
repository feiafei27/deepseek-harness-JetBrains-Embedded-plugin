package com.deepseek.harness.jetbrains.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class HarnessToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HarnessToolWindowPanel()
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        // 标题栏刷新按钮：渲染在「更多(…)」与「最小化」这些标准按钮的左侧。
        toolWindow.setTitleActions(listOf(RefreshAction(panel::restartHarness)))
    }

    companion object {
        /** 与 plugin.xml 中 `<toolWindow id="...">` 保持一致。 */
        const val TOOL_WINDOW_ID = "DeepSeek Harness"
    }
}

/**
 * 点击后关掉本窗口的 dsh 服务、重跑一次启动流程，再用新抓到的地址重新加载页面。
 *
 * 图标直接用平台自带的 [AllIcons.Actions.Refresh]：新 UI 下它对应 expui/general/refresh.svg，
 * 矢量且带 _dark 变体，会跟随主题配色，不需要自定义图标资源。
 */
private class RefreshAction(private val onRefresh: () -> Unit) : AnAction(
    "刷新",
    "关闭并重新启动 dsh 服务，用新地址重新加载页面",
    AllIcons.Actions.Refresh,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        onRefresh()
    }
}
