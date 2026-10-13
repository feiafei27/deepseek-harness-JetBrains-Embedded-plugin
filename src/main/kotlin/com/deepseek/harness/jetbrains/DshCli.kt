package com.deepseek.harness.jetbrains

import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * dsh 命令行工具的封装：检测命令是否可用、启动 dsh web 服务、抓取带 token 的地址、结束后台进程。
 *
 * 本类不依赖任何 UI 类，[log] 回调可从任意线程调用。
 * [detect] 与 [startWeb] 都会阻塞，必须在后台线程调用。
 */
class DshCli(private val log: (LogLevel, String) -> Unit) {

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

    fun isRunning(): Boolean = process?.isAlive == true

    fun detect(): DetectResult {
        val startedAt = System.nanoTime()
        val command = listOf("cmd.exe", "/c", DETECT_COMMAND)
        log(LogLevel.INFO, "执行检测命令：${command.joinToString(" ")}")

        val proc = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return DetectResult.Unavailable("无法执行检测命令：${e.message}")
        }

        // 必须"先等结束、再读输出"：先读会在进程挂住时永久阻塞。
        // dsh --version 的输出极小，不会撑满管道缓冲区，所以这个顺序是安全的。
        val finished = try {
            proc.waitFor(DETECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            proc.destroyForcibly()
            return DetectResult.Unavailable("检测被中断")
        }
        if (!finished) {
            proc.destroyForcibly()
            return DetectResult.Unavailable("执行 $DETECT_COMMAND 超过 $DETECT_TIMEOUT_SECONDS 秒未结束")
        }

        val output = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val exitCode = proc.exitValue()
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
        val port = findFreePort()
            ?: return StartResult.Failed("端口 ${PORT_RANGE.first}-${PORT_RANGE.last} 全部被占用")

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

        return try {
            val url = urlFuture.get(URL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            log(LogLevel.INFO, "捕获到 Harness 地址：$url")
            StartResult.Started(url, port)
        } catch (e: TimeoutException) {
            StartResult.Failed("$URL_TIMEOUT_SECONDS 秒内未打印出 dsh web 地址")
        } catch (e: ExecutionException) {
            StartResult.Failed(e.cause?.message ?: "dsh web 启动失败")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            StartResult.Failed("等待 dsh web 地址时被中断")
        }
    }

    /**
     * 结束 [startWeb] 拉起的进程树。
     *
     * `cmd.exe /c dsh web ...` 会产生 cmd.exe → DeepSeek Harness.exe 两层以上进程，
     * 只 destroy() cmd.exe 会让子进程变成孤儿继续占用端口，所以必须用 taskkill /T。
     */
    fun stop() {
        val proc = process ?: return
        process = null

        if (!proc.isAlive) {
            log(LogLevel.INFO, "dsh web 进程已自行结束，退出码 ${runCatching { proc.exitValue() }.getOrNull()}")
            return
        }

        val command = listOf("taskkill", "/F", "/T", "/PID", proc.pid().toString())
        log(LogLevel.INFO, "停止 dsh web：${command.joinToString(" ")}")
        try {
            val killer = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = killer.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            val finished = killer.waitFor(KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val exitCode = if (finished) killer.exitValue() else null
            val level = if (finished && exitCode == 0) LogLevel.INFO else LogLevel.WARN
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
    }
}
