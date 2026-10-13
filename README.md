# DeepSeek Harness for JetBrains IDEs

[English](README.en.md) | **中文**

把本机已安装的 **DeepSeek Harness** 直接嵌进 JetBrains IDE：点击侧边栏图标，就能在 IDE 里用上 Harness 的完整界面。

![DeepSeek Harness Embedded 在 IDE 中的界面](docs/images/README-IMG1.png)

---

## 为什么写这个插件

我在插件市场里装过现有的 DeepSeek Harness 插件，**总是用不起来，配置过程也很麻烦**。

所以我自己写了这一个：**逻辑刻意做得极简** —— 它只做三件事：检测 `dsh` 命令、启动本地 Harness 服务、把服务地址嵌进 IDE。不解析协议、不管模型、不碰登录态，因此出错的地方少，可用性反而更强。

## 前置条件（注意看这里）

1. **已安装 DeepSeek Harness 桌面端**，并**至少启动过一次**。
2. **`dsh` 命令行工具已安装且在当前 PATH 上。**
   安装方式：打开桌面端，左上角菜单 **「应用 → 管理 dsh 命令…」**。
   官网：<https://harness.deepseek.com>
3. 你的 IDE 支持 JCEF（JetBrains 家的 IDE 都自带，用于内嵌浏览器）。

> **注意 PATH。** 插件通过 `cmd.exe /c dsh ...` 调用，继承的是 IDE 进程的环境变量。
> 如果你是**先开着 IDE、后装的 `dsh`**，IDE 看不到新的 PATH —— **重启一次 IDE** 即可。

## 它有哪些好处

- **与系统安装的 DeepSeek Harness 完全统一。** 插件复用你本机那一份 Harness，**不引入第二份配置**：模型、登录态、历史会话、工作目录全部就是你桌面端里已有的那套，开箱即是同一个环境。
- **不需要在插件里重新配置任何东西。** 不用填路径、不用填端口、不用填 token —— 服务地址由插件自己启动并抓取（含 token），端口被占用会自动顺延。
- **简单易用。** 装完点一下侧边栏图标就行，不必自己开终端敲 `dsh web`。
- **干净。** 每个项目窗口各起一个 `dsh` 进程，窗口释放时用 `taskkill /F /T` 结束整棵进程树，不留孤儿进程、不占端口。
- **自带调试日志。** 窗口里的「日志」页签会逐行打印执行的命令、退出码、耗时、命令原始输出和最终打开的地址。

## 安装

### 方式一：插件市场

在 `设置 → 插件 → Marketplace` 里搜索 **DeepSeek Harness Embedded** 安装。

### 方式二：从磁盘安装

1. 先按下方「从源码构建」生成 zip。
2. `设置 → 插件 → 齿轮 ⚙ → 从磁盘安装插件…`，选中该 zip。
3. **重启 IDE。**

## 使用

1. 重启后，右侧边栏会出现 **DeepSeek Harness** 图标（那条竖排按钮里的鲸鱼，线条颜色自动跟随 IDE 主题）。
2. 点击它打开窗口。窗口有两个页签：

   - **Harness** —— 主界面。插件会自动检测 `dsh` 并拉起本地服务，然后把 Harness 界面嵌在这里。
   - **日志** —— 全流程调试信息。遇到问题时**先看这里**。

3. 如果本机没有可用的 `dsh`，Harness 页签会显示安装指引和提示语「dsh 命令完成后，点击下方刷新按钮」。
   按指引装好 `dsh` 后，**点「刷新」**即可，不需要重启 IDE。

## 配置项

配置路径：`设置 → 工具 → DeepSeek Harness Embedded`。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| 在工具窗口上方渲染「Harness」和「日志」页签 | 勾选 | 勾选时窗口上方显示这两个页签，可在主界面与调试日志之间切换；取消勾选则不渲染页签栏，只显示 Harness 主界面，把页签栏占用的高度让给内容区域，适合屏幕高度有限或不需要在 IDE 内看日志的场景。取消后日志仍照常写入 IDE 的 `idea.log`。修改立即生效，无需重启 IDE。 |

## 工作原理

```text
点击侧边栏图标
   │
   ├─ 检测：cmd.exe /c dsh --version          （5 秒超时）
   │     ├─ 不可用 ──► 显示安装指引 + 刷新按钮
   │     └─ 可用 ──► 继续
   │
   ├─ 选端口：在 3080..3100 中找第一个空闲端口
   │
   ├─ 启动：cmd.exe /c dsh web --host 127.0.0.1 --port <端口> --no-open
   │         工作目录 = 用户主目录
   │
   ├─ 抓地址：并发读取其 stdout，匹配 `dsh web: <url>`（含 token）  （60 秒超时）
   │
   ├─ 内嵌：用 JCEF 浏览器加载该地址
   │
   └─ 释放：窗口关闭 / 项目关闭 / IDE 退出时
             taskkill /F /T /PID <pid>   ← 结束整棵进程树
```

