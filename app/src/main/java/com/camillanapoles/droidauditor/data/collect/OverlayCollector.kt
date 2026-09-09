package com.camillanapoles.droidauditor.data.collect

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.data.db.FindingsDao
import com.camillanapoles.droidauditor.data.exec.RootAccess
import com.camillanapoles.droidauditor.data.exec.ShellExec
import java.io.File

/**
 * Overlay topology per package:
 *  - permission to draw overlays via AppOpsManager (public API, no root);
 *  - windows actually ON SCREEN right now via `dumpsys window windows`
 *    (root best-effort — degrades to permission-only when denied).
 * Results go to overlay_state + findings ("qual app esta em sobreposicao").
 */
class OverlayCollector(
    private val context: Context,
    private val shellExec: ShellExec,
    private val rootAccess: RootAccess,
    private val dataDao: DataDao,
    private val findingsDao: FindingsDao
) : Collector {

    override val id: String = CollectorIds.OVERLAY_STATE

    /** AppOps mode -> (allowed?, label). */
    private fun modeLabel(mode: Int): Pair<Boolean, String> = when (mode) {
        AppOpsManager.MODE_ALLOWED -> true to "allowed"
        AppOpsManager.MODE_IGNORED -> false to "ignored"
        AppOpsManager.MODE_ERRORED -> false to "errored"
        else -> false to "default"
    }

    override suspend fun collect(runId: Long, runDir: File) {
        val pm = context.packageManager
        val appOps = context.getSystemService(AppOpsManager::class.java)

        val installed = try {
            pm.getInstalledPackages(0)
        } catch (_: Exception) {
            emptyList()
        }

        // 1) Overlay permission per package (no root required).
        val states = ArrayList<Triple<String, Boolean, String>>() // pkg, allowed, mode
        for (pi in installed) {
            val pkg = pi.packageName ?: continue
            val ai: ApplicationInfo = pi.applicationInfo ?: continue
            val pair = try {
                modeLabel(
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, ai.uid, pkg
                    )
                )
            } catch (_: Exception) {
                false to "unknown"
            }
            if (pair.first || pair.second == "unknown") {
                states.add(Triple(pkg, pair.first, pair.second))
            }
        }
        val allowedSet = states.filter { it.second }.map { it.first }.toSet()
        val systemSet = installed.mapNotNull { pi ->
            val name = pi.packageName ?: return@mapNotNull null
            if ((pi.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0) name else null
        }.toSet()

        // 2) Active overlay windows right now (root best-effort).
        val activeWindows = countActiveOverlayWindows()

        // 3) Persist + findings.
        dataDao.replaceOverlayStates(runId, states.map { (pkg, allowed, mode) ->
            com.camillanapoles.droidauditor.domain.OverlayStateRow(
                packageName = pkg,
                overlayAllowed = allowed,
                appopsMode = mode,
                activeWindows = activeWindows[pkg] ?: 0,
                isSystem = systemSet.contains(pkg)
            )
        })

        var denied = activeWindows.isEmpty()
        activeWindows.forEach { (pkg, count) ->
            val sys = systemSet.contains(pkg)
            findingsDao.insert(
                runId, id,
                if (sys) "INFO" else "HIGH",
                "Sobreposicao ATIVA agora: $pkg ($count janela(s) sobre a tela)",
                null, pkg
            )
        }
        allowedSet.asSequence()
            .filter { !systemSet.contains(it) }
            .filter { (activeWindows[it] ?: 0) == 0 }
            .forEach { pkg ->
                findingsDao.insert(
                    runId, id, "MEDIUM",
                    "$pkg tem permissao de sobreposicao (pode desenhar sobre outros apps)",
                    null, pkg
                )
            }
        if (denied && rootAccess.isRootAvailable().not()) {
            findingsDao.insert(
                runId, id, "INFO",
                "Janelas ativas exigem root (dumpsys window) — apenas permissoes verificadas",
                null, null
            )
        }
    }

    /**
     * Parses `dumpsys window windows` for overlay-typed windows and counts
     * them per package. Returns an empty map when the dump is not readable.
     */
    internal fun countActiveOverlayWindows(): Map<String, Int> {
        val dump = try {
            val result = shellExec.exec(
                "dumpsys window windows",
                asRoot = rootAccess.isRootAvailable()
            )
            if (result.exitCode != 0) return emptyMap() else result.output
        } catch (_: Exception) {
            return emptyMap()
        }
        val counts = HashMap<String, Int>()
        var currentPkg: String? = null
        for (line in dump.lineSequence()) {
            val wMatch = WINDOW_LINE.find(line)
            if (wMatch != null) {
                currentPkg = wMatch.groupValues[1]
                continue
            }
            val tMatch = TYPE_LINE.find(line)
            if (tMatch != null && currentPkg != null) {
                val type = tMatch.groupValues[1].toIntOrNull() ?: continue
                if (type in OVERLAY_WINDOW_TYPES) {
                    counts.merge(currentPkg!!, 1, Int::plus)
                }
            }
        }
        return counts
    }

    companion object {
        /** Window#hash u0 pkg/Activity — captures pkg. */
        private val WINDOW_LINE = Regex("Window\\{[^}]*\\s(?:u0\\s|u10\\s)?([a-zA-Z][\\w.]*)/")

        /** mType=2038 / type=2038 style lines. */
        private val TYPE_LINE = Regex("\\bm?[Tt]ype=(\\d+)")

        /** PHONE, ALERT, TOAST, SYSTEM_OVERLAY, APPLICATION_OVERLAY. */
        private val OVERLAY_WINDOW_TYPES = intArrayOf(2002, 2003, 2005, 2006, 2038)
    }
}
