# JetBrains 内嵌 Harness 插件 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做一个 JetBrains 插件，在 IDE 侧边栏点一下 DeepSeek 图标，就能把本机已安装的 DeepSeek Harness 以 JCEF 内嵌浏览器的方式在 IDE 窗口里使用；`dsh` 命令不可用时给出安装指引与刷新按钮。

**Architecture:** 一个工具窗口承载 `JBTabbedPane`，左 tab 是四态卡片（CHECKING / GUIDE / BROWSER / ERROR），右 tab 是调试日志。进程逻辑收在一个不依赖任何 UI 类的 `DshCli` 里，通过 `(LogLevel, String) -> Unit` 回调把日志外抛给 UI；进程与 JCEF 浏览器的生命周期都绑在 `HarnessPanel` 的 `Disposable` 上。

**Tech Stack:** Kotlin 2.4.21、Gradle 9.7.0、IntelliJ Platform Gradle Plugin 2.19.0、JVM target 21、IntelliJ Platform 2025.3（本机 PyCharm 作为编译平台）、JCEF（`JBCefBrowser`）。

**Spec:** `docs/superpowers/specs/2026-10-13-jetbrains-embedded-harness-plugin-design.md`

## Global Constraints

以下值全部来自 spec，每个任务都隐含适用：

- 版本组合（**被约束夹死，不可升降**）：Gradle `9.7.0`、IntelliJ Platform Gradle Plugin `2.19.0`、Kotlin `2.4.21`、JVM target `21`。
  - 依据：平台 Gradle 插件要求 Gradle ≥ 9.0.0；Kotlin 2.4.21 完全支持到 Gradle 9.7.0（Kotlin 2.2.x 只到 8.14，会冲突）。
- 编译平台取自 `gradle.properties` 的 `intellijPlatformLocalPath`，当前值 `D:/PyCharm 2025.3.3`；**不下载 IDE 安装包**。
- 插件 ID `com.deepseek.harness.jetbrains`；名称 `DeepSeek Harness`；供应商 `DeepSeek Harness Community`；版本 `0.1.0`。
- `sinceBuild = "243"`，不设 `untilBuild`。
- `plugin.xml` 只依赖 `com.intellij.modules.platform`（保证 PyCharm / WebStorm / IDEA 全系可装）。JCEF 属平台自带，不额外声明依赖，运行时用 `JBCefApp.isSupported()` 兜底。
- 包结构：逻辑在 `com.deepseek.harness.jetbrains`，UI 在 `com.deepseek.harness.jetbrains.ui`。**`DshCli` 不得 import 任何 `ui` 包或 Swing 类。**
- 所有用户可见文案为中文、硬编码，**不做 i18n**。
- 命令固定为：检测 `cmd.exe /c dsh --version`；启动 `cmd.exe /c dsh web --host 127.0.0.1 --port <port> --no-open`。
- 超时：检测 5 秒；等待 URL 60 秒；`taskkill` 5 秒。
- 端口范围 `3080..3100`。
- URL 解析正则：`^\s*dsh web:\s*(http://\S+)`。
- 日志行格式：`[HH:mm:ss.SSS] [INFO|WARN|ERROR] 内容`，同时写日志 tab 与 `idea.log`。
- **本项目不做单元测试**（spec 已明确），也**不主动运行任何构建命令**（项目规范）：验证步骤中标「用户执行」的部分由用户跑，AI 只做静态检查。
- 提交信息格式：`[FEAT/FIX/...] JetBrains 内嵌 Harness 插件 -- 中文描述`。

## Review Focus

以下是 spec 隐含、但没有自动化测试覆盖、最可能咬到真实使用者的输入类别。每条都在对应任务里被钉成一条**明确的代码要求**，并配一个可执行的检查动作。

1. **`dsh --version` 成功但输出异常**（stderr 混入、中文乱码、多行）——判定只能看退出码，不能因输出内容异常判为不可用。→ Task 4。
2. **URL 行带前后空格或 ANSI 颜色码**——正则以 `^\s*` 容忍行首空白，捕获组用 `\S+` 取整段 URL。→ Task 4。
3. **进程已退出后才读完输出 / 最后一行没有换行结尾**——读取循环结束时必须主动结束等待，否则要白等满 60 秒；且不能因为无换行而丢掉 URL 行。→ Task 4。
4. **重复点刷新、快速开关工具窗口**——不能起出第二个进程，也不能留下孤儿进程占端口。→ Task 4 + Task 5。
5. **3080 被非 dsh 程序占用**（当前机器就是这种情况）——必须顺延到 3081 并照常工作，不能报错或崩溃。→ Task 4。

---

## 文件结构

