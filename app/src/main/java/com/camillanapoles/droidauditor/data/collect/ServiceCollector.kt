package com.camillanapoles.droidauditor.data.collect

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.camillanapoles.droidauditor.data.db.DataDao
import com.camillanapoles.droidauditor.data.exec.ShellExec
import java.io.File

/**
 * Parses `dumpsys activity services` ServiceRecord blocks: package, class,
 * process, clients (lines mentioning clients, validated against known packages)
 * and callers when present. Best effort on format drift.
 */
class ServiceCollector(
    private val shellExec: ShellExec,
    private val dataDao: DataDao
) : Collector {

    override val id: String = CollectorIds.SERVICES

    override suspend fun collect(runId: Long, runDir: File) {
        val known = HashSet<String>(dataDao.packageNamesFor(runId))
        val output = shellExec.exec("dumpsys activity services", asRoot = false).output

        val headerRe = Regex("ServiceRecord\\{[0-9a-fA-F]+ (?:u\\d+ )?([^\\s}]+)")
        val processRe = Regex("processName=(\\S+)")
        val callersRe = Regex("callers?=(\\S+)")

        val rows = LinkedHashMap<String, ContentValues>()
        var currentPkg: String? = null
        var currentCls: String? = null
        var processName: String? = null
        var callers: String? = null
        val clients = LinkedHashSet<String>()

        fun reset() {
            currentPkg = null
            currentCls = null
            processName = null
            callers = null
            clients.clear()
        }

        fun flush() {
            val pkg = currentPkg
            val cls = currentCls
            if (pkg != null && cls != null) {
                val key = "$pkg|$cls"
                if (!rows.containsKey(key)) {
                    val cv = ContentValues()
                    cv.put("run_id", runId)
                    cv.put("package_name", pkg)
                    cv.put("service_class", cls)
                    cv.put("process_name", processName)
                    cv.put("client_packages", clients.filter { it != pkg }.joinToString(","))
                    cv.put("started_by", callers ?: clients.firstOrNull { it != pkg })
                    rows[key] = cv
                }
            }
            reset()
        }

        for (raw in output.lineSequence()) {
            val line = raw.trim()
            val header = headerRe.find(line)
            if (header != null) {
                flush()
                val component = header.groupValues[1]
                val slash = component.indexOf('/')
                if (slash > 0) {
                    val pkg = component.substring(0, slash)
                    val cls = ParseUtil.expandComponent(pkg, component.substring(slash + 1))
                    currentPkg = pkg
                    currentCls = cls
                }
                continue
            }
            if (currentPkg == null) continue
            processRe.find(line)?.let { processName = it.groupValues[1] }

            callersRe.find(line)?.let { callers = it.groupValues[1] }
            if (line.contains("client", ignoreCase = true)) {
                val matcher = ParseUtil.PACKAGE_PATTERN.matcher(line)
                while (matcher.find()) {
                    val token = matcher.group() ?: continue
                    if (known.contains(token)) clients.add(token)
                }
            }
        }
        flush()

        dataDao.inTx { db ->
            for (cv in rows.values) {
                db.insertWithOnConflict("services", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }
}
