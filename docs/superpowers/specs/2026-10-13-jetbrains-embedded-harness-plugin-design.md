# JetBrains 内嵌 Harness 插件 —— 设计文档

- 需求名称：JetBrains 内嵌 Harness 插件
- 日期：2026-10-13
- 分支：`feat-prod-ff-jetbrains-embedded-harness-20261013`
- 插件 ID：`com.deepseek.harness.jetbrains`

---

## 1. 背景与目标

把本机已安装的 DeepSeek Harness 以**内嵌浏览器**的形式搬进 JetBrains IDE，让开发者在 IDE 内直接使用 Harness。

用一句话定义成功：**在 IDE 侧边栏点一下 DeepSeek 图标，就能在 IDE 窗口里正常使用 Harness；如果本机没装 `dsh` 命令，就给出可执行的安装指引并能一键重新检测。**

## 2. 已核实的环境事实

以下均为实测结论，不是假设。设计中的每个技术选择都基于这些事实。

| 事实 | 核实方式 |
| --- | --- |
| `dsh` 已在 PATH 上，唯一命中 `D:\DeepSeek Harness\resources\runtime\cli\bin\dsh.cmd` | `cmd /c where dsh` |
| `dsh --version` 输出 `0.2.0-rc.2`，**退出码 0**；不存在的命令退出码为 1 | `cmd.exe /c "dsh --version"` → `$LASTEXITCODE=0` |
| `dsh.cmd` 是转发脚本：设置 `ELECTRON_RUN_AS_NODE=1` 后执行 `DeepSeek Harness.exe --expose-internals ...\cli.js %*` | 读取 `dsh.cmd` 内容 |
| 3080 端口已被一个手动启动的 `dsh web --host 127.0.0.1 --port 3080 --no-open` 占用（PID 26500）；无 token 访问返回 **401** | `Win32_Process.CommandLine` + HTTP 探测 |
| 「应用 → 管理 dsh 命令…」是 **DSH 桌面端**的菜单项 | `app.asar` 中 `application: "应用"`、`cliCommandMenu: "管理 dsh 命令…"` |
| DSH 官网为 `https://harness.deepseek.com` | `app.asar` 中的 URL 扫描 |
| 本机 IDE：PyCharm 2025.3.3（`PY-253.31033.139`）、目录名为 `WebStorm 2024.3.5` 实为 WebStorm 2025.3.2（`253.30387.83`） | `product-info.json` |
| JCEF 可用：`com/intellij/ui/jcef/JBCefApp` 在 `lib/app.jar`，另有 `lib/module-intellij.libraries.jcef.jar` | jar 内条目扫描 |
| PyCharm/WebStorm 的 `lib/util-8.jar` 内置完整 kotlin-stdlib（`CollectionsKt`/`StringsKt`/`FilesKt`/`SequencesKt` 均在） | jar 内条目扫描 |
| 两个 IDE 都自带 JBR 21（PyCharm `21.0.10`） | `jbr/release` |
| 本机无 Gradle、无 `~/.gradle`；`services.gradle.org`、`plugins.gradle.org`、`repo1.maven.org`、`cache-redirector.jetbrains.com` 均可达（HTTP 200） | 目录探测 + HTTP 探测 |
| 桌面图标 `C:\Users\ThinkPad\Desktop\deepseek-icon.ico` 只含 **1 张 225×225 32bpp** 位图 | ICO 头解析 |

### 2.1 由事实推出的两条硬约束

1. **插件必须自己启动 `dsh web` 进程，不能复用已存在的实例。** 因为访问 Harness 需要 `?token=...`，而 token 只在该进程启动时打印在 stdout；别人的实例拿不到 token（实测 401）。这也正是「3080 被占用就换端口」这一需求成立的原因。
2. **必须用 `cmd.exe /c` 执行 dsh。** Windows 的 `CreateProcess` 不能直接执行 `.cmd`，Java 的 `ProcessBuilder` 也不行。

## 3. 范围

### 3.1 做