| 文件 | 职责 |
| --- | --- |
| `settings.gradle.kts` | 项目名 |
| `gradle.properties` | 平台路径、Gradle JVM 参数 |
| `build.gradle.kts` | 插件、仓库、平台依赖、JVM target、sinceBuild |
| `gradle/wrapper/gradle-wrapper.properties` | 指向 Gradle 9.7.0 |
| `gradle/wrapper/gradle-wrapper.jar`、`gradlew`、`gradlew.bat` | wrapper（下载官方文件） |
| `src/main/resources/META-INF/plugin.xml` | 插件清单与工具窗口注册 |
| `src/main/resources/META-INF/pluginIcon.png` | 插件图标（40×40） |
| `src/main/resources/icons/deepseek-13.png` | 侧边栏图标（13×13） |
| `src/main/kotlin/com/deepseek/harness/jetbrains/LogLevel.kt` | 日志级别枚举（不含 UI 依赖） |
| `src/main/kotlin/com/deepseek/harness/jetbrains/DshCli.kt` | 检测 / 选端口 / 起服务 / 抓 URL / 停进程 |
| `src/main/kotlin/com/deepseek/harness/jetbrains/ui/LogPanel.kt` | 日志 tab（线程安全的追加） |
| `src/main/kotlin/com/deepseek/harness/jetbrains/ui/HarnessPanel.kt` | 四态卡片 + JBCefBrowser + 进程生命周期 |
| `src/main/kotlin/com/deepseek/harness/jetbrains/ui/HarnessToolWindowPanel.kt` | 两个 tab 的容器 |
| `src/main/kotlin/com/deepseek/harness/jetbrains/ui/HarnessToolWindowFactory.kt` | 工具窗口入口 |

---

### Task 1: Gradle 脚手架 + 最小可构建插件

**Files:**
- Create: `settings.gradle.kts`
- Create: `gradle.properties`
- Create: `build.gradle.kts`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar`（下载）
- Create: `gradlew`、`gradlew.bat`（下载）
- Create: `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: 无。
- Produces: 可用的 `gradlew.bat`；`build.gradle.kts` 中已配好的 Kotlin/JVM/平台依赖，后续任务只需往 `src/main/kotlin` 加文件。此任务的 `plugin.xml` **不放**任何 `<extensions>`，因此能独立构建成功。

- [ ] **Step 1: 写 `settings.gradle.kts`**

```kotlin
rootProject.name = "deepseek-harness-jetbrains-embedded-plugin"
```

- [ ] **Step 2: 写 `gradle.properties`**

路径用正斜杠，避免 `.properties` 的反斜杠转义歧义。

```properties
# 本机 JetBrains IDE 安装目录，作为编译平台（不下载 IDE 安装包）
intellijPlatformLocalPath=D:/PyCharm 2025.3.3

org.gradle.jvmargs=-Xmx2g
org.gradle.caching=true
```

- [ ] **Step 3: 写 `build.gradle.kts`**

`local(...)` 与 `sinceBuild` 是 spec 钉死的两个决策，其余为常规配置。

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.deepseek.harness"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("intellijPlatformLocalPath").get())
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
        }
    }
}
```

- [ ] **Step 4: 下载 wrapper 三个文件**

本机没有 Gradle，也**不要**去下 150 MB 的 `gradle-9.7.0-bin.zip`：官方仓库对应 tag 里就有 wrapper jar。

```powershell
New-Item -ItemType Directory -Force -Path "gradle\wrapper" | Out-Null
Invoke-WebRequest "https://raw.githubusercontent.com/gradle/gradle/v9.7.0/gradle/wrapper/gradle-wrapper.jar" -OutFile "gradle\wrapper\gradle-wrapper.jar"
Invoke-WebRequest "https://raw.githubusercontent.com/gradle/gradle/v9.7.0/gradlew"     -OutFile "gradlew"
Invoke-WebRequest "https://raw.githubusercontent.com/gradle/gradle/v9.7.0/gradlew.bat" -OutFile "gradlew.bat"
```

- [ ] **Step 5: 写 `gradle/wrapper/gradle-wrapper.properties`**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.0-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 6: 静态验证 wrapper 与配置文件**

```powershell
# wrapper jar 必须是合法 jar 且含 GradleWrapperMain
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path "gradle\wrapper\gradle-wrapper.jar"))
$z.Entries | Where-Object { $_.FullName -eq "org/gradle/wrapper/GradleWrapperMain.class" } | Select-Object FullName
$z.Dispose()
# plugin.xml 必须是合法 XML
[xml](Get-Content "src\main\resources\META-INF\plugin.xml" -Raw) | Out-Null; "plugin.xml OK"
# distributionUrl 必须指向 9.7.0-bin.zip
Select-String -Path "gradle\wrapper\gradle-wrapper.properties" -Pattern "gradle-9.7.0-bin.zip"
```

预期：输出 `GradleWrapperMain.class`、`plugin.xml OK`、以及匹配到 `gradle-9.7.0-bin.zip` 的那一行。

- [ ] **Step 7: 写最小 `src/main/resources/META-INF/plugin.xml`**

```xml
<idea-plugin>
    <id>com.deepseek.harness.jetbrains</id>
    <name>DeepSeek Harness</name>
    <vendor>DeepSeek Harness Community</vendor>
    <description><![CDATA[在 JetBrains IDE 内嵌使用本机已安装的 DeepSeek Harness。]]></description>
    <depends>com.intellij.modules.platform</depends>
