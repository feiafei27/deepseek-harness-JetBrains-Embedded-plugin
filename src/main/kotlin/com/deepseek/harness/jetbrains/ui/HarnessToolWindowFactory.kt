package com.deepseek.harness.jetbrains.ui

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
    }

    companion object {
        /** 与 plugin.xml 中 `<toolWindow id="...">` 保持一致。 */
        const val TOOL_WINDOW_ID = "DeepSeek Harness"
    }
}