- IDE 侧边栏工具窗口 + DeepSeek 图标。
- 打开窗口后自动检测 `dsh --version`。
- 检测失败 → 展示安装指引 + 刷新按钮。
- 检测成功 → 起 `dsh web` 进程、抓取带 token 的 URL、用 JCEF 内嵌加载。
- 窗口内两个 tab：Harness / 日志。
- 日志 tab 覆盖全部关键节点，供开发调试。
- Gradle 构建出可本地安装（Install Plugin from Disk）的插件包。

### 3.2 不做（YAGNI）

| 不做的事 | 原因 |
| --- | --- |
| 插件内自动安装 dsh CLI | 安装由 DSH 桌面端的「管理 dsh 命令」负责，插件只做指引 |
| 多语言 i18n | 目标用户只有中文场景，硬编码中文即可 |
| 插件设置页（可配端口/路径） | 需求没要求；默认值 + 自动顺延端口已够用 |
| 跨项目共享一个 dsh 进程 | 已确认按「每个项目窗口各起一个」 |
| 单元测试 | 纯 UI + 进程编排；用日志 tab 替代，由用户手动验证 |
| README / 市场描述文案 | 等验证通过后再补 |

## 4. 架构

### 4.1 组件

```
HarnessToolWindowFactory            注册并创建工具窗口内容
   └── HarnessToolWindowPanel       JBTabbedPane：「Harness」+「日志」
         ├── HarnessPanel           CardLayout 四态卡片；BROWSER 态持有 JBCefBrowser
         └── LogPanel               只读文本域 + 自动滚到底

DshCli（纯逻辑，无 UI 依赖）
   ├── detect()     执行 cmd /c dsh --version
   ├── startWeb()   选端口 → 起进程 → 解析 stdout 拿 URL
   └── stop()       taskkill /F /T 结束进程树
```

### 4.2 文件结构

```
deepseek-harness-JetBrains-Embedded-plugin/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/*
├── docs/superpowers/specs/2026-10-13-jetbrains-embedded-harness-plugin-design.md
└── src/main/
    ├── kotlin/com/deepseek/harness/jetbrains/
    │   ├── DshCli.kt
    │   └── ui/
    │       ├── HarnessToolWindowFactory.kt
    │       ├── HarnessToolWindowPanel.kt
    │       ├── HarnessPanel.kt
    │       └── LogPanel.kt
    └── resources/
        ├── META-INF/plugin.xml
        ├── META-INF/pluginIcon.png
        └── icons/deepseek-13.png
```

### 4.3 依赖方向

`HarnessToolWindowFactory` → `HarnessToolWindowPanel` → `HarnessPanel` / `LogPanel` → `DshCli`。

`DshCli` 不依赖任何 UI 类，通过构造参数拿两个回调：

- `log: (level, message) -> Unit`
- `onResult: (Result) -> Unit`

这样进程逻辑可以被单独读懂，也避免 UI 与进程逻辑双向耦合。

## 5. 交互状态机

```
工具窗口打开
   │
   ▼
[CHECKING] 自动执行 dsh --version
   ├── 退出码 0 ──────────────▶ [STARTING] 选端口 + 起进程 + 抓 URL
   │                                ├── 拿到 URL ──▶ [BROWSER] JCEF 加载
   │                                └── 失败/超时 ─▶ [ERROR]
   └── 非 0 / 超时 ───────────▶ [GUIDE] 安装指引 + 刷新按钮
                                     └── 点刷新 ──▶ 回到 [CHECKING]
[ERROR] 也提供刷新按钮 → 回到 [CHECKING]
```

- 首次打开工具窗口即自动执行 CHECKING，不需要用户再点一次。
- 刷新按钮的精确语义（避免歧义）：
  1. 若已有存活的进程（`Process.isAlive()` 为 true）→ **不做任何检测**，直接回到 BROWSER 态，日志记一条「复用已有进程」。
  2. 否则 → 走完整的 CHECKING → (GUIDE | STARTING) 流程。
- 进程一旦成功启动，在本次面板生命周期内**复用**，不重复起进程。
- 刷新按钮在 CHECKING / STARTING 期间禁用，防止重入。

## 6. 关键实现细节

### 6.1 dsh 检测

```
命令：cmd.exe /c dsh --version
超时：5 秒（超时即判定不可用）
判定：退出码 == 0 → 可用
```