</idea-plugin>
```

- [ ] **Step 8: 用户执行构建验证**

Run: `.\gradlew.bat buildPlugin`
Expected: `BUILD SUCCESSFUL`，并生成 `build/distributions/deepseek-harness-jetbrains-embedded-plugin-0.1.0.zip`。

首次执行会下载 Gradle 9.7.0 发行版（约 150 MB）与平台插件依赖，耗时较长属正常。

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts gradle.properties build.gradle.kts gradle gradlew gradlew.bat src/main/resources/META-INF/plugin.xml
git commit -m "[FEAT] JetBrains 内嵌 Harness 插件 -- 搭建 Gradle 构建脚手架与最小插件骨架"
```

---

### Task 2: 图标资源

**Files:**
- Create: `src/main/resources/icons/deepseek-13.png`
- Create: `src/main/resources/META-INF/pluginIcon.png`

**Interfaces:**
- Consumes: 无。
- Produces: 两个固定路径的 PNG，Task 3 的 `plugin.xml` 用 `/icons/deepseek-13.png`，`pluginIcon.png` 由平台按约定自动读取。

源图：`C:\Users\ThinkPad\Desktop\deepseek-icon.ico`（实测只含 1 张 225×225 32bpp 位图）。

- [ ] **Step 1: 用 Pillow 生成两个尺寸**

用 DSH 自带的 Python（含 Pillow 12.3.0）。脚本写到临时目录，用完删掉，不进仓库。

```python
from PIL import Image
src = Image.open(r"C:\Users\ThinkPad\Desktop\deepseek-icon.ico").convert("RGBA")
for size, dst in [
    (13, r"src\main\resources\icons\deepseek-13.png"),
    (40, r"src\main\resources\META-INF\pluginIcon.png"),
]:
    src.resize((size, size), Image.LANCZOS).save(dst)
    print(dst, size)
```

- [ ] **Step 2: 验证生成的图片**

```powershell
$py = "C:\Users\ThinkPad\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
& $py -c "from PIL import Image; [print(p, Image.open(p).size, Image.open(p).mode) for p in [r'src\main\resources\icons\deepseek-13.png', r'src\main\resources\META-INF\pluginIcon.png']]"
```

预期：分别输出 `(13, 13) RGBA` 与 `(40, 40) RGBA`。再用 `read_image` 看一眼 40×40 那张，确认图案没糊成一团。

- [ ] **Step 3: 删除临时脚本**（文件在系统临时目录，不在仓库内）

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/icons/deepseek-13.png src/main/resources/META-INF/pluginIcon.png
git commit -m "[FEAT] JetBrains 内嵌 Harness 插件 -- 添加侧边栏图标与插件图标"
```

---

### Task 3: 日志面板 + 工具窗口注册（第一个可见里程碑）

**Files:**
- Create: `src/main/kotlin/com/deepseek/harness/jetbrains/LogLevel.kt`
- Create: `src/main/kotlin/com/deepseek/harness/jetbrains/ui/LogPanel.kt`
- Create: `src/main/kotlin/com/deepseek/harness/jetbrains/ui/HarnessToolWindowPanel.kt`
- Create: `src/main/kotlin/com/deepseek/harness/jetbrains/ui/HarnessToolWindowFactory.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`（加 `<extensions>`）

**Interfaces:**
- Consumes: Task 2 的 `/icons/deepseek-13.png`。
- Produces:
  - `enum class LogLevel { INFO, WARN, ERROR }`（包 `com.deepseek.harness.jetbrains`，**无 UI 依赖**）
  - `class LogPanel : JPanel`，方法 `fun append(level: LogLevel, message: String)`，**可从任意线程调用**
  - `class HarnessToolWindowPanel(project: Project) : JPanel, Disposable`

- [ ] **Step 1: 写 `LogLevel.kt`**

```kotlin
package com.deepseek.harness.jetbrains