几个关键设计点：

- **每个项目窗口各起一个 `dsh` 进程**，互不干扰。
- **不复用别人已经起好的服务。** Harness 的 token 只出现在进程自己的 stdout 里，无法从外部获取，所以插件必须自己拉起进程。
- **必须 `taskkill /F /T`。** `cmd.exe /c dsh web ...` 会形成 `cmd.exe → DeepSeek Harness.exe` 两层以上的进程树，只结束最外层会留下孤儿进程继续占着端口。
- **必须边读边等。** Windows 上如果子进程的 stdout 是无人读取的管道，`cmd.exe` 不会退出。所以读输出和等退出是并发进行的，而不是「先等完再读」。
- **端口被占用会自动顺延**，所以你已有的 `dsh web` 实例不受影响。

## 版本要求

- **IDE：2025.3（build 253）及以上。** 插件不声明 `until-build`，后续版本可继续安装。
- 已在 **WebStorm 2025.3.2** 与 **PyCharm 2025.3.3** 上核对过兼容性。
- 插件用 Kotlin 2.4.21 编译，语言/API 版本对齐 Kotlin 2.2，JVM 目标 21。
- 更老的 IDE（如 2024.3）暂未支持：那需要下载对应 IDE 跑一遍二进制兼容性验证，目前还没有做。如果你需要，可以提 issue。

## 从源码构建

```bash
# 先按你本机的 IDE 路径改 gradle.properties 里的 intellijPlatformLocalPath
./gradlew buildPlugin          # Windows: gradlew.bat buildPlugin
```

产物在 `build/distributions/deepseek-harness-jetbrains-embedded-plugin-<版本>.zip`。

想在沙箱 IDE 里直接试（不影响你现有配置）：

```bash
./gradlew runIde
```

> `gradle.properties` 里的 `intellijPlatformLocalPath` 指向本机 IDE，用来当编译平台。
> `org.gradle.java.installations.paths` 指向该 IDE 自带的 JBR —— 平台插件会强制要求 Java 21 toolchain，
> 直接复用 IDE 自带的 JDK 比另外装一个省事。

## 故障排查

| 现象 | 先看这里 | 常见原因 |
| --- | --- | --- |
| 点开就是安装指引，但我明明装了 | 「日志」页签里的检测退出码与原始输出 | IDE 没继承到 `dsh` 的 PATH，重启 IDE |
| 一直停在「正在检测 dsh 命令…」 | 日志里的超时提示 | `dsh --version` 超过 5 秒没返回 |
| 显示 Harness 启动失败 | 日志里 `dsh:` 开头的原始输出 | `dsh web` 自身报错，按输出内容排查 |
| 页面加载不出来 | 日志里 `JBCefApp.isSupported()` 的结果 | IDE 不支持/未启用 JCEF |
| 关掉窗口后端口还被占着 | 日志里 taskkill 那行 | 请提 issue，附上日志 |

「日志」页签里的内容同时会写进 IDE 的 `idea.log`。

## 已知限制

- 插件**不管理** Harness 的模型、账号与登录态 —— 全部沿用你桌面端已有的配置。这也正是它的设计目标。
- 「日志」页签里的 3080–3100 端口全部被占用时会直接报错，不会继续往上试。
- 日志页签最多保留最近 5000 行。
- 日志界面不做富文本渲染，纯文本输出。

## 目录结构

```text
src/main/kotlin/com/deepseek/harness/jetbrains/
├── DshCli.kt                      # dsh 进程封装：检测 / 启动 / 抓地址 / 结束进程树
├── LogLevel.kt
└── ui/
    ├── HarnessPanel.kt            # 四态卡片：CHECKING / GUIDE / BROWSER / ERROR
    ├── HarnessToolWindowFactory.kt
    ├── HarnessToolWindowPanel.kt  # 两个页签的容器与释放链
    └── LogPanel.kt                # 日志页签
src/main/resources/
├── icons/deepseek.svg             # 侧边栏工具窗口图标
└── META-INF/
    ├── plugin.xml
    └── pluginIcon.svg
```

设计与实施文档见 [`docs/superpowers/`](docs/superpowers/)。

## 许可

见仓库 LICENSE（如未附带，请按仓库默认约定）。
