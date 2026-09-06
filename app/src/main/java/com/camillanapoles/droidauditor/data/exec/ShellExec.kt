package com.camillanapoles.droidauditor.data.exec

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Executes shell commands. Non-root: `sh -c <cmd>`.
 * Root: `su -c "sh -c '<escaped>'"`.
 * Combined stdout+stderr is captured (capped) and optionally streamed to a file.
 */
class ShellExec(private val rootAccess: RootAccess) {

    class Result(
        val exitCode: Int,
        val output: String,
        val durationMs: Long
    )

    fun exec(command: String, timeoutSec: Int = DEFAULT_TIMEOUT, asRoot: Boolean? = null): Result {
        val useRoot = asRoot ?: rootAccess.isRootAvailable()
        return run(command, useRoot, timeoutSec, null)
    }

    fun execToFile(
        command: String,
        outFile: File,
        timeoutSec: Int = DEFAULT_TIMEOUT,
        asRoot: Boolean? = null
    ): Result {
        val useRoot = asRoot ?: rootAccess.isRootAvailable()
        return run(command, useRoot, timeoutSec, outFile)
    }

    private fun run(command: String, useRoot: Boolean, timeoutSec: Int, outFile: File?): Result {
        val start = SystemClock.elapsedRealtime()
        val argv = if (useRoot) {
            arrayOf("su", "-c", "sh -c " + quote(command))
        } else {
            arrayOf("sh", "-c", command)
        }
        val process = try {
            ProcessBuilder(*argv).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return Result(-1, "exec failed: ${e.message}\n", 0L)
        }

        val captured = ByteArrayOutputStream()
        val reader = Thread {
            var fileOut: OutputStream? = null
            try {
                val input = process.inputStream
                val buffer = ByteArray(8192)
                var totalCaptured = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (fileOut == null && outFile != null) {
                        fileOut = FileOutputStream(outFile)
                    }
                    fileOut?.write(buffer, 0, n)
                    if (totalCaptured < MAX_CAPTURE) {
                        val take = minOf(n, MAX_CAPTURE - totalCaptured)
                        captured.write(buffer, 0, take)
                        totalCaptured += take
                    }
                }
                fileOut?.flush()
            } catch (_: IOException) {
                // stream closed when the process is killed on timeout
            } finally {
                try {
                    fileOut?.close()
                } catch (_: IOException) {
                    // ignore
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = try {
            process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
        }
        try {
            reader.join(3000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        process.destroy()

        val duration = SystemClock.elapsedRealtime() - start
        val exitCode = if (finished) {
            try {
                process.exitValue()
            } catch (_: IllegalThreadStateException) {
                TIMEOUT_EXIT
            }
        } else {
            TIMEOUT_EXIT
        }
        val text = captured.toString("UTF-8")
        return Result(exitCode, text, duration)
    }

    companion object {
        const val DEFAULT_TIMEOUT = 120
        const val TIMEOUT_EXIT = -124
        const val MAX_CAPTURE = 4 * 1024 * 1024

        /** Single-quotes [s] safely for use inside `sh -c`. */
        fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