enum class LogLevel { INFO, WARN, ERROR }
```

- [ ] **Step 2: 写 `LogPanel.kt`**

要点（都是 spec 钉死的决策）：

- `JBTextArea`，`isEditable = false`，`lineWrap = true`，字体 `JBUI.Fonts.create("JetBrains Mono", 12)`（IDE 自带该字体）。
- 放进 `JBScrollPane`，日志 tab 里占满。
- `append` 内部**无条件**走 `ApplicationManager.getApplication().invokeLater { ... }`，因此从任意线程调用都安全——这是让 `DshCli` 保持无 UI 依赖的关键，不要改成要求调用方在 EDT。
- 时间戳用 `java.time.LocalTime.now()` + `DateTimeFormatter.ofPattern("HH:mm:ss.SSS")`（**不要用 `SimpleDateFormat`**，它不是线程安全的）。
- 追加后把 `caretPosition` 设为文档长度，实现自动滚到底。
- 只保留最后 `MAX_LINES = 5000` 行：超过时从文档头部删到第一个换行之后。
- 同时用 `Logger.getInstance(LogPanel::class.java)` 按级别写 `idea.log`。

- [ ] **Step 3: 写 `HarnessToolWindowPanel.kt`**

组合两个 tab，日志 tab 已可用；Harness tab 本任务先放一个占位 `JLabel`（内容 `正在检测 dsh 命令…`），Task 6 换成真正的 `HarnessPanel`。

- `class HarnessToolWindowPanel(project: Project) : JPanel(BorderLayout()), Disposable`
- 持有 `private val logPanel = LogPanel()`
- 用 `com.intellij.ui.components.JBTabbedPane()` 加两个 tab，标题依次为 `Harness`、`日志`
  - 注意包名是 **`com.intellij.ui.components`**，不是 `com.intellij.ui.tabs`（后者只有 `JBTabs`，没有 `JBTabbedPane`；已在 PyCharm 2025.3 的 `lib/app.jar` 里核对过）
- 构造完成后写第一条日志：`logPanel.append(LogLevel.INFO, "工具窗口已创建")`
- `dispose()` 目前为空（Task 6 会往里加进程与浏览器的释放）

- [ ] **Step 4: 写 `HarnessToolWindowFactory.kt`**

```kotlin
class HarnessToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HarnessToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)          // 让面板的 dispose 跟工具窗口内容绑定
        toolWindow.contentManager.addContent(content)
    }
}
```

- [ ] **Step 5: 在 `plugin.xml` 里注册工具窗口**

在 `<depends>` 之后加入：

```xml
    <extensions defaultExtensionNs="com.intellij">
        <toolWindow id="DeepSeek Harness"
                    anchor="right"
                    icon="/icons/deepseek-13.png"
                    factoryClass="com.deepseek.harness.jetbrains.ui.HarnessToolWindowFactory"/>
    </extensions>
```

- [ ] **Step 6: 用户执行构建验证**

Run: `.\gradlew.bat buildPlugin`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 7: 用户执行安装验证**

`Settings → Plugins → ⚙ → Install Plugin from Disk…` 选 `build/distributions/` 下的 zip，重启 IDE。
Expected: 侧边栏出现 DeepSeek 图标；点击后窗口有两个 tab；`日志` tab 里有一行 `[..:..:.. . .. ] [INFO] 工具窗口已创建`。

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin src/main/resources/META-INF/plugin.xml
git commit -m "[FEAT] JetBrains 内嵌 Harness 插件 -- 添加工具窗口与调试日志面板"
```

---

### Task 4: DshCli 进程逻辑

**Files:**
- Create: `src/main/kotlin/com/deepseek/harness/jetbrains/DshCli.kt`

**Interfaces:**
- Consumes: `LogLevel`（Task 3）。
- Produces:

```kotlin
package com.deepseek.harness.jetbrains

class DshCli(private val log: (LogLevel, String) -> Unit) {

    sealed interface DetectResult {
        data class Available(val version: String) : DetectResult
        data class Unavailable(val reason: String) : DetectResult
    }

    sealed interface StartResult {
        data class Started(val url: String, val port: Int) : StartResult
        data class Failed(val reason: String) : StartResult
    }

    fun detect(): DetectResult     // 阻塞，须在后台线程调用
    fun startWeb(): StartResult    // 阻塞，须在后台线程调用
    fun isRunning(): Boolean
    fun stop()
}
```

`log` 回调**可从任意线程调用**（`LogPanel.append` 自己会切 EDT）。

- [ ] **Step 1: 实现 `detect()`**

