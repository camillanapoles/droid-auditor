package com.camillanapoles.droidauditor.data.exec

import android.content.Context
import java.io.File

/**
 * Copies the bundled forensic toolchain from assets/toolchain into
 * filesDir/toolchain on first launch; re-copies a script when its size
 * differs from the bundled asset (asset newer).
 */
class ScriptInstaller(private val context: Context) {

    fun toolchainDir(): File = File(context.filesDir, "toolchain")

    fun ensureInstalled(): File {
        val dir = toolchainDir()
        if (!dir.exists()) dir.mkdirs()
        val names = context.assets.list("toolchain") ?: return dir
        for (name in names) {
            if (!name.endsWith(".sh")) continue
            val target = File(dir, name)
            var needsCopy = !target.exists()
            if (!needsCopy) {
                val assetLength = assetLength(name)
                if (assetLength >= 0 && assetLength != target.length()) needsCopy = true
            }
            if (needsCopy) {
                context.assets.open("toolchain/$name").use { input ->
                    File(dir, "$name.tmp").outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val tmp = File(dir, "$name.tmp")
                if (!tmp.renameTo(target)) {
                    target.delete()
                    if (!tmp.renameTo(target)) {
                        // Fall back to a direct copy if rename keeps failing.
                        context.assets.open("toolchain/$name").use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        tmp.delete()
                    }
                }
            }
        }
        return dir
    }

    private fun assetLength(name: String): Long = try {
        context.assets.open("toolchain/$name").use { it.available().toLong() }
    } catch (_: Exception) {
        -1L
    }
}