实测 `cmd.exe /c "dsh --version"` 退出码为 0、不存在的命令为 1，判定成立。

输出按 UTF-8 无 BOM 解码写日志。cmd.exe 自身的报错可能使用 OEM 代码页，中文可能显示为乱码；这不影响判定（只看退出码），URL 是纯 ASCII 也不受影响。

### 6.2 端口选择

从 **3080** 开始，`ServerSocket.bind(port)` 逐个试到 **3100**：

- 第一个能绑上的端口即为选中端口，**立即释放**再交给 dsh（存在极小的 TOCTOU 竞态窗口，接受）。
- 若端口被占，记一条日志（如 `3080 被占用，改用 3081`）。
- 3080–3100 全部被占 → ERROR 态。

### 6.3 启动服务与 URL 抓取

```
命令：cmd.exe /c dsh web --host 127.0.0.1 --port <port> --no-open
工作目录：user.home（写入日志）
stdout/stderr：合并（redirectErrorStream = true）
```

合并 stderr 的理由：省掉一个读线程；URL 解析只认固定前缀，不会被 stderr 干扰。

逐行读取，匹配正则 `^\s*dsh web:\s*(http://\S+)`，取捕获组作为 URL。实测 dsh 打印形如：

```
dsh web: http://127.0.0.1:3080/?token=aXvtZ6k93EaVvSNnDy-gvKDiF4rHjGWd_1YdSCbLyBc
```

- **等待超时 60 秒**未匹配到 URL → ERROR。
- 进程在匹配到 URL 之前退出 → ERROR，日志附退出码与已收到的全部输出。
- stdout 的**每一行**都原样写入日志 tab（不只是 URL 行）。

### 6.4 进程生命周期

- 进程存活判定统一使用 `Process.isAlive()`。
- 进程随 `HarnessToolWindowPanel` 的 `Disposable` 释放：
  - 工具窗口被**隐藏** → 不杀进程（符合已确认的选择）。
  - 工具窗口被**移除** / 项目关闭 / IDE 退出 → 调用 `stop()`。
- `stop()` 执行 `taskkill /F /T /PID <pid>`：因为 `cmd.exe /c dsh.cmd` 会产生 cmd.exe → `DeepSeek Harness.exe` 两层进程，只 `destroy()` cmd.exe 会让 exe 变成孤儿继续占用端口。
- `taskkill` 本身有 5 秒超时，失败只记日志、不抛异常。
- 面板释放时同时释放 `JBCefBrowser`。

### 6.5 JCEF 内嵌

- 进入 BROWSER 态前先检查 `JBCefApp.isSupported()`；不支持则进 ERROR 态并提示可复制 URL 到外部浏览器打开。
- `JBCefBrowser` 在 **EDT** 上创建，懒加载并缓存于 `HarnessPanel`，避免每次切换重复创建。
- 加载完成后日志记录页面加载事件。
- 面板释放时 `browser.dispose()`。

### 6.6 日志

统一行格式：

```
[HH:mm:ss.SSS] [INFO|WARN|ERROR] 内容
```

写入两处：日志 tab 的文本域，以及 IDE 的 `idea.log`（`Logger.getInstance(...)`）。

必须覆盖的日志点：

| 时机 | 内容 |
| --- | --- |
| 工具窗口打开 | 面板创建 |
| 开始检测 | 完整命令行、工作目录 |
| 检测结束 | 退出码、耗时、stdout/stderr 全部内容 |
| 端口选择 | 每个被跳过端口的占用情况、最终选中端口 |
| 启动服务 | 完整命令行、工作目录、进程 PID |
| 进程输出 | stdout/stderr 的每一行原样 |
| 抓到 URL | 完整 URL |
| JCEF | `isSupported()` 结果、加载开始/结束、加载失败 |
| 进程退出 | 退出码、发生时机（启动前/运行中） |
| 停止 | 触发的 taskkill 命令与结果 |
| 异常 | 堆栈摘要 |

### 6.7 线程模型

- 检测、起进程、读 stdout、taskkill 全部在后台线程执行（`ApplicationManager.getApplication().executeOnPooledThread`）。
- 任何 UI 更新（切换卡片、追加日志、创建浏览器）通过 `ApplicationManager.getApplication().invokeLater` 回到 EDT。
- 日志 tab 的追加在 EDT 上做，保证文本域线程安全。