- 命令：`ProcessBuilder("cmd.exe", "/c", "dsh --version")`，`redirectErrorStream(true)`。
- **先** `waitFor(5, TimeUnit.SECONDS)`，**再**读输出；未在 5 秒内结束就 `destroyForcibly()` 并返回 `Unavailable("执行 dsh --version 超时")`。
  - 顺序不能反：先读会在进程挂住时永久阻塞。`--version` 输出极小，不会撑满管道缓冲区，先等后读是安全的。
- **判定只看退出码**：`exitValue() == 0` → `Available(输出.trim())`；否则 `Unavailable("dsh --version 退出码为 $code")`。输出内容无论多脏都不影响判定（Review Focus 1）。
- 日志：完整命令行、退出码、耗时毫秒、原始输出全部写入。

- [ ] **Step 2: 实现 `findFreePort()`**

```kotlin
private fun findFreePort(): Int? {
    for (port in 3080..3100) {
        try {
            ServerSocket(port).use { }
            log(LogLevel.INFO, "选中端口 $port")
            return port
        } catch (e: IOException) {
            log(LogLevel.WARN, "端口 $port 被占用，尝试下一个")
        }
    }
    return null
}
```

绑上就立刻释放，再把端口交给 dsh（存在极小 TOCTOU 竞态窗口，spec 已接受）。绑定用的是通配地址，比 dsh 实际绑的 `127.0.0.1` 更严格，不会误判。

- [ ] **Step 3: 实现 `startWeb()` 的进程启动与读取**

- 命令：`ProcessBuilder("cmd.exe", "/c", "dsh", "web", "--host", "127.0.0.1", "--port", "$port", "--no-open")`。
  - 注意 `cmd.exe /c` 之后的参数要作为**独立参数**传入，不要自己拼成一整条带引号的字符串。
- `redirectErrorStream(true)`；`directory(File(System.getProperty("user.home")))`；工作目录写日志。
- 起进程后记录 PID。
- 用一个 `CompletableFuture<String>` 承接 URL，另起一个线程逐行读取：

```kotlin
val urlFuture = CompletableFuture<String>()
val reader = Thread {
    try {
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                log(LogLevel.INFO, "dsh: $line")          // 每一行都原样记录
                if (!urlFuture.isDone) {
                    URL_RE.find(line)?.let { urlFuture.complete(it.groupValues[1]) }
                }
            }
        }
    } catch (e: Exception) {
        log(LogLevel.WARN, "读取 dsh 输出失败：${e.message}")
    } finally {
        // 关键：流结束（进程退出）时主动结束等待，否则要白等满 60 秒（Review Focus 3）
        urlFuture.completeExceptionally(
            IllegalStateException("dsh 进程已结束，退出码 ${runCatching { process.exitValue() }.getOrNull()}")
        )
    }
}
reader.isDaemon = true
reader.start()
```

`URL_RE = Regex("""^\s*dsh web:\s*(http://\S+)""")` —— 行首 `\s*` 容忍缩进/ANSI 前置空白，`\S+` 一次取完整 URL（Review Focus 2）。

- [ ] **Step 4: 实现 `startWeb()` 的结果处理**

- `urlFuture.get(60, TimeUnit.SECONDS)`：
  - 拿到 URL → 记日志并返回 `Started(url, port)`。
  - `TimeoutException` → 返回 `Failed("60 秒内未打印出 dsh web 地址")`。
  - `ExecutionException` → 返回 `Failed(原因消息)`（进程提前退出的情况）。
- 无论成功失败，失败路径都要把已收到的输出留在日志里（读取线程已经逐行记过了）。

- [ ] **Step 5: 实现 `stop()` 与 `isRunning()`**

- `isRunning()` = `process?.isAlive() == true`。
- `stop()`：无进程直接返回；否则执行 `ProcessBuilder("taskkill", "/F", "/T", "/PID", pid)`。
  - `taskkill.exe` 是 System32 下的真实可执行文件，**不需要** `cmd.exe /c` 包一层。
  - `redirectErrorStream(true)`，`waitFor(5, TimeUnit.SECONDS)`，把 taskkill 的输出与退出码写日志。
  - 整个 `stop()` 用 `try/catch(Exception)` 包住，失败只记 `WARN`，**不抛异常**。
  - 结束后把 `process` 置空。
  - `/T` 不能省：`cmd.exe /c dsh.cmd` 会产生 cmd.exe → `DeepSeek Harness.exe` 两层进程，只 `destroy()` cmd.exe 会让 exe 变孤儿继续占端口（Review Focus 4）。

- [ ] **Step 6: 静态检查**

```powershell
Select-String -Path "src\main\kotlin\com\deepseek\harness\jetbrains\DshCli.kt" -Pattern "^import"
```

