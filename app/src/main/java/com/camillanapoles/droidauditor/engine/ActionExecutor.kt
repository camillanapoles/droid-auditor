package com.camillanapoles.droidauditor.engine

import com.camillanapoles.droidauditor.data.exec.RootAccess
import com.camillanapoles.droidauditor.data.exec.ShellExec
import com.camillanapoles.droidauditor.domain.CommandRow

/**
 * Executes optimizer actions (kind=action rows) with &lt;pkg&gt;/&lt;outdir&gt;
 * template substitution. The UI must confirm dangerous actions before calling.
 */
class ActionExecutor(
    private val shellExec: ShellExec,
    private val rootAccess: RootAccess
) {

    class ActionResult(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    fun execute(commandRow: CommandRow, pkg: String? = null): ActionResult =
        runRaw(commandRow.command, commandRow.requiresRoot, pkg)

    fun runRaw(command: String, requiresRoot: Boolean, pkg: String? = null): ActionResult {
        val resolved = command
            .replace("<pkg>", pkg ?: "")
            .replace("<outdir>", "")
        val useRoot = requiresRoot && rootAccess.isRootAvailable()
        val result = shellExec.exec(resolved, asRoot = useRoot)
        return ActionResult(result.exitCode, result.output.take(4000))
    }

    companion object {
        /** rm the cache directory contents of an app (root). */
        fun clearAppCacheCommand(pkg: String): String = "rm -rf /data/data/$pkg/cache/*"

        /** Force stop an app (root or shell). */
        fun forceStopCommand(pkg: String): String = "am force-stop $pkg"
    }
}