## 7. UI 规格

### 7.1 两个 Tab

| Tab 标题 | 内容 |
| --- | --- |
| `Harness` | 四态卡片（CHECKING / GUIDE / BROWSER / ERROR） |
| `日志` | 只读文本域，自动滚到底 |

### 7.2 GUIDE 卡片（严格按需求的排列）

```
未检测到 dsh 命令

请先安装 DeepSeek Harness：
1. 打开官网 https://harness.deepseek.com 下载并安装桌面端。
2. 安装完成后，在桌面端左上角菜单「应用 → 管理 dsh 命令…」中安装 dsh 命令行工具。

dsh 命令完成后，点击下方刷新按钮

[ 刷新 ]
```

顺序说明：提示语写的是「点击下方刷新按钮」，所以按钮放在提示语下方。

### 7.3 其余三态

| 态 | 内容 |
| --- | --- |
| CHECKING | `正在检测 dsh 命令…` |
| BROWSER | `JBCefBrowser` 占满整个 tab |
| ERROR | `Harness 启动失败` + 失败原因 + `刷新` 按钮 |

## 8. 构建与打包

版本组合是**被两个硬约束夹出来的唯一解**，不能随意升降：

| 组件 | 版本 | 约束来源 |
| --- | --- | --- |
| Gradle | **9.7.0** | IntelliJ Platform Gradle Plugin 2.x 要求 Gradle ≥ 9.0.0；Kotlin 2.4.21 完全支持到 9.7.0 |
| IntelliJ Platform Gradle Plugin | **2.19.0** | 当前最新 |
| Kotlin | **2.4.21** | Kotlin 2.2.x 最高只支持 Gradle 8.14，与 Gradle ≥ 9 冲突 |
| JVM target | **21** | 两个目标 IDE 都跑 JBR 21 |

`build.gradle.kts` 要点：

- `repositories { mavenCentral(); intellijPlatform { defaultRepositories() } }`
- `dependencies { intellijPlatform { local(providers.gradleProperty("intellijPlatformLocalPath").get()) } }` —— 用本机 IDE 当编译平台，**不下载 IDE 安装包**。
  - 平台路径不写死在 `build.gradle.kts` 里，而是放在 `gradle.properties` 的 `intellijPlatformLocalPath`（当前值 `D:\\PyCharm 2025.3.3`），换机器只改这一行。
- 不配置 Java toolchain（避免 Gradle 去下载/探测 JDK），直接设 `sourceCompatibility = targetCompatibility = 21` 与 Kotlin `jvmTarget = JVM_21`。
- `intellijPlatform { pluginConfiguration { ideaVersion { sinceBuild = "243" } } }`，不设 `untilBuild`。
- 无测试源集，不加测试依赖。

`plugin.xml` 要点：

```xml
<idea-plugin>
  <id>com.deepseek.harness.jetbrains</id>
  <name>DeepSeek Harness</name>
  <vendor>DeepSeek Harness Community</vendor>
  <depends>com.intellij.modules.platform</depends>
  <extensions defaultExtensionNs="com.intellij">
    <toolWindow id="DeepSeek Harness"
                anchor="right"
                icon="/icons/deepseek-13.png"
                factoryClass="com.deepseek.harness.jetbrains.ui.HarnessToolWindowFactory"/>
  </extensions>
</idea-plugin>
```

只依赖 `com.intellij.modules.platform`，因此 PyCharm / WebStorm / IDEA 全系可装。JCEF 属于平台自带，运行时用 `JBCefApp.isSupported()` 兜底，不额外声明依赖。

产物：`build/distributions/*.zip`，通过 `Settings → Plugins → ⚙ → Install Plugin from Disk…` 本地安装；同一份 zip 也满足市场发布的打包格式。

### 8.1 Gradle wrapper 的生成方式

本机没有 Gradle，需要先生成 wrapper。已实测：