预期：**没有任何** `ui` 包或 Swing / IntelliJ 平台类的 import（只应有 `java.io`、`java.net`、`java.nio.charset`、`java.util.concurrent` 等 JDK 包）。这是本任务的核心约束。

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/deepseek/harness/jetbrains/DshCli.kt
git commit -m "[FEAT] JetBrains 内嵌 Harness 插件 -- 添加 dsh 命令检测与服务进程管理"
```

---

### Task 5: HarnessPanel 四态卡片 + JCEF 内嵌

**Files:**
- Create: `src/main/kotlin/com/deepseek/harness/jetbrains/ui/HarnessPanel.kt`

**Interfaces:**
- Consumes: `DshCli` 与 `LogLevel`（Task 4）、`LogPanel`（Task 3）。
- Produces:

```kotlin
package com.deepseek.harness.jetbrains.ui

class HarnessPanel(
    private val project: Project,
    private val log: (LogLevel, String) -> Unit,
) : JPanel(CardLayout()), Disposable
```

- [ ] **Step 1: 搭四态 CardLayout**

- 私有枚举 `private enum class Card { CHECKING, GUIDE, BROWSER, ERROR }`，四个卡片各是一个 `JPanel`，用 `add(panel, Card.name)` 加入。
- 持有 `private val dsh = DshCli(log)`，并缓存 `private var currentUrl: String? = null`。
- `init { start() }` —— 打开窗口即自动检测，不需要用户再点一次。
- 卡片内容按 `BoxLayout(Y_AXIS)` 排布、放 `BorderLayout.NORTH`、外边距 `JBUI.Borders.empty(16)`、组件左对齐。

- [ ] **Step 2: 写四态的切换与文案（文案为 spec 钉死，逐字照抄）**

| 态 | 内容 |
| --- | --- |
| CHECKING | `正在检测 dsh 命令…` |
| GUIDE | 标题 `未检测到 dsh 命令`；正文两行（见下）；提示语 `dsh 命令完成后，点击下方刷新按钮`；按钮 `刷新` |
| BROWSER | `JBCefBrowser` 占满 |
| ERROR | 标题 `Harness 启动失败`；失败原因；按钮 `刷新` |

GUIDE 正文两行逐字为：

```
请先安装 DeepSeek Harness：
1. 打开官网 https://harness.deepseek.com 下载并安装桌面端。
2. 安装完成后，在桌面端左上角菜单「应用 → 管理 dsh 命令…」中安装 dsh 命令行工具。
```

**控件顺序必须是：正文 → 提示语 → 刷新按钮。** 因为提示语写的是「点击下方刷新按钮」。

- [ ] **Step 3: 实现 `start()` 与刷新**

```
start():
  showChecking()
  后台线程:
    dsh.detect()
      Unavailable -> EDT: showGuide()
      Available   -> 记日志(版本) -> 继续走 startService()

startService()  [已在后台线程]:
  dsh.startWeb()
    Failed  -> EDT: showError(reason)
    Started -> currentUrl = url; EDT: showBrowser(url)

refresh():
  if (dsh.isRunning() && currentUrl != null) {   // 复用已有进程，不做任何检测
      记日志("复用已有进程")
      showBrowser(currentUrl)
      return
  }
  start()
