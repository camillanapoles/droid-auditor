package com.camillanapoles.droidauditor.data.exec

import java.io.IOException
import java.util.concurrent.TimeUnit

/** Root detection via `su -c id`, result cached until explicitly rechecked. */
class RootAccess {

    @Volatile
    private var cached = false

    @Volatile
    private var checked = false

    fun isRootAvailable(forceCheck: Boolean = false): Boolean {
        if (!checked || forceCheck) {
            cached = probe()
            checked = true
        }
        return cached
    }

    private fun probe(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) output.append(line)
                    }
                } catch (_: IOException) {
                    // process died while reading; treated as no root below
                }
            }
            reader.isDaemon = true
            reader.start()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            reader.join(2000)
            process.exitValue() == 0 && output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }
}