- `gradle-9.7.0-bin.zip` 可下载（HTTP 200，约 150 MB）。
- **`gradle-9.7.0-wrapper.jar` 不可下载（HTTP 404）** —— 该路径下只发布了 `.sha256` 校验文件，没有 jar 本体。所以「直接下载 wrapper jar」这条捷径不成立，虽然 Gradle 官网的 current-version JSON 里确实有 `wrapperChecksumUrl` 字段。
- `gradlew` / `gradlew.bat` 可从 Gradle 官方仓库 `v9.7.0` tag 取到（HTTP 200）。

因此采用以下步骤，全部在仓库外的临时目录完成：

1. 下载 `https://services.gradle.org/distributions/gradle-9.7.0-bin.zip` 到临时目录并解压。
2. 用解压出的 `gradle` 执行 `gradle wrapper --gradle-version 9.7.0`，生成 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`、`gradle/wrapper/gradle-wrapper.properties`。
3. 校验 `gradle-wrapper.properties` 的 `distributionUrl` 指向 `gradle-9.7.0-bin.zip`。

**只做脚手架，不执行 `buildPlugin`** —— 编译验证由用户执行。

## 9. 图标资源

源图：`C:\Users\ThinkPad\Desktop\deepseek-icon.ico`（单张 225×225 32bpp 位图）。

用 Pillow（随 DSH 提供的 Python 12.3.0 已含）生成：

| 输出 | 尺寸 | 用途 |
| --- | --- | --- |
| `src/main/resources/icons/deepseek-13.png` | 13×13 | 工具窗口侧边栏图标 |
| `src/main/resources/META-INF/pluginIcon.png` | 40×40 | 插件图标（Plugins 列表 / 市场） |

生成脚本用 `Image.open(...).resize((n, n), Image.LANCZOS)`，使用 `convert("RGBA")` 保留透明通道。脚本只在开发期使用，不保留在仓库里。

## 10. 错误处理

| 场景 | 处理 |
| --- | --- |
| `dsh --version` 非 0 或超时 | GUIDE 卡片 |
| 3080–3100 全被占 | ERROR 卡片，提示端口被占用 |
| 60 秒未打印 URL | ERROR 卡片 + 日志附已收到的输出 |
| 进程在打印 URL 前退出 | ERROR 卡片，附退出码与输出 |
| `JBCefApp.isSupported()` 为 false | ERROR 卡片，提示复制 URL 到外部浏览器 |
| JCEF 加载失败 | 日志记错误；浏览器保留 IDE 默认错误页 |
| taskkill 失败 | 仅记日志 |

所有错误态都提供刷新按钮回到 CHECKING。

## 11. 验证方式

按项目规范，构建与运行验证由用户执行，AI 不主动跑构建。

1. `gradlew buildPlugin`
2. `Settings → Plugins → ⚙ → Install Plugin from Disk…` 选 `build/distributions/` 下的 zip，重启 IDE
3. 侧边栏出现 DeepSeek 图标 → 点击打开窗口
4. 本机 `dsh` 可用，预期直接进入 BROWSER 态并加载 Harness 界面
5. 想验证 GUIDE 态：临时把 dsh 从 PATH 移除（或用一台未安装的机器）
6. 日志 tab 应能看到第 6.6 节列出的全部节点

## 12. 风险与取舍

| 项 | 说明 |
| --- | --- |
| 端口 TOCTOU 竞态 | `bind` 探测与 dsh 实际监听之间有窗口期；概率极低，接受。若真撞上，dsh 会报错退出 → ERROR 态 + 刷新可恢复 |
| 首次构建下载量 | 用 local 平台已避开 IDE 安装包；仍需下载 Gradle 发行版、平台插件与少量 Maven 依赖 |
| 每个项目窗口各一个 dsh 进程 | 已确认的行为；代价是每个进程一份内存。若日后要全局共享，需把进程提升为 application service，并解决 JCEF 实例跨项目复用问题 |
| `sinceBuild = "243"` | 编译平台是 253，理论上可能误用 244–253 才有的 API。本插件只用 JCEF/ToolWindow/Swing 等长期稳定 API，风险低 |
| cmd.exe 报错信息乱码 | 仅影响日志可读性，不影响功能判定 |
| 用户自行构建 | 版本组合已按官方兼容表夹定，但首次构建仍可能因网络/镜像差异需要调整 |