```

- 后台线程统一用 `ApplicationManager.getApplication().executeOnPooledThread { ... }`。
- 所有 UI 变更统一用 `ApplicationManager.getApplication().invokeLater { ... }` 回到 EDT。
- `刷新` 按钮在 CHECKING / STARTING 期间 `isEnabled = false`，进入 GUIDE / ERROR / BROWSER 时才恢复，防止重入（Review Focus 4）。

- [ ] **Step 4: 实现 `showBrowser(url)`**

- 先判 `JBCefApp.isSupported()`；为 false 则改走 `showError("当前 IDE 不支持内嵌浏览器（JCEF），请手动在浏览器打开：$url")` 并 `return`。
- `JBCefBrowser` **懒加载并缓存**（只在 EDT 上创建一次），再次进入 BROWSER 态复用同一实例，避免反复创建。
- 创建时用 `JBCefBrowser(url)`，日志记录 `isSupported()` 结果、开始加载的 URL。
- 加加载监听（下面三处 API 都已在 PyCharm 2025.3 里用 `javap` 核对过签名）：

```kotlin
browser.getJBCefClient().addLoadHandler(
    object : CefLoadHandlerAdapter() {
        override fun onLoadingStateChange(
            b: CefBrowser, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean,
        ) {
            if (!isLoading) log(LogLevel.INFO, "页面加载完成：${b.url}")
        }
    },
    browser.getCefBrowser(),
)
```

  - **必须写 `getJBCefClient()`（大写 C）并用显式方法调用**，不要写 `browser.jbCefClient`：真实方法名是 `getJBCefClient`，按 Kotlin 的合成属性规则首两字母大写时不做首字母小写化，属性名会变得反直觉，显式调方法消除歧义。
  - `CefLoadHandlerAdapter` 与 `CefBrowser` 在 `org.cef.*`，来自 IDE 的 `lib/module-intellij.libraries.jcef.jar`，编译期由平台类路径提供。
  - `b.url` 对应 `CefBrowser.getURL()`（首两字母 `ge` 非全大写，属性名 `url` 是安全的）。
- 切到 BROWSER 卡片。

- [ ] **Step 5: 实现 `dispose()`**

- `dsh.stop()`（内部已 try/catch，不会抛）。
- `browser?.dispose()`，并把缓存置空。
- 这个 `dispose()` 由 Task 3 的 `content.setDisposer(panel)` 触发，覆盖「工具窗口被移除 / 项目关闭 / IDE 退出」三种情况；工具窗口只是**隐藏**时不会触发（符合已确认的选择）。

- [ ] **Step 6: 静态检查**

```powershell
# 确认所有 UI 变更都走 invokeLater，后台任务都走 executeOnPooledThread
Select-String -Path "src\main\kotlin\com\deepseek\harness\jetbrains\ui\HarnessPanel.kt" -Pattern "invokeLater|executeOnPooledThread"
```

预期：两种调用都出现，且没有在 EDT 上直接调用 `dsh.detect()` / `dsh.startWeb()`。

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/deepseek/harness/jetbrains/ui/HarnessPanel.kt
git commit -m "[FEAT] JetBrains 内嵌 Harness 插件 -- 添加四态面板与 JCEF 内嵌浏览器"
```

---

### Task 6: 组装接线与端到端

**Files:**
- Modify: `src/main/kotlin/com/deepseek/harness/jetbrains/ui/HarnessToolWindowPanel.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`（补插件描述文案，非必需但让插件列表可读）

**Interfaces:**
- Consumes: `HarnessPanel(project, log)`（Task 5）、`LogPanel.append`（Task 3）。
- Produces: 完整可用的插件。

- [ ] **Step 1: 把占位 `JLabel` 换成真正的 `HarnessPanel`**

```kotlin
private val harnessPanel = HarnessPanel(project, logPanel::append)
```

`logPanel::append` 的类型正好是 `(LogLevel, String) -> Unit`，无需适配层。这就是 `DshCli` 能完全不认识 UI 类的原因。

- [ ] **Step 2: 让 `HarnessToolWindowPanel.dispose()` 释放 `harnessPanel`**

`harnessPanel.dispose()`（它会停进程、释放浏览器）。

- [ ] **Step 3: 补 `plugin.xml` 的描述文案**

把 `<description>` 写成一段可读的中文说明（用途、依赖 `dsh` 命令、官网地址），便于插件列表与后续市场发布。

- [ ] **Step 4: 静态检查**

```powershell
# 确认接线完整：HarnessPanel 被创建且被 dispose
Select-String -Path "src\main\kotlin\com\deepseek\harness\jetbrains\ui\HarnessToolWindowPanel.kt" -Pattern "HarnessPanel|logPanel::append|dispose"
# 确认 plugin.xml 里没有残留占位内容
Select-String -Path "src\main\resources\META-INF\plugin.xml" -Pattern "TODO|TBD"
```

预期：`HarnessPanel`、`logPanel::append`、`dispose` 都出现；`plugin.xml` 无 TODO/TBD。

- [ ] **Step 5: 用户执行构建验证**

Run: `.\gradlew.bat buildPlugin`
Expected: `BUILD SUCCESSFUL`，产出 zip。

- [ ] **Step 6: 用户执行端到端验证**

安装 zip 并重启 IDE 后：

1. 点侧边栏 DeepSeek 图标 → 窗口打开。
2. 本机 `dsh` 可用 → 预期自动进入内嵌浏览器并加载出 Harness 界面。
   - 注意：本机 3080 已被一个手动启动的实例占用，因此日志里应能看到「端口 3080 被占用，尝试下一个」并最终选中 3081。这正是 Review Focus 5 的验证点。
