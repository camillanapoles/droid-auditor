package com.camillanapoles.droidauditor.data.collect

import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.data.db.SettingsDao
import com.camillanapoles.droidauditor.data.exec.ShellExec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Launch graph: `logcat -d -v time -s ActivityTaskManager` (capped by
 * settings.max_logcat_lines). Parses `START u0 ... cmp=PKG/ACT ... from uid NNNN`,
 * maps uid -> package and stores launch_events. Edges are derived later by the Correlator.
 */
class LaunchGraphCollector(
    private val shellExec: ShellExec,
    private val dataDao: DataDao,
    private val settingsDao: SettingsDao
) : Collector {

    override val id: String = CollectorIds.LAUNCH_GRAPH

    override suspend fun collect(runId: Long, runDir: File) {
        val maxLines = settingsDao.getLong("max_logcat_lines", 5000L).toInt().coerceAtLeast(100)
        val output = shellExec.exec(
            "logcat -d -v time -s ActivityTaskManager -t $maxLines",
            asRoot = false
        )
        val uidMap = dataDao.uidToPackageMap(runId)
        val cmpRe = Regex("cmp=([^\\s}]+)")
        val uidRe = Regex("from uid (\\d+)")

        for (line in output.output.lineSequence()) {
            if (!line.contains("START u0")) continue
            val cmp = cmpRe.find(line)?.groupValues?.get(1) ?: continue
            val parts = splitComponent(cmp) ?: continue
            val fromUid = uidRe.find(line)?.groupValues?.get(1)?.toIntOrNull()
            val fromPkg = fromUid?.let { uidMap[it] }
            val ts = parseLogcatTimestamp(line)
            dataDao.insertLaunchEvent(runId, ts, fromPkg, parts.first, parts.second, line.trim().take(400))
        }
    }

    private fun splitComponent(component: String): Pair<String, String>? {
        var value = component
        if (value.startsWith("ComponentInfo{")) {
            value = value.removePrefix("ComponentInfo{").trimEnd('}')
        }
        val slash = value.indexOf('/')
        if (slash <= 0) return null
        val pkg = value.substring(0, slash)
        if (!ParseUtil.looksLikePackage(pkg)) return null
        return pkg to ParseUtil.expandComponent(pkg, value.substring(slash + 1))
    }

    private fun parseLogcatTimestamp(line: String): Long {
        return try {
            if (line.length >= 18 && line[2] == ':') {
                val year = Calendar.getInstance().get(Calendar.YEAR)
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                format.parse("$year-${line.substring(0, 18)}")?.time ?: System.currentTimeMillis()
            } else {
                System.currentTimeMillis()
            }
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}
