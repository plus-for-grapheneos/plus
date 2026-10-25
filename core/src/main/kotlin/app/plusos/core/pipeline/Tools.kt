// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.pipeline

import java.io.File

/** Paths to the avbroot and afsr executables (bundled per host: Windows, Linux, Android arm64). */
data class Tools(val avbroot: String, val afsr: String)

data class ToolResult(val exit: Int, val stderrTail: String)

/** Runs one external tool. The pipeline only ever talks to the tools through this, so tests can stand in for them. */
fun interface ToolRunner {
    fun run(args: List<String>, env: Map<String, String>, stdin: File?): ToolResult
}

/** The real runner: a child process; stdout and stderr lines go to [log], the last 4 KB of stderr is kept for errors. */
class ProcessToolRunner(private val timeoutMs: Long = 3 * 60 * 60 * 1000L, private val log: (String) -> Unit) : ToolRunner {
    override fun run(args: List<String>, env: Map<String, String>, stdin: File?): ToolResult {
        val pb = ProcessBuilder(args).redirectErrorStream(false)
        pb.environment().putAll(env)
        if (stdin != null) pb.redirectInput(stdin) else pb.redirectInput(ProcessBuilder.Redirect.from(nullFile()))
        val p = pb.start()
        val tail = StringBuilder()
        val errReader = Thread {
            p.errorStream.bufferedReader().forEachLine { line ->
                log(line)
                synchronized(tail) { tail.append(line).append('\n'); if (tail.length > 4096) tail.delete(0, tail.length - 4096) }
            }
        }.apply { isDaemon = true; start() }
        val outReader = Thread { p.inputStream.bufferedReader().forEachLine(log) }.apply { isDaemon = true; start() }
        // Review I1/I9: a tool that hangs (a helper waiting forever, fastboot waiting for a device) is killed.
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return ToolResult(-1, "timed out after ${timeoutMs / 1000} s")
        }
        val exit = p.exitValue()
        outReader.join(2000)
        errReader.join(2000)
        return ToolResult(exit, synchronized(tail) { tail.toString() })
    }

    private fun nullFile() = File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")
}
