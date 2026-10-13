package com.deepseek.harness.jetbrains.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * 插件全局设置。做成应用级：一次配置对所有项目窗口生效。
 */
@State(name = "DeepSeekHarnessEmbedded", storages = [Storage("deepseekHarnessEmbedded.xml")])
class DshSettings : PersistentStateComponent<DshSettings.State> {

    class State {
        /** 是否在工具窗口上方渲染「Harness」与「日志」页签，默认渲染。 */
        var renderTabs: Boolean = true
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): DshSettings = service()
    }
}