3. 切到 `日志` tab，应能看到：工具窗口创建、完整检测命令行、退出码、耗时、dsh 版本、端口选择过程、完整启动命令行、进程 PID、stdout 每一行、抓到的完整 URL、JCEF 支持情况、页面加载完成。
4. 再点一次 `刷新` → 预期日志出现「复用已有进程」，不产生第二个进程。
5. 关闭 IDE 后检查没有残留的 `DeepSeek Harness.exe` 进程占用 3081 端口：
   ```powershell
   Get-NetTCPConnection -LocalPort 3081 -State Listen -ErrorAction SilentlyContinue
   Get-CimInstance Win32_Process -Filter "Name='DeepSeek Harness.exe'" | Select-Object ProcessId,CommandLine
   ```
   预期：没有监听 3081 的进程，且没有命令行里带 `--port 3081` 的 `DeepSeek Harness.exe`。

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin src/main/resources/META-INF/plugin.xml
git commit -m "[FEAT] JetBrains 内嵌 Harness 插件 -- 接线工具窗口并补全插件描述"
```

---

## API 签名核对记录

本项目不跑构建，因此计划里用到的每个非平凡平台 API 都已用 JDK 24 的 `javap` 对着 `D:\PyCharm 2025.3.3\lib\` 下的真实 class 逐个核对过：

```powershell
$javap = "C:\Program Files\Java\jdk-24\bin\javap.exe"
$cp = "D:\PyCharm 2025.3.3\lib\app.jar;D:\PyCharm 2025.3.3\lib\util-8.jar;D:\PyCharm 2025.3.3\lib\util.jar;D:\PyCharm 2025.3.3\lib\module-intellij.libraries.jcef.jar"
& $javap -cp $cp com.intellij.ui.components.JBTabbedPane
```

| API | 核对结果 |
| --- | --- |
| `JBCefApp.isSupported()` | `public static boolean isSupported()` ✓ |
| `JBCefBrowser(String)` | 存在；另有 builder 形式 ✓ |
| `JBCefBrowser.getComponent()` / `dispose()` | ✓ |
| `JBCefBrowserBase.getJBCefClient()` / `getCefBrowser()` | 由 `JBCefBrowser` 继承 ✓ |
| `JBCefClient.addLoadHandler(CefLoadHandler, CefBrowser)` | 返回 `JBCefClient` ✓ |
| `org.cef.handler.CefLoadHandlerAdapter` | 抽象类，含 `onLoadingStateChange(CefBrowser, boolean, boolean, boolean)`；在 `module-intellij.libraries.jcef.jar` ✓ |
| `org.cef.browser.CefBrowser.getURL()` | ✓ |
| `JBUI.Fonts.create(String, int)` | 返回 `JBFont` ✓ |
| `JBUI.Borders.empty(int)` | 返回 `Border` ✓ |
| `JBTextArea` / `JBScrollPane(Component)` | ✓ |
| `Content.setDisposer(Disposable)` | ✓ |
| `ContentFactory.getInstance()` / `createContent(JComponent, String, boolean)` | ✓ |
| `ToolWindowFactory.createToolWindowContent(Project, ToolWindow)` | ✓ |
| `Application.executeOnPooledThread(Runnable)` / `invokeLater(Runnable)` | ✓ |
| `ApplicationManager.getApplication()` | ✓ |

**核对中改掉的 2 个错误**（原计划里凭印象写错，会直接编译失败）：

1. `com.intellij.ui.tabs.JBTabbedPane` —— **该类不存在**，正确位置是 `com.intellij.ui.components.JBTabbedPane`。
2. `browser.jbCefClient` / `browser.cefBrowser` —— 真实方法名是 `getJBCefClient()` / `getCefBrowser()`，首两字母大写导致 Kotlin 合成属性名反直觉，改成显式方法调用。

## 计划自审记录

- **spec 覆盖**：§4 架构 → Task 3/5/6；§5 状态机 → Task 5；§6.1 检测 → Task 4；§6.2 端口 → Task 4；§6.3 启动与抓 URL → Task 4；§6.4 生命周期 → Task 4/5；§6.5 JCEF → Task 5；§6.6 日志 → Task 3/4/5；§6.7 线程模型 → Task 5；§7 UI → Task 3/5；§8 构建 → Task 1；§9 图标 → Task 2；§10 错误处理 → Task 4/5。无遗漏。
- **类型一致性**：`LogLevel` 三值在 Task 3/4/5 一致；`DshCli` 的 `detect/startWeb/isRunning/stop` 四个方法在 Task 4 定义、Task 5 使用，签名一致；`HarnessPanel(project, log)` 在 Task 5 定义、Task 6 按同签名构造。
- **无自动化测试的替代**：spec 已明确不做单元测试，因此每个任务的验证由「可跑的静态检查命令」+「用户执行的构建/端到端步骤」组成；Review Focus 的 5 条各在 Task 4/5 里被钉成明确的代码要求，并在 Task 6 第 6 步有对应的端到端检查动作。
