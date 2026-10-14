package com.deepseek.harness.jetbrains

import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * dsh 命令行工具的封装：检测命令是否可用、启动 dsh web 服务、抓取带 token 的地址、结束后台进程。
 *
 * 本类不依赖任何 UI 类，[log] 与 [onStage] 回调都可能从任意线程调用。
 * [detect] 与 [startWeb] 都会阻塞，必须在后台线程调用。
 */
class DshCli(
    private val log: (LogLevel, String) -> Unit,
    private val onStage: (Stage) -> Unit,
) {

    /**
     * 启动流程中的阶段，按实际发生顺序声明。UI 用它渲染步骤清单。
     *
     * [LOADING_UI] 不由本类上报：它对应"创建并加载内嵌浏览器"，属于界面层的步骤。
     */
    enum class Stage { STOPPING, DETECTING, PICKING_PORT, STARTING, WAITING_URL, LOADING_UI }

    sealed interface DetectResult {
        data class Available(val version: String) : DetectResult
        data class Unavailable(val reason: String) : DetectResult
    }

    sealed interface StartResult {
        data class Started(val url: String, val port: Int) : StartResult
        data class Failed(val reason: String) : StartResult
    }

    @Volatile
    private var process: Process? = null

    fun detect(): DetectResult {
        onStage(Stage.DETECTING)
        val startedAt = System.nanoTime()
        val command = listOf("cmd.exe", "/c", DETECT_COMMAND)
        log(LogLevel.INFO, "执行检测命令：${command.joinToString(" ")}")

        val proc = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return DetectResult.Unavailable("无法执行检测命令：${e.message}")
        }

        val (exitCode, output) = waitForExit(proc, DETECT_TIMEOUT_SECONDS)
        if (exitCode == null) {
            killTree(proc)
            return DetectResult.Unavailable("执行 $DETECT_COMMAND 超过 $DETECT_TIMEOUT_SECONDS 秒未结束")
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        log(LogLevel.INFO, "检测结束：退出码=$exitCode 耗时=${elapsedMs}ms")
        output.lineSequence().forEach { log(LogLevel.INFO, "dsh --version: $it") }

        // 判定只看退出码，输出内容多脏都不影响结论。
        if (exitCode != 0) {
            return DetectResult.Unavailable("$DETECT_COMMAND 退出码为 $exitCode")
        }
        // Node 的 DeprecationWarning 走 stderr，与 stdout 合并后会混进来，
        // 因此取第一条以数字开头的行作为版本号。
        val version = output.lineSequence()
            .firstOrNull { it.isNotBlank() && it.trimStart().first().isDigit() }
            ?.trim()
            .orEmpty()
        return DetectResult.Available(version)
    }

    fun startWeb(): StartResult {
        onStage(Stage.PICKING_PORT)
        val port = findFreePort()
            ?: return StartResult.Failed("端口 ${PORT_RANGE.first}-${PORT_RANGE.last} 全部被占用")

        onStage(Stage.STARTING)
        val command = listOf(
            "cmd.exe", "/c", "dsh", "web",
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "--no-open",
        )
        val workingDir = File(System.getProperty("user.home"))
        log(LogLevel.INFO, "启动命令：${command.joinToString(" ")}")
        log(LogLevel.INFO, "工作目录：$workingDir")

        val proc = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .directory(workingDir)
                .start()
        } catch (e: IOException) {
            return StartResult.Failed("启动 dsh web 失败：${e.message}")
        }
        process = proc
        log(LogLevel.INFO, "dsh web 进程已启动，PID=${proc.pid()}")

        val urlFuture = CompletableFuture<String>()
        val reader = Thread({
            try {
                proc.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        log(LogLevel.INFO, "dsh: $line")
                        if (!urlFuture.isDone) {
                            URL_PATTERN.find(line)?.let { urlFuture.complete(it.groupValues[1]) }
                        }
                    }
                }
            } catch (e: Exception) {
                log(LogLevel.WARN, "读取 dsh 输出失败：${e.message}")
            } finally {
                // 流结束说明进程已退出：主动结束等待，否则要白等满整个超时时间。
                urlFuture.completeExceptionally(
                    IllegalStateException(
                        "dsh 进程已结束，退出码 ${runCatching { proc.exitValue() }.getOrNull()}"
                    )
                )
            }
        }, "dsh-web-output-reader")
        reader.isDaemon = true
        reader.start()

        onStage(Stage.WAITING_URL)
        var failure = "dsh web 启动失败"
        val url = try {
            urlFuture.get(URL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            failure = "$URL_TIMEOUT_SECONDS 秒内未打印出 dsh web 地址"
            null
        } catch (e: ExecutionException) {
            failure = e.cause?.message ?: failure
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = "等待 dsh web 地址时被中断"
            null
        }

        if (url == null) {
            // 启动失败还留着进程会白占端口，且下次刷新会再起一个，所以这里直接清理掉。
            killTree(proc)
            process = null
            log(LogLevel.ERROR, "启动 Harness 服务失败：$failure")
            return StartResult.Failed(failure)
        }

        log(LogLevel.INFO, "捕获到 Harness 地址：$url")
        return StartResult.Started(url, port)
    }

    /** 结束 [startWeb] 拉起的进程树。 */
    fun stop() {
        // 即使没有存活进程也上报：这样 UI 上"关闭旧服务"那一步不会被永久留在未完成态。
        onStage(Stage.STOPPING)
        val proc = process ?: return
        process = null
        killTree(proc)
    }

    /**
     * 并发读取子进程输出，并按超时等待它结束。返回 (退出码, 输出)，退出码为 null 表示没等到结束。
     *
     * 必须"边读边等"，不能"先 waitFor 再读输出"：实测（JDK 24 / Windows 11 / `cmd.exe /c dsh --version`）
     * 当 stdout 是无人读取的管道时，子进程根本不退出，`waitFor` 会一路耗到超时仍拿不到退出码；
     * 加上并发读取后 382ms 就正常结束（该命令总共只输出 11 字节，与管道缓冲区大小无关）。
     */
    private fun waitForExit(proc: Process, timeoutSeconds: Long): Pair<Int?, String> {
        val outputFuture = CompletableFuture<String>()
        val reader = Thread({
            try {
                outputFuture.complete(decodeProcessOutput(proc.inputStream.readBytes()))
            } catch (e: Exception) {
                outputFuture.completeExceptionally(e)
            }
        }, "process-output-reader")
        reader.isDaemon = true
        reader.start()

        val finished = try {
            proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log(LogLevel.WARN, "等待进程结束时被中断")
            return null to ""
        }

        val output = try {
            outputFuture.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ""
        } catch (e: Exception) {
            log(LogLevel.WARN, "读取进程输出失败：$e")
            ""
        }
        return (if (finished) proc.exitValue() else null) to output
    }

    /**
     * 子进程输出有两种可能的编码：dsh（Node/Electron）输出 UTF-8，而 cmd.exe 自己的输出
     * （taskkill 的结果、`dsh 不是内部或外部命令` 这类报错）用的是系统 ANSI 代码页，
     * 中文 Windows 上是 GBK。先按 UTF-8 解，只有解出替换字符 U+FFFD 时才回退到系统代码页。
     */
    private fun decodeProcessOutput(bytes: ByteArray): String {
        val utf8 = String(bytes, StandardCharsets.UTF_8)
        return if (utf8.contains('\uFFFD')) String(bytes, CONSOLE_CHARSET) else utf8
    }

    /**
     * 用 taskkill /F /T 结束整棵进程树。
     *
     * `cmd.exe /c dsh web ...` 会产生 cmd.exe → DeepSeek Harness.exe 两层以上进程（实测 taskkill
     * 报告终止了一个子进程），只 destroy() 最外层会留下孤儿进程继续占用端口，所以必须带 /T。
     */
    private fun killTree(proc: Process) {
        if (!proc.isAlive) {
            log(LogLevel.INFO, "进程已自行结束，退出码 ${runCatching { proc.exitValue() }.getOrNull()}，无需 taskkill")
            return
        }

        val command = listOf("taskkill", "/F", "/T", "/PID", proc.pid().toString())
        log(LogLevel.INFO, "结束进程树：${command.joinToString(" ")}")
        try {
            val killer = ProcessBuilder(command).redirectErrorStream(true).start()
            val (exitCode, output) = waitForExit(killer, KILL_TIMEOUT_SECONDS)
            val level = if (exitCode == 0) LogLevel.INFO else LogLevel.WARN
            log(level, "taskkill 退出码=$exitCode 输出=${output.trim()}")
        } catch (e: Exception) {
            log(LogLevel.WARN, "taskkill 执行失败：${e.message}")
        }
    }

    private fun findFreePort(): Int? {
        for (port in PORT_RANGE) {
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

    companion object {
        private const val DETECT_COMMAND = "dsh --version"
        private const val DETECT_TIMEOUT_SECONDS = 5L
        private const val URL_TIMEOUT_SECONDS = 60L
        private const val KILL_TIMEOUT_SECONDS = 5L
        private val PORT_RANGE = 3080..3100
        private val URL_PATTERN = Regex("""^\s*dsh web:\s*(http://\S+)""")

        // 系统 ANSI 代码页：Java 18+ 起 file.encoding 被固定为 UTF-8，
        // 但 sun.jnu.encoding 仍然是系统的本地编码，用它来解 cmd.exe 的输出。
        private val CONSOLE_CHARSET: Charset =
            runCatching { Charset.forName(System.getProperty("sun.jnu.encoding")) }
                .getOrDefault(StandardCharsets.UTF_8)
    }
}
